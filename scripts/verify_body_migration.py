"""Real HTTP and storage proof on an exact synthetic source copy, never user data."""

import argparse
import hashlib
import json
import os
import shutil
import sqlite3
import time
from contextlib import closing
from pathlib import Path

import httpx
from smoke_long_fields import (
    LoopbackServer,
    SyntheticProvider,
    require,
    signature,
    snapshot,
    verify_all_fragments,
    verify_page,
    verify_reference,
)

SOURCE_SHA = "3abc5989d1024138ebf38aa0fa816e6a4240d74015377d1960757df05d8b45b0"


def hashes(directory):
    result = {}
    for name in ("chat.sqlite3", "chat.sqlite3-wal", "chat.sqlite3-shm"):
        path = directory / name
        if path.exists():
            with path.open("rb") as stream:
                result[name] = hashlib.file_digest(stream, "sha256").hexdigest()
    return result


def row_digests(chat):
    from app.body_codec import decode_body

    result = {}
    with chat.connection() as db:
        db.execute("BEGIN")
        for table, order in (("conversations", "id"), ("messages", "sequence"),
                             ("memory_fragments", "id"), ("memory_fts", "rowid")):
            digest, count = hashlib.sha256(), 0
            sql = "SELECT rowid FROM memory_fts ORDER BY rowid" if table == "memory_fts" else (
                "SELECT * FROM " + table + " ORDER BY " + order)
            for row in db.execute(sql):
                values = dict(row)
                if table == "messages":
                    values = {**values, "content": decode_body(values["content"])}
                fields = {key: {"blob": value.hex()} if isinstance(value, bytes) else value
                          for key, value in values.items()}
                payload = json.dumps(fields, ensure_ascii=False, sort_keys=True).encode()
                digest.update(len(payload).to_bytes(8, "big"))
                digest.update(payload)
                count += 1
            result[table] = {"rows": count, "sha256": digest.hexdigest()}
    with chat.connection() as db:
        db.execute("INSERT INTO memory_fts(memory_fts) VALUES('integrity-check')")
    return result


def originals(chat, owner, identifier):
    with chat.connection() as db:
        return [signature(chat._message(row)) for row in db.execute(
            "SELECT * FROM messages WHERE conversation_id=? ORDER BY sequence", (identifier,))]


def history_check(client, identifier, headers, expected):
    position, before, seen, pages = len(expected), None, set(), 0
    while True:
        response = client.get(f"/api/conversations/{identifier}/history", headers=headers,
                              params={"limit": 100, **({"before": before} if before else {})})
        require(response.status_code == 200, "History HTTP failure")
        page = response.json()
        position = verify_page(page["messages"], expected, position, seen)
        pages += 1
        if not page["has_more"]:
            break
        before = page["next_before"]
    require(position == 0 and len(seen) == len(expected), "Incomplete history")
    return {"pages": pages, "messages": len(seen), "all_original_metadata_hashes_equal": True}


def migration(chat, owner, identifier):
    after, through, reports = 0, None, []
    while True:
        result = chat.migrate_bodies(owner, identifier, after=after, through=through)
        require(result is not None, "Owned migration returned no result")
        reports.append(result)
        after, through = result["after"], result["through"]
        if result["complete"]:
            break
        require(result["visited"] > 0, "Migration did not advance")
    return {"batches": len(reports), "visited": sum(row["visited"] for row in reports),
            "converted": sum(row["converted"] for row in reports), "through": through,
            "original_utf8_bytes": sum(row["raw_bytes"] for row in reports),
            "encoded_bytes": sum(row["stored_bytes"] for row in reports),
            "max_batch_old_plus_new_bytes": max(row["raw_bytes"] + row["stored_bytes"] for row in reports),
            "oversized_single_rows": sum(row["oversized_row"] for row in reports)}


def probes(chat, client, owner, identifier, headers):
    cases = [(role, turn, location) for role, turn in (("user", 1), ("assistant", 1), ("assistant", 250))
             for location in ("middle", "tail")]
    output = []
    for role, turn, location in cases:
        with chat.connection() as db:
            rows = db.execute("SELECT * FROM messages WHERE conversation_id=? ORDER BY sequence LIMIT 2 OFFSET ?",
                              (identifier, (turn - 1) * 2)).fetchall()
        user, assistant = (chat._message(row) for row in rows)
        query = f"Long{role.title()}{location.title()}{turn:04d}"
        response = client.post(f"/api/conversations/{identifier}/messages", headers=headers,
                               json={"content": query})
        require(response.status_code == 200, "Compressed original RAG HTTP failure")
        output.append(verify_reference(response.json()["memory_refs"],
                                       user if role == "user" else assistant,
                                       user["id"], location, turn))
    return output


def run(source, target, report_path):
    started = time.perf_counter()
    require(source.resolve() != target.resolve() and not target.resolve().is_relative_to(source.resolve()),
            "Source and copy must be distinct")
    require(not report_path.resolve().is_relative_to(source.resolve()), "Report must stay outside source")
    require(not target.exists() and not report_path.exists(), "Use fresh copy and report paths")
    source_before = hashes(source)
    require(source_before.get("chat.sqlite3") == SOURCE_SHA, "Only the exact stopped synthetic d65 source is accepted")
    target.mkdir(parents=True)
    for name in source_before:
        shutil.copy2(source / name, target / name)
    from app.config import Settings
    from app.main import create_app

    app = create_app(Settings(data_dir=target, allow_demo=True), provider_client=SyntheticProvider())
    server = LoopbackServer(app)
    with httpx.Client(base_url=server.start(), timeout=120, trust_env=False) as client:
        try:
            result = verify_run(app, client, target)
        finally:
            server.stop()
    source_after = hashes(source)
    require(source_before == source_after, "Retained source files changed")
    report = {"status": "passed", "scope": "synthetic 20000 originals copied; source readonly; no cloud calls",
              "owner_rebound_only_in_copy": True, "uid": os.getuid(),
              "source_before": source_before, "source_after": source_after, "source_unchanged": True,
              "elapsed_seconds": time.perf_counter() - started, **result}
    with report_path.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return report


def verify_run(app, client, target):
    chat = app.state.chat
    response = client.post("/api/auth/login", json={"provider_id": "demo", "api_key": "synthetic-body-migration-key"})
    require(response.status_code == 200, "Synthetic authentication failed")
    headers = {"Authorization": "Bearer " + response.json()["token"]}
    owner = app.state.sessions.get(response.json()["token"]).owner
    with chat.connection() as db:
        identifier = db.execute("SELECT id FROM conversations").fetchone()[0]
        require(db.execute("SELECT count(*) FROM messages").fetchone()[0] == 20000, "Source has unexpected rows")
        db.execute("UPDATE conversations SET owner=? WHERE id=?", (owner, identifier))
    expected, baseline = originals(chat, owner, identifier), row_digests(chat)
    before = snapshot(chat.path)
    backup = target.parent / "rollback.sqlite3"
    require(not backup.exists(), "Do not overwrite an existing rollback database")
    with chat.connection() as db, closing(sqlite3.connect(backup)) as destination:
        db.backup(destination)
    require(chat.migrate_bodies("foreign-owner", identifier) is None, "Foreign migration must be rejected")
    require(row_digests(chat) == baseline, "Rejected migration changed originals")
    before_history = history_check(client, identifier, headers, expected)
    fault = fault_check(chat, owner, identifier, baseline)
    result = migration(chat, owner, identifier)
    require(result["visited"] == 20000, "Incomplete migration")
    require(row_digests(chat) == baseline, "Migration changed originals, metadata or fragment identities")
    migrated_digests = row_digests(chat)
    after_history = history_check(client, identifier, headers, expected)
    offsets, after = verify_all_fragments(chat.path), snapshot(chat.path)
    require(chat.migrate_bodies(owner, identifier)["converted"] == 0, "Repeated migration is not idempotent")
    recall = probes(chat, client, owner, identifier, headers)
    # Compact a separate disposable output; do not VACUUM the source or user files.
    compacted = target.parent / "compacted.sqlite3"
    with chat.connection() as db:
        db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        db.execute("VACUUM INTO ?", (str(compacted),))
    rollback = rollback_check(chat, backup, target.parent / "rollback-check", baseline)
    restart = restart_check(target, identifier, originals(chat, owner, identifier), headers)
    return {"before": before, "fault_batch_rollback": fault, "migration": result, "after": after,
            "original_digests": baseline, "migrated_digests": migrated_digests,
            "before_history": before_history, "after_history": after_history,
            "offsets": offsets, "recall": recall, "rollback_restore": rollback,
            "restart": restart, "backup_bytes": backup.stat().st_size,
            "compacted_bytes_after_six_probe_turns": compacted.stat().st_size,
            "backup_path": str(backup), "compacted_path": str(compacted),
            "limitation": "Compact output includes six newly saved probe turns; backup/working/compact are all temporary copies. No raw-source or user-data compaction; no cloud factuality or cold retrieval claim."}


def fault_check(chat, owner, identifier, baseline):
    from app.source_codec import ChatStorageError

    with chat.connection() as db:
        sequence = db.execute("SELECT sequence FROM messages WHERE conversation_id=? ORDER BY sequence LIMIT 1 OFFSET 1",
                              (identifier,)).fetchone()[0]
        db.execute(f"""CREATE TRIGGER migration_test_fault BEFORE UPDATE OF content ON messages
            WHEN new.sequence={int(sequence)} BEGIN SELECT RAISE(ABORT,'synthetic migration fault'); END""")
    failed = False
    try:
        chat.migrate_bodies(owner, identifier)
    except ChatStorageError:
        failed = True
    require(failed and row_digests(chat) == baseline, "Failed batch did not roll back completely")
    with chat.connection() as db:
        db.execute("DROP TRIGGER migration_test_fault")
    return {"fault_at_batch_message": 2, "safe_error": True, "all_original_and_index_digests_unchanged": True}


def rollback_check(chat, backup, destination, baseline):
    from app.database import ChatDatabase

    destination.mkdir()
    with closing(sqlite3.connect(backup.as_uri() + "?mode=ro", uri=True)) as source, closing(
        sqlite3.connect(destination / "chat.sqlite3")
    ) as target:
        source.backup(target)
    restored = ChatDatabase(destination)
    require(row_digests(restored) == baseline, "Backup restoration changed original records")
    with restored.connection() as db:
        blobs = db.execute("SELECT count(*) FROM messages WHERE typeof(content)='blob'").fetchone()[0]
    require(blobs == 0, "Rollback copy is not legacy TEXT")
    return {"all_original_and_index_digests_equal": True, "body_blobs": blobs,
            "limitation": "Backup restored through new backward-compatible reader; old runtime not launched"}


def restart_check(target, identifier, expected, old_headers):
    from app.config import Settings
    from app.main import create_app

    app = create_app(Settings(data_dir=target, allow_demo=True), provider_client=SyntheticProvider())
    server = LoopbackServer(app)
    with httpx.Client(base_url=server.start(), timeout=120, trust_env=False) as client:
        try:
            require(client.get("/api/conversations", headers=old_headers).status_code == 401, "Old session unexpectedly retained")
            response = client.post("/api/auth/login", json={"provider_id": "demo", "api_key": "synthetic-body-migration-key"})
            require(response.status_code == 200, "Restart login failed")
            headers = {"Authorization": "Bearer " + response.json()["token"]}
            history = history_check(client, identifier, headers, expected)
            owner = app.state.sessions.get(response.json()["token"]).owner
            recall = probes(app.state.chat, client, owner, identifier, headers)
            return {"history": history, "same_synthetic_key_restores_owner": True,
                    "old_token_rejected": True, "recall": recall}
        finally:
            server.stop()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-dir", type=Path, required=True)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = run(args.source_dir, args.data_dir, args.report)
        print(json.dumps({key: result[key] for key in ("status", "migration", "elapsed_seconds")}))
    except Exception as error:
        # A rejected report path must never be used by the failure handler either.
        # stdout/nonzero retains the failure without writing any unverified path.
        failure = {"status": "failed", "failure_type": type(error).__name__}
        print(json.dumps(failure))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
