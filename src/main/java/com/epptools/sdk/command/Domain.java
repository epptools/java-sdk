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
import static com.epptools.sdk.command.Commands.single;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
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
        Frame frame = client.frame();
        Element check = frame.ns(frame.verb("check"), D, "domain:check");
        for (String name : names) {
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
        if (!wanted.isEmpty() || currency != null) {
            Element feeCheck = frame.ns(frame.extension(), Namespaces.FEE, "fee:check");
            if (currency != null) {
                frame.ns(feeCheck, Namespaces.FEE, "fee:currency", currency.toUpperCase());
            }
            for (String[] w : wanted) {
                Element cmd = frame.ns(feeCheck, Namespaces.FEE, "fee:command", null, single("name", w[0]));
                Map<String, Object> attrs = single("unit", "y");
                frame.ns(cmd, Namespaces.FEE, "fee:period", w[1], attrs);
            }
        }
        return client.request(frame);
    }

    public Response check(List<String> names) {
        return check(names, null, null);
    }

    /** {@code hosts} picks which hosts the answer lists: all (default), del, sub or none. */
    public Response info(String name, String authInfo, String hosts) {
        Frame frame = client.frame();
        Element info = frame.ns(frame.verb("info"), D, "domain:info");
        frame.ns(info, D, "domain:name", name, single("hosts", hosts == null ? "all" : hosts));
        if (authInfo != null) {
            Element ai = frame.ns(info, D, "domain:authInfo");
            frame.ns(ai, D, "domain:pw", authInfo);
        }
        return client.request(frame);
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
            frame.ns(create, D, "domain:period", String.valueOf(Commands.optInt(options, 1, "years")), single("unit", "y"));
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
                    frame.ns(secCreate, Namespaces.SECDNS, "secDNS:maxSigLife", String.valueOf(Commands.optInt(secDns, 0, "maxSigLife")));
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

        Frame frame = client.frame();
        Element update = frame.ns(frame.verb("update"), D, "domain:update");
        frame.ns(update, D, "domain:name", name);

        List<Map<String, Object>> specs = Arrays.asList(add, rem);
        String[] ops = {"add", "rem"};
        for (int i = 0; i < 2; i++) {
            Map<String, Object> spec = specs.get(i);
            if (spec == null || spec.isEmpty()) {
                continue;
            }
            Options.check(spec, BLOCK_KEYS, "domain:update '" + ops[i] + "'");
            Element block = frame.ns(update, D, "domain:" + ops[i]);
            List<Object> nsList = asList(spec.get("ns"));
            if (nsList != null && !nsList.isEmpty()) {
                appendNameservers(frame, block, nsList);
            }
            for (String[] pair : contactPairs(spec.get("contacts"))) {
                frame.ns(block, D, "domain:contact", pair[1], single("type", pair[0]));
            }
            List<Object> statuses = asList(spec.get("statuses"));
            if (statuses != null) {
                for (Object s : statuses) {
                    frame.ns(block, D, "domain:status", null, single("s", String.valueOf(s)));
                }
            }
        }

        if (chg != null && !chg.isEmpty()) {
            Options.check(chg, Commands.DOMAIN_CHG_KEYS, "domain:update 'chg'");
            Element block = frame.ns(update, D, "domain:chg");
            Object registrant = opt(chg, "registrant");
            if (registrant != null) {
                frame.ns(block, D, "domain:registrant", String.valueOf(registrant));
            }
            Object authInfo = opt(chg, "authInfo");
            Object clear = opt(chg, "clearAuthInfo");
            if (Commands.isTrue(clear)) {
                // <authInfo><null/> REMOVES the transfer secret rather than setting it to something. After a
                // leak that distinction matters: an empty <pw/> stores the empty string, which a holder can
                // still present, so the domain stays as movable as it was. The schema cannot express both.
                if (authInfo != null) {
                    throw new ValidationException("domain:update 'chg' cannot both set authInfo and clear it - "
                            + "drop one of authInfo / clearAuthInfo");
                }
                Element ai = frame.ns(block, D, "domain:authInfo");
                frame.ns(ai, D, "domain:null");
            } else if (authInfo != null) {
                Element ai = frame.ns(block, D, "domain:authInfo");
                frame.ns(ai, D, "domain:pw", String.valueOf(authInfo));
            }
        }

        if (Commands.isTrue(opt(options, "restore"))) {
            Element rgp = frame.ns(frame.extension(), Namespaces.RGP, "rgp:update");
            frame.ns(rgp, Namespaces.RGP, "rgp:restore", null, single("op", "request"));
        }
        Object license = opt(options, "license");
        if (license != null) {
            String uri = client.requireRegistryExtUri("domain:update with a licence");
            Element u = frame.ns(frame.extension(), uri, "registry:update");
            frame.ns(u, uri, "registry:license", String.valueOf(license));
        }

        // DNSSEC delta (RFC 5910). At least one of rem/add/maxSigLife is required: an empty map must not emit a
        // childless <secDNS:update/>, which the server rejects with 2003 for what reads as a no-op, so the
        // DNSSEC change would be lost behind a 1000.
        Map<String, Object> secDns = asMap(opt(options, "secDNS"));
        if (secDns != null && !secDns.isEmpty()) {
            secDns = Options.canonicalise(secDns, SECDNS_REM);
            Options.check(secDns, Commands.SECDNS_UPDATE_KEYS, "domain:update 'secDNS'");
            Object remAll = opt(secDns, "remAll");
            Map<String, Object> remSpec = asMap(opt(secDns, "rem"));
            Map<String, Object> addSpec = asMap(opt(secDns, "add"));
            Object msl = maxSigLife(secDns);
            if (Commands.isTrue(remAll) || remSpec != null || addSpec != null || msl != null) {
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
                    frame.ns(chgSec, Namespaces.SECDNS, "secDNS:maxSigLife", String.valueOf(Commands.optInt(secDns, 0, "maxSigLife")));
                }
            }
        }
        Object fee = opt(options, "fee");
        if (fee != null) {
            appendFeeAgreement(frame, "update", fee);
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
        frame.ns(renew, D, "domain:period", String.valueOf(years), single("unit", "y"));
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
        transfer.setAttribute("op", op);
        Element d = frame.ns(transfer, D, "domain:transfer");
        frame.ns(d, D, "domain:name", name);
        if (years != null) {
            frame.ns(d, D, "domain:period", String.valueOf(years.intValue()), single("unit", "y"));
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
