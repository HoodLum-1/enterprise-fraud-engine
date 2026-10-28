package com.capitec.connect.fraudengine.api.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException() {
        super("The requested resource was not found");
    }
}
