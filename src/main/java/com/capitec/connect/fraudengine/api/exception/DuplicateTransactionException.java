package com.capitec.connect.fraudengine.api.exception;

import java.util.UUID;

public class DuplicateTransactionException extends RuntimeException {
    public DuplicateTransactionException(String message) {
        super(message);
    }

    public DuplicateTransactionException(UUID transactionId) {
        super("Transaction already exists with id: " + transactionId);
    }
}
