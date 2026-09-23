package com.epptools.sdk.exception;

import com.epptools.sdk.Response;
import com.epptools.sdk.ResultCode;

import java.util.List;
import java.util.Map;

/**
 * The server answered with an EPP error result code of 2000 or above. The code and the full parsed
 * {@link Response} are attached so the caller can branch. Catch a subclass when the response should differ:
 *
 * InsufficientFundsException is 2104 - top up, because every later billable command fails too. AuthorizationException
 * is 2201/2202 - not yours, or the wrong authInfo. ObjectExistsException is 2302 - already registered.
 * ObjectDoesNotExistException is 2303 - no such name, handle or host. ObjectStatusException is 2304/2305 - a status or
 * association is in the way. PolicyException is 2306/2308 - the registry's own rules refuse the value. SessionException
 * is 2500 to 2502 - reconnect and log in again. AuthenticationException is 2200 - the login itself failed.
 */
public class CommandException extends EppException {
    private static final long serialVersionUID = 1L;

    private final int eppCode;
    private final transient Response response;

    public CommandException(int eppCode, String message, Response response) {
        super(message);
        this.eppCode = eppCode;
        this.response = response;
    }

    /** The EPP result code (&ge; 2000). */
    public int eppCode() {
        return eppCode;
    }

    /** The full parsed response, or {@code null} when none was captured. */
    public Response response() {
        return response;
    }

    /**
     * Whether sending the very same command again could succeed. True only for failures about the moment rather
     * than the request: retrying a 2302 cannot make a name free, and a loop that treats every failure as
     * transient turns one refusal into a rate-limit ban.
     */
    public boolean isRetryable() {
        return eppCode == ResultCode.COMMAND_FAILED
                || eppCode == ResultCode.COMMAND_FAILED_SERVER_CLOSING
                || eppCode == ResultCode.AUTHENTICATION_SERVER_CLOSING
                || eppCode == ResultCode.SESSION_LIMIT_EXCEEDED_SERVER_CLOSING;
    }

    /**
     * The object the registry objected to, when it said which - the one name in a batch of five that was
     * rejected. {@code null} when the answer named nothing, which is common.
     */
    public String subject() {
        if (response == null) {
            return null;
        }
        for (Map<String, Object> ext : response.extValues()) {
            Object text = ext.get("text");
            if (text != null && !text.toString().isEmpty()) {
                return text.toString();
            }
        }
        return null;
    }

    /** Extra diagnostic text the registry attached, beyond the one-line message. */
    public List<String> reasons() {
        return response == null ? java.util.Collections.<String>emptyList() : response.errorReasons();
    }

    /**
     * Build the most specific exception for a result code. One place decides, so the mapping cannot drift
     * between the commands that use it.
     */
    public static CommandException forCode(int code, String message, Response response) {
        switch (code) {
            case ResultCode.BILLING_FAILURE:
                return new InsufficientFundsException(code, message, response);
            case ResultCode.AUTHORIZATION_ERROR:
            case ResultCode.INVALID_AUTHORIZATION:
                return new AuthorizationException(code, message, response);
            case ResultCode.OBJECT_EXISTS:
                return new ObjectExistsException(code, message, response);
            case ResultCode.OBJECT_DOES_NOT_EXIST:
                return new ObjectDoesNotExistException(code, message, response);
            case ResultCode.OBJECT_STATUS_PROHIBITS_OPERATION:
            case ResultCode.OBJECT_ASSOCIATION_PROHIBITS_OPERATION:
                return new ObjectStatusException(code, message, response);
            case ResultCode.PARAMETER_VALUE_POLICY_ERROR:
            case ResultCode.DATA_MANAGEMENT_POLICY_VIOLATION:
                return new PolicyException(code, message, response);
            case ResultCode.COMMAND_FAILED_SERVER_CLOSING:
            case ResultCode.AUTHENTICATION_SERVER_CLOSING:
            case ResultCode.SESSION_LIMIT_EXCEEDED_SERVER_CLOSING:
                return new SessionException(code, message, response);
            default:
                return new CommandException(code, message, response);
        }
    }
}
