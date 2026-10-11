"""Corrupt-header checks for the read-only model footprint audit."""
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('qwen_audit', Path(__file__).parents[1] / 'audit_qwen_image_edge.py')
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class ModelHeaderAuditTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / 'test.safetensors'

    def write(self, shape, payload_bytes, dtype='BF16', offsets=None):
        header = json.dumps({'weight': {'dtype': dtype, 'shape': shape,
                                       'data_offsets': offsets or [0, payload_bytes]}}).encode()
        self.path.write_bytes(struct.pack('<Q', len(header)) + header + bytes(payload_bytes))

    def test_valid_bf16_header(self):
        self.write([2, 3], 12)
        result = AUDIT.audit_safetensors(self.path)
        self.assertEqual(6, result['parameters'])
        self.assertEqual({'BF16': 6}, result['dtype_parameters'])

    def test_rejects_shape_payload_mismatch(self):
        self.write([10_000], 2)
        with self.assertRaises(ValueError):
            AUDIT.audit_safetensors(self.path)

    def test_rejects_boolean_dimension(self):
        self.write([True], 2)
        with self.assertRaises(ValueError):
            AUDIT.audit_safetensors(self.path)

    def test_rejects_unknown_dtype(self):
        self.write([1], 2, dtype='not-a-format')
        with self.assertRaises(ValueError):
            AUDIT.audit_safetensors(self.path)

    def test_rejects_out_of_range_offset(self):
        self.write([1], 2, offsets=[0, 4])
        with self.assertRaises(ValueError):
            AUDIT.audit_safetensors(self.path)

    def test_rejects_oversized_header(self):
        self.path.write_bytes(struct.pack('<Q', 16 * 1024 * 1024 + 1))
        with self.assertRaises(ValueError):
            AUDIT.audit_safetensors(self.path)

    def test_rejects_missing_transformer(self):
        with self.assertRaises(ValueError):
            AUDIT.audit_transformer(Path(self.temp.name))

    def test_rejects_missing_index_shard(self):
        self.write([1], 2)
        index = {'metadata': {'total_size': 4}, 'weight_map': {
            'weight': self.path.name, 'missing': 'missing.safetensors'}}
        (self.path.parent / 'diffusion_pytorch_model.safetensors.index.json').write_text(json.dumps(index))
        with self.assertRaises(ValueError):
            AUDIT.audit_transformer(self.path.parent)

    def test_rejects_unindexed_multishard_folder(self):
        self.write([1], 2)
        self.path.with_name('other.safetensors').write_bytes(self.path.read_bytes())
        with self.assertRaises(ValueError):
            AUDIT.audit_transformer(self.path.parent)

    def test_indexed_transformer_matches_payload(self):
        self.write([1], 2)
        index = {'metadata': {'total_size': 2}, 'weight_map': {'weight': self.path.name}}
        (self.path.parent / 'diffusion_pytorch_model.safetensors.index.json').write_text(json.dumps(index))
        self.assertEqual(1, AUDIT.audit_transformer(self.path.parent)['parameters'])


if __name__ == '__main__':
    unittest.main()
