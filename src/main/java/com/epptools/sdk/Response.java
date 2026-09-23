package com.epptools.sdk;

import com.epptools.sdk.exception.ConnectionException;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A parsed EPP response (or greeting). It wraps the raw XML with accessors for the result code and message,
 * the transaction ids, the availability map from a check, prices and fees, contacts and postal data, DNSSEC,
 * and the raw tree for anything bespoke.
 *
 * Element lookups are by local name and ignore namespaces, so a change in the response's prefixes never breaks
 * an accessor. Text is read from an element's own direct character data, not from its descendants, so a
 * container element never returns the concatenated text of everything inside it.
 */
public final class Response {
    private final String raw;
    private final Document root;

    private Response(String raw, Document root) {
        this.raw = raw;
        this.root = root;
    }

    /**
     * Parse a response. An EPP response never carries a DOCTYPE; DTDs are refused outright, which also closes
     * the XXE and entity-expansion doors a hostile or man-in-the-middle endpoint would otherwise have.
     */
    public static Response fromXml(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setExpandEntityReferences(false);
            f.setXIncludeAware(false);
            setFeature(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
            setFeature(f, "http://xml.org/sax/features/external-general-entities", false);
            setFeature(f, "http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder b = f.newDocumentBuilder();
            Document doc = b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return new Response(xml, doc);
        } catch (org.xml.sax.SAXParseException e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.toLowerCase().contains("doctype")) {
                throw new ConnectionException("Server returned XML with a DOCTYPE - refused");
            }
            throw new ConnectionException("Server returned malformed XML: " + m);
        } catch (ConnectionException e) {
            throw e;
        } catch (Exception e) {
            throw new ConnectionException("Server returned malformed XML: " + e.getMessage());
        }
    }

    private static void setFeature(DocumentBuilderFactory f, String name, boolean value) {
        try {
            f.setFeature(name, value);
        } catch (Exception ignore) {
            // A parser that does not know the feature is fine; the others still apply.
        }
    }

    // --- element helpers (namespace-agnostic, by local name) -------------------------------------------------

    private static String local(Node n) {
        String ln = n.getLocalName();
        return ln != null ? ln : n.getNodeName();
    }

    private static String directText(Node n) {
        if (n == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.TEXT_NODE || k.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(k.getNodeValue());
            }
        }
        return sb.toString().trim();
    }

    private void walk(Node n, String localName, List<Element> out) {
        if (n.getNodeType() == Node.ELEMENT_NODE && local(n).equals(localName)) {
            out.add((Element) n);
        }
        NodeList kids = n.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            walk(kids.item(i), localName, out);
        }
    }

    private List<Element> all(String localName) {
        List<Element> out = new ArrayList<>();
        walk(root.getDocumentElement(), localName, out);
        return out;
    }

    private Element first(String localName) {
        List<Element> a = all(localName);
        return a.isEmpty() ? null : a.get(0);
    }

    private static Element directChild(Element parent, String localName) {
        if (parent == null) {
            return null;
        }
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            Node k = kids.item(i);
            if (k.getNodeType() == Node.ELEMENT_NODE && local(k).equals(localName)) {
                return (Element) k;
            }
        }
        return null;
    }

    private static List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) {
            return out;
        }
        NodeList kids = parent.getChildNodes();
        for (int i = 0; i < kids.getLength(); i++) {
            if (kids.item(i).getNodeType() == Node.ELEMENT_NODE) {
                out.add((Element) kids.item(i));
            }
        }
        return out;
    }

    /** First descendant (not the node itself) with this local name, in any namespace. */
    private static Element firstIn(Element node, String localName) {
        NodeList all = node.getElementsByTagName("*");
        for (int i = 0; i < all.getLength(); i++) {
            Element e = (Element) all.item(i);
            if (local(e).equals(localName)) {
                return e;
            }
        }
        return null;
    }

    private static boolean truthy(String v) {
        return "1".equals(v) || "true".equalsIgnoreCase(v);
    }

    private static int parseInt(String v, int dflt) {
        if (v == null || v.isEmpty()) {
            return dflt;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    // --- result / trID ---------------------------------------------------------------------------------------

    /** The EPP result code (e.g. 1000, 1001, 2200), or 0 for a greeting or codeless frame. */
    public int code() {
        Element result = first("result");
        if (result == null) {
            return 0;
        }
        return parseInt(result.getAttribute("code"), 0);
    }

    public String message() {
        Element result = first("result");
        if (result == null) {
            return null;
        }
        Element msg = directChild(result, "msg");
        return msg != null ? directText(msg) : null;
    }

    /** The language of the result msg ("en", "uk", "ua" or "ru"), or null. */
    public String messageLang() {
        Element result = first("result");
        Element msg = result == null ? null : directChild(result, "msg");
        return msg != null ? attrOrNull(msg, "lang") : null;
    }

    /** A 1xxx code means success (1000 done, 1001 action pending). */
    public boolean isSuccess() {
        int c = code();
        return c >= 1000 && c < 2000;
    }

    public boolean isPending() {
        return code() == 1001;
    }

    public boolean isGreeting() {
        return first("greeting") != null;
    }

    public String clTRID() {
        Element trid = first("trID");
        Element node = trid == null ? null : directChild(trid, "clTRID");
        return node != null ? directText(node) : null;
    }

    public String svTRID() {
        Element trid = first("trID");
        Element node = trid == null ? null : directChild(trid, "svTRID");
        return node != null ? directText(node) : null;
    }

    // --- check / poll ----------------------------------------------------------------------------------------

    /** Availability map for a check response: name/id to is-available. */
    public Map<String, Boolean> availability() {
        // Only the name/id CHILD of a *:cd block. Matching any element carrying an avail attribute would also
        // catch fee:cd, which sets avail="0" on the block itself when a zone is not served - a junk entry
        // beside the real one on a check that carries a fee rider.
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (Element cd : all("cd")) {
            for (Element child : childElements(cd)) {
                if (child.hasAttribute("avail")) {
                    out.put(directText(child), truthy(child.getAttribute("avail")));
                }
            }
        }
        return out;
    }

    /** Poll only: the queued message id to pass to poll ack, or null. */
    public String messageId() {
        Element msgq = first("msgQ");
        return msgq != null ? attrOrNull(msgq, "id") : null;
    }

    /** Poll only: how many messages remain in the queue. */
    public int messageCount() {
        Element msgq = first("msgQ");
        return msgq == null ? 0 : parseInt(msgq.getAttribute("count"), 0);
    }

    private Element queueChild(String localName) {
        Element msgq = first("msgQ");
        return msgq == null ? null : directChild(msgq, localName);
    }

    /**
     * Poll only: the queued NOTICE text from msgQ/msg. Not the same as {@link #message()}, which returns the
     * command-result banner identical on every poll reply; reading a notice with message() hands you that
     * constant string while the real content is discarded and the ack dequeues it for good.
     */
    public String queueMessage() {
        Element el = queueChild("msg");
        return el != null ? directText(el) : null;
    }

    /** Poll only: the language of the queued notice ('uk' | 'ru' | 'en'), or null. */
    public String queueMessageLang() {
        Element el = queueChild("msg");
        return el != null ? attrOrNull(el, "lang") : null;
    }

    /** Poll only: when the notice was queued (msgQ/qDate), or null. */
    public String queueDate() {
        Element el = queueChild("qDate");
        return el != null ? directText(el) : null;
    }

    /**
     * Poll only: the outcome of an operation the registry processed offline (domain/contact panData). This is
     * how a deferred command reports back: you send a create, get 1001, and the answer arrives later here.
     * Keys: object, success (the only thing that says it worked), clTRID and svTRID of the ORIGINAL command,
     * and date. Null when the message carries no panData.
     */
    public Map<String, Object> pendingActionData() {
        Element pan = first("panData");
        if (pan == null) {
            return null;
        }
        Element nameEl = firstIn(pan, "name");
        if (nameEl == null) {
            nameEl = firstIn(pan, "id");
        }
        String flag = nameEl != null ? nameEl.getAttribute("paResult") : "";
        Element trid = firstIn(pan, "paTRID");
        Element dateEl = firstIn(pan, "paDate");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", nameEl != null ? directText(nameEl) : "");
        out.put("success", truthy(flag));
        out.put("clTRID", tridChild(trid, "clTRID"));
        out.put("svTRID", tridChild(trid, "svTRID"));
        out.put("date", dateEl != null ? directText(dateEl) : null);
        return out;
    }

    private static String tridChild(Element trid, String localName) {
        if (trid == null) {
            return null;
        }
        Element el = firstIn(trid, localName);
        String text = el != null ? directText(el) : "";
        return text.isEmpty() ? null : text;
    }

    /** Object status values from the s attribute (e.g. ["ok"] or ["clientHold", ...]). */
    public List<String> statuses() {
        List<String> out = new ArrayList<>();
        for (Element el : all("status")) {
            if (el.hasAttribute("s")) {
                out.add(el.getAttribute("s"));
            }
        }
        return out;
    }

    // --- balance / prices / licence --------------------------------------------------------------------------

    /** Account figures from a balance:info response, or null when this is not a balance response. */
    public Map<String, String> balance() {
        String limit = value("creditLimit");
        String avail = value("availableCredit");
        if (limit == null && avail == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("creditLimit", limit != null ? limit : "");
        out.put("balance", value("balance") != null ? value("balance") : "");
        out.put("availableCredit", avail != null ? avail : "");
        return out;
    }

    /** Renewal/restore price hints from a domain:info response (registry priceData), keyed by operation. */
    public Map<String, Map<String, String>> prices() {
        Map<String, Map<String, String>> out = new LinkedHashMap<>();
        for (Element node : all("price")) {
            String op = node.getAttribute("operation");
            if (op == null || op.isEmpty()) {
                continue;
            }
            Map<String, String> p = new LinkedHashMap<>();
            p.put("value", directText(node));
            p.put("currency", node.getAttribute("currency"));
            out.put(op, p);
        }
        return out;
    }

    /** The opaque price channel this domain is billed on, or null when the response carries no price data. */
    public String priceChannel() {
        Element node = first("priceData");
        String channel = node != null ? node.getAttribute("channel").trim() : "";
        return channel.isEmpty() ? null : channel;
    }

    /** The registrar of record at the registry when it is not you; null when the response carries none. */
    public String registrarOfRecord() {
        Element node = first("registrar");
        String handle = node != null ? directText(node) : "";
        return handle.isEmpty() ? null : handle;
    }

    /** Per-name prices from a domain:check + fee response (RFC 8748 fee:chkData), keyed by domain name. */
    public Map<String, Object> fees() {
        Element chk = null;
        for (Element el : all("chkData")) {
            if (Namespaces.FEE.equals(el.getNamespaceURI())) {
                chk = el;
                break;
            }
        }
        if (chk == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("_currency", directText(directChild(chk, "currency")));
        for (Element cd : childElements(chk)) {
            if (!local(cd).equals("cd")) {
                continue;
            }
            String name = directText(directChild(cd, "objID"));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("avail", !"0".equals(cd.getAttribute("avail")));
            String reason = directText(directChild(cd, "reason"));
            entry.put("reason", reason.isEmpty() ? null : reason);
            Map<String, Object> commands = new LinkedHashMap<>();
            List<Map<String, Object>> periods = new ArrayList<>();
            String klass = directText(directChild(cd, "class"));
            if (!klass.isEmpty()) {
                entry.put("class", klass);
            }
            for (Element cmd : childElements(cd)) {
                if (!local(cmd).equals("command")) {
                    continue;
                }
                Element feeEl = directChild(cmd, "fee");
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("years", parseInt(directText(directChild(cmd, "period")), 1));
                c.put("fee", feeEl != null ? directText(feeEl) : null);
                String cmdReason = directText(directChild(cmd, "reason"));
                if (!cmdReason.isEmpty()) {
                    c.put("reason", cmdReason);
                }
                String op = cmd.getAttribute("name");
                Map<String, Object> period = new LinkedHashMap<>();
                period.put("op", op);
                period.putAll(c);
                periods.add(period);
                // Asking one operation at several periods brings back one command per period; the map keeps the
                // FIRST (the period asked for first). Read "periods" for the rest.
                if (!commands.containsKey(op)) {
                    commands.put(op, c);
                }
            }
            entry.put("commands", commands);
            entry.put("periods", periods);
            out.put(name, entry);
        }
        return out;
    }

    /** The fee actually charged, echoed on a transform that carried a fee agreement, or null. */
    public Map<String, String> chargedFee() {
        for (String localName : new String[]{"creData", "renData", "trnData", "updData", "delData"}) {
            for (Element el : all(localName)) {
                if (Namespaces.FEE.equals(el.getNamespaceURI())) {
                    Map<String, String> out = new LinkedHashMap<>();
                    out.put("currency", directText(directChild(el, "currency")));
                    out.put("fee", directText(directChild(el, "fee")));
                    return out;
                }
            }
        }
        return null;
    }

    /** The trademark or licence number from a domain:info response (registry extension), or null. */
    public String license() {
        return value("license");
    }

    /** RGP status values from a domain:info response (e.g. ["redemptionPeriod"]). */
    public List<String> rgpStatus() {
        List<String> out = new ArrayList<>();
        for (Element el : all("rgpStatus")) {
            if (el.hasAttribute("s")) {
                out.add(el.getAttribute("s"));
            }
        }
        return out;
    }

    /** The transfer status from a transfer response or poll trnData (e.g. "pending"). */
    public String transferStatus() {
        return value("trStatus");
    }

    // --- DNSSEC ----------------------------------------------------------------------------------------------

    public List<Map<String, Object>> dsRecords() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element ds : all("dsData")) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("keyTag", parseInt(directText(directChild(ds, "keyTag")), 0));
            r.put("alg", parseInt(directText(directChild(ds, "alg")), 0));
            r.put("digestType", parseInt(directText(directChild(ds, "digestType")), 0));
            r.put("digest", directText(directChild(ds, "digest")));
            out.add(r);
        }
        return out;
    }

    public List<Map<String, Object>> keyRecords() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element inf : all("infData")) {
            for (Element kd : childElements(inf)) {
                if (!local(kd).equals("keyData")) {
                    continue;
                }
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("flags", parseInt(directText(directChild(kd, "flags")), 0));
                r.put("protocol", parseInt(directText(directChild(kd, "protocol")), 0));
                r.put("alg", parseInt(directText(directChild(kd, "alg")), 0));
                r.put("pubKey", directText(directChild(kd, "pubKey")));
                out.add(r);
            }
        }
        return out;
    }

    public boolean isSigned() {
        return !dsRecords().isEmpty() || !keyRecords().isEmpty();
    }

    // --- object fields ---------------------------------------------------------------------------------------

    /** The object this response is about: a domain name, a host name or a contact id. */
    public String objectName() {
        // From the DIRECT child of the object block. A document-wide search for <name> finds a contact's
        // postalInfo name first, so contact:info would answer with the person's full name where the caller
        // asked for the handle - and feeding that back as an id draws a 2303.
        Element data = resData();
        List<Element> obj = childElements(data);
        if (!obj.isEmpty()) {
            for (Element child : childElements(obj.get(0))) {
                String ln = local(child);
                if (("id".equals(ln) || "name".equals(ln)) && !directText(child).isEmpty()) {
                    return directText(child);
                }
            }
        }
        String v = value("name");
        return v != null ? v : value("id");
    }

    /** Expiry, as the registry sent it - kept as the server's string, never reparsed through a local timezone. */
    public String expiryDate() {
        return value("exDate");
    }

    public String createdDate() {
        return value("crDate");
    }

    public String updatedDate() {
        return value("upDate");
    }

    public String roid() {
        return value("roid");
    }

    public String registrant() {
        return value("registrant");
    }

    /** The registrar currently sponsoring the object. */
    public String sponsor() {
        return value("clID");
    }

    public String createdBy() {
        return value("crID");
    }

    /** The registrar that last changed the object, or null when it never has. Sent only to the sponsor. */
    public String updatedBy() {
        return value("upID");
    }

    public String transferDate() {
        return value("trDate");
    }

    /**
     * The object's authorisation code (authInfo/pw), or null when the registry withheld it. This is the secret
     * that lets any registrar take the domain away: never log it, and roll it after passing it to a customer.
     */
    public String authInfo() {
        for (Element el : all("authInfo")) {
            String pw = directText(directChild(el, "pw"));
            if (!pw.isEmpty()) {
                return pw;
            }
        }
        return null;
    }

    /** A domain's nameservers, from either hostObj or hostAttr, lower-cased and de-duplicated. */
    public List<String> nameservers() {
        List<String> out = new ArrayList<>();
        for (String ln : new String[]{"hostObj", "hostName"}) {
            for (Element el : all(ln)) {
                String name = directText(el).toLowerCase();
                if (!name.isEmpty() && !out.contains(name)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** A domain's INLINE glue (hostAttr), keyed by nameserver name. Empty for a registry that uses hostObj. */
    public Map<String, List<Map<String, String>>> nameserverAddresses() {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        for (Element attr : all("hostAttr")) {
            String name = directText(directChild(attr, "hostName")).toLowerCase();
            if (name.isEmpty()) {
                continue;
            }
            List<Element> addrs = new ArrayList<>();
            for (Element e : childElements(attr)) {
                if (local(e).equals("hostAddr")) {
                    addrs.add(e);
                }
            }
            out.put(name, addresses(addrs));
        }
        return out;
    }

    /** A host object's own glue addresses. Empty for an external nameserver, which is normal. */
    public List<Map<String, String>> hostAddresses() {
        Element data = resData();
        List<Element> obj = childElements(data);
        if (obj.isEmpty()) {
            return new ArrayList<>();
        }
        List<Element> addrs = new ArrayList<>();
        for (Element e : childElements(obj.get(0))) {
            // The contact <addr> is a container; the host <addr> is a leaf. The leaf test keeps them apart, and
            // scoping to the object itself stops a domain's hostAttr glue from flattening in.
            if (local(e).equals("addr") && childElements(e).isEmpty()) {
                addrs.add(e);
            }
        }
        return addresses(addrs);
    }

    private static List<Map<String, String>> addresses(List<Element> nodes) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Element el : nodes) {
            String ip = directText(el);
            if (ip.isEmpty()) {
                continue;
            }
            Map<String, String> a = new LinkedHashMap<>();
            a.put("ip", ip);
            // Absent @ip means v4 per the host schema default.
            String ver = el.getAttribute("ip");
            a.put("version", ver.isEmpty() ? "v4" : ver);
            out.add(a);
        }
        return out;
    }

    /** Nameserver objects that live UNDER this domain (domain:host in a domain:info), lower-cased. */
    public List<String> subordinateHosts() {
        List<String> out = new ArrayList<>();
        for (Element el : all("host")) {
            if (!childElements(el).isEmpty()) {
                continue;
            }
            String name = directText(el).toLowerCase();
            if (!name.isEmpty() && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    /** A domain's role contacts, keyed by role. The registrant is separate; see {@link #registrant()}. */
    public Map<String, List<String>> contacts() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (Element el : all("contact")) {
            String role = el.getAttribute("type");
            String handle = directText(el);
            if (role.isEmpty() || handle.isEmpty()) {
                continue;
            }
            List<String> list = out.get(role);
            if (list == null) {
                list = new ArrayList<>();
                out.put(role, list);
            }
            list.add(handle);
        }
        return out;
    }

    /** The handles in ONE role, matched case-insensitively. Empty when the domain carries nobody in that role. */
    public List<String> contactsFor(String role) {
        for (Map.Entry<String, List<String>> e : contacts().entrySet()) {
            if (e.getKey().equalsIgnoreCase(role)) {
                return e.getValue();
            }
        }
        return new ArrayList<>();
    }

    public List<String> adminContacts() {
        return contactsFor("admin");
    }

    public List<String> techContacts() {
        return contactsFor("tech");
    }

    public List<String> billingContacts() {
        return contactsFor("billing");
    }

    /** Every handle attached to the domain in any capacity, registrant included, de-duplicated. */
    public List<String> allContacts() {
        List<String> out = new ArrayList<>();
        String registrant = registrant();
        if (registrant != null && !registrant.isEmpty()) {
            out.add(registrant);
        }
        for (List<String> handles : contacts().values()) {
            for (String h : handles) {
                if (!out.contains(h)) {
                    out.add(h);
                }
            }
        }
        return out;
    }

    public String email() {
        return value("email");
    }

    public String voice() {
        return value("voice");
    }

    public String fax() {
        return value("fax");
    }

    /** A contact's postal addresses, keyed by form: "int" (ASCII) and "loc" (local script). */
    public Map<String, Map<String, Object>> postalInfo() {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Element el : all("postalInfo")) {
            Element addr = directChild(el, "addr");
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("name", directText(directChild(el, "name")));
            info.put("org", directText(directChild(el, "org")));
            List<String> street = new ArrayList<>();
            for (Element s : childElements(addr)) {
                if (local(s).equals("street")) {
                    street.add(directText(s));
                }
            }
            info.put("street", street);
            info.put("city", addr != null ? directText(directChild(addr, "city")) : "");
            info.put("sp", addr != null ? directText(directChild(addr, "sp")) : "");
            info.put("pc", addr != null ? directText(directChild(addr, "pc")) : "");
            info.put("cc", addr != null ? directText(directChild(addr, "cc")) : "");
            String type = el.getAttribute("type");
            out.put(type.isEmpty() ? "int" : type, info);
        }
        return out;
    }

    /** A contact's disclosure preference (flag + elements), or null when it carries none. */
    public Map<String, Object> disclose() {
        Element el = first("disclose");
        if (el == null) {
            return null;
        }
        List<String> elements = new ArrayList<>();
        NodeList descendants = el.getElementsByTagName("*");
        for (int i = 0; i < descendants.getLength(); i++) {
            Element child = (Element) descendants.item(i);
            String kind = child.getAttribute("type");
            elements.add(kind.isEmpty() ? local(child) : local(child) + ":" + kind);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("flag", truthy(el.getAttribute("flag")));
        out.put("elements", elements);
        return out;
    }

    /** A transfer notice in full - who asked, when, who must answer, and by when. Null when there is none. */
    public Map<String, String> transfer() {
        Element el = first("trnData");
        if (el == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("status", directText(directChild(el, "trStatus")));
        out.put("requestedBy", directText(directChild(el, "reID")));
        out.put("requestedAt", directText(directChild(el, "reDate")));
        out.put("actingClient", directText(directChild(el, "acID")));
        out.put("actBy", directText(directChild(el, "acDate")));
        out.put("expiryDate", directText(directChild(el, "exDate")));
        return out;
    }

    /** What the registry did to one of your objects without you asking (RFC 8590 change poll), or null. */
    public Map<String, String> change() {
        Element el = first("changeData");
        if (el == null) {
            return null;
        }
        Element operation = directChild(el, "operation");
        Map<String, String> out = new LinkedHashMap<>();
        out.put("operation", directText(operation));
        out.put("op", operation != null ? operation.getAttribute("op") : "");
        // 'after' is the schema default, so an omitted attribute means 'after', not "unknown".
        String state = el.getAttribute("state");
        out.put("state", state.isEmpty() ? "after" : state);
        out.put("date", directText(directChild(el, "date")));
        out.put("svTRID", directText(directChild(el, "svTRID")));
        out.put("who", directText(directChild(el, "who")));
        out.put("reason", directText(directChild(el, "reason")));
        return out;
    }

    // --- availability / money shortcuts ----------------------------------------------------------------------

    /** Is this one name available? null when the response says nothing about it (NOT the same as taken). */
    public Boolean isAvailable(String name) {
        for (Map.Entry<String, Boolean> e : availability().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }

    /** Why a name in a check response is unavailable (e.g. "In use"); null when available or no reason given. */
    public String unavailableReason(String name) {
        for (Element cd : all("cd")) {
            Element label = directChild(cd, "name");
            if (label == null) {
                label = directChild(cd, "id");
            }
            if (label == null || !directText(label).equalsIgnoreCase(name)) {
                continue;
            }
            String reason = directText(directChild(cd, "reason"));
            return reason.isEmpty() ? null : reason;
        }
        return null;
    }

    /** The quoted price for ONE operation at ONE period, or null when the answer carried no such quote. */
    @SuppressWarnings("unchecked")
    public String feeFor(String name, String operation, int years) {
        for (Map.Entry<String, Object> e : fees().entrySet()) {
            if (e.getKey().equals("_currency") || !(e.getValue() instanceof Map) || !e.getKey().equalsIgnoreCase(name)) {
                continue;
            }
            Object periods = ((Map<String, Object>) e.getValue()).get("periods");
            if (periods instanceof List) {
                for (Object q : (List<Object>) periods) {
                    Map<String, Object> quote = (Map<String, Object>) q;
                    if (operation.equals(quote.get("op")) && Integer.valueOf(years).equals(quote.get("years"))) {
                        return (String) quote.get("fee");
                    }
                }
            }
        }
        return null;
    }

    public String feeFor(String name, String operation) {
        return feeFor(name, operation, 1);
    }

    /** The registry's fee class for a name (e.g. "premium"); null when the answer carried no class. */
    @SuppressWarnings("unchecked")
    public String feeClass(String name) {
        for (Map.Entry<String, Object> e : fees().entrySet()) {
            if (e.getKey().equals("_currency") || !(e.getValue() instanceof Map)) {
                continue;
            }
            if (name != null && !e.getKey().equalsIgnoreCase(name)) {
                continue;
            }
            Object klass = ((Map<String, Object>) e.getValue()).get("class");
            if (klass != null) {
                return klass.toString();
            }
        }
        return null;
    }

    public String feeClass() {
        return feeClass(null);
    }

    /** Whether the registry priced the name outside the standard list. A false is not a promise of standard price. */
    public boolean isPremium(String name) {
        String klass = feeClass(name);
        return klass != null && !klass.equalsIgnoreCase("standard");
    }

    public boolean isPremium() {
        return isPremium(null);
    }

    public String creditLimit() {
        Map<String, String> bal = balance();
        return bal != null ? bal.get("creditLimit") : null;
    }

    public String currentBalance() {
        Map<String, String> bal = balance();
        return bal != null ? bal.get("balance") : null;
    }

    public String availableCredit() {
        Map<String, String> bal = balance();
        return bal != null ? bal.get("availableCredit") : null;
    }

    public String feeAmount() {
        Map<String, String> fee = chargedFee();
        return fee != null ? fee.get("fee") : null;
    }

    public String feeCurrency() {
        Map<String, String> fee = chargedFee();
        return fee != null ? fee.get("currency") : null;
    }

    // --- diagnostics / greeting ------------------------------------------------------------------------------

    /** Every extValue on a failed command, unpacked: which element the registry rejected and why. */
    public List<Map<String, Object>> extValues() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Element ext : all("extValue")) {
            Element value = directChild(ext, "value");
            List<Element> valueKids = childElements(value);
            Element offender = valueKids.isEmpty() ? null : valueKids.get(0);
            Element reason = directChild(ext, "reason");
            Map<String, String> values = new LinkedHashMap<>();
            String xml = "";
            if (offender != null) {
                for (Element child : childElements(offender)) {
                    values.put(local(child), directText(child));
                }
                xml = serialize(offender);
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("element", offender != null ? local(offender) : "");
            entry.put("namespace", offender != null && offender.getNamespaceURI() != null ? offender.getNamespaceURI() : "");
            entry.put("text", offender != null ? directText(offender) : directText(value));
            entry.put("values", values);
            entry.put("xml", xml);
            entry.put("reason", directText(reason));
            entry.put("lang", reason != null ? reason.getAttribute("lang") : "");
            out.add(entry);
        }
        return out;
    }

    /** Extra diagnostic text from a failed command's extValue/reason elements. */
    public List<String> errorReasons() {
        List<String> out = new ArrayList<>();
        for (Element ext : all("extValue")) {
            NodeList descendants = ext.getElementsByTagName("*");
            for (int i = 0; i < descendants.getLength(); i++) {
                Element r = (Element) descendants.item(i);
                if (local(r).equals("reason")) {
                    out.add(directText(r));
                }
            }
        }
        return out;
    }

    /** Login only: the server's security warnings about THIS session (RFC 8807 loginSec:event). */
    public List<Map<String, String>> securityEvents() {
        List<Map<String, String>> out = new ArrayList<>();
        for (Element data : all("loginSecData")) {
            for (Element event : childElements(data)) {
                if (!local(event).equals("event")) {
                    continue;
                }
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("text", directText(event));
                for (String attr : new String[]{"type", "name", "level", "exDate", "value", "duration", "lang"}) {
                    if (event.hasAttribute(attr)) {
                        entry.put(attr, event.getAttribute(attr));
                    }
                }
                out.add(entry);
            }
        }
        return out;
    }

    /** Greeting only: the object services the server advertises. */
    public List<String> serviceObjUris() {
        return values("objURI");
    }

    /** Greeting only: the extension services the server advertises. */
    public List<String> serviceExtUris() {
        return values("extURI");
    }

    // --- generic getters -------------------------------------------------------------------------------------

    /** First element anywhere with this local name (namespace-agnostic), trimmed; null when none. */
    public String value(String localName) {
        Element el = first(localName);
        return el != null ? directText(el) : null;
    }

    /** Every element with this local name, trimmed. */
    public List<String> values(String localName) {
        List<String> out = new ArrayList<>();
        for (Element e : all(localName)) {
            out.add(directText(e));
        }
        return out;
    }

    /** The resData element of the response, if present (for custom parsing). */
    public Element resData() {
        return first("resData");
    }

    /** The response exactly as it arrived on the wire. */
    public String raw() {
        return raw;
    }

    /** The parsed DOM document, for anything the accessors do not cover. */
    public Document dom() {
        return root;
    }

    private static String attrOrNull(Element el, String name) {
        return el.hasAttribute(name) ? el.getAttribute(name) : null;
    }

    private static String serialize(Node node) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
