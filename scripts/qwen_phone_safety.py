"""Pure parsing and resource limits for the explicitly owned research job."""
import re


def battery_temperature(thermal, battery_service=''):
    marker = 'Current temperatures from HAL:'
    if marker in thermal:
        current = thermal.split(marker, 1)[1].split('Current cooling devices from HAL:', 1)[0]
        temperatures = [float(value) for value in re.findall(
            r'Temperature\{mValue=([0-9.]+), mType=2, mName=battery[^,}]*,', current)]
        if temperatures:
            return max(temperatures)
    value = re.search(r'^\s*temperature:\s+(\d+)\s*$', battery_service, re.M)
    return int(value[1])/10 if value else None


def stop_reason(observation):
    if observation.get('battery_celsius') is not None and observation['battery_celsius'] >= 44:
        return 'phone battery reached 44C'
    if observation.get('thermal_status') is not None and observation['thermal_status'] >= 3:
        return 'phone OS reported severe or higher thermal status'
    if observation.get('available_bytes') is not None and observation['available_bytes'] < 384*1024**2:
        return 'phone available memory below 384MiB'
    return None
