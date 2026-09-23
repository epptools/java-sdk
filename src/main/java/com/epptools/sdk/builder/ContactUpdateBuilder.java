package com.epptools.sdk.builder;

import com.epptools.sdk.Response;
import com.epptools.sdk.command.Contact;

import java.util.List;
import java.util.Map;

/**
 * Changes a contact, one named step at a time. {@link #send()} calls {@link Contact#update}.
 *
 * A postal block is REPLACED as a whole, not merged field by field, so each change-address step takes the
 * complete block. Read the current one with {@code contact.info()} and send it back with your change applied.
 */
public final class ContactUpdateBuilder extends Builder {
    private final Contact handler;

    public ContactUpdateBuilder(Contact handler, String contactId) {
        super(contactId);
        this.handler = handler;
    }

    /** Replace the ASCII address. The whole block is sent; see the note on this class. */
    public ContactUpdateBuilder changeInternationalAddress(String name, String city, String countryCode,
            List<String> street, String org, String stateProvince, String postalCode) {
        listIn(chg(), "postalInfos").add(
                postalMap("int", name, city, countryCode, street, org, stateProvince, postalCode));
        return this;
    }

    public ContactUpdateBuilder changeInternationalAddress(String name, String city, String countryCode) {
        return changeInternationalAddress(name, city, countryCode, null, null, null, null);
    }

    /** Replace the local-script address. The whole block is sent; see the note on this class. */
    public ContactUpdateBuilder changeLocalizedAddress(String name, String city, String countryCode,
            List<String> street, String org, String stateProvince, String postalCode) {
        listIn(chg(), "postalInfos").add(
                postalMap("loc", name, city, countryCode, street, org, stateProvince, postalCode));
        return this;
    }

    public ContactUpdateBuilder changeLocalizedAddress(String name, String city, String countryCode) {
        return changeLocalizedAddress(name, city, countryCode, null, null, null, null);
    }

    public ContactUpdateBuilder changeVoice(String number) {
        chg().put("voice", number == null ? "" : number);
        return this;
    }

    public ContactUpdateBuilder changeFax(String number) {
        chg().put("fax", number == null ? "" : number);
        return this;
    }

    public ContactUpdateBuilder changeEmail(String email) {
        chg().put("email", email);
        return this;
    }

    public ContactUpdateBuilder changeAuthInfo(String password) {
        chg().put("authInfo", password);
        return this;
    }

    /** Allow these fields to be published; everything not named takes the opposite. */
    public ContactUpdateBuilder publish(String... fields) {
        chg().put("disclose", disclosureMap(true, fields));
        return this;
    }

    /** Withhold these fields from publication; everything not named takes the opposite. */
    public ContactUpdateBuilder withhold(String... fields) {
        chg().put("disclose", disclosureMap(false, fields));
        return this;
    }

    /** Add a client status. Accumulates. */
    public ContactUpdateBuilder addStatus(String... statuses) {
        appendStatuses("addStatuses", statuses);
        return this;
    }

    /** Remove a client status. Accumulates. */
    public ContactUpdateBuilder remStatus(String... statuses) {
        appendStatuses("remStatuses", statuses);
        return this;
    }

    private void appendStatuses(String key, String... statuses) {
        List<Object> list = listIn(options, key);
        for (String s : statuses) {
            if (s != null && !s.trim().isEmpty()) {
                list.add(s.trim());
            }
        }
    }

    private Map<String, Object> chg() {
        return nested("chg");
    }

    @Override
    public Response send() {
        markSent();
        return handler.update(objectId, options);
    }
}
