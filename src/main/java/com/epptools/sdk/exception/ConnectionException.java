package com.epptools.sdk.exception;

/** A transport-level problem (connect/read/write/TLS/framing). */
public class ConnectionException extends EppException {
    private static final long serialVersionUID = 1L;
    public ConnectionException(String message) { super(message); }
    public ConnectionException(String message, Throwable cause) { super(message, cause); }
}
