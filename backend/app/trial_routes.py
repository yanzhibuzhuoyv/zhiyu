"""A same-origin preview for the native app's versioned companion preset."""

from pathlib import Path

from fastapi import APIRouter
from fastapi.responses import FileResponse

router = APIRouter()
ASSETS = Path(__file__).with_name("trial_assets")
HEADERS = {"Content-Security-Policy": "default-src 'self'; script-src 'self'; "
           "style-src 'self'; connect-src 'self'; img-src 'self' data:; "
           "base-uri 'none'; frame-ancestors 'none'; form-action 'self'"}


@router.get("/trial", include_in_schema=False)
def trial():
    return FileResponse(ASSETS / "index.html", headers=HEADERS)


@router.get("/trial/app.js", include_in_schema=False)
def script():
    return FileResponse(ASSETS / "app.js", media_type="text/javascript", headers=HEADERS)


@router.get("/trial/style.css", include_in_schema=False)
def stylesheet():
    return FileResponse(ASSETS / "style.css", headers=HEADERS)
