package com.capitec.connect.fraudengine.infrastructure.messaging;

public class MalformedTransactionEventException extends RuntimeException {

    public MalformedTransactionEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
