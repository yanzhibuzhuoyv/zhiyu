"""Real, optional sentence embeddings from a trusted local model directory."""

import hashlib
import json
import math
from pathlib import Path
from threading import Lock


class RagSemanticError(RuntimeError):
    """A configured semantic model cannot currently produce usable embeddings."""


class LocalSemanticEncoder:
    def __init__(self, model_path: str):
        directory = Path(model_path).expanduser()
        if not directory.is_dir():
            raise ValueError("语义检索需要已存在的本地 BGE 模型目录；不会自动下载模型")
        directory = directory.resolve()
        try:
            from sentence_transformers import SentenceTransformer
        except ImportError as exc:
            raise ValueError("启用语义检索前请安装后端的 semantic 可选依赖") from exc
        try:
            self._model = SentenceTransformer(
                str(directory), local_files_only=True, trust_remote_code=False, device="cpu"
            )
        except (OSError, ValueError, RuntimeError) as exc:
            raise ValueError("本地嵌入模型加载失败，请检查完整模型文件和 semantic 依赖") from exc
        self._lock = Lock()
        identity, model_name = _model_identity(directory)
        self.identity = identity
        self.mode = "bge-hybrid" if "bge" in model_name else "semantic-hybrid"
        self._query_prefix = _query_prefix(model_name)

    def encode_documents(self, texts: list[str]) -> list[list[float]]:
        return self._encode(texts)

    def encode_query(self, query: str) -> list[float]:
        return self._encode([self._query_prefix + query])[0]

    def _encode(self, texts: list[str]) -> list[list[float]]:
        try:
            with self._lock:
                result = self._model.encode(
                    texts,
                    normalize_embeddings=True,
                    convert_to_numpy=True,
                    show_progress_bar=False,
                    batch_size=32,
                )
            vectors = result.tolist()
            if len(vectors) != len(texts):
                raise RagSemanticError("语义模型返回的向量数量不一致")
            normalized = [_normalize(vector) for vector in vectors]
            if len({len(vector) for vector in normalized}) != 1:
                raise RagSemanticError("语义模型返回的向量维度不一致")
            return normalized
        except (OSError, ValueError, TypeError, RuntimeError, AttributeError) as exc:
            if isinstance(exc, RagSemanticError):
                raise
            raise RagSemanticError("语义检索生成向量失败，请检查本地模型") from exc


def _normalize(vector) -> list[float]:
    if not isinstance(vector, list) or not 1 <= len(vector) <= 8192:
        raise RagSemanticError("语义模型返回的向量无效")
    numbers = [float(number) for number in vector]
    if not all(math.isfinite(number) for number in numbers):
        raise RagSemanticError("语义模型返回的向量含有非有限数值")
    norm = math.sqrt(sum(number * number for number in numbers))
    if norm == 0:
        raise RagSemanticError("语义模型返回了零向量")
    return [number / norm for number in numbers]


def _model_identity(directory: Path) -> tuple[str, str]:
    digest = hashlib.sha256(str(directory).encode())
    model_name = directory.name.lower()
    try:
        for path in sorted(directory.rglob("*")):
            if not path.is_file():
                continue
            stat = path.stat()
            digest.update(
                f"{path.relative_to(directory)}:{stat.st_size}:{stat.st_mtime_ns}".encode()
            )
            if path.name == "config.json":
                raw = path.read_bytes()
                digest.update(raw)
                config = json.loads(raw)
                model_name += " " + str(config.get("_name_or_path", "")).lower()
    except (OSError, json.JSONDecodeError, AttributeError) as exc:
        raise ValueError("本地模型配置不可读取或已损坏") from exc
    return digest.hexdigest(), model_name


def _query_prefix(model_name: str) -> str:
    # BGE M3 uses multilingual embeddings without the v1.5 retrieval instruction.
    if "bge" not in model_name or "bge-m3" in model_name:
        return ""
    if "-zh" in model_name:
        return "为这个句子生成表示以用于检索相关文章："
    if "-en" in model_name:
        return "Represent this sentence for searching relevant passages: "
    return ""


def fuse_scores(query_vector, vectors, lexical_scores) -> list[float]:
    lexical_max = max(lexical_scores, default=0.0) or 1.0
    scores = []
    for vector, lexical in zip(vectors, lexical_scores, strict=True):
        if len(vector) != len(query_vector):
            raise RagSemanticError("已缓存的语义向量维度不匹配，请重新建立知识库")
        cosine = sum(left * right for left, right in zip(query_vector, vector, strict=True))
        if not math.isfinite(cosine):
            raise RagSemanticError("已缓存的语义向量无效")
        semantic = max(0.0, min(1.0, cosine)) if cosine >= 0.35 else 0.0
        scores.append(0.45 * lexical / lexical_max + 0.55 * semantic)
    return scores
