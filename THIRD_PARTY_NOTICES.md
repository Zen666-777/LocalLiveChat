# Third-party components

## llama.cpp / ggml

`app/src/main/cpp/vendor/llama.cpp` reuses the llama.cpp source snapshot in this
repository's MEL Studio / LocalLiveChat recovery source. Its CMake version is
`0.3.0-dev`; an unverified upstream commit is not claimed. The full snapshot is
committed, so builds do not download a moving upstream branch.

Upstream: https://github.com/ggml-org/llama.cpp

MIT license: `app/src/main/cpp/vendor/llama.cpp/LICENSE`; also see individual
headers and license files under `ggml` and `vendor`. The main license is packaged
in the APK as `assets/llama-LICENSE.txt`.

## Recommended model (downloaded separately)

- Base: https://huggingface.co/Qwen/Qwen3-4B-Instruct-2507
- Quantization: https://huggingface.co/unsloth/Qwen3-4B-Instruct-2507-GGUF
- File: `Qwen3-4B-Instruct-2507-Q4_K_M.gguf`, 2,497,281,120 bytes.
- Revision: `18727206c51467496bfba014368bd0a30e97f411`.
- SHA-256: `3605803b982cb64aead44f6c1b2ae36e3acdb41d8e46c8a94c6533bc4c67e597`.
- License: Apache License 2.0, https://www.apache.org/licenses/LICENSE-2.0

MEL RP is an application conversation profile. This project has not fine-tuned
the recommended model's weights. Imported models retain their own licenses.
