from fastapi import APIRouter, Depends, File, HTTPException, Request, Response, UploadFile
from starlette.concurrency import run_in_threadpool

from .auth import Session
from .dependencies import current_session
from .schemas import Search

router = APIRouter(prefix="/api")
MAX_UPLOAD = 5 * 1024 * 1024


@router.get("/documents")
def documents(request: Request, session: Session = Depends(current_session)):
    return request.app.state.rag.list_documents(session.owner)


@router.post("/documents", status_code=201)
async def upload(request: Request, file: UploadFile = File(...), session: Session = Depends(current_session)):
    try:
        content = await file.read(MAX_UPLOAD + 1)
        if len(content) > MAX_UPLOAD:
            raise HTTPException(413, "单个文件不能超过 5 MiB")
        async with request.app.state.parse_slots:
            return await run_in_threadpool(request.app.state.rag.ingest, session.owner, file.filename or "", content)
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
    finally:
        await file.close()


@router.delete("/documents/{identifier}", status_code=204)
def delete_document(identifier: str, request: Request, session: Session = Depends(current_session)):
    try:
        deleted = request.app.state.rag.delete(session.owner, identifier)
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
    if not deleted:
        raise HTTPException(404, "文档不存在")
    return Response(status_code=204)


@router.post("/knowledge/search")
def search(body: Search, request: Request, session: Session = Depends(current_session)):
    try:
        return request.app.state.rag.search(session.owner, body.query, body.top_k)
    except ValueError as error:
        raise HTTPException(400, str(error)) from error
