"""Reproduce wide-text statistics spilling into a bounded SQLite temp directory."""

import argparse
import json
import os
import sqlite3
import time
from contextlib import closing
from pathlib import Path

from smoke_long_fields import require, role_storage_counts


def run(data_dir, report_path):
    started = time.perf_counter()
    result = {"status": "running", "scope": "synthetic statistics fixture only; no app or shared database"}
    try:
        tmp = os.statvfs("/tmp")
        require(tmp.f_blocks * tmp.f_frsize == 128 * 1024 * 1024, "Use exactly 128 MiB /tmp")
        require(os.getuid() == 10001, "Use unprivileged fixture user")
        data_dir.mkdir(parents=True, exist_ok=True)
        path = data_dir / "wide.sqlite3"
        require(not path.exists(), "Use a fresh fixture directory")
        payload, rows = "长文🙂abc\n" * 2048, 8192
        raw_bytes = rows * len(payload.encode())
        require(raw_bytes > 128 * 1024 * 1024, "Fixture must exceed the sorting directory limit")
        with closing(sqlite3.connect(path)) as db:
            db.execute("CREATE TABLE messages(role TEXT,content TEXT)")
            db.executemany("INSERT INTO messages VALUES(?,?)",
                           (("user" if i % 2 == 0 else "assistant", payload) for i in range(rows)))
            db.commit()
        old_failed = False
        with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as db:
            try:
                db.execute("SELECT role,count(*),coalesce(sum(length(CAST(content AS BLOB))),0) FROM messages GROUP BY role").fetchall()
            except sqlite3.OperationalError as error:
                require(error.sqlite_errorcode == sqlite3.SQLITE_FULL, "Old query failed for a different reason")
                result["old_query_error"] = {"type": "OperationalError", "sqlite_code": error.sqlite_errorcode}
                old_failed = True
        require(old_failed, "Old wide-text GROUP BY did not reproduce the temp-space failure")
        with closing(sqlite3.connect(path.as_uri() + "?mode=ro", uri=True)) as db:
            db.execute("BEGIN")
            counts = {role: {"count": count, "utf8_bytes": size} for role, count, size in role_storage_counts(db)}
            expected = {role: {"count": rows // 2, "utf8_bytes": raw_bytes // 2} for role in ("user", "assistant")}
            require(counts == expected, "New statistics changed role counts or UTF-8 byte totals")
            require(db.execute("SELECT count(*) FROM messages WHERE content<>?", (payload,)).fetchone()[0] == 0,
                    "Fixture originals changed")
            require(db.execute("PRAGMA quick_check").fetchone()[0] == "ok", "Fixture integrity failed")
        result.update(status="passed", messages=rows, original_utf8_bytes=raw_bytes, roles=counts,
                      tmp_limit_bytes=tmp.f_blocks * tmp.f_frsize, old_query_reproduced_full=True,
                      new_statistics_without_full_text_sort=True, originals_unchanged=True,
                      database_bytes=path.stat().st_size, elapsed_seconds=round(time.perf_counter() - started, 3))
    except Exception as error:  # noqa: BLE001 -- never print arbitrary bodies from a failing fixture.
        result.update(status="failed", failure_type=type(error).__name__)
    report_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-dir", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    evidence = run(args.data_dir, args.report)
    print(json.dumps(evidence, ensure_ascii=False), flush=True)
    raise SystemExit(0 if evidence["status"] == "passed" else 1)
