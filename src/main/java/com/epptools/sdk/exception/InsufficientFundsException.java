package com.epptools.sdk.exception;

import com.epptools.sdk.Response;

/** The account cannot pay for this operation (2104). Every later billable command fails the same way until the balance is topped up; stop, alert whoever funds the account, resume afterwards. */
public class InsufficientFundsException extends CommandException {
    private static final long serialVersionUID = 1L;
    public InsufficientFundsException(int eppCode, String message, Response response) { super(eppCode, message, response); }
}
