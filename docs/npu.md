# Hexagon NPU trial

User device: SM-F971Z. Samsung identifies it as Galaxy Z Fold8; SoftBank lists
Snapdragon 8 Elite Gen 5 for Galaxy and 12 GB RAM for that carrier model.
This identifies the hardware family, not permission for an ordinary Android
app to use its NPU driver. No physical Galaxy is attached to this build host.

- Samsung: https://www.samsung.com/jp/support/model.SM-F971ZZWASBM/
- Carrier specifications: https://www.softbank.jp/mobile/products/samsung-galaxy/samsung-galaxy-z-fold8/
- Backend documentation: https://github.com/ggml-org/llama.cpp/tree/master/docs/backend/snapdragon

## Build variants

Normal APKs are GPU/CPU builds (`1.4.1-gpu`). The separate **MEL Chat Hexagon
NPU verification** workflow builds `1.5.0-npu-test` with the same package,
database and fixed certificate. Its `MEL-Chat-NPU-TEST-APK` artifact includes
the optional Hexagon host backend and four DSP kernels (v73/v75/v79/v81).
Local Windows builds remain GPU/CPU unless an appropriate SDK is supplied.
The available pinned SDK is Linux x86-64, so the NPU APK is built in Actions.

The SDK is Community Edition 6.7.0.0, fixed by release URL and SHA-256. We build
our bundled ggml DSP sources; SDK compilers themselves are not packaged in the
APK. DSP ELFs are checked for the Hexagon machine type and packaged as assets,
not ARM JNI libraries. Their manifest hashes are checked before copying them
to the app's private no-backup directory. Driver lookup uses the optional
`libcdsprpc.so` native-library declaration. No root or cloud inference is used.

## Try on the phone

1. Update-install the NPU test APK. The ordinary GPU APK does not contain NPU code.
2. In connection settings, choose **NPU優先（試験）**.
3. Download the linked Q4_0 model in the browser (about 2.4 GB), then use the
   existing GGUF import button to add and select it. Keep the old model for comparison.
4. Send a short message. Check the reported processing destination in settings.

Trial model: `bartowski/Qwen_Qwen3-4B-Instruct-2507-GGUF`, revision
`ae44f08e1392f39c0e474af10c3ff8355c8b6688`,
`Qwen_Qwen3-4B-Instruct-2507-Q4_0.gguf`, 2,375,772,896 bytes, SHA-256
`b2198e1e35b98e2e126a00d9e853a53bc3a4bcca5f82cfbf2a03b38a91f2662c`.
The browser-import flow accepts custom GGUFs and displays the calculated hash;
it does not authenticate every imported file against this particular hash.

The vendored backend's matrix kernels support F32/F16, Q4_0/Q4_1/Q8_0, IQ4_NL
and MXFP4. We inspect GGUF tensor types before probing NPU. The input
`token_embd.weight` is excluded because llama.cpp always keeps that layer on
CPU. Inspection of the pinned trial file's bounded header found 145 F32,
248 Q4_0, four Q4_1 tensors and one Q6_K input embedding. The existing
recommended Q4_K_M includes unsupported K-quants and is routed to GPU/CPU,
with a reason shown in settings. A newer upstream backend may support other
formats; do not assume those capabilities exist in this vendored snapshot.

## Recovery and interpretation

NPU mode is an explicit opt-in. It tries NPU, then GPU, then CPU. CPU-only mode
does not initialize Hexagon. Unknown or unverifiable NPU architectures are
rejected rather than guessing an older kernel. A rejected driver/session or
model/context allocation returns to GPU/CPU where recoverable. Native driver
crashes cannot all be caught in-process. Persistent markers inside the worker
cause CPU recovery after process termination, including conservative recovery
after an OS kill. Re-select a processing mode to retry.

The reported NPU name describes model assignment and can include CPU work. It
does not prove every operator runs on NPU or establish an advantage over GPU.
The protocol and model-format checks can run on CI without an NPU. A successful
APK build or missing-driver test is not physical NPU inference validation.

Pending: actual SM-F971Z driver access, full generation, time to first text,
tokens/second, cancellation/recovery and sustained thermal/power comparisons.
NPU support does not establish RP quality or the separate zeta-quality target.
