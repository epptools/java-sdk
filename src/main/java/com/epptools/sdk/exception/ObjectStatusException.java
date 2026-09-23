package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** The object's current state forbids the operation (2304/2305): a clientHold, a pending transfer, a nameserver still used by a domain. Clear what blocks you and the same request works. */
public class ObjectStatusException extends CommandException {
    private static final long serialVersionUID = 1L;
    public ObjectStatusException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
