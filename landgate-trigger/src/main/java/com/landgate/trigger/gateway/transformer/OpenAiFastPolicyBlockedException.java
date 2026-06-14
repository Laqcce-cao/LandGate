package com.landgate.trigger.gateway.transformer;

/**
 * Raised when an OpenAI fast-policy rule blocks a request before upstream send.
 */
public class OpenAiFastPolicyBlockedException extends RuntimeException {

    private final String tier;
    private final String model;

    public OpenAiFastPolicyBlockedException(String message, String tier, String model) {
        super(message);
        this.tier = tier == null ? "" : tier;
        this.model = model == null ? "" : model;
    }

    public String tier() {
        return tier;
    }

    public String model() {
        return model;
    }
}
