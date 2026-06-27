package com.k2fsa.sherpa.onnx;

public class OnlineModelConfig {
    // JNI accesses fields directly by name
    public OnlineTransducerModelConfig transducer;
    public String tokens;
    public int numThreads;
    public boolean debug;
    public String provider;
    public String modelType;

    public OnlineModelConfig(OnlineTransducerModelConfig transducerModel,
                             Object paraformer, Object neMoCtc, Object zipformer2Ctc,
                             String tokens, int numThreads, boolean debug,
                             String provider, String modelType,
                             Object o1, Object o2) {
        this.transducer = transducerModel;
        this.tokens = tokens;
        this.numThreads = numThreads;
        this.debug = debug;
        this.provider = provider;
        this.modelType = modelType;
    }

    public OnlineModelConfig(OnlineTransducerModelConfig transducerModel, String tokens,
                             int numThreads, String provider, String modelType) {
        this(transducerModel, null, null, null, tokens, numThreads, false, provider, modelType, null, null);
    }

    public OnlineTransducerModelConfig getTransducer() { return transducer; }
    public String getTokens() { return tokens; }
    public int getNumThreads() { return numThreads; }
    public boolean getDebug() { return debug; }
    public String getProvider() { return provider; }
    public String getModelType() { return modelType; }
}
