"""No weights, API calls or private data are needed for comparison contract tests."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

SOURCE = Path(__file__).resolve().parents[1] / 'qwen_extract_student_compare.py'
SPEC = importlib.util.spec_from_file_location('qwen_student_compare', SOURCE)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class StudentCompareTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='zhiyu_student_')
        self.root = Path(self.temporary.name)
        self.addCleanup(self.temporary.cleanup)

    def folder(self, name, **overrides):
        output = self.root / name
        output.mkdir()
        raw = np.ones((1, 2, 6144), dtype='<f4').tobytes()
        metadata = {'shape': [1, 2, 6144], 'dtype': 'float32', 'tokens_dropped': 0,
                    'hf_hidden_state_indices': [-1, -4, -7, -10, -13, -16],
                    'token_ids_sha256': hashlib.sha256(b'public test tokens').hexdigest()}
        metadata.update(overrides)
        (output / 'metadata.json').write_text(json.dumps(metadata), encoding='utf-8')
        (output / 'features.f32').write_bytes(raw)
        return output

    def test_same_token_features_match(self):
        result = MODULE.compare(self.folder('left'), self.folder('right'))
        self.assertEqual([1] * 6, result['mean_layer_cosines'])

    def test_different_tokens_with_same_shape_rejected(self):
        with self.assertRaisesRegex(ValueError, 'token identities'):
            MODULE.compare(self.folder('left'), self.folder('right', token_ids_sha256='0' * 64))

    def test_wrong_layer_order_rejected(self):
        with self.assertRaisesRegex(ValueError, 'layer order'):
            MODULE.compare(self.folder('left'), self.folder('right', hf_hidden_state_indices=[-4, -1]))

    def test_nonfinite_vectors_rejected(self):
        path = self.folder('bad')
        values = np.ones((1, 2, 6144), dtype='<f4')
        values[0, 1, 6143] = np.inf
        (path / 'features.f32').write_bytes(values.tobytes())
        with self.assertRaisesRegex(ValueError, 'Non-finite'):
            MODULE.matrix(path)

    def test_wrong_file_size_rejected(self):
        path = self.folder('bad')
        (path / 'features.f32').write_bytes(b'\0' * 4)
        with self.assertRaisesRegex(ValueError, 'byte count'):
            MODULE.matrix(path)

    def test_dropped_tokens_rejected(self):
        with self.assertRaisesRegex(ValueError, 'untrimmed'):
            MODULE.matrix(self.folder('bad', tokens_dropped=14))

    def test_unbounded_dimensions_rejected(self):
        with self.assertRaisesRegex(ValueError, 'bounded'):
            MODULE.matrix(self.folder('bad', shape=[1, 513, 6144]))


if __name__ == '__main__':
    unittest.main()
