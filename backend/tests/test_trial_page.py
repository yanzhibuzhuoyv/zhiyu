"""Real HTTP assets and the existing authenticated API boundary."""

from fastapi.testclient import TestClient
from test_api import FakeProvider

from app.config import Settings
from app.main import create_app


def test_trial_assets_are_local_and_protected_against_injected_scripts(tmp_path):
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        for path, mime in (("/trial", "text/html"), ("/trial/app.js", "javascript"),
                           ("/trial/style.css", "text/css")):
            response = client.get(path)
            assert response.status_code == 200
            assert mime in response.headers["content-type"]
            assert "script-src 'self'" in response.headers["content-security-policy"]
            assert "frame-ancestors 'none'" in response.headers["content-security-policy"]
            assert response.headers["cache-control"] == "no-store"
        html = client.get("/trial").text
        assert "恋人仅此唯一，此择不可挽回" in html
        assert "API Key" in html and "温柔相伴" in html
        assert client.get("/trial/secret.env").status_code == 404
        assert client.get("/api/conversations").status_code == 401


def test_trial_uses_plain_text_rendering_and_never_persists_a_key(tmp_path):
    with TestClient(create_app(Settings(data_dir=tmp_path), provider_client=FakeProvider())) as client:
        script = client.get("/trial/app.js").text
        assert "textContent" in script and "innerHTML" not in script
        assert "warm_v1" in script
        assert "api_key: key" in script
        assert "localStorage.setItem(BINDING" in script
        assert "sessionStorage" not in script
