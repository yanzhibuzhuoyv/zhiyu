"""Download pinned, official Sherpa Android assets; never runs downloaded source code."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path, PurePosixPath
import tarfile
import urllib.request
from concurrent.futures import ThreadPoolExecutor

ROOT = Path(__file__).resolve().parents[1] / "artifacts" / "native-voice"
RELEASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/"
DOWNLOADS = (
    ("sherpa-onnx-static-link-onnxruntime-1.13.8.aar", "v1.13.8/", 38691998,
     "b22c3fc1b6a45666d28892bb2f7694beeb77a8362d7ebd77c1a5431ec9435471"),
    ("vits-icefall-zh-aishell3.tar.bz2", "tts-models/", 31559701, None),
    ("sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23.tar.bz2", "asr-models/", 74004050, None),
)


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def download(item: tuple) -> dict:
    name, tag, size, published_digest = item
    path = ROOT / name
    if not path.is_file():
        temporary = ROOT / (name + ".part")
        url = RELEASE + tag + name
        count = 0
        with urllib.request.urlopen(url, timeout=45) as response, temporary.open("wb") as output:
            while block := response.read(1024 * 1024):
                count += len(block)
                if count > size:
                    raise ValueError("Official asset exceeds release size")
                output.write(block)
        if count != size:
            raise ValueError("Incomplete official asset")
        temporary.replace(path)
    if path.stat().st_size != size:
        raise ValueError("Existing asset differs from release size")
    actual = digest(path)
    if published_digest and actual != published_digest:
        raise ValueError("Asset differs from GitHub published SHA256")
    return {"file": name, "url": RELEASE + tag + name, "bytes": size,
            "sha256": actual, "published_digest_verified": bool(published_digest)}


def unpack(path: Path) -> list[dict]:
    result = []
    with tarfile.open(path, "r:bz2") as archive:
        for member in archive:
            relative = PurePosixPath(member.name)
            if relative.is_absolute() or ".." in relative.parts:
                if member.isfile():
                    raise ValueError("Unexpected model archive path")
                continue
            if len(relative.parts) != 2:
                # Official test recordings are fixtures, not production model files.
                continue
            if not member.isfile() or member.size > 180 * 1024 * 1024:
                continue
            if relative.name.endswith(".onnx") and "zipformer" in relative.parts[0]:
                if relative.name not in {"encoder-epoch-99-avg-1.int8.onnx", "decoder-epoch-99-avg-1.onnx",
                                         "joiner-epoch-99-avg-1.int8.onnx"}:
                    continue
            if relative.suffix not in {".onnx", ".txt", ".fst", ".md"} and relative.name not in {"LICENSE", "NOTICE"}:
                continue
            destination = ROOT / Path(*relative.parts)
            destination.parent.mkdir(parents=True, exist_ok=True)
            content = archive.extractfile(member)
            if content is None:
                raise ValueError("Missing model archive stream")
            data = content.read(member.size + 1)
            if len(data) != member.size:
                raise ValueError("Truncated model member")
            if destination.exists() and destination.read_bytes() != data:
                raise ValueError("Existing model member changed")
            destination.write_bytes(data)
            result.append({"file": str(destination.relative_to(ROOT)).replace("\\", "/"),
                           "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()})
    return result


def main() -> None:
    ROOT.mkdir(parents=True, exist_ok=True)
    with ThreadPoolExecutor(max_workers=3) as pool:
        downloads = list(pool.map(download, DOWNLOADS))
    models = []
    for entry in downloads:
        if entry["file"].endswith(".tar.bz2"):
            models.extend(unpack(ROOT / entry["file"]))
    report = {"sherpa_version": "1.13.8", "downloads": downloads, "model_files": models,
              "runtime_requires_download": False}
    (ROOT / "manifest.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"assets": len(downloads), "model_files": len(models),
                      "model_bytes": sum(item["bytes"] for item in models)}))


if __name__ == "__main__":
    main()
