package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** Already registered (2302). Not a fault in your request, and retrying cannot help. */
public class ObjectExistsException extends CommandException {
    private static final long serialVersionUID = 1L;
    public ObjectExistsException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
