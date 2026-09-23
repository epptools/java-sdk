package com.epptools.sdk.builder;

import com.epptools.sdk.Response;
import com.epptools.sdk.command.Domain;
import com.epptools.sdk.exception.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registers a domain, one named step at a time. {@link #send()} calls {@link Domain#create}. */
public final class DomainCreateBuilder extends Builder {
    private final Domain handler;

    public DomainCreateBuilder(Domain handler, String name) {
        super(name);
        this.handler = handler;
    }

    /** Registration period in years. Omit it and the registry applies its own default. */
    public DomainCreateBuilder years(int years) {
        options.put("years", years);
        return this;
    }

    /** The registrant - the holder of the domain. Required by the registry. */
    public DomainCreateBuilder registrant(String handle) {
        options.put("registrant", handle);
        return this;
    }

    /**
     * Attach contacts in a role. Every list step accumulates: pass several at once, or call it again, or both.
     * RFC 5731 puts no limit on handles per role.
     */
    public DomainCreateBuilder contact(String role, String... handles) {
        appendContacts(options, role, handles);
        return this;
    }

    /** The people authorised to make decisions about the domain. Accumulates. */
    public DomainCreateBuilder adminContact(String... handles) {
        return contact("admin", handles);
    }

    /** The people to reach about DNS and delegation. Accumulates. */
    public DomainCreateBuilder techContact(String... handles) {
        return contact("tech", handles);
    }

    /** The people to reach about invoices for this domain. Accumulates. */
    public DomainCreateBuilder billingContact(String... handles) {
        return contact("billing", handles);
    }

    /** One nameserver to delegate to. Accumulates; suits a loop or a conditional. */
    public DomainCreateBuilder nameserver(String host) {
        return nameservers(host);
    }

    /** The nameservers to delegate to. Accumulates. */
    public DomainCreateBuilder nameservers(String... hosts) {
        append("nameservers", hosts);
        return this;
    }

    /**
     * A nameserver given with its glue addresses, inlined in the command (RFC 5731 hostAttr). Use this where
     * the registry expects the addresses with the name rather than a reference to a host object created
     * beforehand. A command cannot mix the two models, so use either this or {@link #nameserver}, not both.
     */
    public DomainCreateBuilder nameserverWithGlue(String host, String... addresses) {
        String name = host == null ? "" : host.trim();
        if (name.isEmpty()) {
            throw new ValidationException("a nameserver name must not be empty");
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", name);
        // Trimmed and blank-dropped, as every other list step here is. This was the one that was not, and glue is
        // where it shows: domain:hostAddr is minLength 3, so one empty string from a form refuses the whole
        // registration with a bare 2001 that names no element.
        List<Object> addrs = new ArrayList<>();
        for (String a : addresses) {
            if (a != null && !a.trim().isEmpty()) {
                addrs.add(a.trim());
            }
        }
        entry.put("addresses", addrs);
        listIn(options, "nameservers").add(entry);
        return this;
    }

    /**
     * The transfer secret. Anyone holding it can move the domain to another registrar, so treat it as a
     * credential: never log it, and roll it after handing it to a customer.
     */
    public DomainCreateBuilder authInfo(String password) {
        options.put("authInfo", password);
        return this;
    }

    /** A trademark or licence number, for registries that require one to register a name. */
    public DomainCreateBuilder license(String number) {
        options.put("license", number);
        return this;
    }

    /** The most you agree to pay for this registration (RFC 8748). */
    public DomainCreateBuilder maxFee(String amount, String currency) {
        options.put("fee", feeAgreement(amount, currency));
        return this;
    }

    public DomainCreateBuilder maxFee(String amount) {
        return maxFee(amount, null);
    }

    /** A DNSSEC delegation-signer record. Accumulates. */
    public DomainCreateBuilder dsRecord(int keyTag, int alg, int digestType, String digest) {
        listIn(secDns(), "dsData").add(dsRecordMap(keyTag, alg, digestType, digest));
        return this;
    }

    /**
     * A DS record carrying the DNSKEY it was computed from (RFC 5910). Registries that accept it can verify the
     * digest for you; ones that do not answer 2306 rather than ignoring it.
     */
    public DomainCreateBuilder dsRecordWithKey(int keyTag, int alg, int digestType, String digest,
            int flags, int protocol, int keyAlg, String pubKey) {
        Map<String, Object> ds = dsRecordMap(keyTag, alg, digestType, digest);
        ds.put("keyData", keyRecordMap(flags, protocol, keyAlg, pubKey));
        listIn(secDns(), "dsData").add(ds);
        return this;
    }

    /** A DNSSEC key record. Accumulates. */
    public DomainCreateBuilder keyRecord(int flags, int protocol, int alg, String pubKey) {
        listIn(secDns(), "keyData").add(keyRecordMap(flags, protocol, alg, pubKey));
        return this;
    }

    /** How long the registry may cache a signature, in seconds. */
    public DomainCreateBuilder maxSigLife(int seconds) {
        secDns().put("maxSigLife", seconds);
        return this;
    }

    private Map<String, Object> secDns() {
        return nested("secDNS");
    }

    @Override
    public Response send() {
        markSent();
        return handler.create(objectId, options);
    }
}
