"""Measure the packed teacher on host; stdin preserves UTF-8 and no user data is opened."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time
from qwen_export_teacher import stop_owned_process, validate_outputs, watch_job, write_report


def probe(args):
    sources = [args.model, args.executable]
    if args.reference_cache:
        sources.append(args.reference_cache)
    validate_outputs(sources, [args.output, args.log, args.report],
                     must_be_new=[args.output, args.log, args.report])
    prompt, reference = cached_reference(args.reference_cache)
    command = [str(args.executable.resolve()), str(args.model.resolve()), str(args.layers), str(args.output.resolve())]
    env = dict(os.environ)
    if args.compiler_runtime:
        env['PATH'] = str(args.compiler_runtime) + os.pathsep + env['PATH']
    started = time.monotonic()
    result = {'scope': 'packed L36/L24 text-only native host CLI; not image generation',
              'layers': args.layers, 'command': command, 'prompt': prompt,
              'complete': False}
    failure, process = None, None
    try:
        with args.log.open('x', encoding='utf-8') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.PIPE, env=env,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            process.stdin.write(prompt.encode('utf-8'))
            process.stdin.close()
            observation = watch_job(process, max_rss_bytes=None, minimum_available_bytes=512*1024**2)
        result.update(observation, exit_code=process.returncode)
        if process.returncode == 0 and observation['stop_reason'] is None:
            finish_probe(args, result, reference)
    except BaseException as error:
        failure = error
        result.update(error_type=type(error).__name__, error=str(error))
    finally:
        if process is not None and process.poll() is None:
            try:
                errors = stop_owned_process(process, {})
                if errors:
                    raise RuntimeError('Owned probe cleanup errors: ' + '; '.join(errors))
            except BaseException as error:
                if failure is None:
                    failure = error
                else:
                    failure.add_note(f'Probe cleanup failed: {error}')
        result['elapsed_seconds'] = time.monotonic()-started
        write_report(args.report, result, failure)
    if failure is not None:
        raise failure
    print(json.dumps(result))
    if not result['complete']:
        raise RuntimeError('Teacher encoding incomplete; preserve report and logs')


def finish_probe(args, result, reference):
    tokens = args.output / 'tokens.i32'; conditions = args.output / 'conditions.f32'
    count = tokens.stat().st_size//4
    if tokens.stat().st_size != count*4 or not 15 <= count <= 512 or conditions.stat().st_size != (count-14)*4096*4:
        raise ValueError('Teacher output dimensions differ')
    import numpy as np
    values = np.fromfile(conditions, dtype='<f4')
    if not np.isfinite(values).all():
        raise ValueError('Non-finite teacher output')
    result.update(complete=True, input_tokens=count, condition_shape=[1, count-14, 4096],
                  token_ids_sha256=hashlib.sha256(tokens.read_bytes()).hexdigest(),
                  conditions_sha256=hashlib.sha256(conditions.read_bytes()).hexdigest())
    if reference is not None:
        from qwen_adapter_compare import metrics
        expected = reference['layers'][args.layers]['embeds'].float().numpy()
        result['versus_provided_cached_teacher'] = metrics(values.reshape(1, count-14, 4096), expected)
        result['cached_reference_scope'] = 'existing provided cache; differing quantization, not same-input full-precision equivalence'


def cached_reference(path):
    prompt = 'A watercolor lakeside garden at sunrise, pale pink flowers, no people, no text.'
    if path is None:
        return prompt, None
    if path.stat().st_size > 64*1024**2:
        raise ValueError('Reference cache exceeds the 64MiB diagnostic bound')
    import torch
    reference = torch.load(path, weights_only=True, map_location='cpu')
    prompt = reference['prompt']
    if not isinstance(prompt, str) or not 1 <= len(prompt.encode('utf-8')) <= 16000:
        raise ValueError('Invalid cached reference prompt')
    return prompt, reference


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('executable', 'model', 'output', 'log', 'report'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--compiler-runtime', type=Path)
    parser.add_argument('--reference-cache', type=Path)
    parser.add_argument('--layers', type=int, choices=(36, 24), required=True)
    probe(parser.parse_args())


if __name__ == '__main__':
    main()
