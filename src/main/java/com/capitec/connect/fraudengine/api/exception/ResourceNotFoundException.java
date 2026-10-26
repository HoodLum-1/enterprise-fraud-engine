package com.capitec.connect.fraudengine.api.exception;

/**
 * The requested resource does not exist, or the caller is not entitled to know whether it does.
 *
 * <p>Both cases deliberately share this one exception and this one message. Returning a different
 * answer for "no such thing" and for "not yours" would let a caller holding a valid token for one
 * account probe which transactions and accounts exist.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException() {
        super("The requested resource was not found");
    }
}
