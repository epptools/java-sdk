package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** No such name, handle or nameserver (2303). Usually a stale identifier or a typo. */
public class ObjectDoesNotExistException extends CommandException {
    private static final long serialVersionUID = 1L;
    public ObjectDoesNotExistException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
