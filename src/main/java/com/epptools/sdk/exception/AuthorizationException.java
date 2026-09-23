package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** The object exists, but not for you (2201/2202): it belongs to another registrar, or the authInfo does not match. Never retry with the same credentials. */
public class AuthorizationException extends CommandException {
    private static final long serialVersionUID = 1L;
    public AuthorizationException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
