package com.epptools.sdk.builder;

import com.epptools.sdk.Response;
import com.epptools.sdk.command.Contact;

import java.util.List;

/**
 * Creates a contact, one named step at a time. {@link #send()} calls {@link Contact#create}.
 *
 * A contact carries its address in one or both forms: {@code int} is ASCII and is accepted by every registry,
 * {@code loc} is the local script. Give at least one.
 */
public final class ContactCreateBuilder extends Builder {
    private final Contact handler;

    public ContactCreateBuilder(Contact handler, String contactId, String email) {
        super(contactId);
        this.handler = handler;
        options.put("email", email);
    }

    /** The ASCII address. Every registry accepts this form, so give it unless you have a reason not to. */
    public ContactCreateBuilder internationalAddress(String name, String city, String countryCode,
            List<String> street, String org, String stateProvince, String postalCode) {
        listIn(options, "postalInfos").add(
                postalMap("int", name, city, countryCode, street, org, stateProvince, postalCode));
        return this;
    }

    public ContactCreateBuilder internationalAddress(String name, String city, String countryCode) {
        return internationalAddress(name, city, countryCode, null, null, null, null);
    }

    /** The address in the local script, as the registrant actually wrote it. */
    public ContactCreateBuilder localizedAddress(String name, String city, String countryCode,
            List<String> street, String org, String stateProvince, String postalCode) {
        listIn(options, "postalInfos").add(
                postalMap("loc", name, city, countryCode, street, org, stateProvince, postalCode));
        return this;
    }

    public ContactCreateBuilder localizedAddress(String name, String city, String countryCode) {
        return localizedAddress(name, city, countryCode, null, null, null, null);
    }

    /** A voice number in the EPP +CC.NNNN form. */
    public ContactCreateBuilder voice(String number) {
        options.put("voice", number);
        return this;
    }

    /** A fax number in the EPP +CC.NNNN form. */
    public ContactCreateBuilder fax(String number) {
        options.put("fax", number);
        return this;
    }

    /** The contact's authorisation code. */
    public ContactCreateBuilder authInfo(String password) {
        options.put("authInfo", password);
        return this;
    }

    /**
     * Allow these fields to be published; everything not named takes the opposite. Fields are name, org, addr,
     * voice, fax and email.
     */
    public ContactCreateBuilder publish(String... fields) {
        options.put("disclose", disclosureMap(true, fields));
        return this;
    }

    /** Withhold these fields from publication; everything not named takes the opposite. */
    public ContactCreateBuilder withhold(String... fields) {
        options.put("disclose", disclosureMap(false, fields));
        return this;
    }

    @Override
    public Response send() {
        markSent();
        return handler.create(objectId, options);
    }
}
