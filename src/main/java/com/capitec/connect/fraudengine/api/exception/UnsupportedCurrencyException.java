package com.capitec.connect.fraudengine.api.exception;

public class UnsupportedCurrencyException extends IllegalArgumentException {

    public UnsupportedCurrencyException(String currency) {
        super("No high-amount threshold configured for currency: " + currency);
    }
}
