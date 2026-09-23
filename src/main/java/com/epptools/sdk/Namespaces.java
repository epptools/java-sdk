package com.epptools.sdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * EPP namespace URIs - the exact strings that go on the wire.
 *
 * <p>Everything here is defined by an RFC and is the same string at every registry on earth. A registry's OWN
 * extensions are deliberately absent: a library that ships one registry's namespace as a constant is that
 * registry's client with the label filed off. Registry extensions are discovered from the {@code <greeting>}
 * instead - see {@link #registryExtension} / {@link #registryBalance}.
 */
public final class Namespaces {
    private Namespaces() {}

    public static final String EPP = "urn:ietf:params:xml:ns:epp-1.0";
    public static final String XSI = "http://www.w3.org/2001/XMLSchema-instance";

    // Standard RFC object mappings (RFC 5731/5732/5733).
    public static final String DOMAIN = "urn:ietf:params:xml:ns:domain-1.0";
    public static final String CONTACT = "urn:ietf:params:xml:ns:contact-1.0";
    public static final String HOST = "urn:ietf:params:xml:ns:host-1.0";

    // Standard extensions.
    public static final String SECDNS = "urn:ietf:params:xml:ns:secDNS-1.1";      // RFC 5910
    public static final String RGP = "urn:ietf:params:xml:ns:rgp-1.0";            // RFC 3915
    public static final String FEE = "urn:ietf:params:xml:ns:epp:fee-1.0";        // RFC 8748 (prices)
    public static final String LOGINSEC = "urn:ietf:params:xml:ns:epp:loginSec-1.0"; // RFC 8807
    public static final String CHANGEPOLL = "urn:ietf:params:xml:ns:changePoll-1.0"; // RFC 8590

    /**
     * Value placed in {@code <pw>}/{@code <newPW>} to say the real password is carried in
     * {@code <loginSec:pw>}/{@code <loginSec:newPW>} instead (RFC 8807 4.1). Reserved: cannot be a password.
     */
    public static final String LOGINSEC_SENTINEL = "[LOGIN-SECURITY]";

    /** Object services a client logs in with by default (standard RFC mappings). */
    public static final List<String> DEFAULT_OBJ_URIS =
            Collections.unmodifiableList(Arrays.asList(CONTACT, DOMAIN, HOST));

    /** Extension services to announce at login when the server sent no greeting to mirror (RFC-defined only). */
    public static final List<String> DEFAULT_EXT_URIS =
            Collections.unmodifiableList(Arrays.asList(SECDNS, RGP, FEE));

    private static final Pattern SPLIT = Pattern.compile("[/:]");

    /**
     * The first advertised URI whose last segment is {@code name} or {@code name-<version>}. Segments split on
     * {@code /} AND {@code :} so URN-form namespaces work. Anything under {@code urn:ietf:} is skipped.
     */
    private static String byLastSegment(Iterable<String> advertised, String name) {
        if (advertised == null) {
            return null;
        }
        Pattern versioned = Pattern.compile("^" + Pattern.quote(name) + "-[0-9.]+$");
        for (String uri : advertised) {
            if (uri == null || uri.startsWith("urn:ietf:")) {
                continue;
            }
            String[] parts = SPLIT.split(uri);
            String last = parts.length == 0 ? uri : parts[parts.length - 1];
            if (last.equals(name) || versioned.matcher(last).matches()) {
                return uri;
            }
        }
        return null;
    }

    /**
     * The registry's own object-data extension, picked out of its greeting by the last segment {@code registry}.
     * Returns {@code null} when the server advertises none - a fact about that server, not an error.
     */
    public static String registryExtension(Iterable<String> advertised) {
        return byLastSegment(advertised == null ? new ArrayList<String>() : advertised, "registry");
    }

    /** The registry's account-balance extension, discovered the same way. */
    public static String registryBalance(Iterable<String> advertised) {
        return byLastSegment(advertised == null ? new ArrayList<String>() : advertised, "balance");
    }
}
