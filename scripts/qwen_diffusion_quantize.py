"""Bounded, offline DiT-only streaming Q4_K conversion and header verification.

Requires an already built, pinned sd-cli; no downloading and no API calls.
Only the explicit new output and report paths are written. Existing files are
never replaced. Failed .partial.gguf files remain available for diagnosis.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time
from qwen_export_teacher import stop_owned_process, validate_outputs, watch_job, write_report

PARAMETERS = 7_115_124_736
TENSOR_COUNT = 297
TYPE_SIZES = {0: (1, 4), 1: (1, 2), 12: (256, 144), 30: (1, 2)}
TYPE_NAMES = {0: "F32", 1: "F16", 12: "Q4_K", 30: "BF16"}
SCALAR_FORMATS = {0: "B", 1: "b", 2: "H", 3: "h", 4: "I", 5: "i",
                  6: "f", 7: "?", 10: "Q", 11: "q", 12: "d"}


def read_exact(stream, count: int) -> bytes:
    data = stream.read(count)
    if len(data) != count:
        raise ValueError("truncated GGUF metadata")
    return data


def integer(stream, width: str):
    return struct.unpack("<" + width, read_exact(stream, struct.calcsize(width)))[0]


def string(stream) -> str:
    length = integer(stream, "Q")
    if length > 1_048_576:
        raise ValueError("GGUF metadata string exceeds bound")
    return read_exact(stream, length).decode("utf-8")


def value(stream, kind: int, depth: int = 0):
    if depth > 1:
        raise ValueError("nested GGUF arrays not supported")
    if kind == 8:
        return string(stream)
    if kind == 9:
        item_kind, count = integer(stream, "I"), integer(stream, "Q")
        if count > 1_048_576:
            raise ValueError("GGUF metadata array exceeds bound")
        return [value(stream, item_kind, depth + 1) for _ in range(count)]
    if kind not in SCALAR_FORMATS:
        raise ValueError(f"unsupported GGUF metadata type {kind}")
    return integer(stream, SCALAR_FORMATS[kind])


def validate_payload(path, tensors, alignment, data_offset):
    end = 0
    for tensor in sorted(tensors, key=lambda record: record['offset']):
        offset = tensor['offset']
        if offset % alignment != 0 or offset != (end + alignment - 1) // alignment * alignment:
            raise ValueError('invalid, misaligned or overlapping GGUF tensor offsets')
        end = offset + tensor['bytes']
    if data_offset + end != path.stat().st_size:
        raise ValueError('DiT GGUF payload or file size differs from header')


def read_metadata(stream, count):
    metadata = {}
    for _ in range(count):
        key = string(stream)
        if key in metadata:
            raise ValueError('duplicate GGUF metadata key')
        metadata[key] = value(stream, integer(stream, 'I'))
    return metadata


def audit_gguf(path: Path, *, expected_tensors: int = TENSOR_COUNT,
               expected_parameters: int = PARAMETERS) -> dict:
    formats = Counter()
    parameters = 0
    tensors = []
    with path.open("rb") as stream:
        if read_exact(stream, 4) != b"GGUF" or integer(stream, "I") != 3:
            raise ValueError("expected GGUF v3")
        count, kv_count = integer(stream, "Q"), integer(stream, "Q")
        if count != expected_tensors or kv_count > 4096:
            raise ValueError("unexpected DiT GGUF tensor or metadata count")
        metadata = read_metadata(stream, kv_count)
        names = set()
        for _ in range(count):
            name, dimensions = string(stream), integer(stream, "I")
            if name in names or not name.startswith("model.diffusion_model."):
                raise ValueError("duplicate or non-DiT tensor name")
            names.add(name)
            if not 1 <= dimensions <= 4:
                raise ValueError("unexpected GGUF tensor rank")
            shape = [integer(stream, "Q") for _ in range(dimensions)]
            kind, offset = integer(stream, "I"), integer(stream, "Q")
            if kind not in TYPE_SIZES or any(size == 0 or size > 2**31 for size in shape):
                raise ValueError("unexpected DiT GGUF dtype or shape")
            block, size = TYPE_SIZES[kind]
            elements = math.prod(shape)
            if shape[0] % block != 0:
                raise ValueError("quantized GGUF row is not block aligned")
            tensors.append({"name": name, "shape": shape, "type": TYPE_NAMES[kind],
                            "offset": offset, "bytes": elements // block * size})
            formats[TYPE_NAMES[kind]] += 1
            parameters += elements
        alignment = metadata.get("general.alignment", 32)
        if type(alignment) is not int or alignment < 1 or alignment > 4096 or alignment & (alignment - 1):
            raise ValueError("invalid GGUF alignment")
        data_offset = (stream.tell() + alignment - 1) // alignment * alignment
    if parameters != expected_parameters or formats["Q4_K"] == 0:
        raise ValueError("DiT GGUF parameter count differs or has no Q4_K tensors")
    if expected_tensors == TENSOR_COUNT:
        layers = {int(name.split("transformer_blocks.", 1)[1].split(".", 1)[0])
                  for name in names if "transformer_blocks." in name}
        if "model.diffusion_model.txt_in.text_norm.weight" not in names or layers != set(range(32)):
            raise ValueError("DiT GGUF does not match the Qwen Image 2.1 32-layer architecture")
    validate_payload(path, tensors, alignment, data_offset)
    return {"path": str(path), "file_bytes": path.stat().st_size, "parameters": parameters,
            "tensor_count": count, "tensor_types": dict(formats), "data_offset": data_offset,
            "tensors": tensors}


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def run_conversion(options, command):
    # Import before spawning, so missing monitor dependencies cannot orphan a job.
    import psutil
    process, failure = None, None
    try:
        with options.log.open('x', encoding='utf-8') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            memory = watch_job(process, max_seconds=1200, max_rss_bytes=8*1024**3,
                               minimum_available_bytes=512*1024**2)
    except BaseException as error:
        failure = error
        raise
    finally:
        try:
            errors = stop_owned_process(process, {}) if process is not None else []
            if errors:
                raise RuntimeError('Owned converter cleanup errors: '+'; '.join(errors))
        except BaseException as error:
            if failure is None:
                raise
            failure.add_note(f'Owned converter cleanup failed: {error}')
    if memory['stop_reason']:
        raise RuntimeError(memory['stop_reason'])
    if process.returncode != 0:
        raise RuntimeError(f'DiT conversion failed with exit {process.returncode}; see {options.log}')
    return memory


def convert_and_publish(options, partial, staging, command, started):
    memory = run_conversion(options, command)
    report = audit_gguf(partial)
    os.link(partial, options.output)
    partial.unlink()
    staging.rmdir()
    report.update({'path': str(options.output), 'elapsed_seconds': time.monotonic() - started,
                   'sha256': sha256_file(options.output), 'command': command,
                   'converter_memory': memory,
                   'architecture': 'QwenImage21Transformer2DModel/32layers/4096context/64latent',
                   'model_directory_written': False, 'text_encoder_loaded': False,
                   'generation_executed': False})
    return report


def quantize(options) -> dict:
    destinations = [options.output, options.log, options.report]
    validate_outputs([options.model_root, options.executable], destinations, must_be_new=destinations)
    index = options.model_root / "models/Qwen-Image-2.1-Turbo/transformer/diffusion_pytorch_model.safetensors.index.json"
    if not index.is_file() or not options.executable.is_file():
        raise ValueError("required DiT shard index or built sd-cli missing")
    for path in destinations:
        path.parent.mkdir(parents=True, exist_ok=True)
    # Upstream owns its output writer. Keep it in a new private staging directory,
    # then publish exclusively rather than POSIX rename() silently replacing a file.
    staging = Path(tempfile.mkdtemp(prefix=options.output.name+".staging-", dir=options.output.parent))
    partial = staging / "dit.partial.gguf"
    command = [str(options.executable.resolve()), "--mode", "convert", "--diffusion-model", str(index.resolve()),
               "--type", "q4_K", "--convert-name", "-t", "4", "-o", str(partial.resolve())]
    started = time.monotonic()
    try:
        return convert_and_publish(options, partial, staging, command, started)
    except BaseException as error:
        write_report(options.report, {'complete': False, 'error_type': type(error).__name__,
            'error': str(error), 'partial': str(partial), 'command': command,
            'elapsed_seconds': time.monotonic()-started}, error)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-root", type=Path, required=True)
    parser.add_argument("--executable", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--log", type=Path, required=True)
    options = parser.parse_args()
    if options.report.exists() or options.log.exists():
        raise FileExistsError("report/log already exists; choose new paths")
    report = quantize(options)
    with options.report.open("x", encoding="utf-8") as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
    print(json.dumps({key: report[key] for key in ("file_bytes", "tensor_count", "tensor_types", "sha256")}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
