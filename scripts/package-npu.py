"""Package our compiled DSP kernels as verified assets, not ARM JNI libraries."""
import hashlib
import json
import pathlib
import shutil
import struct
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path("app/src/main/assets/npu")
destination.mkdir(parents=True, exist_ok=True)
entries = []
for version in (73, 75, 79, 81):
    name = f"libggml-htp-v{version}.so"
    data = (source / name).read_bytes()
    if not (0 < len(data) <= 32 * 1024 * 1024):
        raise ValueError(f"Invalid kernel size: {name}")
    if len(data) < 20 or data[:6] != b"\x7fELF\x01\x01" or struct.unpack_from("<H", data, 18)[0] != 164:
        raise ValueError(f"Expected a little-endian 32-bit Hexagon ELF: {name}")
    (destination / name).write_bytes(data)
    entries.append({"name": name, "sha256": hashlib.sha256(data).hexdigest()})
(destination / "manifest.json").write_text(json.dumps(entries, indent=2) + "\n", encoding="utf-8")
shutil.copyfile("app/src/main/assets/llama-LICENSE.txt", destination / "LICENSE-llama.txt")
(destination / "BUILD.txt").write_text(
    "MEL Chat experimental DSP kernels, built from the bundled llama.cpp MIT sources.\n"
    "Build toolchain: Hexagon SDK Community Edition 6.7.0.0 (not bundled).\n"
    "https://github.com/snapdragon-toolchain/hexagon-sdk/releases/tag/v6.7.0.0\n"
    "No physical NPU inference or Snapdragon benchmark is implied by packaging.\n", encoding="utf-8")
print(f"Verified and packaged {len(entries)} Hexagon kernel assets.")
