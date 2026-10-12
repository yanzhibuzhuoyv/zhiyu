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


def inspect_checkpoint(args):
    if not args.checkpoint.is_file() or args.checkpoint.stat().st_size != 262288:
        raise RuntimeError('Complete native checkpoint is missing or has an invalid size')
    checksum = digest(args.checkpoint)
    env = {**os.environ, 'PATH': str(args.compiler_runtime) + os.pathsep + os.environ['PATH']}
    receipt = subprocess.run([str(args.inspector.resolve()), '--inspect', str(args.checkpoint.resolve())],
        env=env, capture_output=True, text=True, check=True, timeout=30)
    metadata = json.loads(receipt.stdout)
    required = {'scope': 'research_checkpoint_inspection', 'format_version': 1,
        'model': 'QwenImage2.1', 'stage': 'diffusion_normalized', 'shape': [32, 32, 64, 1],
        'value_count': 65536, 'dtype': 'F32LE', 'layout': 'width_fast', 'sampling_steps': 8,
        'seed': args.seed, 'bytes': 262288, 'crc_verified': True, 'finite': True, 'model_loaded': False}
    if not isinstance(metadata, dict) or any(type(metadata.get(k)) is not type(v) or metadata.get(k) != v
        for k, v in required.items()):
        raise ValueError('Native checkpoint inspection returned incompatible metadata')
    if digest(args.checkpoint) != checksum:
        raise ValueError('Checkpoint changed during native inspection')
    return {'latent_complete': True, 'latent_metadata': metadata,
            'latent_bytes': args.checkpoint.stat().st_size, 'latent_sha256': checksum}


def prepare_command(args):
    checkpoint = getattr(args, 'checkpoint', None)
    sample_only = getattr(args, 'sample_only', False)
    inspector = getattr(args, 'inspector', None)
    if (sample_only and checkpoint is None) or (checkpoint is not None and inspector is None):
        raise ValueError('Checkpoint mode requires a checkpoint destination and native inspector')
    sources = [args.executable, args.dit, args.vae, args.conditions]
    destinations = [args.output, args.report, args.log]
    if checkpoint is not None:
        sources.append(inspector)
        destinations.append(checkpoint)
    if any(part.casefold() == 'qwen-image-edge' for path in destinations for part in path.resolve().parts):
        raise ValueError('Never write output, checkpoint, log or report inside the original model tree')
    validate_outputs(sources, destinations, must_be_new=destinations)
    for path in destinations:
        path.parent.mkdir(parents=True, exist_ok=True)
    command = [str(args.executable.resolve()), str(args.dit.resolve()), str(args.vae.resolve()),
               str(args.conditions.resolve()), str(args.tokens), str(args.seed), str(args.output.resolve())]
    if checkpoint is not None:
        command += ['--sample-only' if sample_only else '--checkpoint', str(checkpoint.resolve())]
    return command


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
    command = prepare_command(args)
    checkpoint = getattr(args, 'checkpoint', None)
    sample_only = getattr(args, 'sample_only', False)
    condition_sha = digest(args.conditions)
    started = time.monotonic()
    stats = {'sampled_peak_rss_bytes': 0, 'windows_peak_working_set_bytes': 0,
             'windows_peak_pagefile_bytes': 0, 'samples': 0, 'sampling_interval_seconds': .2}
    process, failure, reason = run_image(args, command, started, stats)
    route = getattr(args, 'condition_route', 'student')
    result = {'scope': f'real desktop native CPU 512x512 Euler8/CFG1 external {route} condition',
              'condition_route': route, 'android_tested': False, 'command': command,
              'exit_code': process.returncode if process else None,
              'elapsed_seconds': time.monotonic()-started, 'stop_reason': reason, 'memory': stats,
              'condition_sha256': condition_sha, 'tokens': args.tokens, 'seed': args.seed,
              'cloud_calls': 0, 'image_complete': False, 'latent_complete': False,
              'sample_only': sample_only}
    if failure is None and reason is None and process and process.returncode == 0:
        try:
            if checkpoint is not None:
                result.update(inspect_checkpoint(args))
            if sample_only:
                if args.output.exists():
                    raise ValueError('Sample-only mode unexpectedly created an image')
            elif args.output.is_file():
                result.update(inspect_image(args.output))
        except BaseException as error:
            failure = error
    result['monitor_error'] = f'{type(failure).__name__}: {failure}' if failure else None
    write_report(args.report, result, failure)
    if failure is not None:
        raise failure
    print(json.dumps(result))
    if not result['latent_complete' if sample_only else 'image_complete']:
        raise RuntimeError('Native smoke failed; see recorded log and report')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('executable', 'dit', 'vae', 'conditions', 'compiler-runtime', 'output', 'report', 'log'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--tokens', type=int, required=True)
    parser.add_argument('--seed', type=int, default=42)
    parser.add_argument('--condition-route', choices=('student', 'l36'), default='student')
    parser.add_argument('--checkpoint', type=Path)
    parser.add_argument('--inspector', type=Path, help='Native checkpoint decoder/inspector')
    parser.add_argument('--sample-only', action='store_true')
    args = parser.parse_args()
    if not 1 <= args.tokens <= 498 or args.seed < 0:
        raise ValueError('Unsupported condition token count or seed')
    smoke(args)


if __name__ == '__main__':
    main()
