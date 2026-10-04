import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Settings:
    data_dir: Path = Path("data")
    allow_demo: bool = False
    session_hours: int = 24
    embedding_model: str | None = None

    def __post_init__(self):
        if not 1 <= self.session_hours <= 168:
            raise ValueError("ZHIYU_SESSION_HOURS 必须在 1 到 168 之间")

    @classmethod
    def from_env(cls):
        return cls(
            data_dir=Path(os.getenv("ZHIYU_DATA_DIR", "data")).resolve(),
            allow_demo=os.getenv("ZHIYU_DEMO", "false").lower() == "true",
            session_hours=int(os.getenv("ZHIYU_SESSION_HOURS", "24")),
            embedding_model=os.getenv("ZHIYU_EMBEDDING_MODEL") or None,
        )
