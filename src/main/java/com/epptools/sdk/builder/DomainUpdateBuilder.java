package com.epptools.sdk.builder;

import com.epptools.sdk.Response;
import com.epptools.sdk.command.Domain;
import com.epptools.sdk.exception.ValidationException;

import java.util.List;
import java.util.Map;

/** Changes a domain, one named step at a time. {@link #send()} calls {@link Domain#update}. */
public final class DomainUpdateBuilder extends Builder {
    private final Domain handler;

    public DomainUpdateBuilder(Domain handler, String name) {
        super(name);
        this.handler = handler;
    }

    /** Delegate to another nameserver. Accumulates. */
    public DomainUpdateBuilder addNameserver(String host) {
        return addNameservers(host);
    }

    public DomainUpdateBuilder addNameservers(String... hosts) {
        appendTo("add", "ns", hosts);
        return this;
    }

    /** Stop delegating to a nameserver. Accumulates. */
    public DomainUpdateBuilder remNameserver(String host) {
        return remNameservers(host);
    }

    public DomainUpdateBuilder remNameservers(String... hosts) {
        appendTo("rem", "ns", hosts);
        return this;
    }

    /** Attach contacts in a role. Accumulates. */
    public DomainUpdateBuilder addContact(String role, String... handles) {
        appendContacts(nested("add"), role, handles);
        return this;
    }

    /** Detach contacts from a role. Accumulates. */
    public DomainUpdateBuilder remContact(String role, String... handles) {
        appendContacts(nested("rem"), role, handles);
        return this;
    }

    /** Add a client status such as clientHold or clientTransferProhibited. Accumulates. */
    public DomainUpdateBuilder addStatus(String... statuses) {
        appendTo("add", "statuses", statuses);
        return this;
    }

    /** Remove a client status. Accumulates. */
    public DomainUpdateBuilder remStatus(String... statuses) {
        appendTo("rem", "statuses", statuses);
        return this;
    }

    /** Move the domain to another registrant. */
    public DomainUpdateBuilder changeRegistrant(String handle) {
        nested("chg").put("registrant", handle);
        return this;
    }

    /** Set a new transfer secret. */
    public DomainUpdateBuilder changeAuthInfo(String password) {
        nested("chg").put("authInfo", password);
        return this;
    }

    /**
     * Remove the transfer secret altogether. After a leak this is the one that helps: setting an empty password
     * stores the empty string, which a holder can still present, so the domain stays as movable as it was.
     */
    public DomainUpdateBuilder clearAuthInfo() {
        nested("chg").put("clearAuthInfo", Boolean.TRUE);
        return this;
    }

    /** Ask for a redemption-period restore (rgp:restore op="request"). */
    public DomainUpdateBuilder restore() {
        options.put("restore", Boolean.TRUE);
        return this;
    }

    /** A trademark or licence number. */
    public DomainUpdateBuilder license(String number) {
        options.put("license", number);
        return this;
    }

    /** The most you agree to pay for this change (RFC 8748) - a restore, typically. */
    public DomainUpdateBuilder maxFee(String amount, String currency) {
        options.put("fee", feeAgreement(amount, currency));
        return this;
    }

    public DomainUpdateBuilder maxFee(String amount) {
        return maxFee(amount, null);
    }

    /** Add a DNSSEC delegation-signer record. Accumulates. */
    public DomainUpdateBuilder addDsRecord(int keyTag, int alg, int digestType, String digest) {
        listIn(nestedIn(secDns(), "add"), "dsData").add(dsRecordMap(keyTag, alg, digestType, digest));
        return this;
    }

    /** Remove one DNSSEC delegation-signer record. Accumulates. */
    public DomainUpdateBuilder remDsRecord(int keyTag, int alg, int digestType, String digest) {
        refuseNamedRemovalAfterRemoveAll();
        listIn(nestedIn(secDns(), "rem"), "dsData").add(dsRecordMap(keyTag, alg, digestType, digest));
        return this;
    }

    /** Add a DNSSEC key record. Accumulates. */
    public DomainUpdateBuilder addKeyRecord(int flags, int protocol, int alg, String pubKey) {
        listIn(nestedIn(secDns(), "add"), "keyData").add(keyRecordMap(flags, protocol, alg, pubKey));
        return this;
    }

    /** Remove one DNSSEC key record. Accumulates. */
    public DomainUpdateBuilder remKeyRecord(int flags, int protocol, int alg, String pubKey) {
        refuseNamedRemovalAfterRemoveAll();
        listIn(nestedIn(secDns(), "rem"), "keyData").add(keyRecordMap(flags, protocol, alg, pubKey));
        return this;
    }

    /**
     * Take the domain unsigned: remove every DS and key record in one step.
     *
     * Mutually exclusive with removing specific records - the protocol has no way to express both, and a frame
     * carrying both is refused. Refused here instead, where the message can say so. Without this the frame
     * carries only the remove-everything element, the named records are dropped from it, and the command still
     * answers 1000 - so the caller is told the change they did not ask for succeeded.
     */
    public DomainUpdateBuilder removeAllDnssec() {
        if (secDns().containsKey("rem")) {
            throw new ValidationException("removeAllDnssec() cannot be combined with remDsRecord()/remKeyRecord() - "
                    + "remove everything, or name what to remove, not both");
        }
        secDns().put("remAll", Boolean.TRUE);
        return this;
    }

    private void refuseNamedRemovalAfterRemoveAll() {
        if (Boolean.TRUE.equals(secDns().get("remAll"))) {
            throw new ValidationException("remDsRecord()/remKeyRecord() cannot be combined with removeAllDnssec() - "
                    + "remove everything, or name what to remove, not both");
        }
    }

    /** How long the registry may cache a signature, in seconds. */
    public DomainUpdateBuilder maxSigLife(int seconds) {
        secDns().put("maxSigLife", seconds);
        return this;
    }

    private Map<String, Object> secDns() {
        return nested("secDNS");
    }

    private void appendTo(String block, String key, String... values) {
        List<Object> list = listIn(nested(block), key);
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                list.add(v.trim());
            }
        }
    }

    @Override
    public Response send() {
        markSent();
        return handler.update(objectId, options);
    }
}
