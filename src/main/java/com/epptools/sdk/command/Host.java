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
        // host:checkType requires at least one <host:name>, so an empty list builds a childless frame the registry
        // refuses - and a caller reaches it by checking the nameservers a form did not carry.
        // A blank name is refused, not dropped - see Commands.identifiers().
        List<String> wanted = names == null || names.isEmpty()
                ? java.util.Collections.<String>emptyList()
                : Commands.identifiers(names, "host:check", "host:name");
        if (wanted.isEmpty()) {
            throw new ValidationException("host:check needs at least one name - RFC 5732 requires a <host:name> "
                    + "child, so an empty list is a frame the registry refuses");
        }
        Frame frame = client.frame();
        Element check = frame.ns(frame.verb("check"), H, "host:check");
        for (String name : wanted) {
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
        // host:addrStringType is minLength 3, so a blank entry is a schema-refused frame - and an address list
        // assembled from a form or split out of a config line is where a blank one comes from.
        for (String ip : Commands.nonBlank(addresses)) {
            frame.ns(create, H, "host:addr", ip, single("ip", ipVersion(ip)));
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

        // Worked out BEFORE the frame exists, because Client.frame() stamps a clTRID and a command that turns out to
        // ask for nothing should not spend one on the way out.
        //
        // Trimmed and blank-dropped here, not only in the builder: host:addrStringType is minLength 3 and
        // host:statusValueType is a closed enumeration, so a blank in either list is a bare 2001 naming nothing -
        // and it takes the good addresses in the same block with it. Filtering before the block is opened also
        // keeps a list of nothing but blanks from emitting a childless <host:add/>, which is refused too.
        String[] ops = {"add", "rem"};
        List<List<String>> addrSets = Arrays.asList(
                Commands.nonBlank(addAddresses), Commands.nonBlank(remAddresses));
        List<List<String>> statusSets = Arrays.asList(
                Commands.nonBlank(addStatuses), Commands.nonBlank(remStatuses));

        // RFC 5732 section 3.2.5: "At least one <host:add>, <host:rem>, or <host:chg> element MUST be provided if the
        // command is not being extended." host:updateType makes all three optional, so the schema cannot say this and
        // an update that asks for nothing has always been valid XML - answered with 2003, or with 1000 and no change
        // made. This library sends no extension with a host:update, and refuses the only chg the RFC defines (see
        // newName above), so there is no case where the empty form is legitimate.
        if (addrSets.get(0).isEmpty() && addrSets.get(1).isEmpty()
                && statusSets.get(0).isEmpty() && statusSets.get(1).isEmpty()) {
            throw new ValidationException("host:update asks for nothing: RFC 5732 requires at least one of "
                    + "addAddresses, addStatuses, remAddresses or remStatuses, so this frame describes no change and "
                    + "the registry has nothing to apply. Check whether the delta you assembled came out empty - a "
                    + "list holding only blanks filters down to nothing.");
        }

        Frame frame = client.frame();
        Element update = frame.ns(frame.verb("update"), H, "host:update");
        frame.ns(update, H, "host:name", name);
        for (int i = 0; i < 2; i++) {
            List<String> addrs = addrSets.get(i);
            List<String> statuses = statusSets.get(i);
            if (addrs.isEmpty() && statuses.isEmpty()) {
                continue;
            }
            Element block = frame.ns(update, H, "host:" + ops[i]);
            for (String s : addrs) {
                frame.ns(block, H, "host:addr", s, single("ip", ipVersion(s)));
            }
            for (String s : statuses) {
                frame.ns(block, H, "host:status", null, single("s", s));
            }
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
