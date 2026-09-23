package com.epptools.sdk.builder;

import com.epptools.sdk.Response;
import com.epptools.sdk.exception.ValidationException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared behaviour of the fluent builders. A builder is a typed facade over the options the command takes; it
 * builds no XML of its own, so a builder and the equivalent direct call produce the identical frame and every
 * check that applies to one applies to the other.
 *
 * Reach for a builder when the command is assembled in pieces - across branches, in a loop, or from a form -
 * rather than written out in one place.
 */
public abstract class Builder {
    /** The options as the equivalent direct call would take them. */
    protected final Map<String, Object> options = new LinkedHashMap<>();
    protected final String objectId;
    private boolean sent;

    protected Builder(String objectId) {
        this.objectId = objectId;
    }

    /**
     * The options as the equivalent direct call would take them. Useful for a dry run, for logging what you are
     * about to do, or for handing the command to a queue. Sends nothing and does not spend the builder.
     *
     * A copy, and a deep one: the result is a value you can keep, log or queue. Handing back the live map would
     * make it change under the caller every time another step is added, so what was logged and what was sent
     * could differ.
     */
    public Map<String, Object> toOptions() {
        return deepCopy(options);
    }

    /** Send the command. A builder carries one command and can be sent only once. */
    public abstract Response send();

    protected void markSent() {
        // A builder is a command that has not happened yet, so sending it twice would be two registrations and
        // two charges - and the second is never what the caller meant.
        if (sent) {
            throw new ValidationException(getClass().getSimpleName() + " has already been sent. A builder carries "
                    + "one command; build another rather than re-sending this one.");
        }
        sent = true;
    }

    static Map<String, Object> deepCopy(Map<String, Object> source) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : source.entrySet()) {
            out.put(e.getKey(), deepCopyValue(e.getValue()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Object deepCopyValue(Object v) {
        if (v instanceof Map) {
            return deepCopy((Map<String, Object>) v);
        }
        if (v instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) v) {
                out.add(deepCopyValue(item));
            }
            return out;
        }
        return v;
    }

    /** The nested map at this key, created on first use. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> nested(String key) {
        Object v = options.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        options.put(key, m);
        return m;
    }

    /** The nested map at owner[key], created on first use. */
    @SuppressWarnings("unchecked")
    protected static Map<String, Object> nestedIn(Map<String, Object> owner, String key) {
        Object v = owner.get(key);
        if (v instanceof Map) {
            return (Map<String, Object>) v;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        owner.put(key, m);
        return m;
    }

    /** The list at owner[key], created on first use. */
    @SuppressWarnings("unchecked")
    protected static List<Object> listIn(Map<String, Object> owner, String key) {
        Object v = owner.get(key);
        if (v instanceof List) {
            return (List<Object>) v;
        }
        List<Object> l = new ArrayList<>();
        owner.put(key, l);
        return l;
    }

    /** Append non-empty values to the list at this key. Every list step accumulates. */
    protected void append(String key, String... values) {
        List<Object> list = listIn(options, key);
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                list.add(v.trim());
            }
        }
    }

    /** Append handles to a contact role inside the given block. */
    protected static void appendContacts(Map<String, Object> block, String role, String... handles) {
        String name = role == null ? "" : role.trim();
        if (name.isEmpty()) {
            throw new ValidationException("a contact role must not be empty (admin, tech, billing, ...)");
        }
        Map<String, Object> contacts = nestedIn(block, "contacts");
        List<Object> list = listIn(contacts, name);
        for (String h : handles) {
            if (h != null && !h.trim().isEmpty()) {
                list.add(h.trim());
            }
        }
    }

    /**
     * The RFC 8748 fee agreement: the most you consent to pay. Not a price you set - the registry charges its
     * own, and if the real price is higher the command is refused and nothing is charged.
     */
    protected static Object feeAgreement(String amount, String currency) {
        String value = amount == null ? "" : amount.trim();
        // Checked here rather than on the wire: a malformed agreement draws a bare 2001 that names no field,
        // and it arrives after the command has been attempted.
        if (!value.matches("^\\d{1,10}(\\.\\d{1,2})?$")) {
            throw new ValidationException("a fee amount must be a plain decimal like '100.00' (got " + amount + ")");
        }
        if (currency == null) {
            return value;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amount", value);
        m.put("currency", currency);
        return m;
    }

    protected static Map<String, Object> dsRecordMap(int keyTag, int alg, int digestType, String digest) {
        String value = digest == null ? "" : digest.trim();
        if (value.isEmpty()) {
            throw new ValidationException("a DS record needs a digest");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyTag", keyTag);
        m.put("alg", alg);
        m.put("digestType", digestType);
        m.put("digest", value);
        return m;
    }

    protected static Map<String, Object> keyRecordMap(int flags, int protocol, int alg, String pubKey) {
        String value = pubKey == null ? "" : pubKey.trim();
        if (value.isEmpty()) {
            throw new ValidationException("a key record needs a public key");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("flags", flags);
        m.put("protocol", protocol);
        m.put("alg", alg);
        m.put("pubKey", value);
        return m;
    }

    /**
     * A postal block. name, city and country code are required because a postalInfo is replaced as a whole, not
     * merged - see the note on {@code Commands.appendPostal}.
     */
    protected static Map<String, Object> postalMap(String kind, String name, String city, String countryCode,
            List<String> street, String org, String stateProvince, String postalCode) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("type", kind);
        block.put("name", name);
        block.put("city", city);
        block.put("cc", countryCode);
        if (street != null && !street.isEmpty()) {
            block.put("street", new ArrayList<Object>(street));
        }
        if (org != null) {
            block.put("org", org);
        }
        if (stateProvince != null) {
            block.put("sp", stateProvince);
        }
        if (postalCode != null) {
            block.put("pc", postalCode);
        }
        return block;
    }

    /**
     * A disclosure choice. name/org/addr exist once per postal form, so the choice is per form and both are
     * named: withholding only the ASCII form while the local one stays public is a privacy setting that reads
     * as applied and is not.
     */
    protected static Map<String, Object> disclosureMap(boolean publish, String... fields) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("flag", publish);
        for (String field : fields) {
            String name = field == null ? "" : field.trim();
            if (name.equals("name") || name.equals("org") || name.equals("addr")) {
                List<Object> forms = new ArrayList<>();
                forms.add("int");
                forms.add("loc");
                spec.put(name, forms);
            } else if (name.equals("voice") || name.equals("fax") || name.equals("email")) {
                spec.put(name, Boolean.TRUE);
            } else {
                throw new ValidationException("'" + field + "' is not a disclosable field. "
                        + "Use: name, org, addr, voice, fax, email.");
            }
        }
        return spec;
    }
}
