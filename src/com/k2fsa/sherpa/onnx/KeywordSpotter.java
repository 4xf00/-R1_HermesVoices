package com.k2fsa.sherpa.onnx;

import android.os.Environment;

public class KeywordSpotter {
    private static boolean loaded = false;
    private long ptr;

    public static void loadLibs() {
        if (loaded) return;
        System.loadLibrary("onnxruntime");
        System.loadLibrary("sherpa-onnx-jni");
        loaded = true;
    }

    public static String findModelDir() {
        String[] paths = {
            Environment.getExternalStorageDirectory().getAbsolutePath() + "/sherpa-onnx-kws",
            "/mnt/internal_sd/sherpa-onnx-kws",
            "/sdcard/sherpa-onnx-kws",
            "/data/local/tmp/sherpa-onnx-kws"
        };
        for (String p : paths) {
            if (new java.io.File(p + "/tokens.txt").exists()) {
                return p;
            }
        }
        return paths[0];
    }

    public KeywordSpotter(KeywordSpotterConfig config) {
        loadLibs();
        this.ptr = newFromFile(config);
    }

    private native long newFromFile(KeywordSpotterConfig config);
    private native long newFromAsset(Object assetManager, KeywordSpotterConfig config);
    private native void delete(long ptr);
    private native long createStream(long ptr, String keywords);
    private native void decode(long ptr, long streamPtr);
    private native boolean isReady(long ptr, long streamPtr);
    private native Object[] getResult(long ptr, long streamPtr);
    private native void reset(long ptr, long streamPtr);

    public OnlineStream createStream(String keywords) {
        return new OnlineStream(createStream(this.ptr, keywords));
    }

    public void decode(OnlineStream stream) {
        decode(this.ptr, stream.getPtr());
    }

    public boolean isReady(OnlineStream stream) {
        return isReady(this.ptr, stream.getPtr());
    }

    public KeywordSpotterResult getResult(OnlineStream stream) {
        Object[] result = getResult(this.ptr, stream.getPtr());
        return new KeywordSpotterResult(
            (String) result[0],
            (String[]) result[1],
            (float[]) result[2]
        );
    }

    public void reset(OnlineStream stream) {
        reset(this.ptr, stream.getPtr());
    }

    public void release() {
        if (ptr != 0) {
            delete(ptr);
            ptr = 0;
        }
    }

    protected void finalize() {
        release();
    }
}
