#!/bin/bash
# Host JNI test: builds lib/src/main/cpp/ai_chat.cpp against third_party/llama.cpp for Linux and runs
# load + setSampling + generation through real JNI. Usage: tests/host_jni/run.sh model.gguf [builddir]
set -euo pipefail
R=$(cd "$(dirname "$0")/../.." && pwd); M=$1; B=${2:-/tmp/mini-ai-hostjni}
J=${JAVA_HOME:-}; [ -f "$J/include/jni.h" ] || J=$(ls -d /home/box/mini-ai-sdk/jdk-* | head -1)
L=$R/third_party/llama.cpp
if [ ! -f "$B/build/src/libllama.a" ]; then
  cmake -G Ninja -S "$L" -B "$B/build" -DCMAKE_BUILD_TYPE=Release -DBUILD_SHARED_LIBS=OFF -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
    -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_CURL=OFF -DLLAMA_OPENSSL=OFF >/dev/null
  cmake --build "$B/build" -j4 --target llama-common >/dev/null
fi
g++ -std=c++17 -O2 -shared -fPIC -o "$B/libai-chat.so" "$R/lib/src/main/cpp/ai_chat.cpp" \
  -I"$R/tests/host_jni" -I"$J/include" -I"$J/include/linux" -I"$L" -I"$L/common" -I"$L/include" -I"$L/ggml/include" -I"$L/ggml/src" -I"$L/vendor" \
  -Wl,--start-group "$B"/build/common/*.a "$B"/build/src/libllama.a "$B"/build/ggml/src/*.a -Wl,--end-group -fopenmp -lpthread -ldl
mkdir -p "$B/cls" && "$J/bin/javac" -d "$B/cls" "$R/tests/host_jni/java/com/arm/aichat/internal/InferenceEngineImpl.java"
"$J/bin/java" -cp "$B/cls" com.arm.aichat.internal.InferenceEngineImpl "$B/libai-chat.so" "$M"
