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
import java.util.Locale;
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

    // The key sets of the NESTED maps. Options.check was applied around each of these and never inside it, so the
    // library's central promise - an unrecognised key is refused, not ignored - stopped one level above the fields
    // that decide what the registry stores. Each misspelling below built a frame the schema accepts and the
    // registry answers 1000, so nothing anywhere said the value had been dropped.

    /** Every key a postalInfo block understands; type picks the int or loc form. */
    static final Set<String> POSTAL_KEYS = new LinkedHashSet<>(Arrays.asList(
            "type", "name", "org", "street", "city", "sp", "pc", "cc"));

    /** Every field that can be disclosed or withheld, plus the flag that says which. */
    static final Set<String> DISCLOSE_KEYS = new LinkedHashSet<>(Arrays.asList(
            "flag", "name", "org", "addr", "voice", "fax", "email"));

    /** Every key a DS record understands; keyData is the RFC 5910 nested form for server validation. */
    static final Set<String> DS_KEYS = new LinkedHashSet<>(Arrays.asList(
            "keyTag", "alg", "digestType", "digest", "keyData"));

    /** Every key a DNSKEY record understands. */
    static final Set<String> KEY_KEYS = new LinkedHashSet<>(Arrays.asList(
            "flags", "protocol", "alg", "pubKey"));

    /** Every key a fee agreement understands. */
    static final Set<String> FEE_KEYS = new LinkedHashSet<>(Arrays.asList("amount", "currency"));

    /** Every key a nameserver given with its glue understands. */
    static final Set<String> GLUE_KEYS = new LinkedHashSet<>(Arrays.asList("name", "addresses"));

    // The enum-shaped arguments. Each set is closed by an XSD enumeration, so a value outside it is a frame the
    // schema refuses and the refusal is a bare 2001 or 2306 naming no attribute - while the library knew the set
    // all along, because its own documentation lists it.

    /** RFC 5730 transferOpType: the five operations a transfer command can carry. */
    static final Set<String> TRANSFER_OPS = new LinkedHashSet<>(Arrays.asList(
            "request", "approve", "reject", "cancel", "query"));

    /** RFC 5731 hostsType: which hosts a domain:info answer lists. */
    static final Set<String> INFO_HOSTS = new LinkedHashSet<>(Arrays.asList("all", "del", "sub", "none"));

    /** RFC 5731 contactAttrType: the roles a domain contact can be attached in. */
    static final Set<String> CONTACT_ROLES = new LinkedHashSet<>(Arrays.asList("admin", "billing", "tech"));

    /** RFC 5733 postalInfoEnumType: the two forms a postal block and a disclosed field come in. */
    static final Set<String> POSTAL_FORMS = new LinkedHashSet<>(Arrays.asList("int", "loc"));

    /**
     * A registration period in years, checked against the bound RFC 5731 puts on it.
     *
     * periodType is 1 to 99, so a 0 is not a small period - it is a frame the schema refuses, and the refusal
     * comes back as a bare 2001 or 2004 that names no element. Checked here so the caller is told which value
     * was wrong, by their own code, before anything is sent.
     */
    static String period(int years, String where) {
        if (years < 1 || years > 99) {
            throw new ValidationException(where + " period must be 1 to 99 years (RFC 5731), not " + years
                    + " - omit it to take the registry's own default");
        }
        return String.valueOf(years);
    }

    /**
     * Check an argument whose accepted values are a closed set, and hand it back.
     *
     * The sets above are XSD enumerations, so the wire has no room for a sixth transfer operation or a third
     * postal form. A value outside one is not a policy the registry might allow: it is a frame refused before
     * the command is looked at, answered with a code that names no attribute. Named here instead, the way a
     * disclosable field name has always been.
     */
    static String enumArg(String value, Set<String> accepted, String what) {
        if (value != null && accepted.contains(value)) {
            return value;
        }
        List<String> names = new ArrayList<>(accepted);
        throw new ValidationException(what + " must be one of " + String.join(", ", names)
                + ", not " + (value == null ? "null" : "'" + value + "'")
                + " - the schema fixes that set, so anything else is refused before the command is read");
    }

    /**
     * A DNSSEC signature lifetime in seconds, checked against the bound RFC 5910 puts on it.
     *
     * secDNS-1.1.xsd restricts maxSigLifeType to minInclusive 1, so a 0 is not a short lifetime - it is a frame
     * the schema refuses, and the refusal names no element. Anything that is not a number was coerced to 0 and
     * reached the wire as one, which is the same refusal wearing the caller's typo. Same shape as the period
     * check above, in the neighbouring element.
     */
    static String maxSigLifeSeconds(Object value, String where) {
        String text = value == null ? "" : String.valueOf(value).trim();
        int seconds;
        try {
            seconds = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            throw new ValidationException(where + " maxSigLife must be a whole number of seconds, 1 or more "
                    + "(RFC 5910), not '" + text + "' - omit it to leave the registry's own lifetime alone");
        }
        if (seconds < 1) {
            throw new ValidationException(where + " maxSigLife must be 1 second or more (RFC 5910), not " + seconds
                    + " - omit it to leave the registry's own lifetime alone");
        }
        return String.valueOf(seconds);
    }

    /**
     * The non-blank, trimmed entries of a list.
     *
     * Every builder list step already did this and every direct call emitted what it was given, so the same
     * command written the other way put `<domain:status s=""/>`, the string "null", an empty hostObj or an empty
     * host addr on the wire - each one schema-refused, each one a round trip spent on a bare 2001 that names
     * nothing. A blank entry is never a value a caller meant to send, so it is dropped here rather than turned
     * into an error: a form that submitted one empty field should not fail the whole command.
     */
    static List<String> nonBlank(List<?> values) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        for (Object v : values) {
            if (v == null) {
                continue;
            }
            String s = String.valueOf(v).trim();
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    /**
     * The list of names or handles a check asks about, with a blank one refused rather than dropped.
     *
     * The opposite treatment to {@link #nonBlank}, for the opposite reason. A blank status in a list of statuses to
     * add is noise, and dropping it performs the change the caller meant. A blank here is the QUESTION, and dropping
     * it answers a different question than the one that was asked: the caller passes fifty names, gets forty-nine
     * answers, and their own loop over the fifty finds nothing under the blank - null here, undefined in JavaScript,
     * a KeyError in Python. Only Python's is loud. In the other three "no answer" reads as "not available", so a
     * silent filter turns a frame the schema would have refused into a WRONG ANSWER further from its cause.
     *
     * Refused with the position named, because a list long enough for one entry to be blank by accident is a list
     * too long to scan by eye. Surrounding whitespace is still trimmed: a copy-paste artefact, not a missing
     * question.
     *
     * @param what    the call, named as the caller wrote it
     * @param element the element the value becomes, named as the schema names it
     */
    static List<String> identifiers(List<?> values, String what, String element) {
        List<String> out = new ArrayList<>();
        if (values == null) {
            return out;
        }
        int asked = values.size();
        int index = 0;
        for (Object v : values) {
            index++;
            String s = v == null ? "" : String.valueOf(v).trim();
            if (s.isEmpty()) {
                throw new ValidationException(what + ": entry " + index + " of " + asked + " is blank. An empty <"
                        + element + "> is a frame the registry refuses, and dropping it would return " + (asked - 1)
                        + " answers to a question about " + asked
                        + " - the missing entry then reads as unavailable rather than as never asked.");
            }
            out.add(s);
        }
        return out;
    }

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
            // Locale.ROOT: case folding here decides whether a disclosure flag means publish or withhold, and it
            // must not depend on which locale the JVM happens to be started in.
            String s = ((String) value).trim().toLowerCase(Locale.ROOT);
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
        // Blank entries are dropped BEFORE the two models are counted, and before the block is opened. A list
        // assembled from a form or a config file carries an empty string, and hostObj is labelType (minLength 1),
        // so emitting one is a schema-refused frame answered with a bare 2001 that names nothing. Dropping them
        // first also keeps an empty string from being counted as a plain name and reported as a mixture, and
        // keeps a list of nothing but blanks from opening a childless <domain:ns/>, which is refused too.
        List<Object> hosts = new ArrayList<>();
        boolean sawName = false;
        boolean sawGlue = false;
        for (Object host : nameservers) {
            if (host instanceof Map) {
                sawGlue = true;
                hosts.add(host);
            } else {
                String name = host == null ? "" : String.valueOf(host).trim();
                if (!name.isEmpty()) {
                    sawName = true;
                    hosts.add(name);
                }
            }
        }
        if (sawName && sawGlue) {
            throw new ValidationException("nameservers must be all names or all name-with-glue, not a mixture - "
                    + "RFC 5731 makes <domain:ns> a choice between the two models");
        }
        if (hosts.isEmpty()) {
            return;
        }
        Element nsEl = frame.ns(parent, D, "domain:ns");
        for (Object host : hosts) {
            if (!(host instanceof Map)) {
                frame.ns(nsEl, D, "domain:hostObj", String.valueOf(host));
                continue;
            }
            Map<String, Object> h = (Map<String, Object>) host;
            // Inside the entry, not just around it. `nmae` for `name` was accepted and built a hostAttr named
            // "null", and `address` for `addresses` discarded the glue - so the delegation went out pointing at a
            // nameserver that does not exist, behind a 1000.
            Options.check(h, GLUE_KEYS, "nameserver with glue");
            String glueName = h.get("name") == null ? "" : String.valueOf(h.get("name")).trim();
            if (glueName.isEmpty()) {
                throw new ValidationException("a nameserver given with its glue must carry a 'name' - an entry "
                        + "without one builds <domain:hostName>null</domain:hostName>, which is a label the "
                        + "schema accepts and no registry holds, so the delegation is answered 1000 and points "
                        + "at nothing");
            }
            Element attr = frame.ns(nsEl, D, "domain:hostAttr");
            frame.ns(attr, D, "domain:hostName", glueName);
            Object addrs = h.get("addresses");
            if (addrs instanceof List) {
                // hostAddr is minLength 3, so the glue gets the same blank filter the plain list does.
                for (String s : nonBlank((List<?>) addrs)) {
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
            // RFC 5731 makes the role a closed enumeration, so a role the registry has a name for - reseller is
            // the one that gets tried - is not a policy question: the frame is refused outright and the whole
            // command with it, contacts the registry would have accepted included.
            String role = enumArg(e.getKey() == null ? null : String.valueOf(e.getKey()),
                    CONTACT_ROLES, "a domain contact role");
            Object v = e.getValue();
            List<Object> seq = v instanceof List ? (List<Object>) v : Arrays.asList(v);
            for (String h : nonBlank(seq)) {
                out.add(new String[]{role, h});
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
            // The currency is half the agreement. A misspelled key dropped it silently, and the cap was then
            // compared in whatever currency the registry prices in - so the one protection a registrar has
            // against a premium price either did not bite, or bit at another figure.
            Options.check(m, FEE_KEYS, "fee agreement");
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
            // fee:currencyType is pattern [A-Z]{3}, so 'uah' is refused with a bare 2001 at the one point money
            // is involved. check()'s currency argument has always been upper-cased; the agreement's went out
            // verbatim. Locale.ROOT, because a Turkish default turns 'ils' into 'İLS' and refuses it again.
            frame.ns(el, Namespaces.FEE, "fee:currency", currency.toUpperCase(Locale.ROOT));
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

    /**
     * Append RFC 5910 dsData / keyData records to a secDNS block (create / add / rem).
     *
     * The two lists are ALTERNATIVES, not a pair. RFC 5910 sections 2 and 4: "&lt;secDNS:dsData&gt; and
     * &lt;secDNS:keyData&gt; MUST NOT be mixed, except for when &lt;secDNS:keyData&gt; is a child element of
     * &lt;secDNS:dsData&gt; for server validation" - and secDNS-1.1.xsd makes dsOrKeyType and remType an XSD
     * choice, so a block carrying both is refused outright, taking the whole DNSSEC change with it. The nested
     * form is the one legal way to send both, and it stays: see the keyData key inside a DS record.
     */
    @SuppressWarnings("unchecked")
    static void appendSecdns(Frame frame, Element parent, Map<String, Object> spec) {
        if (!dsData(spec).isEmpty() && !keyData(spec).isEmpty()) {
            throw new ValidationException("secDNS must carry dsData or keyData, not a mixture - RFC 5910 makes "
                    + "<secDNS:dsData> and <secDNS:keyData> a choice. To send a DS record together with the "
                    + "DNSKEY it was computed from, put the key INSIDE the DS record (the 'keyData' key of a "
                    + "dsData entry, or dsRecordWithKey() on a builder), which is the one nesting the RFC allows.");
        }
        for (Map<String, Object> ds : dsData(spec)) {
            // Inside the record, not just around it. `key_tag` for `keyTag` was accepted and defaulted to 0, and a
            // DS record of zeros with an empty digest is SCHEMA-VALID - so the registry answers 1000 and the
            // domain is published with a signer that matches no key. Once the parent zone carries it, every
            // validating resolver answers SERVFAIL for the name.
            Options.check(ds, DS_KEYS, "secDNS dsData record");
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
        // Inside the record, as for a DS record above: `pubkey` for `pubKey` was accepted and left the element
        // empty, and `flag` for `flags` silently published the key as a zone-signing key rather than a KSK.
        Options.check(key, KEY_KEYS, "secDNS keyData record");
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
        // The key check reaches INSIDE the block, not just the option that carries it. `postalCode` for `pc` was
        // accepted and dropped, and a postalInfo on an update REPLACES the stored one, so the registrant's
        // postcode was deleted behind a 1000.
        Options.check(pi, POSTAL_KEYS, "contact postalInfo");
        String type = optString(pi, "int", "type");
        if (type == null || type.isEmpty()) {
            type = "int";
        }
        // postalInfoEnumType is int or loc and nothing else, so a "ascii" or "local" that reads perfectly well to
        // a person is a refused frame naming no attribute.
        Element block = frame.ns(parent, C, "contact:postalInfo", null,
                single("type", enumArg(type, POSTAL_FORMS, "a postalInfo 'type'")));

        // name, city and cc are the three the block cannot be sent without: name and city are postalLineType
        // (minLength 1) and cc is exactly two characters, so none of them can be cleared, and a block missing one
        // is refused here rather than as a bare 2001. There is no second, narrower check on the name below: a
        // guard that cannot fire is worse than none, because a reader counts it as protection.
        for (String required : new String[]{"name", "city", "cc"}) {
            Object v = pi.get(required);
            if (v == null || String.valueOf(v).trim().isEmpty()) {
                throw new ValidationException("postalInfo: a <contact:postalInfo> is REPLACED as a whole, not "
                        + "merged, so every change must carry the complete block - \"" + required + "\" is missing. "
                        + "(A registry that replaces answers 1000 and silently drops everything you left out.) "
                        + "Read the current block with contact.info() and send it back with your change applied.");
            }
        }

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

    // There is no requireNotEmpty helper any more. It tested the name for a second time, directly below the loop
    // that had already refused an empty one with the identical test, and that loop was reached first on every
    // path - so the helper could not fire, and its only call site was the one that proved it. Removed rather than
    // kept for symmetry: an unreachable guard is worse than none, because a reader counts it as protection and
    // stops looking for the check that does the work.

    /**
     * Build a contact:disclose block. name/org/addr take a list of forms (int|loc); voice/fax/email are bare
     * flags. Every flag is read through isTrue, so the string "0" means HIDE.
     */
    @SuppressWarnings("unchecked")
    static void appendDisclose(Frame frame, Element parent, Map<String, Object> disclose) {
        // A misspelled field here is a privacy instruction that reads as applied and is not: the frame goes out as
        // a childless <contact:disclose flag="0"/>, the registry answers 1000, and the address or e-mail the
        // registrant asked to withhold stays published.
        Options.check(disclose, DISCLOSE_KEYS, "contact disclose");
        String flag = isTrue(disclose.get("flag")) ? "1" : "0";
        Element disc = frame.ns(parent, C, "contact:disclose", null, single("flag", flag));
        for (String f : new String[]{"name", "org", "addr"}) {
            Object v = disclose.get(f);
            if (v == null) {
                continue;
            }
            List<Object> types = v instanceof List ? (List<Object>) v : Arrays.asList(v);
            for (Object t : types) {
                // The form is int or loc, the same closed set the postal block's type comes from. Withholding a
                // field in a form the schema does not know refuses the whole command.
                String form = enumArg(t == null ? null : String.valueOf(t), POSTAL_FORMS,
                        "the form of a disclosed '" + f + "'");
                frame.ns(disc, C, "contact:" + f, null, single("type", form));
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
