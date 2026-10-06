"""Verify reversible cold RAG only on an operator-owned synthetic source COPY.

Operator must mount the retained source read-only, create a separately labelled
copy volume, and prepare SYNTHETIC-MEMORY-COLD-<run>/data plus its scope marker.
This script never reads the source mount, deletes files, or contacts AI services.
"""

import argparse
import hashlib
import json
import os
import re
import sqlite3
import time
from contextlib import closing, contextmanager
from datetime import UTC, datetime
from pathlib import Path

from smoke_long_fields import CheckFailure, anchor_marker, require, verify_reference

SOURCE_SHA = "3abc5989d1024138ebf38aa0fa816e6a4240d74015377d1960757df05d8b45b0"
EXPECTED_ROWS = 20000
BATCH_ROWS = 128
BATCH_BYTES = 8 * 1024 * 1024
CASES = (("user", 9000, "middle"), ("user", 9000, "tail"),
         ("assistant", 9000, "middle"), ("assistant", 9000, "tail"),
         ("user", 1, "middle"), ("assistant", 250, "tail"))


def file_sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def guard(data, report, run_id):
    require(bool(re.fullmatch(r"[0-9a-f]{32}", run_id)), "Invalid synthetic run token")
    require(os.environ.get("ZHIYU_TEST_SCOPE") == "memory-cold-copy"
            and os.environ.get("ZHIYU_TEST_RUN") == run_id, "Missing owned synthetic scope")
    data, report = data.resolve(), report.resolve()
    root = data.parent
    require(data.name == "data" and root.name == "SYNTHETIC-MEMORY-COLD-" + run_id,
            "Only the dedicated synthetic COPY path is allowed")
    require(report.parent == root and report.name == "report.json" and not report.exists(),
            "Report must be a fresh owned report.json outside data")
    require(not (root / "compacted").exists(), "Compacted output must be fresh")
    marker = root / "SYNTHETIC_MEMORY_COLD_COPY.json"
    require(marker.is_file() and marker.stat().st_size <= 4096, "Missing synthetic COPY marker")
    try:
        value = json.loads(marker.read_text(encoding="utf-8"))
    except (ValueError, UnicodeError) as exc:
        raise CheckFailure("Invalid COPY marker") from exc
    require(value == {"scope": "memory-cold-copy", "run_id": run_id,
                      "source_sha256": SOURCE_SHA, "directory": "data"}, "Wrong COPY provenance")
    database = data / "chat.sqlite3"
    require(not database.is_symlink() and database.resolve().parent == data
            and database.is_file() and database.stat().st_nlink == 1, "COPY must not alias a source file")
    for name in ("chat.sqlite3-wal", "chat.sqlite3-shm"):
        sidecar = data / name
        require(not sidecar.is_symlink() and sidecar.resolve().parent == data, "COPY sidecar must not alias a source")
        require(not sidecar.exists() or (sidecar.is_file() and sidecar.stat().st_nlink == 1),
                "COPY sidecar must be an independent regular file")
    wal = data / "chat.sqlite3-wal"
    require(not wal.exists() or wal.stat().st_size == 0, "Only a stopped checkpointed source COPY is accepted")
    require(database.is_file() and file_sha(database) == SOURCE_SHA, "COPY is not the stopped d65 synthetic database")
    return data, report


def _digest_row(digest, values):
    payload = json.dumps(values, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()
    digest.update(len(payload).to_bytes(8, "big"))
    digest.update(payload)


def canonical(chat):
    from app.body_codec import decode_body
    from app.source_codec import decode_sources

    results, samples = {}, {}
    with chat.connection() as db:
        db.execute("BEGIN")
        previous_user = None
        for table, order in (("conversations", "id"), ("messages", "sequence")):
            digest, count, raw_bytes = hashlib.sha256(), 0, 0
            for row in db.execute("SELECT * FROM " + table + " ORDER BY " + order):
                values = dict(row)
                if table == "messages":
                    original = decode_body(values["content"])
                    encoded = {"stored_sources_sha256": hashlib.sha256(values["sources"] if isinstance(
                        values["sources"], bytes) else values["sources"].encode()).hexdigest()}
                    values = {**values, **encoded, "content": original, "sources": decode_sources(values["sources"])}
                    raw_bytes += len(original.encode("utf-8"))
                    if values["role"] == "user":
                        previous_user = values["id"]
                    header = re.match(r"LONGFIELD turn=(\d+) role=(user|assistant)", original[:100])
                    if header and int(header[1]) in {1, 250, 9000}:
                        samples[(values["role"], int(header[1]))] = {**values, "turn_id": previous_user}
                _digest_row(digest, values)
                count += 1
            results[table] = {"rows": count, "sha256": digest.hexdigest(), "original_utf8_bytes": raw_bytes}
    return {"digests": results, "samples": samples}


def _estimate(row):
    if row["body_type"] == "text":
        size = row["stored_bytes"]
    else:
        from app.body_codec import MAGIC

        prefix = row["prefix"]
        require(row["body_type"] == "blob" and prefix.startswith(MAGIC) and len(prefix) >= len(MAGIC) + 4,
                "Unsupported canonical body storage")
        size = int.from_bytes(prefix[len(MAGIC):len(MAGIC) + 4], "big")
    return size + min(1024 * 1024, size * 4 + 128)


def cool_batches(chat, owner, identifier):
    from app.body_codec import decode_body

    after, batches, visited, peak_bytes = 0, 0, 0, 0
    while True:
        with chat.connection() as db:
            db.execute("BEGIN IMMEDIATE")
            require(db.execute("SELECT 1 FROM conversations WHERE owner=? AND id=?", (owner, identifier)).fetchone(),
                    "COPY owner scope changed")
            targets = db.execute("""SELECT sequence,typeof(content) body_type,
                length(CAST(content AS BLOB)) stored_bytes,substr(CAST(content AS BLOB),1,10) prefix
                FROM messages WHERE conversation_id=? AND sequence>? ORDER BY sequence LIMIT ?""",
                                 (identifier, after, BATCH_ROWS)).fetchall()
            if not targets:
                break
            metadata = chat.annotations.metadata(db, [row["sequence"] for row in targets], datetime.now(UTC))
            budget, processed = 0, 0
            for row in targets:
                estimate = _estimate(row)
                if processed and budget + estimate > BATCH_BYTES:
                    break
                require(estimate <= BATCH_BYTES, "Single original exceeds proof batch budget")
                meta = metadata[row["sequence"]]
                require(meta["tier"] == "mid" and not meta["pinned"], "Protected original must not be cooled")
                original = decode_body(db.execute("SELECT content FROM messages WHERE sequence=?",
                                                  (row["sequence"],)).fetchone()[0])
                chat.cold.cool(db, row["sequence"], identifier, original, chat.memory)
                actual = len(original.encode("utf-8")) + db.execute(
                    "SELECT length(bloom) FROM memory_cold_directory WHERE sequence=?", (row["sequence"],)).fetchone()[0]
                require(actual <= estimate and budget + actual <= BATCH_BYTES, "Cold batch exceeded storage budget")
                budget += actual
                after, processed = row["sequence"], processed + 1
                del original
            visited += processed
            batches += 1
            peak_bytes = max(peak_bytes, budget)
        if batches % 10 == 0:
            print(json.dumps({"phase": "cooling", "batches": batches, "visited": visited}), flush=True)
    return {"visited": visited, "batches": batches, "max_batch_raw_plus_directory_bytes": peak_bytes,
            "batch_rows_limit": BATCH_ROWS, "batch_bytes_limit": BATCH_BYTES}


def migrate(chat, owner, identifier):
    started = time.perf_counter()
    with chat.connection() as db:
        through = db.execute("SELECT max(sequence) FROM messages WHERE conversation_id=?", (identifier,)).fetchone()[0]
    after, batches = 0, []
    while True:
        result = chat.migrate_bodies(owner, identifier, after=after, through=through,
                                    batch_rows=BATCH_ROWS, byte_limit=BATCH_BYTES)
        require(result is not None and result["through"] == through, "Owned migration range changed")
        batches.append(result)
        if len(batches) % 10 == 0:
            print(json.dumps({"phase": "body_migration", "batches": len(batches), "through_visited": result["after"]}), flush=True)
        if result["complete"]:
            break
        require(result["after"] > after and result["visited"] > 0, "Body migration did not advance")
        after = result["after"]
    return {"batches": len(batches), "visited": sum(row["visited"] for row in batches),
            "converted": sum(row["converted"] for row in batches), "through": through,
            "original_utf8_bytes": sum(row["raw_bytes"] for row in batches),
            "encoded_bytes": sum(row["stored_bytes"] for row in batches),
            "max_batch_raw_plus_encoded_bytes": max(row["raw_bytes"] + row["stored_bytes"] for row in batches),
            "seconds": time.perf_counter() - started}


def measure(chat, *, writable=True):
    with chat.connection() as db:
        if writable:
            db.execute("INSERT INTO memory_fts(memory_fts) VALUES('integrity-check')")
    with chat.connection() as db:
        if writable:
            db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        db.execute("BEGIN")
        try:
            tables = dict(db.execute("SELECT name,sum(pgsize) FROM dbstat GROUP BY name"))
            allocation_available = True
        except sqlite3.OperationalError as exc:
            if str(exc) != "no such table: dbstat":
                raise
            tables, allocation_available = {}, False
        counts = {key: db.execute(sql).fetchone()[0] for key, sql in {
            "messages": "SELECT count(*) FROM messages", "fragments": "SELECT count(*) FROM memory_fragments",
            "fts_rows": "SELECT count(*) FROM memory_fts", "directory_rows": "SELECT count(*) FROM memory_cold_directory",
            "cold_messages": "SELECT count(*) FROM memory_index_state WHERE state='cold'",
            "stored_body_bytes": "SELECT sum(length(CAST(content AS BLOB))) FROM messages",
        }.items()}
        require(db.execute("PRAGMA quick_check").fetchone()[0] == "ok", "COPY quick_check failed")
        require(counts["fragments"] == counts["fts_rows"], "FTS identity count differs")
        counts["active_messages"] = counts["messages"] - counts["cold_messages"]
        counts["allocation"] = {"available": allocation_available, "tables_bytes": tables,
            "active_index_bytes": sum(size for name, size in tables.items() if name.startswith(("memory_fts", "memory_fragments", "sqlite_autoindex_memory_fragments"))) if allocation_available else None,
            "directory_bytes": sum(size for name, size in tables.items() if name.startswith(("memory_cold_directory", "sqlite_autoindex_memory_cold_directory"))) if allocation_available else None}
        counts["sqlite"] = {name: db.execute("PRAGMA " + name).fetchone()[0]
                            for name in ("page_size", "page_count", "freelist_count")}
    return {**counts, "files_bytes": {suffix or "main": Path(str(chat.path) + suffix).stat().st_size
                                     if Path(str(chat.path) + suffix).exists() else 0 for suffix in ("", "-wal", "-shm")}}


def verify_directory(chat):
    from app.body_codec import decode_body
    from app.memory_bloom import decode_bloom

    count, digest = 0, hashlib.sha256()
    with chat.connection() as db:
        db.execute("BEGIN")
        for row in db.execute("""SELECT d.*,m.content FROM memory_cold_directory d
            JOIN messages m ON m.sequence=d.sequence ORDER BY d.sequence"""):
            original = decode_body(row["content"])
            require(len(original) == row["char_length"] and hashlib.sha256(original.encode()).hexdigest() == row["source_sha"],
                    "Directory original integrity mismatch")
            decode_bloom(row["bloom"])
            digest.update(row["sequence"].to_bytes(8, "big") + hashlib.sha256(row["bloom"]).digest())
            count += 1
    return {"rows": count, "frames_sha256": digest.hexdigest(), "all_frames_and_original_sha_verified": True}


def lexical_probe_query(original, marker):
    from app.memory_index import chunk_offsets
    from app.vendor_rag.bm25 import _tokenize

    # A synthetic marker may touch a random ASCII prefix in the source. Use its
    # actual indexed whole token; never rewrite the original to create a hit.
    for start, end in chunk_offsets(original):
        for term in _tokenize(original[start:end]):
            if marker.lower() in term:
                return term
    raise CheckFailure("Synthetic marker has no complete lexical token")


def probes(chat, owner, identifier, samples):
    from app.conversation_memory import (
        _references,
        conversation_context_result,
        memory_terms,
    )
    from app.memory_ranking import select_memory

    normal, paged = [], []
    with chat.connection() as db:
        before = db.execute("SELECT max(sequence)+1 FROM messages WHERE conversation_id=?", (identifier,)).fetchone()[0]
    for role, turn, location in CASES:
        source = samples[(role, turn)]
        marker, started = anchor_marker(role, location, turn), time.perf_counter()
        query = lexical_probe_query(source["content"], marker)
        context = conversation_context_result(chat, owner, identifier, query)
        if turn == 9000:
            record = verify_reference(context.references, source, source["turn_id"], location, turn)
            normal.append({**record, "complete": context.complete, "seconds": time.perf_counter() - started})
            continue
        cursor, pages, found, scans, decodes = None, 0, False, 0, 0
        while True:
            with chat.connection() as db:
                db.execute("BEGIN")
                result = chat.cold.search(db, owner, identifier, before, memory_terms(query), cursor=cursor)
            chosen = select_memory(result["turns"], memory_terms(query), scope=(owner, identifier))
            refs = [ref for item in chosen for ref in _references(item)]
            pages, scans, decodes = pages + 1, scans + result["scanned"], decodes + result["decoded"]
            found = any(ref["message_id"] == source["id"] and marker in ref["text"] for ref in refs)
            if found or result["complete"]:
                break
            next_cursor = result["next_before"]
            require(next_cursor is not None and (cursor is None or next_cursor < cursor), "Cold continuation did not advance")
            cursor = next_cursor
        require(found, "Paged cold original missing")
        record = verify_reference(refs, source, source["turn_id"], location, turn)
        paged.append({**record, "initial_context_complete": context.complete,
                      "initial_context_found": any(ref["message_id"] == source["id"] and marker in ref["text"] for ref in context.references),
                      "pages": pages, "scanned": scans, "decoded": decodes,
                      "final_lookup_complete": result["complete"], "seconds": time.perf_counter() - started})
    return {"normal_context": normal, "early_paged_recovery": paged}


def warm_samples(chat, identifier, samples):
    with chat.connection() as db:
        db.execute("BEGIN IMMEDIATE")
        for role in ("user", "assistant"):
            source = samples[(role, 9000)]
            chat.cold.warm(db, source["sequence"], identifier, source["content"], chat.memory)
    return {"warmed": 2}


def readonly_chat(path):
    from app.database import ChatDatabase
    from app.memory_annotations import AnnotationsStore
    from app.memory_cold import ColdIndex
    from app.memory_index import MemoryIndex

    class ReadOnlyChat(ChatDatabase):
        def __init__(self):
            self.path, self.memory, self.cold = path, MemoryIndex(), ColdIndex()
            self.annotations, self.auto_annotations = AnnotationsStore(), False

        @contextmanager
        def connection(self):
            with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
                db.row_factory = sqlite3.Row
                db.execute("PRAGMA query_only=ON")
                yield db

    return ReadOnlyChat()


def compact_check(chat, data, baseline, owner, identifier):
    compact_path = data.parent / "compacted" / "chat.sqlite3"
    compact_path.parent.mkdir()
    with chat.connection() as db:
        db.execute("VACUUM INTO ?", (str(compact_path),))
    compact_hash = file_sha(compact_path)
    compact = readonly_chat(compact_path)
    compact_stats = measure(compact, writable=False)
    require(canonical(compact)["digests"] == baseline["digests"], "Compacted COPY changed canonical originals")
    compact_probes = probes(compact, owner, identifier, baseline["samples"])
    require(file_sha(compact_path) == compact_hash and canonical(chat)["digests"] == baseline["digests"], "Read-only compact proof changed data")
    return {"readonly": True, "canonical_unchanged": True, "sha256": compact_hash,
            "stats": compact_stats, "probes": compact_probes}


def run(data, report, run_id):
    started = time.perf_counter()
    data, report = guard(data, report, run_id)
    from app.database import ChatDatabase

    chat = ChatDatabase(data, auto_annotations=False)
    with chat.connection() as db:
        rows = db.execute("SELECT owner,id FROM conversations").fetchall()
        require(len(rows) == 1, "COPY must contain one unique conversation")
        owner, identifier = rows[0]
    baseline = canonical(chat)
    require(baseline["digests"]["messages"]["rows"] == EXPECTED_ROWS, "Unexpected COPY message count")
    require(all((role, turn) in baseline["samples"] for role, turn, _ in CASES), "Synthetic probe records missing")
    before = measure(chat)
    print(json.dumps({"phase": "baseline_verified", "messages": before["messages"]}), flush=True)
    require(before["cold_messages"] == 0, "COPY must begin fully active")
    body_migration = migrate(chat, owner, identifier)
    require(body_migration["visited"] == EXPECTED_ROWS, "Body migration did not visit all legacy originals")
    require(canonical(chat)["digests"] == baseline["digests"], "Body compression changed canonical originals")
    compressed = measure(chat)
    cooling = cool_batches(chat, owner, identifier)
    require(cooling["visited"] == EXPECTED_ROWS, "Cold batches did not visit all originals")
    require(canonical(chat)["digests"] == baseline["digests"], "Cooling changed canonical originals")
    directory, cold = verify_directory(chat), measure(chat)
    require(cold["cold_messages"] == cold["directory_rows"] == EXPECTED_ROWS and cold["fragments"] == 0,
            "Cold directory or active index is incomplete")
    optimize_started = time.perf_counter()
    with chat.connection() as db:
        db.execute("INSERT INTO memory_fts(memory_fts) VALUES('optimize')")
    optimized = {"stats": measure(chat), "seconds": time.perf_counter() - optimize_started,
                 "derived_fts_only": True}
    chat = ChatDatabase(data, auto_annotations=False)
    restart = measure(chat)
    require(restart["fragments"] == 0 and canonical(chat)["digests"] == baseline["digests"], "Restart revived cold originals")
    cold_probes = probes(chat, owner, identifier, baseline["samples"])
    warm = warm_samples(chat, identifier, baseline["samples"])
    mixed, warm_probes = measure(chat), probes(chat, owner, identifier, baseline["samples"])
    compacted = compact_check(chat, data, baseline, owner, identifier)
    result = {"status": "passed", "scope": "synthetic COPY only; no source mutation; no cloud calls", "run_id": run_id, "uid": getattr(os, "getuid", lambda: None)(),
        "canonical_digests": baseline["digests"], "canonical_unchanged": True, "before": before,
        "body_migration": body_migration, "compressed": compressed,
        "cooling": cooling, "directory": directory, "cold": cold,
        "derived_fts_optimization": optimized, "restart": restart,
        "cold_probes": cold_probes, "warm": warm, "mixed": mixed, "warm_probes": warm_probes,
        "compacted": compacted,
        "elapsed_seconds": time.perf_counter() - started,
        "limitation": "Finite lexical Bloom scans may require pages; latency is measured, not constant-time. All backup/copy/compact bytes remain additional storage. No raw source compaction or semantic/LLM factuality claim."}
    with report.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--run-id", required=True)
    args = parser.parse_args()
    try:
        result = run(args.data_dir, args.report, args.run_id)
        print(json.dumps({key: result[key] for key in ("status", "cooling", "elapsed_seconds")}))
    except Exception as error:  # noqa: BLE001 - CLI failures must not expose originals or key-bearing exceptions.
        print(json.dumps({"status": "failed", "failure_type": type(error).__name__}))
        raise SystemExit(1) from None


if __name__ == "__main__":
    main()
