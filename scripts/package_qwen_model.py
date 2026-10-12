"""Create or stream-verify the pinned ZYQPACK1 offline Qwen model bundle."""
from pathlib import Path
import os

MAGIC = b"ZYQPACK1"
MANIFEST_SHA = "9232afcbed6d188a9bdc58c0b8841b35bb12773aaee9fc745dc8027641c656e6"
CHUNK_BYTES = 64 * 1024


def package_model(source: Path, output: Path, *, trusted_sha=MANIFEST_SHA,
                  check_cancelled=lambda: None):
    raise NotImplementedError("Offline bundle producer is not implemented")


def verify_package(path: Path, *, trusted_sha=MANIFEST_SHA,
                   check_cancelled=lambda: None):
    raise NotImplementedError("Offline bundle verification is not implemented")


def main(argv=None):
    raise NotImplementedError("Offline bundle CLI is not implemented")


if __name__ == "__main__":
    main()
