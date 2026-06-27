package com.k2fsa.sherpa.onnx;

public class OnlineTransducerModelConfig {
    public String encoder;
    public String decoder;
    public String joiner;

    public OnlineTransducerModelConfig(String encoder, String decoder, String joiner) {
        this.encoder = encoder;
        this.decoder = decoder;
        this.joiner = joiner;
    }

    public String getEncoder() { return encoder; }
    public String getDecoder() { return decoder; }
    public String getJoiner() { return joiner; }
}
