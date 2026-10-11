"""Offline BF16 L36 reference with bounded GPU/CPU allocation and owned disk offload."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time

from qwen_export_teacher import (constrain_worker_environment, require_worker, stop_owned_process, validate_config,
                                validate_outputs, watch_job, worker_environment, write_report)


def reference(args):
    require_worker(args)
    validate_outputs([args.packed, args.processor],
                     [args.output, args.log, args.report, args.output / 'offload'])
    if not args.output.is_dir() or any(args.output.iterdir()):
        raise FileExistsError('Private reference worker requires a new empty output directory')
    with (args.output / '.worker-claim').open('x', encoding='utf-8') as claim:
        claim.write('owned teacher reference worker\n')
    constrain_worker_environment(args.output)
    import torch
    from transformers import AutoTokenizer, Qwen3VLModel
    torch.set_num_threads(2)
    validate_config(json.loads((args.packed / 'config.json').read_text(encoding='utf-8')), args.layers)
    if not torch.cuda.is_available():
        raise RuntimeError('This bounded reference requires the desktop CUDA device')
    model = Qwen3VLModel.from_pretrained(args.packed, dtype=torch.bfloat16,
        device_map='auto', max_memory={0: '5GiB', 'cpu': '4GiB'},
        offload_folder=str(args.output / 'offload'), offload_state_dict=True,
        local_files_only=True, trust_remote_code=False, use_safetensors=True,
        attn_implementation='eager').eval()
    tokenizer = AutoTokenizer.from_pretrained(args.processor, local_files_only=True, trust_remote_code=False)
    prompt = 'A watercolor lakeside garden at sunrise, pale pink flowers, no people, no text.'
    wrapped = ('<|im_start|>system\nComprehend and analyze the provided prompt.<|im_end|>\n'
               f'<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n')
    inputs = tokenizer(wrapped, return_tensors='pt')
    tokens = inputs['input_ids'].to(torch.int32).numpy().astype('<i4')
    if not 15 <= tokens.shape[1] <= 512:
        raise ValueError('Reference tokens outside bounded 15..512 range')
    with (args.output / 'tokens.i32').open('xb') as data:
        tokens.tofile(data)
    hook = model.language_model.norm.register_forward_hook(lambda module, incoming, output: incoming[0])
    started = time.monotonic()
    try:
        with torch.inference_mode():
            output = model(**inputs, use_cache=False, output_hidden_states=False)
            conditions = output.last_hidden_state[:, 14:].to(torch.bfloat16).float().cpu().numpy()
    finally:
        hook.remove()
    metadata = {'layers': args.layers, 'dtype': 'BF16 original weights, unnormalized final hidden state',
                'condition_shape': list(conditions.shape), 'forward_seconds': time.monotonic()-started,
                'device_map': {name: str(device) for name, device in model.hf_device_map.items()}}
    save_reference(args.output, conditions, tokens.shape[1], metadata)
    print(json.dumps(metadata), flush=True)


def save_reference(directory, conditions, tokens, metadata):
    import numpy as np
    if list(conditions.shape) != [1, tokens-14, 4096]:
        raise ValueError('Reference condition dimensions differ')
    if not np.isfinite(conditions).all():
        raise ValueError('Non-finite reference condition')
    with (directory / 'conditions.f32').open('xb') as data:
        conditions.astype('<f4').tofile(data)
    with (directory / 'metadata.json').open('x', encoding='utf-8') as data:
        json.dump(metadata, data, indent=2)


def launch(args):
    validate_outputs([args.packed, args.processor],
                     [args.output, args.log, args.report, args.output / 'offload'],
                     must_be_new=[args.output, args.log, args.report])
    if args.log.resolve().is_relative_to(args.output.resolve()) or args.report.resolve().is_relative_to(args.output.resolve()):
        raise ValueError('Log and report must be outside the private reference directory')
    args.output.mkdir()
    token, env = worker_environment()
    command = [sys.executable, str(Path(__file__).resolve()), '--worker', '--worker-token', token,
               '--packed', str(args.packed),
               '--processor', str(args.processor), '--layers', str(args.layers), '--output', str(args.output),
               '--log', str(args.log), '--report', str(args.report)]
    started = time.monotonic()
    result = {'scope': 'offline full-precision teacher reference, not mobile execution',
              'complete': False}
    failure, process = None, None
    try:
        with args.log.open('x', encoding='utf-8') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            observation = watch_job(process)
        result.update(observation, exit_code=process.returncode,
                      sampled_family_peak_rss_bytes=observation['sampled_peak_rss_bytes'])
        if process.returncode == 0 and observation['stop_reason'] is None:
            finish_reference(args, result)
    except BaseException as error:
        failure = error
        result.update(error_type=type(error).__name__, error=str(error))
    finally:
        if process is not None and process.poll() is None:
            try:
                errors = stop_owned_process(process, {})
                if errors:
                    raise RuntimeError('Owned reference cleanup errors: ' + '; '.join(errors))
            except BaseException as error:
                if failure is None:
                    failure = error
                else:
                    failure.add_note(f'Reference cleanup failed: {error}')
        result['elapsed_seconds'] = time.monotonic()-started
        write_report(args.report, result, failure)
    if failure is not None:
        raise failure
    print(json.dumps(result))
    if not result['complete']:
        raise RuntimeError('Reference incomplete; retained diagnostic files')


def finish_reference(args, result):
    import numpy as np
    tokens = args.output / 'tokens.i32'
    conditions = args.output / 'conditions.f32'
    count = tokens.stat().st_size // 4
    if tokens.stat().st_size != count*4 or not 15 <= count <= 512:
        raise ValueError('Invalid reference token dimensions')
    if conditions.stat().st_size != (count-14)*4096*4:
        raise ValueError('Invalid reference condition dimensions')
    if not np.isfinite(np.fromfile(conditions, dtype='<f4')).all():
        raise ValueError('Non-finite reference condition output')
    metadata = json.loads((args.output/'metadata.json').read_text(encoding='utf-8'))
    if metadata.get('layers') != args.layers or metadata.get('condition_shape') != [1, count-14, 4096]:
        raise ValueError('Reference metadata differs')
    result.update(complete=True, metadata=metadata,
                  conditions_sha256=hashlib.sha256(conditions.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('packed', 'processor', 'output', 'log', 'report'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--layers', type=int, choices=(36, 24), required=True)
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--worker-token', help=argparse.SUPPRESS)
    args = parser.parse_args()
    reference(args) if args.worker else launch(args)


if __name__ == '__main__':
    main()
