from typing import Annotated

from pydantic import BaseModel, ConfigDict, Field, SecretStr, StringConstraints, field_validator

ModelName = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=160, pattern=r"^[a-zA-Z0-9][a-zA-Z0-9/._:+-]*$")]
Title = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=100)]
Content = Annotated[str, StringConstraints(strip_whitespace=True, min_length=1, max_length=16000)]


class Input(BaseModel):
    model_config = ConfigDict(extra="forbid")


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


class EditConversation(Input):
    title: Title | None = None
    use_rag: bool | None = None

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
