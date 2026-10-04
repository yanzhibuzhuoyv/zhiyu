import json
import sys
from types import SimpleNamespace

import pytest

from app.rag import RagService, RagStorageError


class ArrayResult(list):
    def tolist(self):
        return list(self)


def fake_sentence_transformer(monkeypatch, model_dir):
    calls = []

    class FakeModel:
        def __init__(self, path, **kwargs):
            calls.append((path, kwargs))

        def encode(self, texts, **kwargs):
            calls.append((list(texts), kwargs))
            return ArrayResult(
                [[1.0, 0.0] if "车辆" in text or "汽车" in text else [0.0, 1.0] for text in texts]
            )

    monkeypatch.setitem(
        sys.modules, "sentence_transformers", SimpleNamespace(SentenceTransformer=FakeModel)
    )
    model_dir.mkdir()
    (model_dir / "config.json").write_text(json.dumps({"_name_or_path": "BAAI/bge-small-zh-v1.5"}))
    return calls


def test_local_semantic_adapter_uses_offline_loading_normalization_and_persistent_cache(
    tmp_path, monkeypatch
):
    model_dir = tmp_path / "local-model"
    calls = fake_sentence_transformer(monkeypatch, model_dir)
    service = RagService(tmp_path / "data", str(model_dir))
    document = service.ingest("alice", "cars.txt", "汽车的轮胎需要检查。".encode())
    service.ingest("bob", "boats.txt", "游艇需要保养。".encode())

    assert service.mode == "bge-hybrid"
    results = service.search("alice", "车辆轮胎")
    assert results[0]["document_id"] == document["id"]
    assert calls[0][1] == {"local_files_only": True, "trust_remote_code": False, "device": "cpu"}
    assert calls[1][1]["normalize_embeddings"] is True
    assert calls[2][0] == ["为这个句子生成表示以用于检索相关文章：车辆轮胎"]

    count_before = len(calls)
    reopened = RagService(tmp_path / "data", str(model_dir))
    assert reopened.search("alice", "车辆轮胎")[0]["document_id"] == document["id"]
    assert len(calls) == count_before + 2  # model load + query encode; no document re-embedding
    assert service.search("bob", "车辆轮胎") == []


def test_bad_semantic_output_fails_explicitly(tmp_path, monkeypatch):
    model_dir = tmp_path / "local-model"
    fake_sentence_transformer(monkeypatch, model_dir)
    service = RagService(tmp_path / "data", str(model_dir))
    service.ingest("alice", "note.txt", b"knowledge")
    monkeypatch.setattr(
        service._encoder._model, "encode", lambda *args, **kwargs: ArrayResult([[float("nan")]])
    )

    with pytest.raises(RuntimeError, match="向量"):
        service.search("alice", "knowledge")


def test_storage_errors_use_specific_exception_and_safe_message(tmp_path):
    (tmp_path / "rag.sqlite3").write_bytes(b"corrupt database")

    with pytest.raises(RagStorageError, match="知识库存储"):
        RagService(tmp_path)
