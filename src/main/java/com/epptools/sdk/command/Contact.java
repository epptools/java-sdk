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
        Frame frame = client.frame();
        Element check = frame.ns(frame.verb("check"), C, "contact:check");
        for (String cid : ids) {
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
            for (Object pi : postalInfos) {
                appendPostal(frame, c, (Map<String, Object>) pi, false);
            }
        } else {
            Map<String, Object> pi = new LinkedHashMap<>();
            pi.put("name", opt(options, "name"));
            pi.put("org", opt(options, "org"));
            pi.put("street", opt(options, "street"));
            pi.put("city", opt(options, "city"));
            pi.put("sp", opt(options, "sp"));
            pi.put("pc", opt(options, "pc"));
            pi.put("cc", opt(options, "cc"));
            pi.put("type", Commands.optString(options, "int", "type"));
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

        Frame frame = client.frame();
        Element update = frame.ns(frame.verb("update"), C, "contact:update");
        frame.ns(update, C, "contact:id", contactId);
        // contact:updateType allows a SINGLE add/rem block, each holding up to seven statuses; emit the wrapper
        // once and append every status into it.
        if (addStatuses != null && !addStatuses.isEmpty()) {
            Element add = frame.ns(update, C, "contact:add");
            for (Object s : addStatuses) {
                frame.ns(add, C, "contact:status", null, single("s", String.valueOf(s)));
            }
        }
        if (remStatuses != null && !remStatuses.isEmpty()) {
            Element rem = frame.ns(update, C, "contact:rem");
            for (Object s : remStatuses) {
                frame.ns(rem, C, "contact:status", null, single("s", String.valueOf(s)));
            }
        }
        if (chg != null && !chg.isEmpty()) {
            Options.check(chg, Commands.CONTACT_CHG_KEYS, "contact:update 'chg'");
            Element block = frame.ns(update, C, "contact:chg");
            // RFC 5733 chg order: postalInfo*, voice?, fax?, email?, authInfo?, disclose?
            List<Object> pis = asList(opt(chg, "postalInfos"));
            Map<String, Object> singlePi = asMap(opt(chg, "postalInfo"));
            if (pis == null && singlePi != null) {
                pis = java.util.Collections.<Object>singletonList(singlePi);
            }
            if (pis != null) {
                for (Object pi : pis) {
                    appendPostal(frame, block, (Map<String, Object>) pi, true);
                }
            }
            if (chg.containsKey("voice")) {
                frame.ns(block, C, "contact:voice", str(chg.get("voice")));
            }
            if (chg.containsKey("fax")) {
                frame.ns(block, C, "contact:fax", str(chg.get("fax")));
            }
            if (chg.containsKey("email")) {
                frame.ns(block, C, "contact:email", str(chg.get("email")));
            }
            Object authInfo = opt(chg, "authInfo");
            if (authInfo != null) {
                Element ai = frame.ns(block, C, "contact:authInfo");
                frame.ns(ai, C, "contact:pw", String.valueOf(authInfo));
            }
            Map<String, Object> disclose = asMap(chg.get("disclose"));
            if (disclose != null && !disclose.isEmpty()) {
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
        transfer.setAttribute("op", op);
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
