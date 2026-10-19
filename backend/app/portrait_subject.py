"""Local salient-subject preview; a mask is never proof of a person's identity.

U2NetP architecture is from Apache-2.0 U-2-Net by Xuebin Qin et al.
Preprocessing follows rembg's MIT-licensed U2netpSession; no rembg code is imported.
The fixed ONNX artifact is provisioned separately and never fetched by requests.
"""

import hashlib
import io
import threading
from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageChops

from .media_images import SafeImage, normalize_image

MODEL_URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"
MODEL_SHA256 = "309c8469258dda742793dce0ebea8e6dd393174f89934733ecc8b14c76f4ddd8"
MODEL_BYTES = 4_574_861
MAX_INFERENCE_EDGE = 1600
ALPHA_BOUNDS_THRESHOLD = 16
MIN_MASK_COVERAGE = 0.001
MAX_MASK_COVERAGE = 0.995


class PortraitExtractionError(ValueError):
    """A bounded user-facing extraction error, without local/runtime details."""


@dataclass(frozen=True)
class PortraitSubject:
    original: SafeImage
    subject: SafeImage
    bbox: tuple[int, int, int, int]
    method: str
    needs_confirmation: bool = True


def _bounds(image, selected):
    width, height = image.size
    if selected is None:
        return (0, 0, width, height)
    valid = isinstance(selected, (tuple, list)) and len(selected) == 4
    if not valid or any(type(value) is not int for value in selected):
        raise PortraitExtractionError("人物选取范围必须是四个整数像素坐标")
    left, top, right, bottom = selected
    if not 0 <= left < right <= width or not 0 <= top < bottom <= height:
        raise PortraitExtractionError("人物选取范围超出图片，请重新框选")
    return (left, top, right, bottom)


def _result(original, pixels, alpha, selected, method):
    visible = alpha.point(lambda value: 255 if value > ALPHA_BOUNDS_THRESHOLD else 0)
    local = visible.getbbox()
    if local is None:
        raise PortraitExtractionError("没有找到可见主体，请调整选取范围或换一张图")
    offset_x, offset_y = selected[:2]
    bbox = (local[0] + offset_x, local[1] + offset_y, local[2] + offset_x, local[3] + offset_y)
    # RGB pixels are copied unchanged. The original normalized image remains separate.
    cutout = pixels.copy()
    cutout.putalpha(alpha)
    output = io.BytesIO()
    cutout.crop(local).save(output, "PNG", optimize=True)
    return PortraitSubject(original, normalize_image(output.getvalue()), bbox, method)


def _authored_alpha(image):
    """A transparent corner/global opacity alone does not establish a cutout."""
    alpha = image.getchannel("A")
    histogram = alpha.histogram()
    visible = sum(histogram[ALPHA_BOUNDS_THRESHOLD + 1:]) / (image.width * image.height)
    if visible < MIN_MASK_COVERAGE:
        raise PortraitExtractionError("没有找到可见主体，请调整选取范围或换一张图")
    if visible > MAX_MASK_COVERAGE:
        return False
    width, height = image.size
    borders = ((0, 0, width, 1), (0, height - 1, width, height),
               (0, 0, 1, height), (width - 1, 0, width, height))
    transparent_border = sum(sum(alpha.crop(box).histogram()[:ALPHA_BOUNDS_THRESHOLD + 1])
                             for box in borders)
    return transparent_border / (2 * width + 2 * height) >= 0.05


def _cpu_session(contents):
    try:
        import onnxruntime as ort

        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        options.enable_cpu_mem_arena = False
        options.log_severity_level = 3
        return ort.InferenceSession(contents, sess_options=options, providers=["CPUExecutionProvider"])
    except ImportError as exc:
        raise PortraitExtractionError("人物分离依赖尚未安装，请检查后端配置") from exc
    except Exception as exc:
        raise PortraitExtractionError("人物分离模型无法载入，请检查后端配置") from exc


class PortraitProcessor:
    def __init__(self, model_path: Path, *, session_factory=None):
        self.model_path = Path(model_path)
        self._factory = session_factory or _cpu_session
        self._session = None
        self._lock = threading.Lock()

    def _verified_session(self):
        if self._session is not None:
            return self._session
        try:
            with self.model_path.open("rb") as model:
                contents = model.read(MODEL_BYTES + 1)
        except OSError as exc:
            raise PortraitExtractionError("人物分离模型尚未准备好，请检查后端配置") from exc
        if hashlib.sha256(contents).hexdigest() != MODEL_SHA256:
            raise PortraitExtractionError("人物分离模型校验失败，请重新准备固定版本模型")
        try:
            self._session = self._factory(contents)
            return self._session
        except PortraitExtractionError:
            raise
        except Exception as exc:
            raise PortraitExtractionError("人物分离模型无法载入，请检查后端配置") from exc

    @staticmethod
    def _input(pixels):
        try:
            import numpy as np
        except ImportError as exc:
            raise PortraitExtractionError("人物分离依赖尚未安装，请检查后端配置") from exc
        bounded = pixels.convert("RGB")
        bounded.thumbnail((MAX_INFERENCE_EDGE, MAX_INFERENCE_EDGE), Image.Resampling.LANCZOS)
        resized = bounded.resize((320, 320), Image.Resampling.LANCZOS)
        array = np.asarray(resized, dtype=np.float32)
        maximum = float(array.max())
        array = array / max(1.0, maximum)
        mean = np.array((0.485, 0.456, 0.406), dtype=np.float32)
        std = np.array((0.229, 0.224, 0.225), dtype=np.float32)
        normalized = (array - mean) / std
        return np.ascontiguousarray(normalized.transpose(2, 0, 1)[None, :, :, :]), bounded.size

    def _mask(self, pixels):
        tensor, bounded_size = self._input(pixels)
        import numpy as np
        try:
            # One cached CPU session and one inference at a time bound resource usage.
            with self._lock:
                session = self._verified_session()
                inputs = session.get_inputs()
                outputs = session.run(None, {inputs[0].name: tensor})
            prediction = np.asarray(outputs[0], dtype=np.float32)
            if prediction.shape != (1, 1, 320, 320) or not np.isfinite(prediction).all():
                raise PortraitExtractionError("人物分离结果无效，请调整选取范围或换一张图")
            mask = prediction[0, 0]
            lower, upper = float(mask.min()), float(mask.max())
            if upper - lower < 1e-6:
                raise PortraitExtractionError("人物分离未找到清晰前景，请调整选取范围或换一张图")
            normalized = (mask - lower) / (upper - lower)
            coverage = float((normalized > ALPHA_BOUNDS_THRESHOLD / 255).mean())
            if not MIN_MASK_COVERAGE <= coverage <= MAX_MASK_COVERAGE:
                raise PortraitExtractionError("人物分离未得到可靠主体，请调整选取范围或换一张图")
            alpha = Image.fromarray((normalized * 255).astype("uint8"))
            return alpha.resize(bounded_size, Image.Resampling.LANCZOS).resize(pixels.size, Image.Resampling.LANCZOS)
        except PortraitExtractionError:
            raise
        except Exception as exc:
            raise PortraitExtractionError("人物分离暂时失败，请调整选取范围或稍后重试") from exc

    def extract(self, raw: bytes, *, crop_bounds: tuple[int, int, int, int] | None = None) -> PortraitSubject:
        original = normalize_image(raw)
        with Image.open(io.BytesIO(original.data)) as decoded:
            if getattr(decoded, "n_frames", 1) != 1:
                raise PortraitExtractionError("人物参考图需要静态图片，请从动画中选择一张静态图")
            image = decoded.convert("RGBA")
        selected = _bounds(image, crop_bounds)
        pixels = image.crop(selected)
        if _authored_alpha(image):
            return _result(original, pixels, pixels.getchannel("A"), selected, "existing_alpha")
        mask = ImageChops.multiply(self._mask(pixels), pixels.getchannel("A"))
        return _result(original, pixels, mask, selected, "u2netp")
