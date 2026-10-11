"""Run the user's authorized Redmi native research image, monitor only our own job."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from qwen_phone_safety import battery_temperature, stop_reason


def adb(args, *command, check=True):
    result = subprocess.run([str(args.adb), '-s', args.serial, *command], capture_output=True,
                            text=True, encoding='utf-8', errors='replace', timeout=15)
    if check and result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    return result.stdout


def status(args, pid):
    text = adb(args, 'shell', 'cat', f'/proc/{pid}/status', check=False)
    memory = {key: int(value)*1024 for key, value in re.findall(r'^(VmRSS|VmHWM|VmSwap):\s+(\d+) kB', text, re.M)}
    available = re.search(r'^MemAvailable:\s+(\d+) kB', adb(args, 'shell', 'cat', '/proc/meminfo'), re.M)
    thermal = adb(args, 'shell', 'dumpsys', 'thermalservice')
    level = re.search(r'Thermal Status:\s+(\d+)', thermal)
    battery = battery_temperature(thermal)
    if battery is None:
        battery = battery_temperature(thermal, adb(args, 'shell', 'dumpsys', 'battery'))
    return {**memory, 'available_bytes': int(available[1])*1024 if available else None,
            'thermal_status': int(level[1]) if level else None,
            'battery_celsius': battery}


def stop_own_job(args, pid):
    identity = adb(args, 'shell', 'cat', f'/proc/{pid}/cmdline', check=False).split('\0')[0].strip()
    if identity != args.directory+'/image_smoke':
        raise RuntimeError('Own native PID identity changed; refused to stop it')
    adb(args, 'shell', 'kill', '-TERM', pid)
    for _ in range(20):
        if pid not in own_pids(args):
            return
        time.sleep(.1)
    identity = adb(args, 'shell', 'cat', f'/proc/{pid}/cmdline', check=False).split('\0')[0].strip()
    if identity != args.directory+'/image_smoke':
        raise RuntimeError('PID ownership changed during cleanup; refused SIGKILL')
    adb(args, 'shell', 'kill', '-KILL', pid)
    if pid in own_pids(args):
        raise RuntimeError('Owned remote process termination could not be confirmed')


def own_pids(args):
    command = ('pidof image_smoke; pidof_status=$?; '
               'printf "\\nZHIYU_PIDOF_STATUS:%s\\n" "$pidof_status"')
    response = adb(args, 'shell', command)
    body, marker, code = response.rpartition('\nZHIYU_PIDOF_STATUS:')
    if not marker or code.strip() not in ('0', '1'):
        raise RuntimeError('Cannot establish a complete remote pidof receipt')
    candidates = body.split()
    if code.strip() == '1' and not candidates:
        return []
    if code.strip() != '0' or not candidates or not all(pid.isdigit() for pid in candidates):
        raise RuntimeError('Malformed remote PID list')
    # A failed cmdline read is unknown ownership, not proof of process absence.
    return [pid for pid in candidates if adb(
        args, 'shell', 'cat', f'/proc/{pid}/cmdline'
    ).split('\0')[0].strip() == args.directory+'/image_smoke']


def cleanup_remote(args):
    """A transport failure never proves that the remote process has exited."""
    try:
        owned = own_pids(args)
        if len(owned) > 1:
            raise RuntimeError('Multiple owned jobs; refused to select a process')
        if not owned:
            return {'status': 'no_owned_job', 'error': None}
        stop_own_job(args, owned[0])
        return {'status': 'stopped_owned_job', 'error': None}
    except Exception as error:
        return {'status': 'unconfirmed', 'error': f'{type(error).__name__}: {error}'}


def close_transport(process):
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def observe_existing(args):
    """Attach without starting another model. Write samples immediately and preserve errors."""
    if args.log.exists() or args.report.exists():
        raise FileExistsError('Choose new observer log and report')
    samples, error, reason, seen, cleanup = [], None, None, False, None
    started = time.monotonic()
    try:
        with args.log.open('x', encoding='utf-8') as log:
            while True:
                owned = own_pids(args)
                if len(owned) > 1:
                    raise RuntimeError('Multiple matching jobs; refused to select a PID')
                if not owned:
                    if not seen:
                        raise RuntimeError('No owned job to observe')
                    break
                seen = True
                observation = status(args, owned[0])
                observation.update(pid=owned[0], elapsed_seconds=time.monotonic()-started)
                samples.append(observation)
                log.write(json.dumps(observation)+'\n')
                log.flush()
                reason = stop_reason(observation)
                if reason:
                    stop_own_job(args, owned[0])
                    break
                time.sleep(5)
    except (Exception, KeyboardInterrupt) as exception:
        error = f'{type(exception).__name__}: {exception}'
        cleanup = cleanup_remote(args)
        raise
    finally:
        result = {'scope': 'attached guard only; not a generation success receipt',
                  'stop_reason': reason, 'error': error, 'samples': samples,
                  'remote_cleanup': cleanup}
        with args.report.open('x', encoding='utf-8') as report:
            json.dump(result, report, indent=2)


def prepare_command(args):
    if args.log.exists() or args.report.exists() or args.output.exists():
        raise FileExistsError('Choose new local logs, report and image')
    remote = args.directory+'/output-512-r1.png'
    presence = subprocess.run([str(args.adb), '-s', args.serial, 'shell', 'test', '-e', remote],
                              capture_output=True, timeout=15)
    if presence.returncode == 0:
        raise ValueError('Remote output already exists')
    if presence.returncode != 1:
        raise RuntimeError('Cannot establish remote output absence')
    command = [str(args.adb), '-s', args.serial, 'shell', args.directory+'/image_smoke',
               args.directory+'/dit.gguf', args.directory+'/vae.safetensors',
               args.directory+'/conditions.f32', '27', '42', remote]
    return command, remote


def watch_phone(args, process, started, statistics):
    while process.poll() is None:
        owned = own_pids(args)
        if len(owned) > 1:
            raise RuntimeError('Multiple matching native jobs; cannot attribute memory')
        if owned:
            statistics['pid'] = owned[0]
            observation = status(args, owned[0])
            observation['elapsed_seconds'] = time.monotonic()-started
            statistics['samples'].append(observation)
            for field, source in (('rss', 'VmRSS'), ('high_water', 'VmHWM'), ('swap', 'VmSwap')):
                statistics[field] = max(statistics[field], observation.get(source, 0))
            statistics['reason'] = stop_reason(observation)
            if statistics['reason']:
                stop_own_job(args, owned[0])
                break
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            pass
    process.wait(timeout=30)


def execute_phone(args, command, remote, started, statistics):
    process, error = None, None
    image_result = {'image_complete': False}
    try:
        with args.log.open('x', encoding='utf-8') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
            watch_phone(args, process, started, statistics)
        if process.returncode == 0:
            image_result = pull_image(args, remote)
    except (Exception, KeyboardInterrupt) as exception:
        error = f'{type(exception).__name__}: {exception}'
    return process, error, image_result


def smoke(args):
    command, remote = prepare_command(args)
    started = time.monotonic()
    statistics = {'pid': None, 'reason': None, 'samples': [], 'rss': 0, 'high_water': 0, 'swap': 0}
    process, error, image_result = execute_phone(args, command, remote, started, statistics)
    cleanup = None if image_result['image_complete'] else cleanup_remote(args)
    try:
        close_transport(process)
    except Exception as exception:
        error = (error or '') + f'; transport cleanup: {type(exception).__name__}: {exception}'
    code = process.returncode if process is not None else None
    result = {'scope': 'Redmi Turbo 3 ARM64 native CLI; not App JNI or background stock integration',
              'serial': args.serial, 'pid': statistics['pid'], 'command': command, 'adb_exit_code': code,
              'native_exit_code': None if code is None or code == 255 or code < 0 else code,
              'monitor_error': error, 'remote_cleanup': cleanup,
              'elapsed_seconds': time.monotonic()-started, 'stop_reason': statistics['reason'],
              'sampled_peak_rss_bytes': statistics['rss'],
              'sampled_kernel_high_water_bytes': statistics['high_water'],
              'sampled_peak_swap_bytes': statistics['swap'], 'samples': statistics['samples'], **image_result}
    with args.report.open('x', encoding='utf-8') as report:
        json.dump(result, report, indent=2)
    print(json.dumps({key: value for key, value in result.items() if key != 'samples'}))
    if not result['image_complete']:
        raise RuntimeError('Full phone generation incomplete; preserve report and log')


def pull_image(args, remote):
    adb(args, 'pull', remote, str(args.output))
    from PIL import Image
    with Image.open(args.output) as image:
        image.load()
        if image.size != (512, 512) or image.mode not in ('RGB', 'RGBA'):
            raise ValueError('Wrong phone image dimensions')
        return {'image_complete': True, 'image_mode': image.mode,
                'image_extrema': image.getextrema(), 'image_bytes': args.output.stat().st_size,
                'image_sha256': hashlib.sha256(args.output.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', type=Path, required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--directory', required=True)
    parser.add_argument('--observe-only', action='store_true')
    for name in ('log', 'report', 'output'):
        parser.add_argument('--'+name, type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r'/data/local/tmp/zhiyu_qwen_research_[A-Za-z0-9_]+', args.directory):
        raise ValueError('Only the explicit owned research directory is accepted')
    if args.observe_only:
        observe_existing(args)
    else:
        smoke(args)


if __name__ == '__main__':
    main()
