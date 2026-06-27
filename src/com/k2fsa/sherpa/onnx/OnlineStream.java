package com.k2fsa.sherpa.onnx;

public class OnlineStream {
    private long ptr;

    public OnlineStream(long ptr) {
        this.ptr = ptr;
    }

    public long getPtr() { return ptr; }

    private native void delete(long ptr);
    private native void acceptWaveform(long ptr, float[] samples, int sampleRate);
    private native void inputFinished(long ptr);

    public void acceptWaveform(float[] samples, int sampleRate) {
        acceptWaveform(this.ptr, samples, sampleRate);
    }

    public void inputFinished() {
        inputFinished(this.ptr);
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
