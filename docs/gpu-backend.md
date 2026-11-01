# GPU backend implementation notes

Android builds enable `MEL_VULKAN` and statically include the existing vendored
llama.cpp Vulkan backend. Host CPU evaluation stays CPU-only by default. The
`gpu-smoke` workflow explicitly enables Vulkan and runs a verified small GGUF
using Mesa lavapipe. It verifies execution, cancellation/retry, switching back
to CPU, and CPU fallback when Vulkan reports no devices. This is not a phone
benchmark, and the small fixture does not replace the recommended 4B model.

The engine registers CPU first while automatic Vulkan probing is disabled.
GPU-priority loads explicitly register Vulkan and pass a selected device and
all-layer offload request to llama.cpp. CPU-only loads pass an empty device
list and zero GPU layers. Failed GPU model/context allocation is freed before
trying CPU. The reported GPU name describes model assignment; supported
operators may still execute on CPU.

`LocalAcceleration` records GPU work inside the actual inference worker, not
the caller coroutine. The existing timeout can return before a stalled native
worker exits, so only that worker's completion clears its persisted marker.
If the process dies, the next startup uses CPU until an explicit GPU retry.
This also conservatively treats OS process termination as an interrupted GPU
operation. It does not catch arbitrary native crashes inside the same process.

Khronos header dependencies are fetched at build time with fixed commits and
SHA-256 hashes. Shaders are compiled by the NDK host glslc and embedded, with
no runtime network downloads. Third-party notices are included in APK assets.
Windows shader generation uses a verified portable LLVM-MinGW host compiler.

Two local changes to the vendored Vulkan backend must be retained on upgrades:

- Pass `CMAKE_MAKE_PROGRAM` to the shader generator's ExternalProject so Android
  Gradle builds do not depend on Ninja being on the system PATH.
- Resolve `vkGetPhysicalDeviceFeatures2` through the existing Vulkan dynamic
  dispatcher and check for `vkEnumerateInstanceVersion` before using it. This
  keeps the APK linkable on minSdk 26 and permits CPU fallback with an older
  Vulkan loader, rather than requiring Android 9 just to load the app.

NPU support is not included. Physical Galaxy validation, latency and thermal
measurements remain outstanding. This release does not satisfy the separate
RP-quality goal recorded in `quality-baseline.md`.
