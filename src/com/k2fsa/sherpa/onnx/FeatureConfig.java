package com.k2fsa.sherpa.onnx;

public class FeatureConfig {
    public int sampleRate;
    public int featureDim;

    public FeatureConfig(int sampleRate, int featureDim) {
        this.sampleRate = sampleRate;
        this.featureDim = featureDim;
    }

    public FeatureConfig(int sampleRate, int featureDim, int mask, Object marker) {
        this.sampleRate = sampleRate;
        this.featureDim = featureDim;
    }

    public int getSampleRate() { return sampleRate; }
    public int getFeatureDim() { return featureDim; }
}
