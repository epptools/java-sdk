package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** The server is ending the session (2500/2501/2502). Reconnect and log in again; the command itself may be perfectly good. */
public class SessionException extends CommandException {
    private static final long serialVersionUID = 1L;
    public SessionException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
