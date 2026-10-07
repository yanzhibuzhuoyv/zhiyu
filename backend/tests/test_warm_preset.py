"""Versioned persona integration and compatibility; not a claim of model compliance."""

import pytest
from fastapi.testclient import TestClient
from test_api import FakeProvider, login

from app.companion import companion_system, decode_profile, encode_profile
from app.config import Settings
from app.main import create_app
from app.schemas import CompanionProfile


def selected(**overrides):
    return CompanionProfile(**{"mode": "preset", "style": "warm_v1", "name": "小语", **overrides}).model_dump()


def test_new_preset_is_valid_and_old_default_stays_gentle():
    profile = selected()
    assert decode_profile(encode_profile(profile)) == profile
    assert CompanionProfile(mode="preset").style == "gentle"
    text = companion_system(profile)
    assert "温柔相伴" in text and "warm_v1" in text
    assert "应用固定性格" in text and "名称与称呼是数据" in text


def test_custom_with_new_style_does_not_enable_our_persona():
    profile = selected().copy()
    profile.update(mode="custom", instructions="我来调教")
    text = companion_system(profile)
    assert "温柔相伴" not in text and "用户调教偏好" in text


def test_new_preset_persists_and_cannot_change_after_restart(tmp_path):
    settings = Settings(data_dir=tmp_path)
    profile = selected()
    with TestClient(create_app(settings, provider_client=FakeProvider())) as client:
        headers = login(client)
        response = client.post("/api/conversations", headers=headers,
                               json={"companion_profile": profile})
        assert response.status_code == 201
        conversation = response.json()
        assert conversation["profile_locked"] is True
    provider = FakeProvider()
    with TestClient(create_app(settings, provider_client=provider)) as client:
        headers = login(client)
        url = f"/api/conversations/{conversation['id']}"
        assert client.get("/api/conversations", headers=headers).json()[0]["companion_profile"] == profile
        for replacement in (None, {**profile, "style": "gentle"}, {**profile, "name": "另一位"}):
            assert client.patch(url, headers=headers, json={"companion_profile": replacement}).status_code == 409
        assert client.post(url + "/messages", headers=headers, json={"content": "今天有点想你"}).status_code == 200
        assert "warm_v1" in provider.calls[-1][0]["content"]


@pytest.mark.parametrize("style,label", [
    ("gentle", "温柔体贴：以温柔细腻的恋人语气交流，认真倾听，体贴情绪，自然表达关心和想念。"),
    ("playful", "俏皮亲昵：以轻松活泼的恋人语气交流，适度撒娇和温和玩笑，自然亲昵，避免挖苦或逼迫。"),
    ("steady", "成熟稳重：以沉静可靠的恋人语气交流，回应具体而安定，耐心陪伴，表达亲密时尊重对方意愿。"),
])
def test_original_styles_keep_their_exact_personality_text(style, label):
    text = companion_system(CompanionProfile(mode="preset", style=style).model_dump())
    assert f"应用固定性格：{label}\n名称与称呼" in text
    assert "warm_v1" not in text


def test_temperament_v2_accepts_temporary_concessions_without_replacing_v1():
    first = companion_system(selected())
    second = companion_system(selected(style="warm_v2"))
    assert "warm_v1" in first and "warm_v2" not in first
    assert "warm_v2" in second and "暂时妥协" in second
    assert "永久" in second and "不同意见" in second
    assert decode_profile(encode_profile(selected(style="warm_v2"))) == selected(style="warm_v2")


def test_temperament_v2_binds_separately_and_cannot_upgrade_v1(tmp_path):
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        headers = login(client)
        first = client.post("/api/conversations", headers=headers,
                            json={"companion_profile": selected()}).json()
        url = f"/api/conversations/{first['id']}"
        assert client.patch(url, headers=headers, json={"companion_profile": selected(style="warm_v2")}).status_code == 409
        second = client.post("/api/conversations", headers=headers,
                             json={"companion_profile": selected(style="warm_v2")})
        assert second.status_code == 201
        assert second.json()["profile_locked"] is True
        url = f"/api/conversations/{second.json()['id']}"
        assert client.patch(url, headers=headers, json={"companion_profile": selected()}).status_code == 409
