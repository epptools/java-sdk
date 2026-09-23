package com.epptools.sdk;

import com.epptools.sdk.exception.EppException;

import java.io.StringWriter;
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
 * Builds an EPP command frame. It keeps the RFC 5730 child order - the command content, then an optional
 * extension, then clTRID - and escapes text properly, because text is set on element nodes and never
 * concatenated into a string. Public so you can assemble a frame for anything the high-level client does not
 * cover and send it with {@link Client#request}.
 *
 * The base epp-1.0 namespace is the default (unprefixed) one; object and extension namespaces carry the usual
 * domain:, contact:, host:, secDNS: prefixes. Each element carries its own prefix in the DOM, so there is no
 * global prefix table to keep in step while a frame is being assembled.
 */
public final class Frame {
    private final Document doc;
    private final Element epp;
    private final Element command;
    private Element extension;
    private String cltrid = "";

    private Frame() {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            DocumentBuilder b = f.newDocumentBuilder();
            this.doc = b.newDocument();
        } catch (Exception e) {
            throw new EppException("cannot create an XML document", e);
        }
        this.epp = doc.createElementNS(Namespaces.EPP, "epp");
        doc.appendChild(epp);
        this.command = doc.createElementNS(Namespaces.EPP, "command");
        epp.appendChild(command);
    }

    /** Start a &lt;command&gt; frame with the given client transaction id. */
    public static Frame command(String cltrid) {
        Frame frame = new Frame();
        frame.cltrid = cltrid == null ? "" : cltrid;
        return frame;
    }

    /** The root &lt;epp&gt; element, for callers that build by hand. */
    public Element root() {
        return epp;
    }

    /** Add the command verb element (check, create, login, poll, ...) and return it. */
    public Element verb(String name) {
        Element v = doc.createElementNS(Namespaces.EPP, name);
        command.appendChild(v);
        return v;
    }

    /** Add (once) and return the &lt;extension&gt; element. */
    public Element extension() {
        if (extension == null) {
            extension = doc.createElementNS(Namespaces.EPP, "extension");
            command.appendChild(extension);
        }
        return extension;
    }

    /** Append an element in the base epp-1.0 namespace (no prefix). */
    public Element epp(Element parent, String name, String text, Map<String, ?> attrs) {
        Element el = doc.createElementNS(Namespaces.EPP, name);
        parent.appendChild(el);
        return fill(el, text, attrs);
    }

    public Element epp(Element parent, String name) {
        return epp(parent, name, null, null);
    }

    public Element epp(Element parent, String name, String text) {
        return epp(parent, name, text, null);
    }

    /** Append a namespaced element such as {@code domain:name}, carrying its xmlns prefix. */
    public Element ns(Element parent, String nsUri, String qname, String text, Map<String, ?> attrs) {
        Element el = doc.createElementNS(nsUri, qname);
        parent.appendChild(el);
        return fill(el, text, attrs);
    }

    public Element ns(Element parent, String nsUri, String qname) {
        return ns(parent, nsUri, qname, null, null);
    }

    public Element ns(Element parent, String nsUri, String qname, String text) {
        return ns(parent, nsUri, qname, text, null);
    }

    /** The whole frame as a UTF-8 XML string, clTRID appended last. Safe to call more than once. */
    public String toXml() {
        // clTRID is always the final child of <command> (RFC 5730), and there is exactly one. toXml() is public,
        // so the same frame may be serialized twice - logged, then sent - and a second clTRID would make the
        // frame invalid and earn a bare 2001. Drop any earlier one before appending the current value.
        NodeList existing = command.getElementsByTagNameNS(Namespaces.EPP, "clTRID");
        for (int i = existing.getLength() - 1; i >= 0; i--) {
            command.removeChild(existing.item(i));
        }
        Element cl = doc.createElementNS(Namespaces.EPP, "clTRID");
        cl.setTextContent(cltrid);
        command.appendChild(cl);
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + serialize(epp);
    }

    private Element fill(Element el, String text, Map<String, ?> attrs) {
        if (text != null) {
            el.setTextContent(text);
        }
        if (attrs != null) {
            for (Map.Entry<String, ?> e : attrs.entrySet()) {
                el.setAttribute(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        return el;
    }

    private static String serialize(Node node) {
        try {
            Transformer t = TransformerFactory.newInstance().newTransformer();
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            StringWriter out = new StringWriter();
            t.transform(new DOMSource(node), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new EppException("cannot serialize the XML frame", e);
        }
    }
}
