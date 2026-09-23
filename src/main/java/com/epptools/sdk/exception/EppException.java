package com.epptools.sdk.exception;

/**
 * Base class for every exception the SDK throws. Catch this to handle any failure; catch a subclass when a
 * particular failure needs a different response.
 *
 * There are four families. ConfigException means the client is set up wrong, so every call fails until it is
 * fixed. ValidationException means this one call's arguments are wrong; the next call can be fine.
 * ConnectionException is a transport problem: TLS, a timeout, framing. CommandException is the registry
 * refusing the command with a result code of 2000 or above, and its subclasses say which refusal it was.
 */
public class EppException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public EppException(String message) {
        super(message);
    }

    public EppException(String message, Throwable cause) {
        super(message, cause);
    }
}
