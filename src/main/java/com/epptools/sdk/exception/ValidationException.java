package com.epptools.sdk.exception;

/**
 * A value passed to this call cannot be used, and nothing was sent. An unknown option key, a fee amount that is
 * not a decimal, an empty contact role, an operation this registry does not implement. The fault is in one
 * call\s arguments - the next call with different arguments works.
 */
public class ValidationException extends EppException {
    private static final long serialVersionUID = 1L;
    public ValidationException(String message) { super(message); }
}
