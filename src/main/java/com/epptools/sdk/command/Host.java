package com.epptools.sdk.command;

import com.epptools.sdk.Client;
import com.epptools.sdk.Frame;
import com.epptools.sdk.Options;
import com.epptools.sdk.Response;
import com.epptools.sdk.builder.HostUpdateBuilder;
import com.epptools.sdk.exception.ValidationException;

import static com.epptools.sdk.command.Commands.H;
import static com.epptools.sdk.command.Commands.asList;
import static com.epptools.sdk.command.Commands.ipVersion;
import static com.epptools.sdk.command.Commands.opt;
import static com.epptools.sdk.command.Commands.single;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Nameserver (host) commands, reached as {@code client.host()}. */
public final class Host {
    private final Client client;

    public Host(Client client) {
        this.client = client;
    }

    /**
     * Every option host:update understands, after {@link Options#canonicalise} has run. newName is in the list
     * only so that a caller who tries to rename a host gets the explanation below rather than a bare
     * "unknown option".
     */
    static final Set<String> UPDATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "addAddresses", "addStatuses", "remAddresses", "remStatuses", "newName"));

    private static final Map<String, String> UPDATE_ALIASES =
            Commands.aliases("removeAddresses", "remAddresses", "removeStatuses", "remStatuses");

    public Response check(List<String> names) {
        Frame frame = client.frame();
        Element check = frame.ns(frame.verb("check"), H, "host:check");
        for (String name : names) {
            frame.ns(check, H, "host:name", name);
        }
        return client.request(frame);
    }

    public Response info(String name) {
        Frame frame = client.frame();
        Element info = frame.ns(frame.verb("info"), H, "host:info");
        frame.ns(info, H, "host:name", name);
        return client.request(frame);
    }

    /** Addresses are IPv4 or IPv6 literals; the version is detected from the literal. */
    public Response create(String name, List<String> addresses) {
        Frame frame = client.frame();
        Element create = frame.ns(frame.verb("create"), H, "host:create");
        frame.ns(create, H, "host:name", name);
        if (addresses != null) {
            for (String ip : addresses) {
                frame.ns(create, H, "host:addr", ip, single("ip", ipVersion(ip)));
            }
        }
        return client.request(frame);
    }

    public Response create(String name) {
        return create(name, null);
    }

    public HostUpdateBuilder updateBuilder(String name) {
        return new HostUpdateBuilder(this, name);
    }

    public Response update(String name, Map<String, Object> options) {
        // Plain words first - see Options.canonicalise().
        options = Options.canonicalise(options, UPDATE_ALIASES);
        Options.check(options, UPDATE_KEYS, "host:update");

        List<Object> addAddresses = asList(opt(options, "addAddresses"));
        List<Object> remAddresses = asList(opt(options, "remAddresses"));
        List<Object> addStatuses = asList(opt(options, "addStatuses"));
        List<Object> remStatuses = asList(opt(options, "remStatuses"));

        Frame frame = client.frame();
        Element update = frame.ns(frame.verb("update"), H, "host:update");
        frame.ns(update, H, "host:name", name);
        String[] ops = {"add", "rem"};
        List<?>[] addrSets = {addAddresses, remAddresses};
        List<?>[] statusSets = {addStatuses, remStatuses};
        for (int i = 0; i < 2; i++) {
            List<?> addrs = addrSets[i];
            List<?> statuses = statusSets[i];
            boolean hasAddrs = addrs != null && !addrs.isEmpty();
            boolean hasStatuses = statuses != null && !statuses.isEmpty();
            if (!hasAddrs && !hasStatuses) {
                continue;
            }
            Element block = frame.ns(update, H, "host:" + ops[i]);
            if (hasAddrs) {
                for (Object ip : addrs) {
                    String s = String.valueOf(ip);
                    frame.ns(block, H, "host:addr", s, single("ip", ipVersion(s)));
                }
            }
            if (hasStatuses) {
                for (Object s : statuses) {
                    frame.ns(block, H, "host:status", null, single("s", String.valueOf(s)));
                }
            }
        }
        // Renaming is not supported by this registry: it reads only host:add and host:rem, so a host:chg is
        // discarded without comment. Sending one lets an address change in the same frame succeed while the
        // rename does not, and the caller is told 1000 - or, with newName alone, the frame carries no change
        // and draws an opaque 2003. Refused here so the answer comes from your own code, where it names the
        // problem.
        Object newName = opt(options, "newName");
        if (newName != null && !String.valueOf(newName).isEmpty()) {
            throw new ValidationException("host rename is not supported by this registry (host:chg is ignored) - "
                    + "create the new host, re-point the domains with domain:update, then delete the old one");
        }
        return client.request(frame);
    }

    public Response delete(String name, boolean force) {
        Frame frame = client.frame();
        Element d = frame.ns(frame.verb("delete"), H, "host:delete");
        frame.ns(d, H, "host:name", name);
        if (force) {
            // A registry extension that detaches the host from every domain before deleting it. Not every
            // registry offers it, so the URI comes from the greeting and its absence is reported rather than
            // guessed at: a forced delete sent without the extension the server knows is an ORDINARY delete,
            // which fails on a host still in use and leaves the caller wondering why force did nothing.
            String uri = client.requireRegistryExtUri("host:delete with force");
            Element u = frame.ns(frame.extension(), uri, "registry:delete");
            frame.ns(u, uri, "registry:deleteNS", null, single("confirm", "yes"));
        }
        return client.request(frame);
    }

    public Response delete(String name) {
        return delete(name, false);
    }
}
