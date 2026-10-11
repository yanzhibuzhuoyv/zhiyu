import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / 'qwen_phone_safety.py'
SPEC = importlib.util.spec_from_file_location('qwen_phone_safety', SCRIPT)
SAFETY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SAFETY)


class PhoneThermalTest(unittest.TestCase):
    def test_hal_temperature_takes_precedence_over_stale_cache(self):
        text = ('Thermal Status: 0\nCached temperatures:\n'
                'Temperature{mValue=32.2, mType=2, mName=battery, mStatus=0}\n'
                'Current temperatures from HAL:\n'
                'Temperature{mValue=43.9, mType=2, mName=battery, mStatus=0}\n'
                'Current cooling devices from HAL:\n')
        self.assertEqual(SAFETY.battery_temperature(text), 43.9)

    def test_cache_alone_does_not_claim_current_temperature(self):
        self.assertIsNone(SAFETY.battery_temperature(
            'Temperature{mValue=32.2, mType=2, mName=battery, mStatus=0}'))

    def test_multiple_current_batteries_use_hottest(self):
        text = ('Current temperatures from HAL:\n'
                'Temperature{mValue=39.0, mType=2, mName=battery, mStatus=0}\n'
                'Temperature{mValue=44.1, mType=2, mName=battery, mStatus=0}')
        self.assertEqual(SAFETY.battery_temperature(text), 44.1)

    def test_battery_service_fallback_uses_tenths_celsius(self):
        self.assertEqual(SAFETY.battery_temperature('', 'temperature: 397\n'), 39.7)

    def test_threshold_boundary_is_enforced(self):
        self.assertIsNotNone(SAFETY.stop_reason({'battery_celsius': 44.0}))
        self.assertIsNone(SAFETY.stop_reason({'battery_celsius': 43.9}))

    def test_memory_and_framework_severe_remain_independent(self):
        self.assertIsNotNone(SAFETY.stop_reason({'available_bytes': 383*1024**2}))
        self.assertIsNotNone(SAFETY.stop_reason({'thermal_status': 3}))
        self.assertIsNone(SAFETY.stop_reason({'available_bytes': 384*1024**2, 'thermal_status': 2}))

    def test_unknown_state_does_not_fabricate_reading(self):
        self.assertIsNone(SAFETY.stop_reason({}))


if __name__ == '__main__':
    unittest.main()
