package com.epptools.sdk.builder;

import com.epptools.sdk.Response;
import com.epptools.sdk.command.Host;

/**
 * Changes a nameserver's glue addresses or statuses. {@link #send()} calls {@link Host#update}.
 *
 * <pre>
 * Response response = client.host().updateBuilder("ns1.acme.example")
 *         .addAddresses("192.0.2.10", "2001:db8::10")
 *         .remAddress("192.0.2.9")
 *         .send();
 * </pre>
 *
 * There is no rename: the registry does not implement host:chg, so a rename request is discarded and the command
 * still answers 1000. Create the new host, repoint the domains that use it with domain:update, then delete the
 * old one.
 */
public final class HostUpdateBuilder extends Builder {
    private final Host handler;

    public HostUpdateBuilder(Host handler, String name) {
        super(name);
        this.handler = handler;
    }

    /** Add one glue address. Accumulates; see {@link #addAddresses} for several at once. */
    public HostUpdateBuilder addAddress(String ip) {
        return addAddresses(ip);
    }

    /** Add glue addresses. IPv4 and IPv6 are told apart from the literal. Accumulates. */
    public HostUpdateBuilder addAddresses(String... ips) {
        append("addAddresses", ips);
        return this;
    }

    /** Remove one glue address. Accumulates. */
    public HostUpdateBuilder remAddress(String ip) {
        return remAddresses(ip);
    }

    /** Remove glue addresses. Accumulates. */
    public HostUpdateBuilder remAddresses(String... ips) {
        append("remAddresses", ips);
        return this;
    }

    /** Set a client-side status. Accumulates. */
    public HostUpdateBuilder addStatus(String... statuses) {
        append("addStatuses", statuses);
        return this;
    }

    /** Clear a client-side status. Accumulates. */
    public HostUpdateBuilder remStatus(String... statuses) {
        append("remStatuses", statuses);
        return this;
    }

    @Override
    public Response send() {
        markSent();
        return handler.update(objectId, options);
    }
}
