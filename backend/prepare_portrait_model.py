"""Provision the fixed public model at image-build time, never during requests."""
import argparse
import hashlib
import urllib.request
from pathlib import Path

from app.portrait_subject import MODEL_BYTES, MODEL_SHA256, MODEL_URL


def prepare(path):
    path = Path(path)
    if path.exists():
        data = path.read_bytes()
    else:
        with urllib.request.urlopen(MODEL_URL, timeout=60) as response:
            data = response.read(MODEL_BYTES + 1)
    if len(data) != MODEL_BYTES or hashlib.sha256(data).hexdigest() != MODEL_SHA256:
        raise ValueError("固定人物分离模型校验失败")
    path.parent.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        with path.open("xb") as output:
            output.write(data)
    print(f"prepared u2netp {len(data)} bytes sha256={MODEL_SHA256}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    prepare(parser.parse_args().output)
