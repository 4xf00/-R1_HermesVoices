package com.k2fsa.sherpa.onnx;

public class KeywordSpotterConfig {
    public FeatureConfig featConfig;
    public OnlineModelConfig modelConfig;
    public int maxActivePaths;
    public String keywordsFile;
    public float keywordsScore;
    public float keywordsThreshold;
    public int numTrailingBlanks;

    public KeywordSpotterConfig(FeatureConfig featConfig, OnlineModelConfig modelConfig,
                                 int maxActivePaths, String keywordsFile,
                                 float keywordsScore, float keywordsThreshold,
                                 int numTrailingBlanks) {
        this.featConfig = featConfig;
        this.modelConfig = modelConfig;
        this.maxActivePaths = maxActivePaths;
        this.keywordsFile = keywordsFile;
        this.keywordsScore = keywordsScore;
        this.keywordsThreshold = keywordsThreshold;
        this.numTrailingBlanks = numTrailingBlanks;
    }

    public FeatureConfig getFeatConfig() { return featConfig; }
    public OnlineModelConfig getModelConfig() { return modelConfig; }
    public int getMaxActivePaths() { return maxActivePaths; }
    public String getKeywordsFile() { return keywordsFile; }
    public float getKeywordsScore() { return keywordsScore; }
    public float getKeywordsThreshold() { return keywordsThreshold; }
    public int getNumTrailingBlanks() { return numTrailingBlanks; }
}
