package com.landgate.trigger.gateway.account;

/**
 * Storage boundary for Sub2API-compatible OpenAI consecutive 403 tracking.
 */
public interface OpenAi403CounterStore {

    long increment(Long accountId);

    void reset(Long accountId);
}
