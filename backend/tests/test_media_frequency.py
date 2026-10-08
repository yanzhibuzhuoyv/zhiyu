from types import SimpleNamespace

from test_media_assets import image_bytes

from app.database import ChatDatabase
from app.life_scenes import DailyScenes
from app.media_assets import MediaLibraries
from app.media_models import AssetMetadata
from app.sticker_intent import prepare_sticker_intent
from app.visual_profiles import VisualProfiles


def test_other_users_and_standalone_messages_do_not_distort_sticker_interval(tmp_path):
    chat = ChatDatabase(tmp_path)
    media = MediaLibraries(tmp_path / "media")
    state = SimpleNamespace(chat=chat, media=media, visual=VisualProfiles(tmp_path / "media"),
                            daily_scenes=DailyScenes(tmp_path / "media"))
    owner, other = "a" * 64, "b" * 64
    first = chat.create_conversation(owner, "日常", "demo", False)["id"]
    second = chat.create_conversation(other, "另一个人的日常", "demo", False)["id"]
    media.ingest("sticker", image_bytes(), AssetMetadata(category="object", tags=["happy"],
                                                       source_label="测试表情", license="test-fixture"))
    chat.save_turn(owner, first, "今天很好", "嗯", [])
    for _ in range(5):
        chat.save_turn(other, second, "别人的日常", "好", [])
    chat.save_assistant(owner, first, "再来聊聊。")
    plan = prepare_sticker_intent(state, owner, first, {"style": "playful"}, [])
    assert len(plan.options) == 1
