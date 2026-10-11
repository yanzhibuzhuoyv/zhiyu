"""Export the requested packed L36/L24 text encoder without copying or editing source weights."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import shutil
import secrets
import subprocess
import sys
import tempfile
import time

LLAMA_COMMIT = '23b0202a189c44a54625aadcb37a946dd1d6278d'


def validate_outputs(sources, destinations, must_be_new=()):
    roots = []
    for source in sources:
        path = Path(source).resolve(strict=True)
        root = path if path.is_dir() else path.parent
        # Packed/processor files both live beneath the original models tree.
        for parent in (root, *root.parents):
            if parent.name.lower() == 'models':
                root = parent.parent
                break
        roots.append(root)
    targets = [Path(path).resolve() for path in destinations]
    if len(set(targets)) != len(targets):
        raise ValueError('Output, log and report paths must be distinct')
    for path in targets:
        if any(path.is_relative_to(root) for root in roots):
            raise ValueError('Never write inside an original source/model tree')
    for path in must_be_new:
        if Path(path).exists():
            raise FileExistsError('Use fresh output, log and report paths')


def verify_llama(path):
    commit = subprocess.check_output(['git', '-C', str(path), 'rev-parse', 'HEAD'], text=True).strip()
    dirty = subprocess.check_output(['git', '-C', str(path), 'status', '--porcelain'], text=True).strip()
    if commit != LLAMA_COMMIT or dirty:
        raise ValueError('Expected pinned unchanged llama.cpp checkout')
    return commit


def require_worker(args):
    token = getattr(args, 'worker_token', '') or ''
    expected = os.environ.get('QWEN_TE_WORKER_TOKEN', '')
    if len(token) != 64 or not expected or not hmac.compare_digest(token, expected):
        raise ValueError('Private worker requires its owned parent launch token')


def worker_environment():
    token = secrets.token_hex(32)
    env = {**os.environ, 'HF_HUB_OFFLINE': '1', 'TRANSFORMERS_OFFLINE': '1',
           'TOKENIZERS_PARALLELISM': 'false', 'QWEN_TE_WORKER_TOKEN': token}
    return token, env


def constrain_worker_environment(directory):
    """Converter temporary tensors must also stay in its owned output tree."""
    value = str(Path(directory).resolve(strict=True))
    os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1',
                      TOKENIZERS_PARALLELISM='false', TMP=value, TEMP=value, TMPDIR=value)
    tempfile.tempdir = value


def write_report(path, result, primary_error=None):
    try:
        with path.open('x', encoding='utf-8') as report:
            json.dump(result, report, indent=2)
    except BaseException as error:
        if primary_error is None:
            raise
        primary_error.add_note(f'Diagnostic report could not be saved: {type(error).__name__}: {error}')


def stop_owned_process(process, known):
    """Reap only this Popen handle and identity-checked children captured by it."""
    import psutil
    errors = []
    members = dict(known)
    if process.poll() is None:
        try:
            parent = psutil.Process(process.pid)
            parent.create_time()
            if process.poll() is None:  # Popen handle confirms this PID still belongs to our job
                for member in [parent, *parent.children(recursive=True)]:
                    members[(member.pid, member.create_time())] = member
        except psutil.NoSuchProcess:
            pass
        except psutil.AccessDenied as error:
            errors.append(str(error))
    alive = []
    for member in reversed(list(members.values())):
        try:
            if member.is_running():  # psutil checks PID reuse using creation time
                member.terminate()
                alive.append(member)
        except psutil.NoSuchProcess:
            pass
        except psutil.AccessDenied as error:
            errors.append(str(error))
    _, survivors = psutil.wait_procs(alive, timeout=5)
    for member in survivors:
        try:
            if member.is_running():
                member.kill()
        except psutil.NoSuchProcess:
            pass
        except psutil.AccessDenied as error:
            errors.append(str(error))
    psutil.wait_procs(survivors, timeout=5)
    if process.poll() is None:
        process.kill()  # exact owned Popen handle, never a name/global PID search
    process.wait(timeout=10)
    return errors


def sample_family(observed, known):
    import psutil
    total, kernel_peak = 0, 0
    for member in [observed, *observed.children(recursive=True)]:
        try:
            known[(member.pid, member.create_time())] = member
            memory = member.memory_info()
            total += memory.rss
            kernel_peak = max(kernel_peak, getattr(memory, 'peak_wset', memory.rss))
        except psutil.NoSuchProcess:
            pass
    return total, kernel_peak


def watch_job(process, max_seconds=1200, max_rss_bytes=8*1024**3,
              minimum_available_bytes=1024**3):
    import psutil
    known, peak, kernel_peak, reason, failure = {}, 0, 0, None, None
    started = time.monotonic()
    try:
        try:
            observed = psutil.Process(process.pid)
            observed.create_time()
        except psutil.NoSuchProcess:
            if process.poll() is None:
                raise
            return {'sampled_peak_rss_bytes': 0, 'windows_peak_working_set_bytes': 0,
                    'stop_reason': None}
        while process.poll() is None:
            if time.monotonic() - started >= max_seconds:
                reason = 'Owned teacher job timeout'
                break
            try:
                total, current_kernel_peak = sample_family(observed, known)
                kernel_peak = max(kernel_peak, current_kernel_peak)
            except psutil.NoSuchProcess:
                break
            peak = max(peak, total)
            if ((max_rss_bytes is not None and peak > max_rss_bytes)
                    or psutil.virtual_memory().available < minimum_available_bytes):
                reason = 'Owned teacher job resource guard'
                break
            try:
                process.wait(timeout=.2)
            except subprocess.TimeoutExpired:
                pass
    except BaseException as error:
        failure = error
        raise
    finally:
        try:
            errors = stop_owned_process(process, known)
            if errors:
                raise RuntimeError('Owned process cleanup errors: ' + '; '.join(errors))
        except BaseException as error:
            if failure is None:
                raise
            failure.add_note(f'Owned process cleanup failed: {type(error).__name__}: {error}')
    return {'sampled_peak_rss_bytes': peak, 'windows_peak_working_set_bytes': kernel_peak,
            'stop_reason': reason}


def watch(process):
    observation = watch_job(process)
    return observation['sampled_peak_rss_bytes'], observation['stop_reason']


def validate_config(config, layers):
    if (config.get('architectures') != ['Qwen3VLForConditionalGeneration']
            or config.get('model_type') != 'qwen3_vl'):
        raise ValueError('Expected packed Qwen3-VL architecture')
    text = config.get('text_config', {})
    actual = text.get('num_hidden_layers')
    if type(actual) is not int or layers not in (36, 24) or actual != layers:
        raise ValueError('Packed layers differ from the explicit L36/L24 request')
    if (text.get('hidden_size') != 4096 or text.get('num_attention_heads') != 32
            or text.get('num_key_value_heads') != 8):
        raise ValueError('Unexpected packed encoder width or attention dimensions')
    return actual


def worker(args):
    require_worker(args)
    verify_llama(args.llama)
    validate_outputs([args.packed, args.processor, args.llama],
                     [args.output, args.log, args.report, args.output.parent / 'tokenizer-input'],
                     must_be_new=[args.output, args.output.parent / 'tokenizer-input'])
    constrain_worker_environment(args.output.parent)
    config = json.loads((args.packed / 'config.json').read_text(encoding='utf-8'))
    validate_config(config, args.layers)
    staging = args.output.parent / 'tokenizer-input'
    staging.mkdir()
    (staging / 'config.json').write_text(json.dumps(config), encoding='utf-8')
    for name in ('tokenizer.json', 'tokenizer_config.json'):
        with (args.processor / name).open('rb') as source, (staging / name).open('xb') as target:
            shutil.copyfileobj(source, target)
    sys.path[:0] = [str(args.llama), str(args.llama / 'gguf-py')]
    import torch
    import gguf
    from conversion.qwen3vl import Qwen3VLTextModel
    torch.set_num_threads(2)

    class ReadOnlyPackedEncoder(Qwen3VLTextModel):
        model_arch = Qwen3VLTextModel.model_arch
        def index_tensors(self, remote_hf_model_id=None):
            if remote_hf_model_id is not None:
                raise ValueError('Only offline local packed weights are accepted')
            tokenizer_directory = self.dir_model
            self.dir_model = args.packed
            try:
                return super().index_tensors()
            finally:
                self.dir_model = tokenizer_directory

    with torch.inference_mode():
        model = ReadOnlyPackedEncoder(staging, gguf.LlamaFileType.MOSTLY_BF16, args.output,
                                      use_temp_file=True, model_name=f'Zhiyu-research-TE-L{args.layers}')
        model.write()


def export(args):
    validate_outputs([args.packed, args.processor, args.llama], [args.output, args.log, args.report],
                     must_be_new=[args.output, args.log, args.report])
    commit = verify_llama(args.llama)
    validate_config(json.loads((args.packed / 'config.json').read_text(encoding='utf-8')), args.layers)
    staging = Path(tempfile.mkdtemp(prefix=f'te-l{args.layers}-', dir=args.output.parent))
    candidate = staging / 'encoder-bf16.gguf'
    token, env = worker_environment()
    command = [sys.executable, str(Path(__file__).resolve()), '--worker', '--worker-token', token,
               '--packed', str(args.packed),
               '--processor', str(args.processor), '--llama', str(args.llama), '--layers', str(args.layers),
               '--output', str(candidate), '--log', str(args.log), '--report', str(args.report)]
    env['CUDA_VISIBLE_DEVICES'] = ''
    started = time.monotonic()
    result = {'scope': 'packed teacher text-only export; not phone execution, editing or vision',
              'layers': args.layers, 'source': str(args.packed), 'llama_commit': commit,
              'complete': False,
              'private_staging': str(staging)}
    failure = None
    process = None
    try:
        with args.log.open('x', encoding='utf-8') as log:
            process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT, env=env,
                                       creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            observation = watch_job(process)
        result.update(observation, exit_code=process.returncode)
        if process.returncode == 0 and observation['stop_reason'] is None and candidate.is_file():
            publish_encoder(args, candidate, result)
    except BaseException as error:
        failure = error
        result.update(error_type=type(error).__name__, error=str(error))
    finally:
        if process is not None and process.poll() is None:
            try:
                errors = stop_owned_process(process, {})
                if errors:
                    raise RuntimeError('Owned export cleanup errors: ' + '; '.join(errors))
            except BaseException as error:
                if failure is None:
                    failure = error
                else:
                    failure.add_note(f'Export cleanup failed: {error}')
        result['elapsed_seconds'] = time.monotonic()-started
        write_report(args.report, result, failure)
    if failure is not None:
        raise failure
    print(json.dumps(result))
    if not result['complete']:
        raise RuntimeError('Teacher export incomplete; report and staging preserved')


def publish_encoder(args, candidate, result):
    sys.path.insert(0, str(args.llama / 'gguf-py'))
    import gguf
    reader = gguf.GGUFReader(str(candidate))
    if (reader.fields['general.architecture'].contents() != 'qwen3vl'
            or reader.fields['qwen3vl.block_count'].contents() != args.layers
            or reader.fields['qwen3vl.embedding_length'].contents() != 4096):
        raise ValueError('Actual exported GGUF architecture differs')
    tensors = len(reader.tensors)
    del reader
    with candidate.open('rb') as image:
        digest = hashlib.file_digest(image, 'sha256').hexdigest()
    os.link(candidate, args.output)  # exclusive publication; never overwrite a caller path
    result.update(complete=True, bytes=args.output.stat().st_size, sha256=digest, tensors=tensors)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('packed', 'processor', 'llama', 'output', 'log', 'report'):
        parser.add_argument('--'+name, type=Path, required=True)
    parser.add_argument('--layers', type=int, choices=(36, 24), required=True)
    parser.add_argument('--worker', action='store_true', help=argparse.SUPPRESS)
    parser.add_argument('--worker-token', help=argparse.SUPPRESS)
    args = parser.parse_args()
    worker(args) if args.worker else export(args)


if __name__ == '__main__':
    main()
