package com.capitec.connect.fraudengine.infrastructure.messaging;

/**
 * Thrown when a transaction event cannot be parsed at all. A body that is not JSON will never
 * become valid on a redelivery, so this is classified as non-retryable and the record goes
 * straight to the dead letter queue instead of burning the retry backoff.
 */
public class MalformedTransactionEventException extends RuntimeException {

    public MalformedTransactionEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
