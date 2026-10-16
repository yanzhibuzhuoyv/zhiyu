"""Atomic revision folders and locked compare-and-swap for local editing."""
import os
from pathlib import Path
import re
import shutil
import threading
import uuid
from contextlib import contextmanager

from .assets import build_asset, decode_source, default_definition
from .image_io import sha
from .package import json_bytes, pack, parse_json, payload, unpack

PROJECT_ID = re.compile(r"[a-f0-9]{32}")
MEDIA_FILE = re.compile(r"source\.png|textures/[a-f0-9]{64}\.png")


@contextmanager
def project_lock(path):
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("a+b") as handle:
        handle.seek(0)
        if handle.read(1) == b"":
            handle.write(b"0")
            handle.flush()
        handle.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(handle.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(handle.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            handle.seek(0)
            if os.name == "nt":
                msvcrt.locking(handle.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(handle.fileno(), fcntl.LOCK_UN)


class Storage:
    _guard = threading.Lock()
    _locks = {}

    def __init__(self, root):
        self.root = Path(root).resolve()
        self.root.mkdir(parents=True, exist_ok=True)
        with self._guard:
            self._lock = self._locks.setdefault(str(self.root), threading.RLock())

    def _project(self, project_id):
        if not isinstance(project_id, str) or not PROJECT_ID.fullmatch(project_id):
            raise ValueError("INVALID_PROJECT_ID")
        path = self.root / project_id
        if not path.resolve().is_relative_to(self.root):
            raise ValueError("UNSAFE_PROJECT_PATH")
        return path

    def _active(self, project_id):
        project = self._project(project_id)
        active = parse_json((project / "active.json").read_bytes())
        if not isinstance(active, dict) or set(active) != {"revision"}:
            raise ValueError("INVALID_ACTIVE")
        revision = active["revision"]
        if type(revision) is not int or not 1 <= revision <= 1_000_000:
            raise ValueError("INVALID_ACTIVE_REVISION")
        version = project / f"rev-{revision}"
        if not version.resolve().is_relative_to(project.resolve()):
            raise ValueError("UNSAFE_REVISION_PATH")
        return project, version, revision

    def _read(self, project_id):
        project, version, revision = self._active(project_id)
        asset = parse_json((version / "asset.json").read_bytes())
        definition = parse_json((version / "definition.json").read_bytes())
        provenance = parse_json((project / "provenance.json").read_bytes())
        return {"id": project_id, "revision": revision, "width": asset["width"],
                "height": asset["height"], "name": asset["name"], "definition": definition,
                "asset": asset, "sourceOriginalSha": provenance["sourceOriginalSha"]}

    def _cleanup(self, path):
        target = path.resolve()
        if target.is_relative_to(self.root) and target != self.root and path.exists():
            shutil.rmtree(path)

    def _points_to(self, project, revision):
        pointer = project / "active.json"
        if not pointer.exists():
            return False
        active = parse_json(pointer.read_bytes())
        return isinstance(active, dict) and active.get("revision") == revision

    def _commit(self, project_id, revision, result):
        project = self._project(project_id)
        destination = project / f"rev-{revision}"
        if destination.exists():
            if self._points_to(project, revision):
                raise ValueError("REVISION_ALREADY_ACTIVE")
            self._cleanup(destination)
        stage = project / f"stage-{uuid.uuid4().hex}"
        pointer = project / f"active-{uuid.uuid4().hex}.tmp"
        stage.mkdir()
        try:
            for filename, raw in payload(result).items():
                target = stage / filename
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open("xb") as handle:
                    handle.write(raw)
                    handle.flush()
                    os.fsync(handle.fileno())
            os.replace(stage, destination)
            with pointer.open("xb") as handle:
                handle.write(json_bytes({"revision": revision}))
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(pointer, project / "active.json")
        except BaseException:
            if not self._points_to(project, revision):
                self._cleanup(destination)
            raise
        finally:
            self._cleanup(stage)
            if pointer.resolve().is_relative_to(self.root):
                pointer.unlink(missing_ok=True)
        return self._read(project_id)

    def _create(self, original, result):
        project_id = uuid.uuid4().hex
        project = self._project(project_id)
        project.mkdir()
        try:
            (project / "original.bin").write_bytes(original)
            (project / "provenance.json").write_bytes(json_bytes({"sourceOriginalSha": sha(original)}))
            return self._commit(project_id, 1, result)
        except BaseException:
            self._cleanup(project)
            raise

    def create(self, raw, name):
        image = decode_source(raw)
        result = build_asset(image, default_definition(image), name)
        with self._lock:
            return self._create(raw, result)

    def get(self, project_id):
        with self._lock:
            return self._read(project_id)

    def build(self, project_id, revision, definition):
        if type(revision) is not int or revision < 1:
            raise ValueError("INVALID_REVISION")
        project = self._project(project_id)
        if not project.exists():
            raise FileNotFoundError(project)
        with self._lock, project_lock(project / ".lock"):
            active, version, current = self._active(project_id)
            if revision != current:
                raise ValueError("REVISION_CONFLICT")
            snapshot = self._read(project_id)
            source = (version / "source.png").read_bytes()
            if sha(source) != snapshot["asset"]["sourceSha"]:
                raise ValueError("SOURCE_HASH_MISMATCH")
            result = build_asset(decode_source(source), definition, snapshot["name"])
            return self._commit(project_id, current + 1, result)

    def export(self, project_id):
        with self._lock:
            _, version, _ = self._active(project_id)
            manifest = parse_json((version / "manifest.json").read_bytes())
            files = {name: (version / name).read_bytes() for name in manifest["files"]}
            if any(sha(raw) != manifest["files"][name] for name, raw in files.items()):
                raise ValueError("STORED_HASH_MISMATCH")
            files["manifest.json"] = json_bytes(manifest)
            return pack(files)

    def restore(self, package):
        result, _ = unpack(package)
        with self._lock:
            return self._create(result["files"]["source.png"], result)

    def file(self, project_id, path):
        if not isinstance(path, str) or not MEDIA_FILE.fullmatch(path):
            raise ValueError("INVALID_MEDIA_PATH")
        with self._lock:
            _, version, _ = self._active(project_id)
            manifest = parse_json((version / "manifest.json").read_bytes())
            if path not in manifest["files"]:
                raise ValueError("MEDIA_NOT_IN_MANIFEST")
            result = version / path
            if not result.resolve().is_relative_to(version.resolve()):
                raise ValueError("UNSAFE_MEDIA_PATH")
            if sha(result.read_bytes()) != manifest["files"][path]:
                raise ValueError("STORED_MEDIA_HASH_MISMATCH")
            return result
