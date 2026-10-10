"""Explicit Agnes API smoke test. Credentials stay in memory; only synthetic artwork is sent."""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import struct
import time
import urllib.error
import urllib.request

ENDPOINT = "https://apihub.agnes-ai.com/v1/images/generations"
MODEL = "agnes-image-2.5-flash"
MAX_RESPONSE = 32 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def key_from_file(path: Path) -> tuple[str, str]:
    data = path.read_bytes()
    if len(data) > 16384:
        raise ValueError("Credential file exceeds expected size")
    encoding = "utf-16" if data.startswith((b"\xff\xfe", b"\xfe\xff")) else "utf-8-sig"
    value = data.decode(encoding).strip()
    if value.startswith("{"):
        value = json.loads(value)["api_key"]
    if not isinstance(value, str) or not re.fullmatch(r"[!-~]{16,4096}", value):
        raise ValueError("Expected one API key, without whitespace")
    return value, hashlib.sha256(data).hexdigest()


def call(key: str, prompt: str, reference: bytes | None = None) -> tuple[dict, bytes]:
    payload = {"model": MODEL, "prompt": prompt, "size": "1K", "ratio": "3:4",
               "return_base64": True, "extra_body": {"response_format": "b64_json"}}
    if reference:
        payload["extra_body"]["image"] = ["data:image/png;base64," + base64.b64encode(reference).decode("ascii")]
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(ENDPOINT, data=body, headers={
        "Authorization": "Bearer " + key, "Content-Type": "application/json", "Accept": "application/json"})
    # Honor the user's configured HTTPS CONNECT proxy, with normal TLS verification.
    # The Authorization header is only sent inside the encrypted Agnes HTTPS session.
    opener = urllib.request.build_opener(NoRedirect())
    started = time.monotonic()
    try:
        with opener.open(request, timeout=360) as response:
            raw = response.read(MAX_RESPONSE + 1)
            if len(raw) > MAX_RESPONSE:
                raise ValueError("Oversized response")
            result = json.loads(raw)
            encoded = result["data"][0].get("b64_json")
            if not isinstance(encoded, str) or not encoded:
                raise ValueError("Expected Base64 image; no remote URL download attempted")
            image = base64.b64decode(encoded, validate=True)
            if not 128 <= len(image) <= 20 * 1024 * 1024:
                raise ValueError("Generated image size is invalid")
            png = image.startswith(b"\x89PNG\r\n\x1a\n")
            if not png and not image.startswith(b"\xff\xd8"):
                raise ValueError("Generated file is not a PNG or JPEG")
            dimensions = list(struct.unpack(">II", image[16:24])) if png else None
            return {"http_status": response.status, "elapsed_seconds": round(time.monotonic() - started, 3),
                    "image_bytes": len(image), "image_sha256": hashlib.sha256(image).hexdigest(),
                    "dimensions": dimensions, "reference_image": reference is not None}, image
    except urllib.error.HTTPError as error:
        # Never print provider body/headers: upstream errors may echo a credential.
        raise RuntimeError("Agnes returned HTTP " + str(error.code)) from None
    except urllib.error.URLError:
        raise RuntimeError("Agnes HTTPS connection failed") from None


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--credential-file", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    key, original_hash = key_from_file(args.credential_file)
    report = {"endpoint": ENDPOINT, "model": MODEL, "synthetic_only": True,
              "key_in_command_arguments": False, "calls": [], "identity_guaranteed": False}
    prompts = [
        "Original adult fictional anime man, age 26, shoulder-length silver hair, amber eyes, "
        "small scar over left eyebrow, olive cardigan, plain black trousers, exactly two pairs "
        "of feathered dark-teal wings attached to his back, a sheathed short sword at his left hip. "
        "Full body, front three-quarter view, centered, soft illustrated detail, clean cream background. "
        "All four wings and sword fully visible. No real person, no text, no logo. This is an OC identity reference.",
        "Use the reference as the ONLY character. Keep exactly the same adult face, silver hair, amber eyes, "
        "left eyebrow scar, olive cardigan, four dark-teal wings and sheathed sword, exact proportions. "
        "Only replace the cream backdrop with a warm bookstore on a rainy evening; keep pose and entire character unchanged. "
        "No other people, no duplicate limbs or wings, no text. Same illustrated rendering style.",
    ]
    try:
        reference = None
        for index, prompt in enumerate(prompts):
            print(json.dumps({"phase": index + 1, "status": "requesting", "model": MODEL}), flush=True)
            item, image = call(key, prompt, reference)
            name = "oc-reference" if index == 0 else "oc-bookstore-reference-edit"
            path = args.output / (name + (".png" if image.startswith(b"\x89PNG") else ".jpg"))
            path.write_bytes(image)
            item.update(file=path.name, prompt=prompt)
            report["calls"].append(item)
            if index == 0:
                reference = image
                if not image.startswith(b"\x89PNG"):
                    raise ValueError("Reference expected PNG for this fixed-format probe")
            print(json.dumps({"phase": index + 1, **{k: item[k] for k in ("http_status", "elapsed_seconds", "image_bytes", "file")}}), flush=True)
        report["status"] = "api_calls_passed_identity_requires_visual_review"
        return_code = 0
    except Exception as error:
        report["status"] = "failed"
        report["failure"] = str(error).replace(key, "[REDACTED]")[:180]
        print(json.dumps({"status": "failed", "type": type(error).__name__, "failure": report["failure"]}), flush=True)
        return_code = 1
    finally:
        report["credential_source_unchanged"] = hashlib.sha256(args.credential_file.read_bytes()).hexdigest() == original_hash
        report["credential_copied_to_artifacts"] = False
        (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        key = ""
    return return_code


if __name__ == "__main__":
    raise SystemExit(main())
