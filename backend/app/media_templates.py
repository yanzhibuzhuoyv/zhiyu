"""Operator-curated OC examples, explicitly selected before any identity-bound output."""

from uuid import uuid4

from .media_models import AssetMetadata
from .visual_profiles import VisualConflict


def templates(libraries):
    grouped = {}
    for asset in libraries.images.template_assets():
        grouped.setdefault(asset["oc_template_id"], []).append(asset)
    return {identifier: values for identifier, values in grouped.items()
            if any(item["category"] == "portrait" and "reference" in item["tags"] for item in values)}


def reference_for(values):
    return next(item for item in values if item["category"] == "portrait" and "reference" in item["tags"])


def template_name(reference):
    gender = {"female": "女性", "male": "男性", "other": "自定义"}.get(reference["gender"], "")
    hair = {"long": "长发", "short": "短发"}.get(reference["hair"], "")
    return f"{gender}{hair} · 虚构 OC 样例".strip()


def catalogue(libraries):
    return [{"template_id": identifier, "name": template_name(reference_for(values)),
             "description": "可选的 OC 样例；选择后只用于当前恋人的视觉档案。",
             "gender": reference_for(values)["gender"], "hair": reference_for(values)["hair"],
             "reference_url": f"/api/media/templates/{identifier}/reference",
             "reference_sha256": reference_for(values)["sha256"], "image_count": len(values)}
            for identifier, values in templates(libraries).items()]


def reference_file(libraries, template_id):
    values = templates(libraries).get(template_id)
    return libraries.images.verified_file(reference_for(values)) if values else None


def adopt(libraries, profiles, owner, conversation_id, template_id):
    values = templates(libraries).get(template_id)
    if not values:
        raise LookupError("OC 样例不存在")
    if len(values) > 64:
        raise VisualConflict("OC 样例素材过多，请由素材管理员拆分")
    prior = profiles.get(owner, conversation_id)
    anchor = reference_for(values)
    visual_id, version = uuid4().hex, prior["visual_version"] + 1
    reference = None
    for original in values:
        file = libraries.images.verified_file(original)
        if file is None:
            raise LookupError("OC 样例素材暂不可用")
        metadata = AssetMetadata.model_validate({
            **{key: original[key] for key in AssetMetadata.model_fields},
            "owner": owner, "visual_id": visual_id, "visual_version": version,
            "reference_sha256": anchor["sha256"],
        })
        copied = libraries.ingest("image", file[0].read_bytes(), metadata)
        if original["id"] == anchor["id"]:
            reference = copied
    return profiles.adopt_template(owner, conversation_id, reference,
                                   expected_version=prior["visual_version"],
                                   description=template_name(anchor))
