"""Refresh OFL Noto font subsets for all current TSX text via Google Fonts."""
from pathlib import Path
from io import BytesIO
import re
from fontTools.ttLib import TTFont
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
HEADER = {"User-Agent": "Mozilla/5.0 Chrome/130.0.0.0 Safari/537.36"}


def fetch(url: str) -> bytes:
    request = Request(url, headers=HEADER)
    with urlopen(request, timeout=60) as response:
        return response.read()

def as_woff2(content: bytes) -> bytes:
    if content[:4] == b"wOF2":
        return content
    if content[:4] not in (b"\x00\x01\x00\x00", b"OTTO", b"wOFF"):
        raise RuntimeError("Unexpected font payload; existing font was kept")
    font = TTFont(BytesIO(content))
    font.flavor = "woff2"
    output = BytesIO()
    font.save(output)
    return output.getvalue()


def main() -> None:
    source = "".join(p.read_text(encoding="utf-8") for p in (ROOT / "src").glob("*.tsx"))
    chars = sorted(set(chr(i) for i in range(32, 127)) | set(re.findall(r"[^\x00-\x7f]", source)))
    for name, family in (("serif", "Noto Serif SC:wght@500"), ("sans", "Noto Sans SC:wght@400")):
        css_url = "https://fonts.googleapis.com/css2?" + urlencode({"family": family, "text": "".join(chars)})
        css = fetch(css_url).decode("utf-8")
        urls = re.findall(r"src:\s*url\((https://[^)]+)\)", css)
        if len(urls) != 1 or urlparse(urls[0]).hostname != "fonts.gstatic.com":
            raise RuntimeError(f"Unexpected Google Fonts response for {family}")
        content = as_woff2(fetch(urls[0]))
        target = ROOT / "public" / "fonts" / f"{name}.woff2"
        target.write_bytes(content)
        print(f"{name}: {len(content)} bytes")


if __name__ == "__main__":
    main()
