#!/usr/bin/env bash
set -euo pipefail
: "${HEXAGON_SDK_ROOT:?HEXAGON_SDK_ROOT must point at the verified SDK}"
: "${ANDROID_HOME:?ANDROID_HOME must be set}"
mel_tools_relative=$(python3 -c 'import json,os; print(json.load(open(os.path.join(os.environ["HEXAGON_SDK_ROOT"],"hexagon_sdk.json")))["root"]["tools"]["info"][0]["path"])')
export HEXAGON_TOOLS_ROOT="$HEXAGON_SDK_ROOT/$mel_tools_relative"
mel_build=/tmp/mel-hexagon-native
mel_output=/tmp/mel-hexagon-verification
mkdir -p "$mel_output"
cmake -S app/src/main/cpp -B "$mel_build" -G Ninja \
  -DCMAKE_BUILD_TYPE=Release \
  -DCMAKE_TOOLCHAIN_FILE="$ANDROID_HOME/ndk/27.2.12479018/build/cmake/android.toolchain.cmake" \
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 \
  -DANDROID_STL=c++_shared -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
  -DMEL_VULKAN=OFF -DGGML_HEXAGON=ON \
  -DPREBUILT_LIB_DIR=android_Release_aarch64 \
  -DHEXAGON_SDK_ROOT="$HEXAGON_SDK_ROOT" -DHEXAGON_TOOLS_ROOT="$HEXAGON_TOOLS_ROOT"
cmake --build "$mel_build" --target mel_llm htp-v73 htp-v75 htp-v79 htp-v81 -j 2
find "$mel_build" -type f -name 'libggml-htp-v*.so' -exec cp {} "$mel_output/" \;
cp "$mel_build/libmel_llm.so" "$mel_output/"
find "$HEXAGON_SDK_ROOT" -maxdepth 3 -type f -iname '*license*' -exec cp --parents {} "$mel_output/" \;
printf 'Build verification only. No physical NPU inference has been run.\n' > "$mel_output/README.txt"
