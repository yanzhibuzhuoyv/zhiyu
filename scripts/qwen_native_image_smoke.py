"""Run one real desktop native image with kernel peak memory, without cloud calls."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

import psutil
from qwen_export_teacher import stop_owned_process, validate_outputs, write_report


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def measure(process, started, stats):
    monitor = psutil.Process(process.pid)
    while process.poll() is None:
        try:
            memory = monitor.memory_info()
            stats['sampled_peak_rss_bytes'] = max(stats['sampled_peak_rss_bytes'], memory.rss)
            for name, field in (('windows_peak_working_set_bytes', 'peak_wset'),
                                ('windows_peak_pagefile_bytes', 'peak_pagefile')):
                stats[name] = max(stats[name], getattr(memory, field, 0))
            stats['samples'] += 1
            if psutil.virtual_memory().available < 512*1024**2:
                return 'desktop available RAM fell below 512MiB'
            if time.monotonic()-started > 1800:
                return 'bounded desktop image timeout (1800s)'
        except psutil.NoSuchProcess:
            break
        try:
            process.wait(timeout=.2)
        except subprocess.TimeoutExpired:
            pass
    process.wait(timeout=30)
    return None


def inspect_image(path):
    from PIL import Image
    with Image.open(path) as image:
        image.load()
        if image.size != (512, 512) or image.mode not in ('RGB', 'RGBA'):
            raise ValueError('Unexpected image output')
        return {'image_complete': True, 'image_mode': image.mode,
                'image_extrema': image.getextrema(), 'image_bytes': path.stat().st_size,
                'image_sha256': digest(path)}


def run_image(args, command, started, stats):
    process, failure, reason = None, None, None
    env = {**os.environ, 'PATH': str(args.compiler_runtime) + os.pathsep + os.environ['PATH']}
    try:
        with args.log.open('x', encoding='utf-8') as log:
            process = subprocess.Popen(command, env=env, stdout=log, stderr=subprocess.STDOUT)
            reason = measure(process, started, stats)
    except BaseException as error:
        failure = error
    finally:
        try:
            errors = stop_owned_process(process, {}) if process is not None else []
            if errors:
                raise RuntimeError('Owned image cleanup errors: '+'; '.join(errors))
        except BaseException as error:
            if failure is None:
                failure = error
            else:
                failure.add_note(f'Owned image cleanup failed: {error}')
    return process, failure, reason


def smoke(args):
    validate_outputs([args.executable, args.dit, args.vae, args.conditions],
        [args.output, args.report, args.log], must_be_new=[args.output, args.report, args.log])
    for path in (args.output, args.report, args.log):
        path.parent.mkdir(parents=True, exist_ok=True)
    condition_sha = digest(args.conditions)
    command = [str(args.executable.resolve()), str(args.dit.resolve()), str(args.vae.resolve()),
               str(args.conditions.resolve()), str(args.tokens), str(args.seed), str(args.output.resolve())]
    started = time.monotonic()
    stats = {'sampled_peak_rss_bytes': 0, 'windows_peak_working_set_bytes': 0,
             'windows_peak_pagefile_bytes': 0, 'samples': 0, 'sampling_interval_seconds': .2}
    process, failure, reason = run_image(args, command, started, stats)
    route = getattr(args, 'condition_route', 'student')
    result = {'scope': 'real desktop native CPU 512x512 Euler8/CFG1 external student condition',
              'condition_route': route, 'android_tested': False, 'command': command,
              'exit_code': process.returncode if process else None,
              'elapsed_seconds': time.monotonic()-started, 'stop_reason': reason, 'memory': stats,
              'condition_sha256': condition_sha, 'tokens': args.tokens, 'seed': args.seed,
              'cloud_calls': 0, 'image_complete': False}
    if failure is None and reason is None and process and process.returncode == 0 and args.output.is_file():
        try:
            result.update(inspect_image(args.output))
        except BaseException as error:
            failure = error
    result['monitor_error'] = f'{type(failure).__name__}: {failure}' if failure else None
    result['scope'] = f'real desktop native CPU 512x512 Euler8/CFG1 external {route} condition'
    write_report(args.report, result, failure)
    if failure is not None:
        raise failure
    print(json.dumps(result))
    if not result['image_complete']:
        raise RuntimeError('Complete image smoke failed; see recorded log and report')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('executable', 'dit', 'vae', 'conditions', 'compiler-runtime', 'output', 'report', 'log'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--tokens', type=int, required=True)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--condition-route', choices=('student', 'l36'), default='student')
    args = parser.parse_args()
    if not 1 <= args.tokens <= 498 or args.seed < 0:
        raise ValueError('Unsupported condition token count or seed')
    smoke(args)


if __name__ == '__main__':
    main()
