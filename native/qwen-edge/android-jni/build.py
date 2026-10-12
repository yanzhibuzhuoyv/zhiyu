"""Build and audit actual Android JNI DSOs sequentially; never executes a model or touches model weights."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys

SOURCE = Path(__file__).resolve().parent
ROOT = SOURCE.parents[2]
JAVA_SYMBOL = "Java_com_zhiyu_app_data_local_qwen_"


def run(command: list[str], log: Path) -> str:
    completed = subprocess.run(command, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
                               stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    with log.open("a", encoding="utf-8") as out:
        out.write(json.dumps(command, ensure_ascii=False) + "\n" + completed.stdout + "\n")
    if completed.returncode:
        raise RuntimeError(f"Native command failed ({completed.returncode}); inspect {log}")
    return completed.stdout


def audit(library: Path, tools: Path, mode: str, log: Path) -> dict:
    suffix = ".exe" if sys.platform == "win32" else ""
    readelf = str(tools / f"llvm-readelf{suffix}")
    symbols = run([str(tools / f"llvm-nm{suffix}"), "--dynamic", "--defined-only", str(library)], log)
    exports = sorted(line.split()[-1] for line in symbols.splitlines() if line.strip())
    methods = ["encode", "runtimeIdentity"] if mode == "text" else ["sample", "decode", "inspectCP", "runtimeIdentity"]
    expected = sorted(JAVA_SYMBOL + ("QwenTextNative_" if mode == "text" else "QwenImageNative_") + name for name in methods)
    if exports != expected:
        raise ValueError(f"Unexpected dynamic exports: {exports}")
    segments = run([readelf, "-lW", str(library)], log)
    alignments = [int(line.split()[-1], 16) for line in segments.splitlines() if line.strip().startswith("LOAD ")]
    if not alignments or any(value < 16384 for value in alignments):
        raise ValueError("ELF LOAD segment alignment is below 16 KiB")
    dynamic = run([readelf, "-dW", str(library)], log)
    needed = re.findall(r"\(NEEDED\).*\[(.*?)\]", dynamic)
    soname = re.findall(r"\(SONAME\).*\[(.*?)\]", dynamic)
    if soname != [library.name] or any(name not in {"libc.so", "libm.so", "libdl.so", "liblog.so"} for name in needed):
        raise ValueError(f"Unexpected SONAME or dependencies: {soname} {needed}")
    return {"library": library.name, "sha256": hashlib.sha256(library.read_bytes()).hexdigest(),
            "bytes": library.stat().st_size, "exports": exports, "load_alignments": alignments,
            "soname": soname[0], "needed": needed, "model_executed": False}


def build_one(options: argparse.Namespace, abi: str, mode: str) -> dict:
    directory = options.output / abi / mode
    directory.mkdir(parents=True, exist_ok=True)
    log = directory / "build.log"
    command = [str(options.cmake), "-S", str(SOURCE), "-B", str(directory), "-G", "Ninja",
               "-DCMAKE_BUILD_TYPE=Release", f"-DCMAKE_MAKE_PROGRAM={options.cmake.parent / 'ninja.exe'}",
               f"-DCMAKE_TOOLCHAIN_FILE={options.ndk / 'build/cmake/android.toolchain.cmake'}",
               f"-DANDROID_ABI={abi}", "-DANDROID_PLATFORM=android-26", "-DANDROID_STL=c++_static",
               "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", f"-DZHIYU_QWEN_JNI_MODE={mode.upper()}",
               f"-DLLAMA_SOURCE_DIR={options.llama}", f"-DQWEN_EDGE_SD_SOURCE={options.sd}",
               f"-DPython3_EXECUTABLE={options.python}"]
    run(command, log)
    target = f"zhiyu_qwen_{mode}_jni"
    run([str(options.cmake), "--build", str(directory), "--target", target, "--parallel", "2"], log)
    tools = options.ndk / "toolchains/llvm/prebuilt/windows-x86_64/bin"
    library = directory / "jniLibs" / f"lib{target}.so"
    result = audit(library, tools, mode, log)
    packaged = options.output / "jniLibs" / abi / library.name
    packaged.parent.mkdir(parents=True, exist_ok=True)
    if packaged.exists() and hashlib.sha256(packaged.read_bytes()).hexdigest() != result["sha256"]:
        raise ValueError(f"Refusing to overwrite a changed JNI artifact {packaged}; use a new output directory")
    if not packaged.exists():
        shutil.copyfile(library, packaged)
    result["abi"] = abi
    return result


def source_hashes() -> dict[str, str]:
    hashes = {}
    for directory in [SOURCE, SOURCE.parent / "student", SOURCE.parent / "adapter", SOURCE.parent / "diffusion"]:
        for path in sorted(directory.rglob("*")):
            if path.is_file() and "__pycache__" not in path.parts:
                hashes[str(path.relative_to(ROOT)).replace("\\", "/")] = hashlib.sha256(path.read_bytes()).hexdigest()
    return hashes


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ndk", type=Path, required=True)
    parser.add_argument("--cmake", type=Path, required=True)
    parser.add_argument("--python", type=Path, default=Path(sys.executable))
    parser.add_argument("--llama", type=Path, default=ROOT / "artifacts/qwen-native-runtime/llama-src")
    parser.add_argument("--sd", type=Path, default=ROOT / "artifacts/qwen-native-runtime/sd-checkpoint-src")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--abi", choices=["arm64-v8a", "x86_64"], action="append")
    parser.add_argument("--mode", choices=["text", "image"], action="append")
    parser.add_argument("--report-name", default="audit.json", help="New filename for a partial build audit")
    options = parser.parse_args()
    options.output = options.output.resolve()
    if "qwen-image-edge" in [part.lower() for part in options.output.parts] or options.output == ROOT:
        raise ValueError("Output cannot be the project root or original model tree")
    if any(options.output.is_relative_to(path.resolve()) for path in [SOURCE, options.llama, options.sd]):
        raise ValueError("Build output cannot overlap native or prepared upstream source directories")
    if Path(options.report_name).name != options.report_name or not options.report_name.endswith(".json"):
        raise ValueError("Report name must be a simple JSON filename")
    sources = source_hashes()
    results = [build_one(options, abi, mode) for abi in options.abi or ["arm64-v8a", "x86_64"]
               for mode in options.mode or ["text", "image"]]
    if source_hashes() != sources:
        raise ValueError("Source changed during compilation; artifact cannot claim a single source snapshot")
    report = {"version": 1, "scope": "actual_android_jni_build_export_alignment_audit", "results": results,
              "source_sha256": sources, "model_executed": False, "adb_executed": False}
    with (options.output / options.report_name).open("x", encoding="utf-8") as out:
        json.dump(report, out, ensure_ascii=False, indent=2)
    print(json.dumps({"artifacts": len(results), "audit": str(options.output / options.report_name)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
