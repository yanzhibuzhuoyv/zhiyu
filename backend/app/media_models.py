"""Validated visual identity and operator-supplied media metadata."""

import unicodedata
from typing import Annotated, Literal
from urllib.parse import urlsplit

from pydantic import Field, StrictBool, StringConstraints, field_validator, model_validator

from .schemas import Input

Identifier = Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{32}$")]
Digest = Annotated[str, StringConstraints(pattern=r"^[a-f0-9]{64}$")]
ShortText = Annotated[str, StringConstraints(strip_whitespace=True, max_length=120)]
Gender = Literal["unspecified", "female", "male", "other"]
Hair = Literal["unspecified", "short", "long", "other"]


class VisualUpdate(Input):
    mode: Literal["none", "import", "generate"] = "none"
    description: Annotated[str, StringConstraints(strip_whitespace=True, max_length=1000)] = ""
    gender: Gender = "unspecified"
    hair: Hair = "unspecified"

    @field_validator("description")
    @classmethod
    def valid_description(cls, value):
        if any(unicodedata.category(char) in {"Cf", "Cs"}
               or (unicodedata.category(char) == "Cc" and char not in "\n\t") for char in value):
            raise ValueError("外观描述不能包含控制字符")
        return value


class VisualGeneration(Input):
    request_id: Annotated[str, StringConstraints(min_length=1, max_length=80,
                                                pattern=r"^[a-zA-Z0-9_-]+$")]
    description: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=1000)]

    _description = field_validator("description")(VisualUpdate.valid_description.__func__)


class TemplateChoice(Input):
    template_id: Annotated[str, StringConstraints(min_length=1, max_length=80, pattern=r"^[a-zA-Z0-9_-]+$")]


class AssetMetadata(Input):
    category: Literal["portrait", "selfie", "pov", "landscape", "cat", "food", "object"]
    tags: list[Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=40)]] = Field(default_factory=list, max_length=20)
    source_label: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160)]
    license: Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=200)]
    source_url: Annotated[str, StringConstraints(max_length=1500)] | None = None
    owner: Digest | None = None
    contains_person: StrictBool = False
    visual_id: Identifier | None = None
    visual_version: int | None = Field(default=None, ge=1)
    reference_sha256: Digest | None = None
    oc_template_id: Annotated[str, StringConstraints(max_length=80, pattern=r"^[a-zA-Z0-9_-]+$")] | None = None
    gender: Gender = "unspecified"
    hair: Hair = "unspecified"
    angle: ShortText = ""
    location: ShortText = ""
    lighting: ShortText = ""
    action: ShortText = ""
    time_slot: Literal["any", "morning", "afternoon", "evening", "night"] = "any"

    @field_validator("source_url")
    @classmethod
    def safe_source_url(cls, value):
        if value is None:
            return value
        try:
            parsed = urlsplit(value)
            valid = parsed.scheme in {"http", "https"} and parsed.hostname and not parsed.username and not parsed.password
        except ValueError as exc:
            raise ValueError("素材来源链接无效") from exc
        if not valid or any(ord(char) < 32 for char in value):
            raise ValueError("素材来源链接必须为无凭据 HTTP(S) 链接")
        return value

    @model_validator(mode="after")
    def identity_binding(self):
        sensitive = self.contains_person or self.gender != "unspecified" or self.hair != "unspecified"
        identity = (self.owner, self.visual_id, self.visual_version, self.reference_sha256)
        if sensitive and not all(identity) and not self.oc_template_id:
            raise ValueError("人物、头发或影子素材必须绑定 OC 身份或待绑定模板")
        if any(identity[1:]) and not all(identity):
            raise ValueError("OC 身份字段必须完整")
        if self.category in {"portrait", "selfie", "pov"} and not self.contains_person:
            raise ValueError("人物或第一视角素材必须声明身份关联")
        return self
