import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    data_dir: Path = Path("data")
    allow_demo: bool = False
    session_hours: int = 24
    embedding_model: str | None = None
    auto_memory_annotations: bool = False
    memory_annotation_mode: str = "semantic"
    media_dir: Path | None = None
    portrait_model_path: Path | None = None

    def __post_init__(self):
        if type(self.auto_memory_annotations) is not bool:
            raise ValueError("自动记忆识别开关无效")
        if self.memory_annotation_mode not in {"semantic", "literal"}:
            raise ValueError("记忆识别方式必须为 semantic 或 literal")
        if not 1 <= self.session_hours <= 168:
            raise ValueError("ZHIYU_SESSION_HOURS 必须在 1 到 168 之间")

    @classmethod
    def from_env(cls):
        return cls(
            data_dir=Path(os.getenv("ZHIYU_DATA_DIR", "data")).resolve(),
            allow_demo=os.getenv("ZHIYU_DEMO", "false").lower() == "true",
            session_hours=int(os.getenv("ZHIYU_SESSION_HOURS", "24")),
            embedding_model=os.getenv("ZHIYU_EMBEDDING_MODEL") or None,
            auto_memory_annotations=os.getenv("ZHIYU_AUTO_MEMORY_ANNOTATIONS", "false").lower() == "true",
            memory_annotation_mode=os.getenv("ZHIYU_MEMORY_ANNOTATION_MODE", "semantic"),
            media_dir=Path(os.environ["ZHIYU_MEDIA_DIR"]).resolve() if os.getenv("ZHIYU_MEDIA_DIR") else None,
            portrait_model_path=Path(os.environ["ZHIYU_PORTRAIT_MODEL_PATH"]).resolve() if os.getenv("ZHIYU_PORTRAIT_MODEL_PATH") else None,
        )
