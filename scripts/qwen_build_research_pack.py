"""Build a local research manifest from actual files; never bundle weights in an APK."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil

LLAMA_COMMIT = '23b0202a189c44a54625aadcb37a946dd1d6278d'
SD_COMMIT = '2988060a4338a5dcd94721c964a8e87003c1ac8b'


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def copy_new(source, target):
    before = digest(source)
    with source.open('rb') as incoming, target.open('xb') as outgoing:
        shutil.copyfileobj(incoming, outgoing, length=1024*1024)
        outgoing.flush()
        os.fsync(outgoing.fileno())
    if digest(target) != before or digest(source) != before:
        raise ValueError('Source changed while copying; pack not finalized')


def build(root, directory):
    if directory.resolve().is_relative_to(root.resolve()):
        raise ValueError('Pack must be outside original weights')
    manifest = directory / 'manifest.json'
    if manifest.exists():
        raise FileExistsError('Manifest already exists')
    copies = [(root / 'distill/gguf/qwen3-0.6b-base-Q4_K_M.gguf', directory / 'student-q4_K_M.gguf'),
              (root / 'models/Qwen-Image-2.1-Turbo/vae/diffusion_pytorch_model.safetensors', directory / 'vae-bf16.safetensors')]
    for source, target in copies:
        if target.exists():
            raise FileExistsError('Choose a pack directory with fresh student/VAE paths')
        if not source.is_file() or source.is_symlink():
            raise ValueError('Missing or linked source file')
    for name in ('adapter.fp32.bin', 'dit-q4_K.gguf'):
        if not (directory / name).is_file() or (directory / name).is_symlink():
            raise ValueError('Export the native adapter and DiT before making a manifest')
    for source, target in copies:
        copy_new(source, target)
    roles = [('student', 'student-q4_K_M.gguf'), ('adapter', 'adapter.fp32.bin'),
             ('dit', 'dit-q4_K.gguf'), ('vae', 'vae-bf16.safetensors')]
    files = [{'role': role, 'path': name, 'bytes': (directory/name).stat().st_size,
              'sha256': digest(directory/name)} for role, name in roles]
    payload = {'format_version': 1, 'pack_id': 'qwen_edge_research_q4_v1',
               'architecture': 'qwen-image-2.1-turbo', 'capabilities': ['text_to_image_background'],
               'files': files, 'runtime': {'llama_cpp_commit': LLAMA_COMMIT,
                 'stable_diffusion_cpp_commit': SD_COMMIT, 'bridge_version': 1},
               'conditioning': {'adapter_format': 'zqadap1_f32_le', 'in_dim': 6144, 'out_dim': 4096,
                                'layers': [-1, -4, -7, -10, -13, -16], 'drop_tokens': 14},
               'sampling': {'width': 512, 'height': 512, 'steps': 8,
                            'sigmas': [1.0, .978453, .95418, .926626, .89508, .845148, .704534, .414568, 0.0]}}
    with manifest.open('x', encoding='utf-8', newline='\n') as output:
        json.dump(payload, output, indent=2)
        output.write('\n')
        output.flush()
        os.fsync(output.fileno())
    # This receipt is research provenance, not a self-signed trust anchor for imports.
    receipt = {'manifest_sha256': digest(manifest), 'actual_total_bytes': sum(file['bytes'] for file in files),
               'scope': 'local research only; Android inference and distribution rights not verified',
               'android_tested': False, 'commercial_distribution_authorized': False}
    with (directory/'research-receipt.json').open('x', encoding='utf-8') as output:
        json.dump(receipt, output, indent=2)
    print(json.dumps(receipt))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-root', type=Path, required=True)
    parser.add_argument('--pack-directory', type=Path, required=True)
    args = parser.parse_args()
    build(args.model_root.resolve(strict=True), args.pack_directory.resolve(strict=True))


if __name__ == '__main__':
    main()
