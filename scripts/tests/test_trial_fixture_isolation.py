"""A disposable subprocess must not initialize an inherited data directory."""

import os
import subprocess
import sys
from pathlib import Path


def test_fixture_import_and_launch_ignore_inherited_data_and_models(tmp_path):
    inherited = tmp_path / "inherited-must-not-open"
    inherited.mkdir()
    sentinel = inherited / "chat.sqlite3"
    sentinel.write_bytes(b"unreadable-synthetic-sentinel")
    root = Path(__file__).resolve().parents[2]
    code = """
import runpy, sys, os, uvicorn
import scripts.serve_trial_fixture
assert 'app.main' not in sys.modules
assert not os.path.exists('data')
def fake_run(app, **kwargs):
    settings = app.state.settings
    assert settings.data_dir.name.startswith('zhiyu-ui-only-')
    assert str(settings.data_dir) != os.environ['INHERITED_TEST_DIR']
    assert settings.embedding_model is None
    assert settings.auto_memory_annotations is False
    assert kwargs['host'] == '127.0.0.1'
    assert type(app.state.provider).__name__ == 'SyntheticProvider'
uvicorn.run = fake_run
runpy.run_module('scripts.serve_trial_fixture', run_name='__main__')
"""
    env = {**os.environ, "PYTHONPATH": str(root), "ZHIYU_DATA_DIR": str(inherited),
           "INHERITED_TEST_DIR": str(inherited), "ZHIYU_AUTO_MEMORY_ANNOTATIONS": "true",
           "ZHIYU_EMBEDDING_MODEL": "unavailable-synthetic-model"}
    result = subprocess.run([sys.executable, "-c", code], cwd=tmp_path, env=env,
                            capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
    assert sentinel.read_bytes() == b"unreadable-synthetic-sentinel"
    assert set(p.name for p in inherited.iterdir()) == {"chat.sqlite3"}
    assert not (tmp_path / "data").exists()
