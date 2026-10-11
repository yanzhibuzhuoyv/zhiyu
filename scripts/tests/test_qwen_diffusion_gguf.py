from pathlib import Path
import importlib.util
import struct
import tempfile
import sys
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / "qwen_diffusion_quantize.py"
sys.path.insert(0, str(SCRIPT.parent))
SPEC = importlib.util.spec_from_file_location("qwen_diffusion_quantize", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def make_string(text):
    data = text.encode("utf-8")
    return struct.pack("<Q", len(data)) + data


def make_gguf(*, dtype=12, width=256, offset=0, name="model.diffusion_model.test.weight", tail=0):
    header = b"GGUF" + struct.pack("<IQQ", 3, 1, 0)
    header += make_string(name) + struct.pack("<IQQIQ", 2, width, 1, dtype, offset)
    header += b"\x00" * ((-len(header)) % 32)
    return header + b"\x00" * (144 + tail)


class GGUFHeaderTest(unittest.TestCase):
    def audit(self, data):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "synthetic.gguf"
            path.write_bytes(data)
            return MODULE.audit_gguf(path, expected_tensors=1, expected_parameters=256)

    def test_quantized_header_validates(self):
        record = self.audit(make_gguf())
        self.assertEqual(record["tensor_types"], {"Q4_K": 1})

    def test_truncated_payload_rejected(self):
        with self.assertRaisesRegex(ValueError, "file size"):
            self.audit(make_gguf()[:-1])

    def test_extra_payload_rejected(self):
        with self.assertRaisesRegex(ValueError, "file size"):
            self.audit(make_gguf(tail=1))

    def test_wrong_parameters_rejected(self):
        with self.assertRaisesRegex(ValueError, "parameter count"):
            self.audit(make_gguf(width=512))

    def test_unaligned_quantized_rows_rejected(self):
        with self.assertRaisesRegex(ValueError, "block aligned"):
            self.audit(make_gguf(width=255))

    def test_unknown_dtype_rejected(self):
        with self.assertRaisesRegex(ValueError, "dtype"):
            self.audit(make_gguf(dtype=99))

    def test_encoder_tensor_rejected(self):
        with self.assertRaisesRegex(ValueError, "non-DiT"):
            self.audit(make_gguf(name="text_encoders.llm.weight"))

    def test_truncated_header_rejected(self):
        with self.assertRaisesRegex(ValueError, "truncated"):
            self.audit(make_gguf()[:13])


if __name__ == "__main__":
    unittest.main()
