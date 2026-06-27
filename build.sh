#!/bin/bash
set -e

# ============================================================
#  HermesVoice — Build Script
#  无需 Android Studio，纯命令行构建
# ============================================================

# --- 配置路径（根据你的环境修改）---
SDK="${ANDROID_SDK:-/opt/android-sdk}"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-22/android.jar"
JAVA8="${JAVA8_HOME:-/usr/lib/jvm/java-8-openjdk}"
JAVA11="${JAVA11_HOME:-/usr/lib/jvm/java-11-openjdk}"
SRC="$(cd "$(dirname "$0")" && pwd)"
SHERPA_LIB="${SHERPA_LIB:-/tmp/sherpa-onnx-libs/lib/armeabi-v7a}"

# 构建目录
P="$SRC/build_output"
B="$P/build"; CL="$P/classes"; O="$P/output"

echo "=== HermesVoice Build ==="
echo "SDK: $SDK"
echo "JAVA8: $JAVA8"
echo "SRC: $SRC"

rm -rf "$B" "$CL" "$O"
mkdir -p "$B" "$CL" "$O" "$B/gen/com/hermes/r1voice"
mkdir -p "$P/res/raw" "$P/res/layout" "$P/res/values"

# 复制资源
cp "$SRC/AndroidManifest.xml" "$P/AndroidManifest.xml"
cp "$SRC/res/raw/pinyin.txt" "$P/res/raw/" 2>/dev/null || true
cp "$SRC/res/raw/r1_control.html" "$P/res/raw/" 2>/dev/null || true
cp "$SRC/res/layout/activity_main.xml" "$P/res/layout/" 2>/dev/null || true
cp "$SRC/res/values/strings.xml" "$P/res/values/" 2>/dev/null || true

# 1. AAPT 打包资源
echo "=== Resources ==="
"$BT/aapt" package -f -S "$P/res" -M "$P/AndroidManifest.xml" -I "$AJ" -F "$B/app.apk" 2>&1

# 2. 生成 R.java（资源 ID 硬编码）
cat > "$B/gen/com/hermes/r1voice/R.java" << 'REOF'
package com.hermes.r1voice;
public final class R {
    public static final class layout { public static final int activity_main = 0x7f020000; }
    public static final class raw { public static final int pinyin = 0x7f030000; public static final int r1_control = 0x7f030001; }
    public static final class string { public static final int app_name = 0x7f040000; }
    public static final class id { public static final int statusText = 0x7f050000; public static final int responseText = 0x7f050001; public static final int serverText = 0x7f050002; }
}
REOF

# 3. Java 编译（Java 7 target）
echo "=== Java ==="
"$JAVA8/bin/javac" -source 1.7 -target 1.7 -bootclasspath "$AJ" -classpath "$AJ" -d "$CL" \
    "$B/gen/com/hermes/r1voice/R.java" \
    "$SRC/src/com/hermes/r1voice/MainActivity.java" \
    "$SRC/src/com/hermes/r1voice/MiniHttpServer.java" \
    "$SRC/src/com/hermes/r1voice/BootReceiver.java" \
    "$SRC/src/com/hermes/r1voice/ChinesePinyin.java" \
    "$SRC/src/com/k2fsa/sherpa/onnx/FeatureConfig.java" \
    "$SRC/src/com/k2fsa/sherpa/onnx/OnlineTransducerModelConfig.java" \
    "$SRC/src/com/k2fsa/sherpa/onnx/OnlineModelConfig.java" \
    "$SRC/src/com/k2fsa/sherpa/onnx/KeywordSpotterConfig.java" \
    "$SRC/src/com/k2fsa/sherpa/onnx/KeywordSpotterResult.java" \
    "$SRC/src/com/k2fsa/sherpa/onnx/OnlineStream.java" \
    "$SRC/src/com/k2fsa/sherpa/onnx/KeywordSpotter.java" \
    2>&1

# 4. D8 转 DEX（不要加 --release，会剥离匿名内部类）
echo "=== DEX ==="
find "$CL" -name "*.class" > /tmp/cl.txt
"$JAVA11/bin/java" -cp "$BT/lib/d8.jar" com.android.tools.r8.D8 \
    --lib "$AJ" --min-api 22 --output "$B/" $(cat /tmp/cl.txt) 2>&1

# 5. 打包 APK
echo "=== Package ==="
cp "$B/app.apk" "$B/u.apk"
cd "$B" && zip -j u.apk classes.dex

# 添加 native .so
echo "=== Native libs ==="
if [ -d "$SHERPA_LIB" ]; then
    mkdir -p stg/lib/armeabi-v7a
    cp "$SHERPA_LIB/libsherpa-onnx-jni.so" stg/lib/armeabi-v7a/
    cp "$SHERPA_LIB/libonnxruntime.so" stg/lib/armeabi-v7a/
    cd stg && zip -r ../u.apk lib/ && cd "$B"
else
    echo "WARNING: sherpa-onnx libs not found at $SHERPA_LIB"
    echo "Download from: https://github.com/k2-fsa/sherpa-onnx/releases"
fi

# 6. 对齐 + 签名
echo "=== Sign ==="
"$BT/zipalign" -f 4 "$B/u.apk" "$B/a.apk" 2>&1
"$BT/apksigner" sign --ks "$SRC/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$O/HermesVoice.apk" "$B/a.apk" 2>&1

echo "=== DONE ==="
ls -la "$O/HermesVoice.apk"
echo "Install: adb push $O/HermesVoice.apk /data/local/tmp/ && adb shell \"/system/bin/pm install -r /data/local/tmp/HermesVoice.apk\""
