"""Test Agnes image understanding with a synthetic local image, without publishing it."""
import argparse
import base64
import hashlib
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
from agnes_image_probe import key_from_file, NoRedirect


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--credential-file", type=Path, required=True)
    parser.add_argument("--image", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    key, digest = key_from_file(args.credential_file)
    image = args.image.read_bytes()
    if not image.startswith(b"\x89PNG\r\n\x1a\n") or len(image) > 5 * 1024 * 1024:
        raise ValueError("Expected bounded synthetic PNG")
    endpoint = "https://apihub.agnes-ai.com/v1/chat/completions"
    report = {"endpoint": endpoint, "model": "agnes-2.5-flash", "synthetic_only": True,
              "input_image_sha256": hashlib.sha256(image).hexdigest(), "input_format": "image_url_data_uri"}
    prompt = "只描述你在图片中实际看到的人物、翅膀数量、衣服和携带物；不猜测身份。用中文，最多200字。"
    body = json.dumps({"model": report["model"], "messages": [{"role": "user", "content": [
        {"type": "text", "text": prompt},
        {"type": "image_url", "image_url": {"url": "data:image/png;base64," + base64.b64encode(image).decode("ascii")}}
    ]}], "stream": False, "max_tokens": 4096}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(endpoint, data=body, headers={"Authorization": "Bearer " + key,
                                 "Content-Type": "application/json", "Accept": "application/json"})
    start = time.monotonic()
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=360) as response:
            raw = response.read(2 * 1024 * 1024 + 1)
            if len(raw) > 2 * 1024 * 1024:
                raise ValueError("Oversized response")
            result = json.loads(raw)
            choice = result["choices"][0]
            content = choice["message"].get("content")
            if choice.get("finish_reason") == "length" or not isinstance(content, str) or not content.strip():
                raise ValueError("Incomplete vision answer")
            report.update(status="passed", http_status=response.status, answer=content.replace(key, "[REDACTED]"),
                          elapsed_seconds=round(time.monotonic() - start, 3), usage=result.get("usage"))
    except urllib.error.HTTPError as error:
        report.update(status="failed", http_status=error.code)
    except Exception as error:
        report.update(status="failed", failure_type=type(error).__name__)
    finally:
        report["credential_source_unchanged"] = hashlib.sha256(args.credential_file.read_bytes()).hexdigest() == digest
        report["credential_copied_to_artifacts"] = False
        (args.output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({k: v for k, v in report.items() if k not in ("answer", "usage")}), flush=True)
        key = ""
    return 0 if report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
