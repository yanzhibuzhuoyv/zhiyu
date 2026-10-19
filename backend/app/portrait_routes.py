"""Preview a local foreground and let the user choose it or the preserved original."""
from typing import Annotated, Literal

from fastapi import APIRouter, Depends, HTTPException, Query, Request, UploadFile
from starlette.concurrency import run_in_threadpool

from .auth import Session
from .chat_routes import owned, turn_lock
from .dependencies import current_session
from .media_images import MAX_FILE_BYTES
from .media_models import Identifier
from .portrait_subject import PortraitExtractionError
from .schemas import Input
from .visual_profiles import VisualConflict

router = APIRouter(prefix="/api/conversations")


class SubjectConfirmation(Input):
    preview_id: Identifier
    choice: Literal["subject", "original"]


@router.get("/{identifier}/visual/subject/capability")
def capability(identifier: str, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    return {"configured": request.app.state.portrait_processor is not None, "requires_confirmation": True}


@router.post("/{identifier}/visual/subject/preview")
async def preview(identifier: str, request: Request, file: UploadFile,
                  crop_bounds: Annotated[list[int] | None, Query(min_length=4, max_length=4)] = None,
                  session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    try:
        if state.portrait_processor is None:
            raise HTTPException(503, "人物分离尚未准备好，可以先保留原图", headers={"X-ZhiYu-Error-Code": "portrait_unconfigured"})
        raw = await file.read(MAX_FILE_BYTES + 1)
        profile = await run_in_threadpool(state.visual.get, session.owner, identifier)
        async with state.portrait_slots:
            result = await run_in_threadpool(state.portrait_processor.extract, raw,
                                            crop_bounds=tuple(crop_bounds) if crop_bounds else None)
        return await run_in_threadpool(state.portrait_previews.save, state.media, session.owner, identifier, profile, result)
    except PortraitExtractionError as exc:
        raise HTTPException(422, str(exc), headers={"X-ZhiYu-Error-Code": "portrait_uncertain"}) from exc
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    finally:
        await file.close()


@router.get("/{identifier}/visual/subject/preview/{preview_id}")
def read_preview(identifier: str, preview_id: Identifier, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    try:
        return request.app.state.portrait_previews.get(request.app.state.media, session.owner, identifier, preview_id)
    except LookupError as exc:
        raise HTTPException(404, str(exc)) from exc
    except VisualConflict as exc:
        raise HTTPException(409, str(exc)) from exc


@router.post("/{identifier}/visual/subject/confirm")
async def confirm(identifier: str, body: SubjectConfirmation, request: Request, session: Session = Depends(current_session)):
    owned(request, session, identifier)
    state = request.app.state
    try:
        async with turn_lock(request, identifier):
            return await run_in_threadpool(state.portrait_previews.confirm, state.media, state.visual, session.owner,
                                           identifier, body.preview_id, body.choice,
                                           configured=state.visual_generator is not None)
    except LookupError as exc:
        raise HTTPException(404, str(exc)) from exc
    except VisualConflict as exc:
        raise HTTPException(409, str(exc)) from exc
