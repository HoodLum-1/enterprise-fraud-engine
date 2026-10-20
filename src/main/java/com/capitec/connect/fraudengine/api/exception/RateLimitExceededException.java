package com.capitec.connect.fraudengine.api.exception;

public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException(Throwable cause) {
        super("Evaluation rate limit exceeded", cause);
    }
}
