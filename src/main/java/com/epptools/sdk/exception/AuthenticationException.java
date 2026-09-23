package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** The LOGIN failed (2200) - bad clID or password. Distinct from AuthorizationException, where the session is fine and one object is out of reach. */
public class AuthenticationException extends CommandException {
    private static final long serialVersionUID = 1L;
    public AuthenticationException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
