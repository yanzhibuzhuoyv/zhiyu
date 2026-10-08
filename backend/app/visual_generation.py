"""Provider seam for a future image relay; nothing calls a cloud without configuration."""

from starlette.concurrency import run_in_threadpool

from .media_images import normalize_image
from .media_models import AssetMetadata
from .visual_profiles import VisualConflict


class GenerationError(RuntimeError):
    def __init__(self, status_code, message, *, code="visual_conflict"):
        super().__init__(message)
        self.status_code = status_code
        self.message = message
        self.code = code


async def generate_visual(profiles, media, generator, owner, conversation_id, request_id, description):
    try:
        prior = await run_in_threadpool(profiles.existing, owner, conversation_id, request_id, description)
        if prior:
            return await run_in_threadpool(profiles.replay, prior, configured=generator is not None)
        if generator is None:
            raise GenerationError(503, "外观生成服务尚未配置，请先导入 OC 参考图片；生成机会未扣除", code="generation_unconfigured")
        operation = await run_in_threadpool(profiles.reserve, owner, conversation_id, request_id, description)
        if operation["replayed"]:
            return await run_in_threadpool(profiles.replay, operation)
    except VisualConflict as exc:
        raise GenerationError(409, str(exc), code=exc.code) from exc
    try:
        # The configured provider implements idempotency using request_id as well.
        raw = await generator(description, owner=owner, conversation_id=conversation_id,
                              request_id=request_id, visual_id=operation["visual_id"])
        image = await run_in_threadpool(normalize_image, raw)
        metadata = AssetMetadata(category="portrait", tags=["portrait", "reference"], owner=owner,
                                 contains_person=True, visual_id=operation["visual_id"],
                                 visual_version=operation["expected_version"] + 1,
                                 reference_sha256=image.sha256, source_label="OC 外观生成",
                                 license="private-user-generation", angle="reference")
        asset = await run_in_threadpool(media.ingest, "image", image.data, metadata)
        return await run_in_threadpool(profiles.finish, operation["id"], asset)
    except Exception as exc:
        await run_in_threadpool(profiles.fail, operation["id"])
        raise GenerationError(502, "外观生成未完成，生成机会未扣除，请稍后重试", code="generation_failed") from exc
