"""Build a small phone seed from verified generated photos and CC0 source images."""
import hashlib
import json
from pathlib import Path


def prepare(project: Path):
    output = project / "artifacts" / "native-media-seed"
    output.mkdir(parents=True, exist_ok=True)
    licensed = project / "artifacts" / "reaction-stickers-v2" / "licensed-base"
    expected = {item["file"]: item["sha256"] for item in json.loads((licensed / "sources.json").read_text("utf-8"))["items"]}
    manifest = []
    for item in json.loads((licensed / "operator-manifest.json").read_text("utf-8")):
        relative = item["file"]
        source = (licensed / relative).resolve()
        if not source.is_relative_to(licensed.resolve()):
            raise ValueError("Seed source escapes its licensed directory")
        raw = source.read_bytes()
        if hashlib.sha256(raw).hexdigest() != expected[relative]:
            raise ValueError("CC0 source hash mismatch")
        (output / source.name).write_bytes(raw)
        metadata = item["metadata"]
        manifest.append({"file": source.name, "kind": "sticker", "description": metadata["action"],
                         "tags": metadata["tags"], "source": metadata["source_label"]})
    photos = project / "artifacts" / "media-seed"
    for item in json.loads((photos / "metadata.json").read_text("utf-8"))["assets"]:
        if item["file"] not in {"photos/orange-tabby-window-midday.png", "photos/park-after-rain-morning.png"}:
            continue
        source = photos / item["file"]
        raw = source.read_bytes()
        if hashlib.sha256(raw).hexdigest() != item["sha256"]:
            raise ValueError("Generated photo hash mismatch")
        (output / source.name).write_bytes(raw)
        description = "窗边毯子上休息的橘猫，安静日常。" if item["category"] == "cat" else "雨后公园，湿润小路与树木，平静的清晨风景。"
        manifest.append({"file": source.name, "kind": "image", "description": description,
                         "tags": item["tags"], "source": "AI 生成的虚构生活场景"})
    license_dir = output / "licenses"
    license_dir.mkdir(exist_ok=True)
    for name in ("CC0-1.0.txt", "magory-original-readme.txt", "magory-author-page.html"):
        (license_dir / name).write_bytes((licensed / "licenses" / name).read_bytes())
    (output / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), "utf-8")
    return {"photos": sum(item["kind"] == "image" for item in manifest),
            "stickers": sum(item["kind"] == "sticker" for item in manifest), "output": str(output)}


if __name__ == "__main__":
    print(json.dumps(prepare(Path(__file__).resolve().parents[1]), ensure_ascii=False))
