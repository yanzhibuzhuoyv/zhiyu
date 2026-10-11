# Qwen3 distilled student's six-layer feature bridge

This is a research CLI, not an Android image generator. It preserves the existing
adapter's six per-token feature layers without changing upstream llama.cpp or
the original `qwen-image-edge` model folder. No prompt or private chat is loaded
from the application's database.

Upstream source is pinned to
`ggml-org/llama.cpp@23b0202a189c44a54625aadcb37a946dd1d6278d`.
Use a separate clean checkout: CMake rejects a different commit or dirty source.
The upstream MIT license remains in the checkout. Model licenses are independent.

## Tensor contract

The 28-layer Hugging Face Qwen3Model's hidden states include the initial token
embedding, layer inputs, and the final RMS-normalized state. Therefore the trained
adapter's `[-1,-4,-7,-10,-13,-16]` is exactly `[28,25,22,19,16,13]`.

| Feature slice | HF hidden state | llama.cpp tensor |
| --- | --- | --- |
| 0 | 28, final RMS-normalized | `result_norm` |
| 1 | 25, after decoder 24 | `l_out-24` |
| 2 | 22, after decoder 21 | `l_out-21` |
| 3 | 19, after decoder 18 | `l_out-18` |
| 4 | 16, after decoder 15 | `l_out-15` |
| 5 | 13, after decoder 12 | `l_out-12` |

At the locked commit, `build_pooling(NONE)` reuses the same final state and
renames it `result_embd_pooled`. This is an alias, with no pooling or new norm.
The CLI fixes pooling to NONE and verifies all six tensors are contiguous F32
`[1024,T,1,1]`. It requests **all** batch outputs so the final layer cannot
collapse to the last token. Missing, duplicated, reshaped or non-finite features
abort the run; a generic last-layer embedding is never substituted.

The output `features.f32` is little-endian float32 `[1,T,6144]`, with token-major
layout followed by the ordered six 1024-dimensional feature slices. It includes
all template tokens. Apply the trained adapter first, then discard the first 14
valid tokens, matching the existing PyTorch pipeline. The CLI accepts explicit
token IDs. `qwen-student-tokenize` also accepts bounded UTF-8 stdin and builds the
frozen chat template; all three synthetic prompts have byte-identical HF/native
token IDs. `student_runtime.hpp` exposes a reusable C++ API with cancellation,
RAII ownership and callback exception containment. This is not an App JNI API.

## Reproduce

From the project root, create the separate upstream checkout under the ignored
`artifacts/qwen-native-runtime/llama-src`, checking out the pinned commit. Then:

```powershell
$env:PATH = 'C:\msys64\ucrt64\bin;' + $env:PATH
$cmake = 'C:\Users\32202\AppData\Local\Android\Sdk\cmake\3.31.6\bin\cmake.exe'
& $cmake -S native/qwen-edge/student -B artifacts/qwen-native-runtime/student-build -G Ninja `
  -DCMAKE_MAKE_PROGRAM=C:/Users/32202/AppData/Local/Android/Sdk/cmake/3.31.6/bin/ninja.exe `
  -DCMAKE_C_COMPILER=C:/msys64/ucrt64/bin/gcc.exe `
  -DCMAKE_CXX_COMPILER=C:/msys64/ucrt64/bin/g++.exe `
  -DCMAKE_BUILD_TYPE=Release `
  -DLLAMA_SOURCE_DIR=C:/Users/32202/Desktop/99999/zhiyu/artifacts/qwen-native-runtime/llama-src
& $cmake --build artifacts/qwen-native-runtime/student-build --parallel 4
& 'C:\Users\32202\AppData\Local\Android\Sdk\cmake\3.31.6\bin\ctest.exe' `
  --test-dir artifacts/qwen-native-runtime/student-build --output-on-failure
```

Use the model folder's existing Python environment for the offline synthetic
reference. This script uses `safetensors`, `local_files_only=True`, no remote code,
four CPU threads, and at most 512 tokens; it does not deserialize arbitrary pickle.

```powershell
$python = 'C:\Users\32202\Desktop\99999\qwen-image-edge\.venv\Scripts\python.exe'
& $python scripts/qwen_extract_student_reference.py `
  --model-root C:/Users/32202/Desktop/99999/qwen-image-edge `
  --output artifacts/qwen-native-runtime/new-hf-reference --dtype float32
& artifacts/qwen-native-runtime/student-build/qwen-student-extract.exe `
  --model C:/Users/32202/Desktop/99999/qwen-image-edge/distill/gguf/qwen3-0.6b-base-Q4_K_M.gguf `
  --tokens artifacts/qwen-native-runtime/new-hf-reference/case-1/tokens.i32 `
  --output artifacts/qwen-native-runtime/new-q4-case-1
& $python scripts/qwen_extract_student_compare.py `
  --reference artifacts/qwen-native-runtime/new-hf-reference/case-1 `
  --actual artifacts/qwen-native-runtime/new-q4-case-1 `
  --report artifacts/qwen-native-runtime/new-q4-comparison.json
& $python -m unittest discover -s scripts/tests -p test_qwen_student_compare.py -v
```

Output directories and reports must be new. `metadata.json` records the pinned
upstream commit, shapes, token and feature SHA-256, final norm distinction,
timing, four threads, zero GPU layers, and desktop peak working set. Original
weights and checkpoint files are not changed.

## Observed desktop results on 2026-10-11

Three public synthetic English/Chinese prompts produce 41, 48 and 228 tokens.
F16 GGUF versus FP32 HF reference has mean per-layer cosine at least 0.99999982.
F16 versus BF16 HF has 0.99968–0.99994: this separates computation precision from
the layer mapping. Q4 versus BF16 HF has 0.9680–0.9929, so Q4 is **not lossless**.
These metrics describe hidden states, not generated image quality.

The Windows x64 AVX2 CPU build's Q4 forwards take about 0.17, 0.18 and 0.76 s;
peak working sets are approximately 0.778, 0.787 and 1.025 GB. The CLI reserves
512-token context/batch capacity, including logits; these are desktop figures,
not Android memory estimates. Redmi Turbo 3 execution of case 1 took 0.954492 s
for the forward, with measured peak RSS 564,887,552 bytes. The adapter and DiT/VAE
belong to separate components. The full phone image attempt finished eight
sampling steps but did not produce a PNG; it is not a successful phone generator.

## Packed L36 / L24 teacher

`qwen-teacher-extract` accepts a text-only Qwen3-VL GGUF, the explicit layer count
(36 or 24), a new output directory, and bounded UTF-8 prompt on stdin. It captures
the final pre-RMS state (`l_out-35` or `l_out-23`), trims 14 template tokens and
rounds to BF16. It never uses the student's adapter or visual tower. The ten
teacher contract checks reject wrong dimensions, missing captures and non-finite
values. L24 is a pruned teacher; the 0.6B student is a separate trained model.

L36 Q4 was exported and executed on the desktop. The same-token BF16 reference
has mean cosine 0.95027; this is diagnostic evidence, not lossless equivalence.
Original BF16 student plus its adapter has mean cosine 0.64110 against that
teacher on the synthetic garden prompt. Adapter implementation parity must not
be confused with student/teacher approximation quality. The same-seed desktop
image pair and unresolved mobile constraints are recorded in
[the native validation report](../../../docs/QWEN_EDGE_NATIVE_PORT_2026-10-11.md).
