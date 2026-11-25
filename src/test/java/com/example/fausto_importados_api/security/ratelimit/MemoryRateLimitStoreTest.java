package com.example.fausto_importados_api.security.ratelimit;

class MemoryRateLimitStoreTest extends RateLimitStoreContract {

    private final RateLimitStore store = new MemoryRateLimitStore();

    @Override
    RateLimitStore store() {
        return store;
    }
}
