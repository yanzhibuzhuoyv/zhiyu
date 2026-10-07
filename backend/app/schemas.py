import unicodedata
from typing import Annotated, Literal

from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    SecretStr,
    StringConstraints,
    field_validator,
    model_validator,
)

ModelName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160, pattern=r"^[a-zA-Z0-9][a-zA-Z0-9/._:+-]*$")]
Title = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
Content = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=16000)]
CompanionName = Annotated[str, StringConstraints(strip_whitespace=True, max_length=24)]


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


class CompanionProfile(Input):
    mode: Literal["preset", "custom"]
    style: Literal["gentle", "playful", "steady", "warm_v1", "warm_v2"] = "gentle"
    role: Literal["girlfriend", "boyfriend", "partner"] = "partner"
    name: CompanionName = ""
    user_name: CompanionName = ""
    instructions: Annotated[str, StringConstraints(strip_whitespace=True, max_length=2000)] = ""

    @field_validator("name", "user_name", mode="before")
    @classmethod
    def safe_name(cls, value):
        if isinstance(value, str) and any(
            char in "<>" or unicodedata.category(char) in {"Cc", "Cf", "Cs"} for char in value
        ):
            raise ValueError("名称不能包含控制字符或尖括号")
        return value

    @field_validator("instructions", mode="before")
    @classmethod
    def safe_instructions(cls, value):
        if isinstance(value, str) and any(
            unicodedata.category(char) in {"Cf", "Cs"}
            or (unicodedata.category(char) == "Cc" and char not in "\n\r\t") for char in value
        ):
            raise ValueError("调教内容不能包含无效控制字符")
        return value

    @model_validator(mode="after")
    def preset_has_no_instructions(self):
        if self.mode == "preset" and self.instructions:
            raise ValueError("预设性格不能附加自定义调教内容")
        return self


class Login(Input):
    provider_id: Annotated[str, StringConstraints(min_length=1, max_length=40)]
    api_key: SecretStr
    model: ModelName | None = None

    @field_validator("api_key")
    @classmethod
    def validate_key(cls, value):
        key = value.get_secret_value().strip()
        if not 4 <= len(key) <= 4096 or any(ord(char) < 32 for char in key):
            raise ValueError("请输入有效 API Key")
        return SecretStr(key)


class NewConversation(Input):
    title: Title = "新对话"
    model: ModelName | None = None
    use_rag: bool = True
    companion_profile: CompanionProfile | None = None


class EditConversation(Input):
    title: Title | None = None
    use_rag: bool | None = None
    companion_profile: CompanionProfile | None = None

    @field_validator("title", "use_rag")
    @classmethod
    def not_null(cls, value):
        if value is None:
            raise ValueError("字段不可为空")
        return value


class NewMessage(Input):
    content: Content


class Search(Input):
    query: Content
    top_k: int = Field(default=5, ge=1, le=10)
