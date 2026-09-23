package com.epptools.sdk.command;

import com.epptools.sdk.Client;
import com.epptools.sdk.Frame;
import com.epptools.sdk.Options;
import com.epptools.sdk.Response;
import com.epptools.sdk.builder.ContactCreateBuilder;
import com.epptools.sdk.builder.ContactUpdateBuilder;
import com.epptools.sdk.exception.ValidationException;

import static com.epptools.sdk.command.Commands.C;
import static com.epptools.sdk.command.Commands.appendDisclose;
import static com.epptools.sdk.command.Commands.appendPostal;
import static com.epptools.sdk.command.Commands.asList;
import static com.epptools.sdk.command.Commands.asMap;
import static com.epptools.sdk.command.Commands.opt;
import static com.epptools.sdk.command.Commands.single;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.w3c.dom.Element;

/** Contact commands, reached as {@code client.contact()}. */
public final class Contact {
    /**
     * The reserved id that asks the registry to CHOOSE the handle instead of you naming it. Send it in place of
     * a contact id on create. It is a request, not a name - the handle the registry mints comes back in the
     * response, and that reply is the only place it appears, so store what {@link Response#objectName()} gives.
     */
    public static final String AUTO_ID = "autonic";

    private final Client client;

    public Contact(Client client) {
        this.client = client;
    }

    /**
     * Every option contact:create understands. A single address can be given flat - name, city, cc and the rest
     * at the top level - or several can be given as postalInfos, so both spellings are here.
     */
    static final Set<String> CREATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "postalInfos", "type", "name", "org", "street", "city", "sp", "pc", "cc",
            "voice", "fax", "email", "authInfo", "disclose"));

    /** Every option contact:update understands, after {@link Options#canonicalise} has run. */
    static final Set<String> UPDATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "chg", "addStatuses", "remStatuses"));

    private static final Map<String, String> UPDATE_ALIASES =
            Commands.aliases("change", "chg", "removeStatuses", "remStatuses");

    public Response check(List<String> ids) {
        // contact:checkType requires at least one <contact:id>, so an empty list builds a childless frame the
        // registry refuses - and a caller reaches it by looping over a form field nobody filled in.
        // A blank handle is refused, not dropped - see Commands.identifiers().
        List<String> wanted = ids == null || ids.isEmpty()
                ? java.util.Collections.<String>emptyList()
                : Commands.identifiers(ids, "contact:check", "contact:id");
        if (wanted.isEmpty()) {
            throw new ValidationException("contact:check needs at least one handle - RFC 5733 requires a "
                    + "<contact:id> child, so an empty list is a frame the registry refuses");
        }
        Frame frame = client.frame();
        Element check = frame.ns(frame.verb("check"), C, "contact:check");
        for (String cid : wanted) {
            frame.ns(check, C, "contact:id", cid);
        }
        return client.request(frame);
    }

    public Response info(String contactId, String authInfo) {
        Frame frame = client.frame();
        Element info = frame.ns(frame.verb("info"), C, "contact:info");
        frame.ns(info, C, "contact:id", contactId);
        if (authInfo != null) {
            Element ai = frame.ns(info, C, "contact:authInfo");
            frame.ns(ai, C, "contact:pw", authInfo);
        }
        return client.request(frame);
    }

    public Response info(String contactId) {
        return info(contactId, null);
    }

    /**
     * Build a contact step by step. The id and e-mail are required by the registry, so they are arguments here
     * rather than steps you can forget. Pass {@link #AUTO_ID} as the id to have the registry mint the handle.
     */
    public ContactCreateBuilder createBuilder(String contactId, String email) {
        return new ContactCreateBuilder(this, contactId, email);
    }

    public ContactUpdateBuilder updateBuilder(String contactId) {
        return new ContactUpdateBuilder(this, contactId);
    }

    /**
     * Create a contact and let the registry choose the handle; read it back with
     * {@link Response#objectName()}. Every call mints a fresh one, so a repeat is a second contact, never a
     * collision - useful when you have no naming scheme and would otherwise retry around 2302.
     */
    public Response createAuto(Map<String, Object> options) {
        return create(AUTO_ID, options);
    }

    @SuppressWarnings("unchecked")
    public Response create(String contactId, Map<String, Object> options) {
        Options.check(options, CREATE_KEYS, "contact:create");
        Frame frame = client.frame();
        Element c = frame.ns(frame.verb("create"), C, "contact:create");
        frame.ns(c, C, "contact:id", contactId);

        List<Object> postalInfos = asList(opt(options, "postalInfos"));
        if (postalInfos != null && !postalInfos.isEmpty()) {
            // The two spellings are ALTERNATIVES. When postalInfos is given the eight flat keys are never read, so
            // an address half in each form went out as whatever postalInfos held and the flat half was dropped in
            // silence - accepted and discarded, which is the one outcome Options.check exists to make impossible.
            List<String> stray = new java.util.ArrayList<>();
            for (String key : Commands.POSTAL_KEYS) {
                if (options.containsKey(key)) {
                    stray.add("'" + key + "'");
                }
            }
            if (!stray.isEmpty()) {
                throw new ValidationException("contact:create takes the postal fields flat OR as 'postalInfos', "
                        + "not both - " + String.join(", ", stray) + " beside 'postalInfos' would be accepted and "
                        + "never read. Move them into a postalInfos block, or drop postalInfos.");
            }
            for (Object pi : postalInfos) {
                appendPostal(frame, c, (Map<String, Object>) pi, false);
            }
        } else {
            // The flat form spreads the postal fields across the top level, beside email, voice and the rest, so
            // only the postal keys are lifted out of it - handing the whole option map to a block that then checks
            // its own keys would refuse every non-postal option in it.
            Map<String, Object> pi = new LinkedHashMap<>();
            for (String key : Commands.POSTAL_KEYS) {
                if (options.containsKey(key)) {
                    pi.put(key, options.get(key));
                }
            }
            appendPostal(frame, c, pi, false);
        }
        Object voice = opt(options, "voice");
        if (voice != null && !String.valueOf(voice).isEmpty()) {
            frame.ns(c, C, "contact:voice", String.valueOf(voice));
        }
        Object fax = opt(options, "fax");
        if (fax != null && !String.valueOf(fax).isEmpty()) {
            frame.ns(c, C, "contact:fax", String.valueOf(fax));
        }
        Object email = opt(options, "email");
        if (email == null || String.valueOf(email).isEmpty()) {
            // RFC 5733 requires a contact e-mail (emailType minLength 1). Fail fast rather than on the wire.
            throw new ValidationException("contact:create requires a non-empty 'email'");
        }
        frame.ns(c, C, "contact:email", String.valueOf(email));
        Object authInfo = opt(options, "authInfo");
        Element ai = frame.ns(c, C, "contact:authInfo");
        frame.ns(ai, C, "contact:pw", authInfo == null ? "" : String.valueOf(authInfo));
        Map<String, Object> disclose = asMap(opt(options, "disclose"));
        if (disclose != null && !disclose.isEmpty()) {
            appendDisclose(frame, c, disclose);
        }
        return client.request(frame);
    }

    @SuppressWarnings("unchecked")
    public Response update(String contactId, Map<String, Object> options) {
        // Plain words first - see Options.canonicalise().
        options = Options.canonicalise(options, UPDATE_ALIASES);
        Options.check(options, UPDATE_KEYS, "contact:update");

        List<Object> addStatuses = asList(opt(options, "addStatuses"));
        List<Object> remStatuses = asList(opt(options, "remStatuses"));
        Map<String, Object> chg = asMap(opt(options, "chg"));

        // Worked out BEFORE the frame exists, because Client.frame() stamps a clTRID and a command that turns out to
        // ask for nothing should not spend one on the way out.
        //
        // Trimmed and blank-dropped here, not only in the builder: contact:statusValueType is a closed enumeration,
        // so an empty s="" is refused with a bare 2001 - and a list of nothing but blanks must not open a childless
        // <contact:add/>, which is refused too.
        List<String> addClean = Commands.nonBlank(addStatuses);
        List<String> remClean = Commands.nonBlank(remStatuses);

        List<Object> pis = null;
        Object authInfo = null;
        Map<String, Object> disclose = null;
        if (chg != null && !chg.isEmpty()) {
            Options.check(chg, Commands.CONTACT_CHG_KEYS, "contact:update 'chg'");
            pis = asList(opt(chg, "postalInfos"));
            Map<String, Object> singlePi = asMap(opt(chg, "postalInfo"));
            if (pis == null && singlePi != null) {
                pis = java.util.Collections.<Object>singletonList(singlePi);
            }
            authInfo = opt(chg, "authInfo");
            disclose = asMap(chg.get("disclose"));
        }
        List<Object> postalInfos = pis == null ? java.util.Collections.<Object>emptyList() : pis;
        boolean disclosing = disclose != null && !disclose.isEmpty();
        // Whether a child will be emitted, rather than whether the caller passed the key. RFC 5733 says of the chg
        // block: "At least one child element MUST be present" - and an empty disclose map or an empty postalInfos
        // list is a key that produces none, so on its own it would open a <contact:chg/> the schema and the server
        // both refuse.
        boolean changing = !postalInfos.isEmpty() || (chg != null && chg.containsKey("voice"))
                || (chg != null && chg.containsKey("fax")) || (chg != null && chg.containsKey("email"))
                || authInfo != null || disclosing;

        // RFC 5733 section 3.2.5: "At least one <contact:add>, <contact:rem>, or <contact:chg> element MUST be
        // provided if the command is not being extended." contact:updateType makes all three optional, so the schema
        // cannot say this and an update that asks for nothing has always been valid XML - answered with 2003, or with
        // 1000 and no change made. This library sends no extension with a contact:update, so there is no case where
        // the empty form is legitimate.
        if (addClean.isEmpty() && remClean.isEmpty() && !changing) {
            throw new ValidationException("contact:update asks for nothing: RFC 5733 requires at least one of "
                    + "addStatuses, remStatuses or chg, so this frame describes no change and the registry has "
                    + "nothing to apply. Check whether the delta you assembled came out empty - a list of statuses "
                    + "holding only blanks filters down to nothing.");
        }

        Frame frame = client.frame();
        Element update = frame.ns(frame.verb("update"), C, "contact:update");
        frame.ns(update, C, "contact:id", contactId);
        // contact:updateType allows a SINGLE add/rem block, each holding up to seven statuses; emit the wrapper
        // once and append every status into it.
        if (!addClean.isEmpty()) {
            Element add = frame.ns(update, C, "contact:add");
            for (String s : addClean) {
                frame.ns(add, C, "contact:status", null, single("s", s));
            }
        }
        if (!remClean.isEmpty()) {
            Element rem = frame.ns(update, C, "contact:rem");
            for (String s : remClean) {
                frame.ns(rem, C, "contact:status", null, single("s", s));
            }
        }
        if (changing) {
            Element block = frame.ns(update, C, "contact:chg");
            // RFC 5733 chg order: postalInfo*, voice?, fax?, email?, authInfo?, disclose?
            for (Object pi : postalInfos) {
                appendPostal(frame, block, (Map<String, Object>) pi, true);
            }
            if (chg.containsKey("voice")) {
                frame.ns(block, C, "contact:voice", str(chg.get("voice")));
            }
            if (chg.containsKey("fax")) {
                frame.ns(block, C, "contact:fax", str(chg.get("fax")));
            }
            if (chg.containsKey("email")) {
                // Not a clearable field: RFC 5733 types it minTokenType (minLength 1), so an empty one is
                // a schema-invalid frame answered with a bare 2001 that names nothing - the same refusal
                // contact:create already makes.
                if (str(chg.get("email")).isEmpty()) {
                    throw new ValidationException("contact:update cannot clear 'email' - RFC 5733 "
                            + "requires a non-empty one");
                }
                frame.ns(block, C, "contact:email", str(chg.get("email")));
            }
            if (authInfo != null) {
                Element ai = frame.ns(block, C, "contact:authInfo");
                frame.ns(ai, C, "contact:pw", String.valueOf(authInfo));
            }
            if (disclosing) {
                appendDisclose(frame, block, disclose);
            }
        }
        return client.request(frame);
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    public Response delete(String contactId) {
        Frame frame = client.frame();
        Element d = frame.ns(frame.verb("delete"), C, "contact:delete");
        frame.ns(d, C, "contact:id", contactId);
        return client.request(frame);
    }

    public Response transfer(String op, String contactId, String authInfo) {
        Frame frame = client.frame();
        Element transfer = frame.verb("transfer");
        // The same closed set domain transfers take (RFC 5730 transferOpType), checked for the same reason.
        transfer.setAttribute("op", Commands.enumArg(op, Commands.TRANSFER_OPS, "a transfer 'op'"));
        Element c = frame.ns(transfer, C, "contact:transfer");
        frame.ns(c, C, "contact:id", contactId);
        if (authInfo != null) {
            Element ai = frame.ns(c, C, "contact:authInfo");
            frame.ns(ai, C, "contact:pw", authInfo);
        }
        return client.request(frame);
    }

    public Response transfer(String op, String contactId) {
        return transfer(op, contactId, null);
    }
}
