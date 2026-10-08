"""Owner-authenticated OC, media file and daily-scene endpoints."""

from uuid import uuid4

from fastapi import APIRouter, Depends, HTTPException, Request, UploadFile
from fastapi.responses import FileResponse
from starlette.concurrency import run_in_threadpool

from .auth import Session
from .chat_routes import owned
from .dependencies import current_session
from .media_images import MAX_FILE_BYTES, normalize_image
from .media_models import AssetMetadata, TemplateChoice, VisualGeneration, VisualUpdate
from .media_templates import adopt, catalogue, reference_file
from .visual_generation import GenerationError, generate_visual
from .visual_profiles import VisualConflict

router = APIRouter(prefix="/api")


@router.get("/conversations/{identifier}/visual")
def visual(identifier: str, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    return state.visual.get(session.owner, identifier, configured=state.visual_generator is not None)


@router.put("/conversations/{identifier}/visual")
def update_visual(identifier: str, body: VisualUpdate, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    try:
        return state.visual.update(session.owner, identifier, body, configured=state.visual_generator is not None)
    except VisualConflict as exc:
        raise HTTPException(409, str(exc)) from exc


@router.post("/conversations/{identifier}/visual/reference")
async def import_reference(identifier: str, request: Request, file: UploadFile, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    try:
        raw = await file.read(MAX_FILE_BYTES + 1)
        image = await run_in_threadpool(normalize_image, raw)
        profile = await run_in_threadpool(state.visual.get, session.owner, identifier)
        metadata = AssetMetadata(category="portrait", tags=["portrait", "reference"], owner=session.owner,
                                 contains_person=True, visual_id=profile["visual_id"] or uuid4().hex,
                                 visual_version=profile["visual_version"] + 1, reference_sha256=image.sha256,
                                 source_label="用户导入的 OC 参考图", license="private-user-upload", angle="reference",
                                 gender=profile["gender"], hair=profile["hair"])
        asset = await run_in_threadpool(state.media.ingest, "image", image.data, metadata)
        await run_in_threadpool(state.visual.attach, session.owner, identifier, asset,
                                expected_version=profile["visual_version"])
        return await run_in_threadpool(state.visual.get, session.owner, identifier,
                                       configured=state.visual_generator is not None)
    except VisualConflict as exc:
        raise HTTPException(409, str(exc)) from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    finally:
        await file.close()


@router.post("/conversations/{identifier}/visual/generate")
async def generate(identifier: str, body: VisualGeneration, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    try:
        return await generate_visual(state.visual, state.media, state.visual_generator, session.owner,
                                     identifier, body.request_id, body.description)
    except GenerationError as exc:
        raise HTTPException(exc.status_code, exc.message, headers={"X-ZhiYu-Error-Code": exc.code}) from exc


@router.get("/conversations/{identifier}/daily-scene")
def daily(identifier: str, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    profile = state.visual.get(session.owner, identifier)
    return state.daily_scenes.get(session.owner, identifier, profile["visual_version"])


@router.get("/media/assets/{identifier}")
def asset_file(identifier: str, request: Request, session: Session = Depends(current_session)):
    found = request.app.state.media.file(identifier, session.owner)
    if found is None:
        raise HTTPException(404, "素材不存在或不属于当前恋人")
    path, asset = found
    return FileResponse(path, media_type=asset["mime_type"])


@router.get("/media/templates")
def list_templates(request: Request, session: Session = Depends(current_session)):
    return catalogue(request.app.state.media)


@router.get("/media/templates/{template_id}/reference")
def template_reference(template_id: str, request: Request, session: Session = Depends(current_session)):
    found = reference_file(request.app.state.media, template_id)
    if found is None:
        raise HTTPException(404, "OC 样例不存在")
    path, asset = found
    return FileResponse(path, media_type=asset["mime_type"])


@router.post("/conversations/{identifier}/visual/template")
def choose_template(identifier: str, body: TemplateChoice, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    try:
        adopt(state.media, state.visual, session.owner, identifier, body.template_id)
        return state.visual.get(session.owner, identifier, configured=state.visual_generator is not None)
    except LookupError as exc:
        raise HTTPException(404, str(exc)) from exc
    except VisualConflict as exc:
        raise HTTPException(409, str(exc)) from exc
