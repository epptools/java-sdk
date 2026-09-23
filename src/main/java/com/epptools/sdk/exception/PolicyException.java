package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** The registry's own rules refuse the value (2306/2308). The command is well-formed; retrying is pointless until the request itself changes. */
public class PolicyException extends CommandException {
    private static final long serialVersionUID = 1L;
    public PolicyException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
