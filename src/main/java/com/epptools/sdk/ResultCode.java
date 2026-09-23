package com.epptools.sdk;

import com.epptools.sdk.exception.CommandException;

/**
 * EPP result codes (RFC 5730 section 3). Branch on {@link Response#code()} or
 * {@link CommandException#eppCode()} with these constants instead of bare numbers:
 *
 * <pre>{@code
 * if (e.eppCode() == ResultCode.OBJECT_EXISTS) { ... }
 * }</pre>
 */
public final class ResultCode {
    private ResultCode() {}

    // 1xxx - success
    public static final int SUCCESS = 1000;
    public static final int SUCCESS_PENDING = 1001;            // action queued; resolved later via poll
    public static final int SUCCESS_NO_MESSAGES = 1300;        // poll: queue empty
    public static final int SUCCESS_ACK_TO_DEQUEUE = 1301;     // poll: a message is waiting
    public static final int SUCCESS_END_SESSION = 1500;        // logout

    // 2000-2099 - protocol / syntax
    public static final int UNKNOWN_COMMAND = 2000;
    public static final int COMMAND_SYNTAX_ERROR = 2001;
    public static final int COMMAND_USE_ERROR = 2002;          // e.g. already logged in
    public static final int REQUIRED_PARAMETER_MISSING = 2003;
    public static final int PARAMETER_VALUE_RANGE_ERROR = 2004;
    public static final int PARAMETER_VALUE_SYNTAX_ERROR = 2005;

    // 2100-2199 - unimplemented / usage / billing
    public static final int UNIMPLEMENTED_PROTOCOL_VERSION = 2100;
    public static final int UNIMPLEMENTED_COMMAND = 2101;
    public static final int UNIMPLEMENTED_OPTION = 2102;
    public static final int UNIMPLEMENTED_EXTENSION = 2103;
    public static final int BILLING_FAILURE = 2104;            // insufficient funds
    public static final int NOT_ELIGIBLE_FOR_RENEWAL = 2105;
    public static final int NOT_ELIGIBLE_FOR_TRANSFER = 2106;

    // 2200-2299 - security
    public static final int AUTHENTICATION_ERROR = 2200;       // bad login
    public static final int AUTHORIZATION_ERROR = 2201;
    public static final int INVALID_AUTHORIZATION = 2202;      // wrong authInfo

    // 2300-2399 - object lifecycle
    public static final int OBJECT_PENDING_TRANSFER = 2300;
    public static final int OBJECT_NOT_PENDING_TRANSFER = 2301;
    public static final int OBJECT_EXISTS = 2302;
    public static final int OBJECT_DOES_NOT_EXIST = 2303;
    public static final int OBJECT_STATUS_PROHIBITS_OPERATION = 2304;
    public static final int OBJECT_ASSOCIATION_PROHIBITS_OPERATION = 2305;
    public static final int PARAMETER_VALUE_POLICY_ERROR = 2306;
    public static final int UNIMPLEMENTED_OBJECT_SERVICE = 2307;
    public static final int DATA_MANAGEMENT_POLICY_VIOLATION = 2308;

    // 2400+ - server
    public static final int COMMAND_FAILED = 2400;
    public static final int COMMAND_FAILED_SERVER_CLOSING = 2500;
    public static final int AUTHENTICATION_SERVER_CLOSING = 2501;
    public static final int SESSION_LIMIT_EXCEEDED_SERVER_CLOSING = 2502;
}
