from typing import Annotated, Literal

from pydantic import Field, model_validator

from .schemas import Content, Input


class MemoryEdit(Input):
    expected_revision: Annotated[int, Field(strict=True, ge=0)]
    tier: Literal["mid", "long", "permanent", "auto"] | None = None
    index_state: Literal["active", "cold"] | None = None
    confirm_independent: Annotated[bool, Field(strict=True)] = False

    @model_validator(mode="after")
    def has_action(self):
        if self.tier is None and self.index_state is None and not self.confirm_independent:
            raise ValueError("请选择记忆操作")
        return self


class ColdSearch(Input):
    query: Content
    cursor: Annotated[int | None, Field(strict=True, ge=1)] = None
