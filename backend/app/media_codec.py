"""Bounded attachment JSON; filenames, owner identity and arbitrary URLs never enter history."""

import json
from typing import Literal

from pydantic import Field, model_validator

from .media_models import Digest, Identifier
from .schemas import Input
from .source_codec import ChatStorageError


class MediaAttachment(Input):
    id: Identifier
    kind: Literal["image", "sticker"]
    url: str
    mime_type: Literal["image/png", "image/gif"]
    sha256: Digest
    day_id: str = Field(pattern=r"^\d{4}-\d{2}-\d{2}$")
    visual_version: int = Field(ge=0)
    source_label: str = Field(min_length=1, max_length=160)

    @model_validator(mode="after")
    def local_url(self):
        if self.url != f"/api/media/assets/{self.id}":
            raise ValueError("附件链接必须来自素材库")
        return self


def validate_attachments(values):
    if values is None:
        return []
    if not isinstance(values, list) or len(values) > 2:
        raise ChatStorageError("媒体附件列表无效")
    try:
        return [MediaAttachment.model_validate(value).model_dump() for value in values]
    except (ValueError, TypeError) as exc:
        raise ChatStorageError("媒体附件数据格式无效") from exc


def encode_attachments(values):
    return json.dumps(validate_attachments(values), ensure_ascii=False, separators=(",", ":"))


def decode_attachments(value):
    try:
        if not isinstance(value, str) or len(value.encode("utf-8")) > 4096:
            raise ChatStorageError("媒体附件存储长度无效")
        return validate_attachments(json.loads(value))
    except (ValueError, TypeError, RecursionError, UnicodeError) as exc:
        raise ChatStorageError("媒体附件存储内容损坏") from exc
