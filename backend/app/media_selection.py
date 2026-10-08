"""Small deterministic media choice, after identity/context/recency filtering."""

import hashlib

PHOTO_WORDS = ("照片", "图片", "自拍", "来张", "发一张", "拍一张", "给我看看")
CATEGORIES = {"cat": ("猫",), "landscape": ("风景", "夕阳", "晚霞", "海边", "天空"),
              "food": ("饭", "吃", "早餐", "午餐", "晚餐"),
              "selfie": ("自拍",), "portrait": ("样子", "长相", "形象", "你自己"),
              "pov": ("第一视角", "第一人称", "视角")}
EMOTIONS = {"comfort": ("难过", "委屈", "不开心", "好累"),
            "happy": ("开心", "哈哈", "快乐", "好棒"), "affection": ("想你", "抱抱", "爱你")}
STICKER_INTERVALS = {"steady": 24, "gentle": 8, "warm_v1": 8, "warm_v2": 8, "playful": 3, "anxious": 3, "distant": 48}


def identity_matches(asset, owner, visual):
    sensitive = asset["contains_person"] or asset["gender"] != "unspecified" or asset["hair"] != "unspecified"
    if not sensitive:
        return asset["owner"] in {None, owner}
    if not visual.get("visual_id") or not visual.get("reference_sha256"):
        return False
    if not (asset["owner"] == owner and asset["visual_id"] == visual["visual_id"]
            and asset["visual_version"] == visual["visual_version"]
            and asset["reference_sha256"] == visual["reference_sha256"]):
        return False
    return all(asset[key] in {"unspecified", visual.get(key)} for key in ("gender", "hair"))


def attachment(asset, day_id, visual_version):
    return {"id": asset["id"], "kind": asset["kind"], "url": f"/api/media/assets/{asset['id']}",
            "mime_type": asset["mime_type"], "sha256": asset["sha256"], "day_id": day_id,
            "visual_version": visual_version, "source_label": asset["source_label"]}


def _choose(candidates, recent, owner, conversation_id, day_id, turn_number):
    used = {item.get("sha256") for item in recent}
    signatures = {tuple(item[key] for key in ("angle", "location", "lighting", "action"))
                  for item in candidates if item["sha256"] in used}
    candidates = [item for item in candidates if item["sha256"] not in used]
    if not candidates:
        return None
    # Angles, actions, locations and light are part of ordering; never repeat one sha.
    seed = f"{owner}:{conversation_id}:{day_id}:{turn_number}"
    return min(candidates, key=lambda item: (
        tuple(item[key] for key in ("angle", "location", "lighting", "action")) in signatures,
        hashlib.sha256(f"{seed}:{item['angle']}:{item['location']}:{item['lighting']}:{item['action']}:{item['sha256']}".encode()).hexdigest(),
    ))


def select_media(stores, owner, conversation_id, text, profile, visual, *, day_id, recent,
                 turn_number, time_slot="any", scene_tags=None):
    profile = profile or {}
    image_request = any(word in text for word in PHOTO_WORDS)
    sticker_request = "表情包" in text
    emotion = next((name for name, words in EMOTIONS.items() if any(word in text for word in words)), None)
    selected = []
    if image_request:
        category = next((name for name, words in CATEGORIES.items() if any(word in text for word in words)), None)
        candidates = [item for item in stores.images.candidates(owner) if identity_matches(item, owner, visual)
                      and item["time_slot"] in {"any", time_slot}
                      and (item["category"] == category if category else bool(set(item["tags"]) & set(scene_tags or [])))]
        chosen = _choose(candidates, recent, owner, conversation_id, day_id, turn_number)
        if chosen:
            selected.append(attachment(chosen, day_id, visual.get("visual_version", 0)))
    interval = STICKER_INTERVALS.get(profile.get("style"), 8)
    if emotion and (sticker_request or turn_number % interval == 0):
        candidates = [item for item in stores.stickers.candidates(owner)
                      if identity_matches(item, owner, visual) and emotion in item["tags"]]
        chosen = _choose(candidates, recent, owner, conversation_id, day_id, turn_number)
        if chosen:
            selected.append(attachment(chosen, day_id, visual.get("visual_version", 0)))
    return selected
