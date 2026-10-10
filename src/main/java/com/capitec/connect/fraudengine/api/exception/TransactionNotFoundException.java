package com.capitec.connect.fraudengine.api.exception;

import java.util.UUID;

public class TransactionNotFoundException extends RuntimeException {
    public TransactionNotFoundException(String message) {
        super(message);
    }

    public TransactionNotFoundException(UUID transactionId) {
        super("Transaction not found with id: " + transactionId);
    }
}
