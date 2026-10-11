import importlib.util
from pathlib import Path
import unittest

SCRIPT = Path(__file__).resolve().parents[1] / 'qwen_export_teacher.py'
SPEC = importlib.util.spec_from_file_location('qwen_export_teacher', SCRIPT)
TEACHER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TEACHER)


def config(layers):
    return {'architectures': ['Qwen3VLForConditionalGeneration'], 'model_type': 'qwen3_vl',
            'text_config': {'num_hidden_layers': layers, 'hidden_size': 4096,
                            'num_attention_heads': 32, 'num_key_value_heads': 8}}


class TeacherContractTest(unittest.TestCase):
    def test_full_and_pruned_require_exact_requested_layers(self):
        self.assertEqual(TEACHER.validate_config(config(36), 36), 36)
        self.assertEqual(TEACHER.validate_config(config(24), 24), 24)

    def test_no_silent_layer_fallback(self):
        with self.assertRaisesRegex(ValueError, 'layers'):
            TEACHER.validate_config(config(24), 36)

    def test_distilled_student_is_a_separate_architecture(self):
        source = config(36); source['model_type'] = 'qwen3'
        with self.assertRaisesRegex(ValueError, 'architecture'):
            TEACHER.validate_config(source, 36)

    def test_unexpected_hidden_width_is_rejected(self):
        source = config(36); source['text_config']['hidden_size'] = 1024
        with self.assertRaisesRegex(ValueError, 'width'):
            TEACHER.validate_config(source, 36)

    def test_layer_count_is_integer_and_known(self):
        for value in (True, 28, '36', 36.0):
            source = config(value)
            with self.assertRaises(ValueError):
                TEACHER.validate_config(source, 36)


if __name__ == '__main__':
    unittest.main()
