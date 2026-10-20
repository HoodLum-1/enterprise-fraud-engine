package com.capitec.connect.fraudengine.api.exception;

public class EvaluationUnavailableException extends RuntimeException {

    public EvaluationUnavailableException(Throwable cause) {
        super("Fraud evaluation is temporarily unavailable", cause);
    }
}
