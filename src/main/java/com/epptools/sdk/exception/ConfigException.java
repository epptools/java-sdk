package com.epptools.sdk.exception;

/**
 * The client itself is misconfigured: no host, no credentials, a password this server cannot carry. Every call
 * fails the same way until the deployment changes. Distinct from {@link ValidationException} because the two
 * need opposite responses: one is answered to whoever made the request, the other alerts whoever runs the service.
 */
public class ConfigException extends EppException {
    private static final long serialVersionUID = 1L;
    public ConfigException(String message) { super(message); }
}
