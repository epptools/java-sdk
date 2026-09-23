package com.epptools.sdk.command;

import com.epptools.sdk.Frame;
import com.epptools.sdk.Options;
import com.epptools.sdk.Namespaces;
import com.epptools.sdk.exception.ValidationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * Shared building blocks for the object commands. Nothing here is public: it is the frame-building and the
 * argument checking that Domain, Contact and Host have in common.
 *
 * Option keys are spelled the way the RFCs spell the elements they build - authInfo, secDNS, dsData - and
 * anything else is refused. A key nobody reads is a change that never happens behind a 1000, so an
 * unrecognised option is an error here rather than silence on the wire.
 */
final class Commands {
    private Commands() {}

    static final String D = Namespaces.DOMAIN;
    static final String C = Namespaces.CONTACT;
    static final String H = Namespaces.HOST;

    /** A fee query carries at most this many fee:command entries; a longer one is refused (2306). */
    static final int MAX_FEE_COMMANDS = 20;

    private static final Pattern FEE_AMOUNT = Pattern.compile("^\\d{1,10}(\\.\\d{1,2})?$");
    private static final Pattern DATE_HEAD = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2})");

    static final Set<String> DOMAIN_CHG_KEYS = new LinkedHashSet<>(Arrays.asList(
            "registrant", "authInfo", "clearAuthInfo"));
    static final Set<String> CONTACT_CHG_KEYS = new LinkedHashSet<>(Arrays.asList(
            "postalInfo", "postalInfos", "voice", "fax", "email", "authInfo", "disclose"));

    /** secDNS on a create carries the records themselves; on an update it carries a delta. */
    static final Set<String> SECDNS_CREATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "dsData", "keyData", "maxSigLife"));
    static final Set<String> SECDNS_UPDATE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "add", "rem", "remAll", "maxSigLife"));

    /** An alias map for {@link Options#canonicalise}, written as plain word, short form, plain word, short form. */
    static Map<String, String> aliases(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * The calendar date at the front of an EPP timestamp, or the string unchanged.
     *
     * Two EPP elements carry the same expiry and are different XML types: domain:exDate is an xs:dateTime
     * ("2027-04-01T09:15:00.0Z") and domain:curExpDate is an xs:date ("2027-04-01"). Feeding what info()
     * returned straight into renew() is otherwise refused, and the reason names neither element. The date is
     * taken exactly as the server wrote it - no parsing, no timezone conversion, because reformatting through a
     * local zone lands a day either side for every domain expiring near midnight.
     */
    static String dateOnly(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = DATE_HEAD.matcher(value);
        return m.find() ? m.group(1) : value;
    }

    /** v6 when the literal parses as IPv6, else v4 - the host schema's own default. */
    static String ipVersion(String ip) {
        return ip != null && ip.indexOf(':') >= 0 ? "v6" : "v4";
    }

    static boolean isTrue(Object value) {
        // "0" / "false" / "" arrive from HTML forms and JSON and are all truthy strings in a loose language, so
        // every switch is resolved here: disclose {"flag": "0"} means WITHHOLD, the way the caller wrote it.
        if (value instanceof String) {
            String s = ((String) value).trim().toLowerCase();
            return !(s.isEmpty() || s.equals("0") || s.equals("false"));
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).doubleValue() != 0;
        }
        return value != null;
    }

    /**
     * Read an option, trying each name in turn. Several names are for the few keys that have two defensible
     * spellings, such as nameservers and nameServers; a name this library does not know is refused by
     * {@link Options#check} rather than read here.
     */
    static Object opt(Map<String, Object> spec, String... names) {
        if (spec == null) {
            return null;
        }
        for (String n : names) {
            if (spec.containsKey(n)) {
                return spec.get(n);
            }
        }
        return null;
    }

    static int optInt(Map<String, Object> spec, int dflt, String... names) {
        Object v = opt(spec, names);
        if (v == null) {
            return dflt;
        }
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    static String optString(Map<String, Object> spec, String dflt, String... names) {
        Object v = opt(spec, names);
        return v == null ? dflt : String.valueOf(v);
    }

    /**
     * Append a domain:ns block. A nameserver is either a NAME (a reference to a host object the registry already
     * holds) or a name WITH its glue inlined. RFC 5731 makes domain:ns a choice, so the two cannot be mixed in
     * one command - a frame carrying both is refused by the schema as a bare 2001 naming no field.
     */
    @SuppressWarnings("unchecked")
    static void appendNameservers(Frame frame, Element parent, List<?> nameservers) {
        boolean sawName = false;
        boolean sawGlue = false;
        for (Object host : nameservers) {
            if (host instanceof Map && ((Map<String, Object>) host).containsKey("name")) {
                sawGlue = true;
            } else {
                sawName = true;
            }
        }
        if (sawName && sawGlue) {
            throw new ValidationException("nameservers must be all names or all name-with-glue, not a mixture - "
                    + "RFC 5731 makes <domain:ns> a choice between the two models");
        }
        Element nsEl = frame.ns(parent, D, "domain:ns");
        for (Object host : nameservers) {
            if (!(host instanceof Map)) {
                frame.ns(nsEl, D, "domain:hostObj", String.valueOf(host));
                continue;
            }
            Map<String, Object> h = (Map<String, Object>) host;
            Element attr = frame.ns(nsEl, D, "domain:hostAttr");
            frame.ns(attr, D, "domain:hostName", String.valueOf(h.get("name")));
            Object addrs = h.get("addresses");
            if (addrs instanceof List) {
                for (Object ip : (List<Object>) addrs) {
                    String s = String.valueOf(ip);
                    frame.ns(attr, D, "domain:hostAddr", s, single("ip", ipVersion(s)));
                }
            }
        }
    }

    /** Flatten a contacts option into (role, handle) pairs, accepting one handle per role or several. */
    @SuppressWarnings("unchecked")
    static List<String[]> contactPairs(Object contacts) {
        List<String[]> out = new ArrayList<>();
        if (!(contacts instanceof Map)) {
            return out;
        }
        for (Map.Entry<String, Object> e : ((Map<String, Object>) contacts).entrySet()) {
            Object v = e.getValue();
            List<Object> seq = v instanceof List ? (List<Object>) v : Arrays.asList(v);
            for (Object handle : seq) {
                String h = handle == null ? "" : String.valueOf(handle).trim();
                if (!h.isEmpty()) {
                    out.add(new String[]{String.valueOf(e.getKey()), h});
                }
            }
        }
        return out;
    }

    /**
     * The RFC 8748 fee AGREEMENT: the most you consent to pay. Not a price you set - the registry charges its
     * own, and if the real price is higher the command is refused (2004) and nothing is charged.
     */
    @SuppressWarnings("unchecked")
    static void appendFeeAgreement(Frame frame, String local, Object fee) {
        Object raw = fee;
        String currency = null;
        if (fee instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) fee;
            raw = m.get("amount");
            Object c = m.get("currency");
            currency = c == null ? null : String.valueOf(c);
        }
        // A numeric 0 is a legitimate agreement ("this operation is free"), so the amount is coerced to a string.
        // The shape is checked here so '100,00' or '$100' fails readably rather than as an opaque wire refusal.
        String amount = raw == null ? "" : String.valueOf(raw).trim();
        if (!FEE_AMOUNT.matcher(amount).matches()) {
            throw new ValidationException("fee amount must be a plain decimal like '100.00' (got " + raw + ")");
        }
        Element el = frame.ns(frame.extension(), Namespaces.FEE, "fee:" + local);
        if (currency != null && !currency.isEmpty()) {
            frame.ns(el, Namespaces.FEE, "fee:currency", currency);
        }
        frame.ns(el, Namespaces.FEE, "fee:fee", amount);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> dsData(Map<String, Object> spec) {
        Object v = opt(spec, "dsData");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<Map<String, Object>>();
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> keyData(Map<String, Object> spec) {
        Object v = opt(spec, "keyData");
        return v instanceof List ? (List<Map<String, Object>>) v : new ArrayList<Map<String, Object>>();
    }

    static Object maxSigLife(Map<String, Object> spec) {
        return opt(spec, "maxSigLife");
    }

    /** Append RFC 5910 dsData / keyData records to a secDNS block (create / add / rem). */
    @SuppressWarnings("unchecked")
    static void appendSecdns(Frame frame, Element parent, Map<String, Object> spec) {
        for (Map<String, Object> ds : dsData(spec)) {
            Element dsData = frame.ns(parent, Namespaces.SECDNS, "secDNS:dsData");
            frame.ns(dsData, Namespaces.SECDNS, "secDNS:keyTag", String.valueOf(optInt(ds, 0, "keyTag")));
            frame.ns(dsData, Namespaces.SECDNS, "secDNS:alg", String.valueOf(optInt(ds, 0, "alg")));
            frame.ns(dsData, Namespaces.SECDNS, "secDNS:digestType", String.valueOf(optInt(ds, 0, "digestType")));
            frame.ns(dsData, Namespaces.SECDNS, "secDNS:digest", optString(ds, "", "digest"));
            // RFC 5910 lets a DS record carry the DNSKEY it was computed from; registries that accept it can
            // verify the digest for you, and ones that do not answer 2306 rather than ignoring it.
            Object nested = opt(ds, "keyData");
            if (nested instanceof Map) {
                appendKeyData(frame, dsData, (Map<String, Object>) nested);
            }
        }
        for (Map<String, Object> key : keyData(spec)) {
            appendKeyData(frame, parent, key);
        }
    }

    /** One secDNS:keyData block, in the element order the schema fixes. */
    static void appendKeyData(Frame frame, Element parent, Map<String, Object> key) {
        Element keyData = frame.ns(parent, Namespaces.SECDNS, "secDNS:keyData");
        frame.ns(keyData, Namespaces.SECDNS, "secDNS:flags", String.valueOf(optInt(key, 257, "flags")));
        frame.ns(keyData, Namespaces.SECDNS, "secDNS:protocol", String.valueOf(optInt(key, 3, "protocol")));
        frame.ns(keyData, Namespaces.SECDNS, "secDNS:alg", String.valueOf(optInt(key, 0, "alg")));
        frame.ns(keyData, Namespaces.SECDNS, "secDNS:pubKey", optString(key, "", "pubKey"));
    }

    /**
     * Build one contact:postalInfo block. On an update (partial) PRESENCE decides: a key left out is not sent
     * and the registry keeps what it holds, while a key present but empty clears an optional field.
     *
     * A postalInfo inside contact:chg REPLACES the stored one - it is not merged field by field. Against a
     * registry that replaces, a chg carrying only an org answers 1000 and leaves the contact with no postal
     * address at all. So the short form does not fail, it destroys, and reports success while doing it. Every
     * change therefore carries the whole block, and this refuses one that does not.
     */
    @SuppressWarnings("unchecked")
    static void appendPostal(Frame frame, Element parent, Map<String, Object> pi, boolean partial) {
        String type = optString(pi, "int", "type");
        if (type == null || type.isEmpty()) {
            type = "int";
        }
        Element block = frame.ns(parent, C, "contact:postalInfo", null, single("type", type));

        for (String required : new String[]{"name", "city", "cc"}) {
            Object v = pi.get(required);
            if (v == null || String.valueOf(v).trim().isEmpty()) {
                throw new ValidationException("postalInfo: a <contact:postalInfo> is REPLACED as a whole, not "
                        + "merged, so every change must carry the complete block - \"" + required + "\" is missing. "
                        + "(A registry that replaces answers 1000 and silently drops everything you left out.) "
                        + "Read the current block with contact.info() and send it back with your change applied.");
            }
        }

        // name is postalLineType, minLength 1: there is no way to clear a name, and an empty element is
        // schema-invalid, so it is refused here where the message can say so.
        requireNotEmpty(pi.get("name"), "name");
        frame.ns(block, C, "contact:name", String.valueOf(pi.get("name")));

        // org is optPostalLineType, which has no minLength - an empty one is legal and is how an org is removed.
        if (partial ? pi.containsKey("org") : truthyString(pi.get("org"))) {
            frame.ns(block, C, "contact:org", pi.get("org") == null ? "" : String.valueOf(pi.get("org")));
        }

        Element addr = frame.ns(block, C, "contact:addr");
        Object street = pi.get("street");
        if (street instanceof List) {
            for (Object line : (List<Object>) street) {
                frame.ns(addr, C, "contact:street", String.valueOf(line));
            }
        }
        frame.ns(addr, C, "contact:city", String.valueOf(pi.get("city")));
        if (partial ? pi.containsKey("sp") : truthyString(pi.get("sp"))) {
            frame.ns(addr, C, "contact:sp", pi.get("sp") == null ? "" : String.valueOf(pi.get("sp")));
        }
        if (partial ? pi.containsKey("pc") : truthyString(pi.get("pc"))) {
            frame.ns(addr, C, "contact:pc", pi.get("pc") == null ? "" : String.valueOf(pi.get("pc")));
        }
        frame.ns(addr, C, "contact:cc", String.valueOf(pi.get("cc")));
    }

    private static boolean truthyString(Object v) {
        return v != null && !String.valueOf(v).isEmpty();
    }

    /**
     * Refuse an empty value for an element whose schema type forbids one. Not a house rule: contact-1.0.xsd
     * gives org, street, sp and pc types with no minimum length, so those clear by being sent empty, while name
     * and city have minLength 1 and cc is exactly two characters. Getting it wrong costs a round trip and
     * returns a bare 2001 with no field named.
     */
    static void requireNotEmpty(Object value, String field) {
        if (value == null || String.valueOf(value).trim().isEmpty()) {
            throw new ValidationException("postalInfo: \"" + field + "\" cannot be empty - RFC 5733 gives it a "
                    + "schema type with a minimum length, so there is no way to clear it. Omit the key to leave "
                    + "it unchanged.");
        }
    }

    /**
     * Build a contact:disclose block. name/org/addr take a list of forms (int|loc); voice/fax/email are bare
     * flags. Every flag is read through isTrue, so the string "0" means HIDE.
     */
    @SuppressWarnings("unchecked")
    static void appendDisclose(Frame frame, Element parent, Map<String, Object> disclose) {
        String flag = isTrue(disclose.get("flag")) ? "1" : "0";
        Element disc = frame.ns(parent, C, "contact:disclose", null, single("flag", flag));
        for (String f : new String[]{"name", "org", "addr"}) {
            Object v = disclose.get(f);
            if (v == null) {
                continue;
            }
            List<Object> types = v instanceof List ? (List<Object>) v : Arrays.asList(v);
            for (Object t : types) {
                frame.ns(disc, C, "contact:" + f, null, single("type", String.valueOf(t)));
            }
        }
        for (String f : new String[]{"voice", "fax", "email"}) {
            if (isTrue(disclose.get(f))) {
                frame.ns(disc, C, "contact:" + f);
            }
        }
    }

    /** A one-entry attribute map, for the many single-attribute elements EPP frames carry. */
    static Map<String, Object> single(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, value);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> asMap(Object v) {
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    @SuppressWarnings("unchecked")
    static List<Object> asList(Object v) {
        return v instanceof List ? (List<Object>) v : null;
    }
}
