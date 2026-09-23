package com.epptools.sdk.command;

import com.epptools.sdk.Client;
import com.epptools.sdk.Frame;
import com.epptools.sdk.Namespaces;
import com.epptools.sdk.Options;
import com.epptools.sdk.Response;
import com.epptools.sdk.builder.DomainCreateBuilder;
import com.epptools.sdk.builder.DomainUpdateBuilder;
import com.epptools.sdk.exception.ValidationException;

import static com.epptools.sdk.command.Commands.D;
import static com.epptools.sdk.command.Commands.appendFeeAgreement;
import static com.epptools.sdk.command.Commands.appendNameservers;
import static com.epptools.sdk.command.Commands.appendSecdns;
import static com.epptools.sdk.command.Commands.asList;
import static com.epptools.sdk.command.Commands.asMap;
import static com.epptools.sdk.command.Commands.contactPairs;
import static com.epptools.sdk.command.Commands.dateOnly;
import static com.epptools.sdk.command.Commands.dsData;
import static com.epptools.sdk.command.Commands.keyData;
import static com.epptools.sdk.command.Commands.maxSigLife;
import static com.epptools.sdk.command.Commands.opt;
import static com.epptools.sdk.command.Commands.period;
import static com.epptools.sdk.command.Commands.single;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/**
 * Domain commands, reached as {@code client.domain()}. Options are passed in a map keyed by the name of the EPP
 * element each one builds; an unrecognised key is refused rather than ignored, because an option nobody reads
 * never reaches the registry and the registry answers 1000 for the command it did receive.
 */
public final class Domain {
    private final Client client;

    public Domain(Client client) {
        this.client = client;
    }

    /** Every option domain:create understands. */
    static final Set<String> CREATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "years", "nameservers", "nameServers", "registrant", "contacts", "authInfo",
            "secDNS", "license", "fee"));

    /**
     * Every option domain:update understands, after {@link Options#canonicalise} has rewritten the plain words.
     * remove and change are accepted too and appear in the documentation; they are rewritten to rem and chg
     * before this list is applied, which is why they are not in it.
     */
    static final Set<String> UPDATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "add", "rem", "chg", "restore", "fee", "license", "secDNS"));

    /** Every key an add or rem block understands. */
    static final Set<String> BLOCK_KEYS = new LinkedHashSet<>(Arrays.asList("ns", "contacts", "statuses"));

    private static final Map<String, String> REM_CHG = Commands.aliases("remove", "rem", "change", "chg");
    private static final Map<String, String> SECDNS_REM = Commands.aliases("remove", "rem", "removeAll", "remAll");

    /**
     * Check availability, optionally asking for prices at the same time (RFC 8748). {@code fee} maps an
     * operation to a period, or to a list of periods - a list asks the same operation at several periods in
     * one command, so a whole price table costs one round trip. Operations are
     * create|renew|transfer|restore|update|delete, and transfer and restore are one-year operations however
     * many years you ask for. {@code currency} asks for the quote in that currency; omit it to take the
     * registry's own, since one it does not price in comes back unavailable with a reason, not converted.
     */
    public Response check(List<String> names, Map<String, Object> fee, String currency) {
        // domain:checkType requires at least one <domain:name>, so an empty list builds a childless frame the
        // schema refuses - and a caller reaches it by looping over a query string or a basket that turned out to
        // be empty. Refused here, where the message says which call had nothing to ask about.
        // A blank name is refused, not dropped - see Commands.identifiers() for why this list is the one place
        // a blank is not filtered.
        List<String> wantedNames = names == null || names.isEmpty()
                ? Collections.<String>emptyList()
                : Commands.identifiers(names, "domain:check", "domain:name");
        if (wantedNames.isEmpty()) {
            throw new ValidationException("domain:check needs at least one name - RFC 5731 requires a "
                    + "<domain:name> child, so an empty list is a frame the registry refuses");
        }
        Frame frame = client.frame();
        Element check = frame.ns(frame.verb("check"), D, "domain:check");
        for (String name : wantedNames) {
            frame.ns(check, D, "domain:name", name);
        }
        List<String[]> wanted = new java.util.ArrayList<>();
        if (fee != null) {
            for (Map.Entry<String, Object> e : fee.entrySet()) {
                List<Object> periods = asList(e.getValue());
                if (periods == null) {
                    periods = Collections.singletonList(e.getValue());
                }
                for (Object y : periods) {
                    int years = 1;
                    try {
                        years = Math.max(1, y instanceof Number ? ((Number) y).intValue()
                                : Integer.parseInt(String.valueOf(y).trim()));
                    } catch (NumberFormatException ignore) {
                        years = 1;
                    }
                    wanted.add(new String[]{e.getKey(), String.valueOf(years)});
                }
            }
        }
        if (wanted.size() > Commands.MAX_FEE_COMMANDS) {
            throw new ValidationException("a fee query carries at most " + Commands.MAX_FEE_COMMANDS
                    + " entries; this one has " + wanted.size());
        }
        if (wanted.isEmpty() && currency != null) {
            // fee:checkType requires at least one <fee:command>, so a currency on its own builds a refused frame.
            // Refused rather than quietly dropped: the caller asked what a name costs in a currency, and dropping
            // the currency would answer a different question in silence, while dropping the whole rider would
            // return an availability answer that reads like a price answer with no prices in it.
            throw new ValidationException("a fee query needs at least one operation - a currency on its own "
                    + "prices nothing. Pass an operation to a period, for example create to 1, alongside "
                    + "the currency (RFC 8748 fee:checkType requires a <fee:command>).");
        }
        if (!wanted.isEmpty()) {
            Element feeCheck = frame.ns(frame.extension(), Namespaces.FEE, "fee:check");
            if (currency != null) {
                // Locale.ROOT: on a Turkish JVM the default folding turns 'ils' into 'İLS', which the
                // [A-Z]{3} pattern refuses just as surely as the lower-case form did.
                frame.ns(feeCheck, Namespaces.FEE, "fee:currency", currency.toUpperCase(Locale.ROOT));
            }
            for (String[] w : wanted) {
                Element cmd = frame.ns(feeCheck, Namespaces.FEE, "fee:command", null, single("name", w[0]));
                Map<String, Object> attrs = single("unit", "y");
                frame.ns(cmd, Namespaces.FEE, "fee:period", w[1], attrs);
            }
        }
        return client.request(frame);
    }

    /**
     * Ask for prices in the registry's own currency. The other languages reach this by leaving their
     * {@code currency} argument at its default; without this overload it is the one call in the library that
     * makes a caller write an explicit null.
     */
    public Response check(List<String> names, Map<String, Object> fee) {
        return check(names, fee, null);
    }

    public Response check(List<String> names) {
        return check(names, null, null);
    }

    /** {@code hosts} picks which hosts the answer lists: all (default), del, sub or none. */
    public Response info(String name, String authInfo, String hosts) {
        Frame frame = client.frame();
        Element info = frame.ns(frame.verb("info"), D, "domain:info");
        // hostsType is all|del|sub|none. "subordinate" or "any" reads as the same request to a person and is a
        // refused frame naming no attribute, and an info is the one command a caller repeats in a loop.
        String scope = Commands.enumArg(hosts == null ? "all" : hosts, Commands.INFO_HOSTS, "info 'hosts'");
        frame.ns(info, D, "domain:name", name, single("hosts", scope));
        if (authInfo != null) {
            Element ai = frame.ns(info, D, "domain:authInfo");
            frame.ns(ai, D, "domain:pw", authInfo);
        }
        return client.request(frame);
    }

    /**
     * Read a domain you do not sponsor, with its transfer secret. The other languages reach this by leaving
     * their {@code hosts} argument at its default; in Java that is an overload, or the three-argument form
     * would make every such caller spell out "all" as well.
     */
    public Response info(String name, String authInfo) {
        return info(name, authInfo, "all");
    }

    public Response info(String name) {
        return info(name, null, "all");
    }

    /** Build a registration step by step instead of passing every option at once. Calls {@link #create}. */
    public DomainCreateBuilder createBuilder(String name) {
        return new DomainCreateBuilder(this, name);
    }

    /** Build a change step by step. Calls {@link #update}. */
    public DomainUpdateBuilder updateBuilder(String name) {
        return new DomainUpdateBuilder(this, name);
    }

    public Response create(String name, Map<String, Object> options) {
        Options.check(options, CREATE_KEYS, "domain:create");
        Frame frame = client.frame();
        Element create = frame.ns(frame.verb("create"), D, "domain:create");
        frame.ns(create, D, "domain:name", name);
        Object years = opt(options, "years");
        if (years != null) {
            frame.ns(create, D, "domain:period",
                    period(Commands.optInt(options, 1, "years"), "domain:create"), single("unit", "y"));
        }
        List<Object> nameservers = asList(Options.pick(options, "nameservers", "nameServers"));
        if (nameservers != null && !nameservers.isEmpty()) {
            appendNameservers(frame, create, nameservers);
        }
        Object registrant = opt(options, "registrant");
        if (registrant != null) {
            frame.ns(create, D, "domain:registrant", String.valueOf(registrant));
        }
        for (String[] pair : contactPairs(opt(options, "contacts"))) {
            frame.ns(create, D, "domain:contact", pair[1], single("type", pair[0]));
        }
        // authInfo is mandatory on domain:create (RFC 5731). Always emit it - with the caller's transfer secret,
        // or an empty <pw/> (pwType allows minLength 0) so the registry applies its per-zone policy.
        Object authInfo = opt(options, "authInfo");
        Element ai = frame.ns(create, D, "domain:authInfo");
        frame.ns(ai, D, "domain:pw", authInfo == null ? "" : String.valueOf(authInfo));

        Map<String, Object> secDns = asMap(opt(options, "secDNS"));
        if (secDns != null) {
            Options.check(secDns, Commands.SECDNS_CREATE_KEYS, "domain:create 'secDNS'");
        }
        // secDNS:create requires at least one dsData or keyData (RFC 5910); an empty mapping must not emit a
        // childless <secDNS:create/>, which is invalid.
        boolean hasSecdns = secDns != null && (!dsData(secDns).isEmpty() || !keyData(secDns).isEmpty());
        Object license = opt(options, "license");
        if (hasSecdns || license != null) {
            Element ext = frame.extension();
            if (hasSecdns) {
                Element secCreate = frame.ns(ext, Namespaces.SECDNS, "secDNS:create");
                Object msl = maxSigLife(secDns);
                if (msl != null) {
                    frame.ns(secCreate, Namespaces.SECDNS, "secDNS:maxSigLife",
                            Commands.maxSigLifeSeconds(msl, "domain:create 'secDNS'"));
                }
                appendSecdns(frame, secCreate, secDns);
            }
            if (license != null) {
                String uri = client.requireRegistryExtUri("domain:create with a licence");
                Element u = frame.ns(ext, uri, "registry:create");
                frame.ns(u, uri, "registry:license", String.valueOf(license));
            }
        }
        Object fee = opt(options, "fee");
        if (fee != null) {
            appendFeeAgreement(frame, "create", fee);
        }
        return client.request(frame);
    }

    public Response update(String name, Map<String, Object> options) {
        // Plain words first - see Options.canonicalise() for why both spellings exist and why the plain one wins.
        options = Options.canonicalise(options, REM_CHG);
        Options.check(options, UPDATE_KEYS, "domain:update");

        Map<String, Object> add = asMap(opt(options, "add"));
        Map<String, Object> rem = asMap(opt(options, "rem"));
        Map<String, Object> chg = asMap(opt(options, "chg"));

        // What the command will actually contain is worked out BEFORE the frame exists, because Client.frame()
        // stamps a clTRID and a command that turns out to ask for nothing should not spend one on the way out.
        List<Map<String, Object>> specs = Arrays.asList(add, rem);
        String[] ops = {"add", "rem"};
        List<List<Object>> blockNs = new java.util.ArrayList<>();
        List<List<String[]>> blockContacts = new java.util.ArrayList<>();
        List<List<String>> blockStatuses = new java.util.ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> spec = specs.get(i);
            if (spec != null && !spec.isEmpty()) {
                Options.check(spec, BLOCK_KEYS, "domain:update '" + ops[i] + "'");
            }
            List<Object> nsList = spec == null ? null : asList(spec.get("ns"));
            blockNs.add(nsList == null ? Collections.<Object>emptyList() : nsList);
            blockContacts.add(spec == null ? Collections.<String[]>emptyList() : contactPairs(spec.get("contacts")));
            // Trimmed and blank-dropped here, not only in the builder: domain:statusValueType is a closed
            // enumeration, so "" and the string "null" are both refused, and the refusal is a bare 2001 that names
            // no element - it takes the nameserver and contact changes in the same block down with it.
            blockStatuses.add(spec == null
                    ? Collections.<String>emptyList()
                    : Commands.nonBlank(asList(spec.get("statuses"))));
        }

        boolean restore = Commands.isTrue(opt(options, "restore"));

        Object registrant = null;
        Object authInfo = null;
        boolean clearingAuthInfo = false;
        if (chg != null && !chg.isEmpty()) {
            Options.check(chg, Commands.DOMAIN_CHG_KEYS, "domain:update 'chg'");
            registrant = opt(chg, "registrant");
            if (registrant != null && String.valueOf(registrant).trim().isEmpty()) {
                // RFC 5731 gives authInfo a nullable form and gives the registrant none, so a domain can be moved
                // to another holder but not left without one. A blank reaches the wire as an empty
                // <domain:registrant/>, which clIDType refuses for minLength 3 - a 2001 naming an element the
                // caller never meant to send.
                throw new ValidationException("domain:update 'chg' cannot clear the registrant: RFC 5731 defines "
                        + "no null form for it, and an empty value is a frame the registry refuses. Name the new "
                        + "holder.");
            }
            authInfo = opt(chg, "authInfo");
            // <authInfo><null/> REMOVES the transfer secret rather than setting it to something. After a leak that
            // distinction matters: an empty <pw/> stores the empty string, which a holder can still present, so the
            // domain stays as movable as it was. The schema cannot express both.
            clearingAuthInfo = Commands.isTrue(opt(chg, "clearAuthInfo"));
            if (clearingAuthInfo && authInfo != null) {
                throw new ValidationException("domain:update 'chg' cannot both set authInfo and clear it - "
                        + "drop one of authInfo / clearAuthInfo");
            }
        }
        // Whether a child will be emitted, rather than whether the caller passed the key: clearAuthInfo=false is a
        // key that produces none, and on its own it would open an empty <domain:chg/>. A restore is the one case
        // where that block is deliberate - see below.
        boolean changing = registrant != null || clearingAuthInfo || authInfo != null;

        // DNSSEC delta (RFC 5910). At least one of rem/add/maxSigLife is required: an empty map must not emit a
        // childless <secDNS:update/>, which the server rejects with 2003 for what reads as a no-op, so the
        // DNSSEC change would be lost behind a 1000.
        Map<String, Object> secDns = asMap(opt(options, "secDNS"));
        Object remAll = null;
        Map<String, Object> remSpec = null;
        Map<String, Object> addSpec = null;
        Object msl = null;
        if (secDns != null && !secDns.isEmpty()) {
            secDns = Options.canonicalise(secDns, SECDNS_REM);
            Options.check(secDns, Commands.SECDNS_UPDATE_KEYS, "domain:update 'secDNS'");
            remAll = opt(secDns, "remAll");
            remSpec = asMap(opt(secDns, "rem"));
            addSpec = asMap(opt(secDns, "add"));
            msl = maxSigLife(secDns);
        }
        boolean hasSecDelta = Commands.isTrue(remAll) || remSpec != null || addSpec != null || msl != null;

        Object license = opt(options, "license");
        Object fee = opt(options, "fee");

        // RFC 5731 section 3.2.5: "At least one <domain:add>, <domain:rem>, or <domain:chg> element MUST be provided
        // if the command is not being extended. All of these elements MAY be omitted if an <update> extension is
        // present."
        //
        // domain-1.0.xsd cannot express that, because it makes all three optional - which is why an update that asks
        // for nothing has always been schema-valid and has never been caught here. A server answers it with 2003, or
        // worse with 1000, and either way the change the caller believes they made was never described. The
        // commonest way to arrive here is not writing update(name) on purpose: it is a delta assembled from
        // variables where every one turned out empty, or a status list that filtered down to nothing just above.
        boolean touchesObject = changing || restore;
        for (int i = 0; i < 2 && !touchesObject; i++) {
            touchesObject = !blockNs.get(i).isEmpty() || !blockContacts.get(i).isEmpty()
                    || !blockStatuses.get(i).isEmpty();
        }
        boolean extended = restore || hasSecDelta || license != null || fee != null;
        if (!touchesObject && !extended) {
            throw new ValidationException("domain:update asks for nothing: RFC 5731 requires at least one of add, "
                    + "rem or chg unless the command carries an extension, so this frame describes no change and "
                    + "the registry has nothing to apply. Check whether the delta you assembled came out empty - a "
                    + "list of statuses holding only blanks filters down to nothing.");
        }

        Frame frame = client.frame();
        Element update = frame.ns(frame.verb("update"), D, "domain:update");
        frame.ns(update, D, "domain:name", name);

        for (int i = 0; i < 2; i++) {
            // The block is opened only once something is known to go inside it. A spec that filters down to nothing
            // - a single blank status, a contact list that was empty - would otherwise reach the wire as
            // <domain:rem/>, and domain-1.0.xsd accepts that, because every child of addRemType is optional. The
            // server reads it as "remove nothing", answers 1000, and the caller is told the removal they asked for
            // succeeded.
            if (blockNs.get(i).isEmpty() && blockContacts.get(i).isEmpty() && blockStatuses.get(i).isEmpty()) {
                continue;
            }
            Element block = frame.ns(update, D, "domain:" + ops[i]);
            if (!blockNs.get(i).isEmpty()) {
                appendNameservers(frame, block, blockNs.get(i));
            }
            for (String[] pair : blockContacts.get(i)) {
                frame.ns(block, D, "domain:contact", pair[1], single("type", pair[0]));
            }
            for (String s : blockStatuses.get(i)) {
                frame.ns(block, D, "domain:status", null, single("s", s));
            }
        }

        // RFC 3915 section 4.2.5: "at least one empty <domain:add>, <domain:rem>, or <domain:chg> element MUST be
        // present if this extension is specified within an <update> command", and the RFC's own restore example
        // carries <domain:chg/>. Emitted here, in the position domain:updateType fixes for it - after add and rem -
        // so the restore is a well-formed update rather than a <domain:update> carrying nothing but a name, which a
        // registry may read as a no-op and answer 2003 for while the redemption window runs out. One block, not
        // two: the schema allows a single chg, and a real one already satisfies the rule.
        if (changing || restore) {
            Element block = frame.ns(update, D, "domain:chg");
            if (registrant != null) {
                frame.ns(block, D, "domain:registrant", String.valueOf(registrant).trim());
            }
            if (clearingAuthInfo) {
                Element ai = frame.ns(block, D, "domain:authInfo");
                frame.ns(ai, D, "domain:null");
            } else if (authInfo != null) {
                Element ai = frame.ns(block, D, "domain:authInfo");
                frame.ns(ai, D, "domain:pw", String.valueOf(authInfo));
            }
        }

        if (restore) {
            Element rgp = frame.ns(frame.extension(), Namespaces.RGP, "rgp:update");
            frame.ns(rgp, Namespaces.RGP, "rgp:restore", null, single("op", "request"));
        }
        if (fee != null) {
            appendFeeAgreement(frame, "update", fee);
        }
        if (license != null) {
            String uri = client.requireRegistryExtUri("domain:update with a licence");
            Element u = frame.ns(frame.extension(), uri, "registry:update");
            frame.ns(u, uri, "registry:license", String.valueOf(license));
        }

        if (hasSecDelta) {
            Element secUpdate = frame.ns(frame.extension(), Namespaces.SECDNS, "secDNS:update");
            if (Commands.isTrue(remAll)) {
                Element remEl = frame.ns(secUpdate, Namespaces.SECDNS, "secDNS:rem");
                frame.ns(remEl, Namespaces.SECDNS, "secDNS:all", "true");
            } else if (remSpec != null) {
                Element remEl = frame.ns(secUpdate, Namespaces.SECDNS, "secDNS:rem");
                appendSecdns(frame, remEl, remSpec);
            }
            if (addSpec != null) {
                Element addEl = frame.ns(secUpdate, Namespaces.SECDNS, "secDNS:add");
                appendSecdns(frame, addEl, addSpec);
            }
            if (msl != null) {
                Element chgSec = frame.ns(secUpdate, Namespaces.SECDNS, "secDNS:chg");
                frame.ns(chgSec, Namespaces.SECDNS, "secDNS:maxSigLife",
                        Commands.maxSigLifeSeconds(msl, "domain:update 'secDNS'"));
            }
        }
        return client.request(frame);
    }

    /**
     * Renew a domain. {@code curExpDate} takes either the date the registry wants ("2027-04-01") or the full
     * timestamp its exDate carries, which is what {@link Response#expiryDate()} returns - the trimming is this
     * library's job, not yours.
     */
    public Response renew(String name, String curExpDate, int years, Object fee) {
        Frame frame = client.frame();
        Element renew = frame.ns(frame.verb("renew"), D, "domain:renew");
        frame.ns(renew, D, "domain:name", name);
        frame.ns(renew, D, "domain:curExpDate", dateOnly(curExpDate));
        frame.ns(renew, D, "domain:period", period(years, "domain:renew"), single("unit", "y"));
        if (fee != null) {
            appendFeeAgreement(frame, "renew", fee);
        }
        return client.request(frame);
    }

    public Response renew(String name, String curExpDate, int years) {
        return renew(name, curExpDate, years, null);
    }

    public Response renew(String name, String curExpDate) {
        return renew(name, curExpDate, 1, null);
    }

    public Response delete(String name) {
        Frame frame = client.frame();
        Element d = frame.ns(frame.verb("delete"), D, "domain:delete");
        frame.ns(d, D, "domain:name", name);
        return client.request(frame);
    }

    /** Restore a redemption-period domain (rgp:restore op="request"). */
    public Response restore(String name, Object fee) {
        Map<String, Object> options = new java.util.LinkedHashMap<>();
        options.put("restore", Boolean.TRUE);
        if (fee != null) {
            options.put("fee", fee);
        }
        return update(name, options);
    }

    public Response restore(String name) {
        return restore(name, null);
    }

    /** {@code op} is one of request|approve|reject|cancel|query. The fee applies to a request. */
    public Response transfer(String op, String name, String authInfo, Integer years, Object fee) {
        Frame frame = client.frame();
        Element transfer = frame.verb("transfer");
        // transferOpType is the five operations and nothing else. "accept" for approve or "deny" for reject is the
        // mistake this catches, and on a transfer the cost of a refused frame is a window that keeps running.
        transfer.setAttribute("op", Commands.enumArg(op, Commands.TRANSFER_OPS, "a transfer 'op'"));
        Element d = frame.ns(transfer, D, "domain:transfer");
        frame.ns(d, D, "domain:name", name);
        // Zero years means "the term does not move", which is how a free-transfer zone states its policy - and
        // the wire expresses it by leaving the period out, because RFC 5731's periodType starts at 1. Writing
        // <domain:period>0</domain:period> instead builds a frame the schema refuses, and a registry that
        // demands 0 in its own error message accepts an absent period just the same.
        if (years != null && years.intValue() != 0) {
            frame.ns(d, D, "domain:period", period(years.intValue(), "domain:transfer"), single("unit", "y"));
        }
        if (authInfo != null) {
            Element ai = frame.ns(d, D, "domain:authInfo");
            frame.ns(ai, D, "domain:pw", authInfo);
        }
        if (fee != null) {
            appendFeeAgreement(frame, "transfer", fee);
        }
        return client.request(frame);
    }

    public Response transfer(String op, String name, String authInfo) {
        return transfer(op, name, authInfo, null, null);
    }

    public Response transfer(String op, String name) {
        return transfer(op, name, null, null, null);
    }
}
