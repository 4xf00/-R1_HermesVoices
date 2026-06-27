#!/bin/bash
set -e

SDK="/Users/4xf00/android-sdk/cmdline-tools/~/android-sdk"
BT="$SDK/build-tools/34.0.0"
AJ="$SDK/platforms/android-22/android.jar"
P="/tmp/r1_voice_app_v3"
B="$P/build"; CL="$P/classes"; O="$P/output"

rm -rf "$B" "$CL" "$O"; mkdir -p "$B" "$CL" "$O" "$B/gen/com/hermes/r1voice"

echo "=== Resources ==="
"$BT/aapt" package -f -S "$P/res" -M "$P/AndroidManifest.xml" -I "$AJ" -F "$B/app.apk" 2>&1

# Create R.java
cat > "$B/gen/com/hermes/r1voice/R.java" << 'EOF'
package com.hermes.r1voice;
public final class R {
    public static final class layout { public static final int activity_main = 0x7f020000; }
    public static final class raw { public static final int pinyin = 0x7f030000; public static final int r1_control = 0x7f030001; }
    public static final class string { public static final int app_name = 0x7f040000; }
    public static final class id { public static final int statusText = 0x7f050000; public static final int responseText = 0x7f050001; public static final int serverText = 0x7f050002; }
}
EOF

echo "=== Java ==="
SRC="$P/src"
javac -source 1.7 -target 1.7 -bootclasspath "$AJ" -classpath "$AJ" -d "$CL" \
    "$B/gen/com/hermes/r1voice/R.java" \
    "$SRC/com/hermes/r1voice/MiniHttpServer.java" \
    "$SRC/com/hermes/r1voice/ChinesePinyin.java" \
    "$SRC/com/hermes/r1voice/BootReceiver.java" \
    "$SRC/com/hermes/r1voice/MainActivity.java" \
    "$SRC/com/k2fsa/sherpa/onnx/FeatureConfig.java" \
    "$SRC/com/k2fsa/sherpa/onnx/KeywordSpotter.java" \
    "$SRC/com/k2fsa/sherpa/onnx/KeywordSpotterConfig.java" \
    "$SRC/com/k2fsa/sherpa/onnx/KeywordSpotterResult.java" \
    "$SRC/com/k2fsa/sherpa/onnx/OnlineModelConfig.java" \
    "$SRC/com/k2fsa/sherpa/onnx/OnlineStream.java" \
    "$SRC/com/k2fsa/sherpa/onnx/OnlineTransducerModelConfig.java" 2>&1

echo "=== DEX ==="
find "$CL" -name "*.class" > /tmp/cl.txt
"$BT/d8" --lib "$AJ" --min-api 22 --output "$B/" $(cat /tmp/cl.txt) 2>&1

echo "=== Package ==="
cp "$B/app.apk" "$B/u.apk"

# Add native libraries
cd "$P" && zip -r "$B/u.apk" lib/ && cd /tmp/r1_voice_app_v3

# Add classes.dex
cd "$B" && zip -j u.apk classes.dex && cd /tmp/r1_voice_app_v3

"$BT/zipalign" -f 4 "$B/u.apk" "$B/a.apk" 2>&1
"$BT/apksigner" sign --ks "$P/debug.keystore" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out "$O/HermesVoice-v3.apk" "$B/a.apk" 2>&1

echo "=== DONE ==="
ls -la "$O/HermesVoice-v3.apk"
