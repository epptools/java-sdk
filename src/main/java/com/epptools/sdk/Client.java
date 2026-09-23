package com.epptools.sdk;

import com.epptools.sdk.command.Contact;
import com.epptools.sdk.command.Domain;
import com.epptools.sdk.command.Host;
import com.epptools.sdk.command.Poll;
import com.epptools.sdk.exception.AuthenticationException;
import com.epptools.sdk.exception.CommandException;
import com.epptools.sdk.exception.ConfigException;
import com.epptools.sdk.exception.ConnectionException;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.w3c.dom.Element;

/**
 * EPP client. Open a connection, log in, then reach the object commands through {@link #domain()},
 * {@link #contact()}, {@link #host()} and {@link #poll()}. Each command returns a {@link Response}. By default
 * any EPP error code of 2000 or above is thrown as a {@link CommandException}; call
 * {@code throwOnFailure(false)} to inspect codes yourself instead.
 *
 * <pre>{@code
 * Client client = new Client(Config.builder("epp.registry.example", "your-clid", "your-secret").build());
 * client.connect();
 * client.login();
 * Map<String, Boolean> free = client.domain().check(Arrays.asList("example.com.ua")).availability();
 * client.logout();
 * client.disconnect();
 * }</pre>
 */
public final class Client implements AutoCloseable {
    /**
     * Bounds of the RFC 5730 pw/newPW schema type (epp:pwType, minLength 6 / maxLength 16). The login frame is
     * always schema-validated, so anything outside this range is rejected as an opaque 2001 naming no field.
     */
    public static final int PW_MIN = 6;
    public static final int PW_MAX = 16;

    /**
     * Maximum password length when the server supports the Login Security extension (RFC 8807), which carries
     * the password outside the 16-character pw element. Applies only when the greeting advertises it.
     */
    public static final int PW_MAX_LOGINSEC = 128;

    // Matches pw and newPW in any namespace prefix, including loginSec:pw. The opening tag may carry attributes,
    // so the pattern accepts anything up to '>': every such element is masked whatever attributes it carries.
    private static final Pattern REDACT = Pattern.compile(
            "(<(?:[\\w.-]+:)?(?:pw|newPW)(?:\\s[^>]*)?>)(.*?)(</(?:[\\w.-]+:)?(?:pw|newPW)\\s*>)", Pattern.DOTALL);
    private static final Pattern CLTRID_RE = Pattern.compile("<clTRID>([^<]*)</clTRID>");
    private static final Pattern CONTROL_RE = Pattern.compile("[\\x00-\\x1F\\x7F]");

    private final Config config;
    private final Transport connection;
    private Logger logger;
    private Response greeting;
    private boolean loggedIn;
    private boolean throwOnFailure = true;
    private int tridCounter;
    private final String processToken;

    private Domain domain;
    private Contact contact;
    private Host host;
    private Poll poll;

    /** Anywhere to send debug lines. Passwords and authInfo are masked before anything is handed over. */
    public interface Logger {
        void debug(String message);

        void info(String message);

        void warning(String message);
    }

    public Client(Config config) {
        this(config, null, null);
    }

    public Client(Config config, Transport connection) {
        this(config, connection, null);
    }

    public Client(Config config, Transport connection, Logger logger) {
        this.config = config;
        this.connection = connection != null ? connection : new Connection(config);
        this.logger = logger;
        // Per-process component of the client transaction id: ids from one process share a stable middle
        // segment and stay unique across concurrent processes.
        String pid;
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            pid = n.contains("@") ? n.substring(0, n.indexOf('@')) : n;
        } catch (Throwable t) {
            pid = String.valueOf(Math.abs(new Object().hashCode()));
        }
        this.processToken = pid;
    }

    public static Client connectAndLogin(Config config) {
        Client client = new Client(config);
        client.connect();
        client.login();
        return client;
    }

    /** Toggle automatic CommandException throwing on EPP error codes. */
    public Client throwOnFailure(boolean throwOnFailure) {
        this.throwOnFailure = throwOnFailure;
        return this;
    }

    /** Attach (or clear) a logger; passwords and authInfo are masked before logging. */
    public Client setLogger(Logger logger) {
        this.logger = logger;
        return this;
    }

    @Override
    public void close() {
        disconnect();
    }

    // --- session -------------------------------------------------------------------------------------------

    /** Open the TLS socket and read the unsolicited greeting. */
    public Response connect() {
        // No host check here. Config's constructor is private and Config.Builder.build() already refuses an
        // empty host, so a guard on this side could never fire - and a guard that cannot fire reads as
        // coverage while proving nothing.
        if (!connection.isOpen()) {
            connection.open();
        }
        String raw = connection.readFrame();
        logDebug("EPP << greeting " + raw);
        Response first = Response.fromXml(raw);
        if (!first.isGreeting()) {
            // The first frame MUST be the <greeting> (RFC 5730). Accepting whatever arrives instead would let a
            // <response> left over from a half-open session - or a middlebox banner - stand in for the service
            // list, which downgrades login() to the DEFAULT services: advertising services this server may not
            // offer and losing the extensions it does, the registry's own among them. Not stored either, so a
            // caller who catches this does not then read a greeting that never arrived.
            throw new ConnectionException("First frame from " + config.host + ":" + config.port
                    + " is not an EPP <greeting> (result " + first.code() + ": "
                    + (first.message() == null ? "no message" : first.message()) + ")");
        }
        greeting = first;
        return greeting;
    }

    public Response greeting() {
        return greeting;
    }

    /**
     * Send hello; the server replies with a fresh greeting.
     *
     * This is the documented keep-alive, so it is the call a long-lived worker makes on a timer - which is why the
     * reply is checked and, when it is not a greeting, the one already stored is left alone. Storing a
     * &lt;response&gt; here would be worse than failing: a response advertises no services, so the next login()
     * falls through to the DEFAULT service list, announcing services this server may not offer and losing the ones
     * it does - the registry's own among them. From that point registryExtUri() answers null, a licence create, a
     * forced host delete and balance() all throw, and change() stops arriving, with nothing in the log to say when
     * it started.
     */
    public Response hello() {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><epp xmlns=\"" + Namespaces.EPP
                + "\"><hello/></epp>";
        // Logged like every other frame. A hello cannot go through transact(), which would check a clTRID a hello
        // does not carry and log a greeting's absent result code as a warning - but skipping the logging entirely
        // left the one frame a worker sends most often invisible in a debug trace.
        logDebug("EPP >> request " + xml);
        connection.writeFrame(xml);
        String raw = connection.readFrame();
        logDebug("EPP << greeting " + raw);
        Response fresh = Response.fromXml(raw);
        if (!fresh.isGreeting()) {
            throw new ConnectionException("Reply to <hello> from " + config.host + ":" + config.port
                    + " is not an EPP <greeting> (result " + fresh.code() + ": "
                    + (fresh.message() == null ? "no message" : fresh.message())
                    + ") - the greeting already read is unchanged");
        }
        greeting = fresh;
        return greeting;
    }

    public Response login() {
        return login(null);
    }

    /**
     * Authenticate. Advertises exactly the services the greeting offered, so the login is never rejected for an
     * unsupported service, unless Config.objUris / extUris override them. Pass a new password to rotate the EPP
     * password during login (RFC 5730 newPW).
     */
    public Response login(String newPassword) {
        // Only the password. The clID half of this guard could never be true: Config's constructor refuses an empty
        // clID outright, and a Config is the only way to reach here, so a reader counting two protections was
        // counting one - and looking in the wrong place for the one that fires. The password is a real case,
        // because Config accepts an empty one and it is the <pw> element that then goes out empty.
        if (config.password == null || config.password.isEmpty()) {
            throw new ConfigException("login requires a non-empty password - check your config");
        }
        // Bounds that hold for every server are checked before connecting, so a misconfigured password never
        // opens a socket. Whether a password longer than PW_MAX is usable depends on the server advertising RFC
        // 8807, so that check happens once the greeting has been read.
        String[][] candidates = {{config.password, "Config.password"}, {newPassword, "the new password"}};
        for (String[] c : candidates) {
            if (c[0] == null) {
                continue;
            }
            if (c[0].length() < PW_MIN || c[0].length() > PW_MAX_LOGINSEC) {
                throw new ConfigException(c[1] + " must be " + PW_MIN + "-" + PW_MAX_LOGINSEC
                        + " characters long (got " + c[0].length() + ")");
            }
        }
        if (greeting == null) {
            connect();
        }

        List<String> greetingObj = greeting != null ? greeting.serviceObjUris() : new ArrayList<String>();
        List<String> greetingExt = greeting != null ? greeting.serviceExtUris() : new ArrayList<String>();
        // An EMPTY objUris list means "not configured", not "announce nothing". epp-1.0.xsd gives loginSvcType an
        // objURI with minOccurs 1, so honouring an empty list writes no objURI at all and the login is refused
        // outright - the least useful error in EPP, on the one command that has to succeed first. Treating it as
        // absent is what a null already did, and what the greeting-mirroring path promises.
        //
        // An empty extUris keeps its meaning, which is to announce no extensions. That one is legitimate: svcExtension
        // is itself optional, so the frame is valid, and a caller who wants a plain RFC session has no other way
        // to ask for one.
        List<String> objUris = config.objUris != null && !config.objUris.isEmpty() ? config.objUris
                : (!greetingObj.isEmpty() ? greetingObj : Namespaces.DEFAULT_OBJ_URIS);
        List<String> extUris = config.extUris != null ? config.extUris
                : (!greetingExt.isEmpty() ? greetingExt : Namespaces.DEFAULT_EXT_URIS);
        // The epp-1.0 base URI is not an object service and is never listed in <login>.
        List<String> objects = new ArrayList<>();
        for (String u : objUris) {
            if (!Namespaces.EPP.equals(u)) {
                objects.add(u);
            }
        }

        // Two separate decisions about the Login Security extension (RFC 8807), both needing the server to
        // advertise it: whether to TAKE PART (which is what makes the server's security events come back) and
        // whether a password has to TRAVEL in it, which is forced only by one longer than 16 characters.
        boolean loginsecAvailable = extUris.contains(Namespaces.LOGINSEC);
        for (String[] c : candidates) {
            if (c[0] != null && c[0].length() > PW_MAX && !loginsecAvailable) {
                throw new ConfigException(c[1] + " is " + c[0].length() + " characters, but this server does not "
                        + "advertise " + Namespaces.LOGINSEC + " - the EPP <pw> schema type allows at most "
                        + PW_MAX + ", so the server would answer a bare 2001");
            }
        }
        // Decided PER ELEMENT, not once for the frame. The sentinel means "the real value is in the matching
        // loginSec element", so putting it in an element whose value was NOT relocated points the server at
        // something that is not there and the login is refused. Rotation across the 16-character boundary is
        // exactly that case: changing a short password to a long one relocates newPW only.
        boolean relocatePw = loginsecAvailable && config.password.length() > PW_MAX;
        boolean relocateNewPw = loginsecAvailable && newPassword != null && newPassword.length() > PW_MAX;
        // The block is also sent when nothing has to be relocated, because that is what makes the server's
        // security events reach you: it returns them only to a client that took part in the extension, since
        // announcing a URI proves nothing.
        boolean useLoginsec = relocatePw || relocateNewPw || (loginsecAvailable && config.loginSecurity);

        Frame frame = frame();
        Element login = frame.verb("login");
        frame.epp(login, "clID", config.clid);
        frame.epp(login, "pw", relocatePw ? Namespaces.LOGINSEC_SENTINEL : config.password);
        if (newPassword != null) {
            frame.epp(login, "newPW", relocateNewPw ? Namespaces.LOGINSEC_SENTINEL : newPassword);
        }
        Element options = frame.epp(login, "options");
        frame.epp(options, "version", "1.0");
        frame.epp(options, "lang", config.lang);
        Element svcs = frame.epp(login, "svcs");
        for (String uri : objects) {
            frame.epp(svcs, "objURI", uri);
        }
        if (!extUris.isEmpty()) {
            Element svcExt = frame.epp(svcs, "svcExtension");
            for (String uri : extUris) {
                frame.epp(svcExt, "extURI", uri);
            }
        }
        if (useLoginsec) {
            Element ext = frame.extension();
            Element block = frame.ns(ext, Namespaces.LOGINSEC, "loginSec:loginSec");
            Element ua = frame.ns(block, Namespaces.LOGINSEC, "loginSec:userAgent");
            // app, tech and os in that order - the schema's userAgentType is a sequence, and a registry support
            // desk asking "which client, on what" answers both from one login.
            frame.ns(ua, Namespaces.LOGINSEC, "loginSec:app", "EppTools Java SDK " + Version.VERSION);
            frame.ns(ua, Namespaces.LOGINSEC, "loginSec:tech", "Java " + System.getProperty("java.version"));
            frame.ns(ua, Namespaces.LOGINSEC, "loginSec:os", osDescription());
            if (relocatePw) {
                frame.ns(block, Namespaces.LOGINSEC, "loginSec:pw", config.password);
            }
            if (relocateNewPw) {
                frame.ns(block, Namespaces.LOGINSEC, "loginSec:newPW", newPassword);
            }
        }

        Response response = transact(frame.toXml());
        if (response.code() != 1000) {
            String message = "Login failed (EPP " + response.code() + "): "
                    + (response.message() == null ? "no message" : response.message());
            // Only 2200 means the credentials are wrong. A login can also be refused at the session limit
            // (2502), because the server is closing (2501), because a service is not offered (2307), because
            // this connection is already logged in (2002) or over the protocol version (2100) - each with its
            // own remedy. Calling them all an authentication failure sends the reader to rotate a password that
            // was never the problem, and hides that the answer is to reconnect.
            if (response.code() == ResultCode.AUTHENTICATION_ERROR) {
                throw new AuthenticationException(response.code(), message, response);
            }
            throw CommandException.forCode(response.code(), message, response);
        }
        loggedIn = true;
        return response;
    }

    public Response logout() {
        Frame frame = frame();
        frame.verb("logout");
        Response response = transact(frame.toXml()); // 1500; the server then closes the link
        loggedIn = false;
        return response;
    }

    public void disconnect() {
        connection.close();
        loggedIn = false;
    }

    public boolean isConnected() {
        return connection.isOpen();
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    // --- resource handlers ---------------------------------------------------------------------------------

    public Domain domain() {
        if (domain == null) {
            domain = new Domain(this);
        }
        return domain;
    }

    public Contact contact() {
        if (contact == null) {
            contact = new Contact(this);
        }
        return contact;
    }

    public Host host() {
        if (host == null) {
            host = new Host(this);
        }
        return host;
    }

    public Poll poll() {
        if (poll == null) {
            poll = new Poll(this);
        }
        return poll;
    }

    // --- the registry's own extensions ---------------------------------------------------------------------

    private List<String> advertisedExt() {
        return greeting != null ? greeting.serviceExtUris() : new ArrayList<String>();
    }

    /** The namespace this registry uses for its own object extensions, or null. Config first, then the greeting. */
    public String registryExtUri() {
        if (config.registryExtUri != null) {
            return config.registryExtUri;
        }
        return Namespaces.registryExtension(advertisedExt());
    }

    /** The namespace this registry uses for account balance, or null. */
    public String registryBalanceUri() {
        if (config.registryBalanceUri != null) {
            return config.registryBalanceUri;
        }
        return Namespaces.registryBalance(advertisedExt());
    }

    /**
     * The registry extension namespace, or an explanation of why there is not one. Callers that MUST have it
     * use this rather than {@link #registryExtUri()}, because the alternative to failing here is failing
     * invisibly: an extension sent under a namespace the server does not recognise is ignored, not rejected, so
     * the command succeeds with the licence quietly missing.
     */
    public String requireRegistryExtUri(String what) {
        String uri = registryExtUri();
        if (uri != null) {
            return uri;
        }
        throw new ConfigException(what + " needs the registry's own EPP extension, and " + config.host
                + " does not advertise one. It offered: " + offered()
                + ". If this registry does support it under a name discovery cannot guess, set registryExtUri in Config.");
    }

    private String offered() {
        List<String> ext = advertisedExt();
        return ext.isEmpty() ? "(no greeting read yet)" : String.join(", ", ext);
    }

    /**
     * Query the registrar account balance. Not an RFC command - it exists only where a registry defines it,
     * which is why the namespace is discovered rather than assumed.
     */
    public Response balance() {
        String uri = registryBalanceUri();
        if (uri == null) {
            throw new ConfigException(config.host + " advertises no account-balance EPP extension. It offered: "
                    + offered() + ". If this registry does support one under a name discovery cannot guess, set "
                    + "registryBalanceUri in Config.");
        }
        Frame frame = frame();
        frame.ns(frame.verb("info"), uri, "balance:info");
        return request(frame);
    }

    // --- low level -----------------------------------------------------------------------------------------

    /** A new command frame with an auto-generated clTRID already stamped. */
    public Frame frame() {
        return Frame.command(nextCltrid());
    }

    public Response request(Frame frame) {
        return request(frame.toXml());
    }

    /**
     * Send a raw XML frame and return the parsed response. Throws CommandException on an EPP error code unless
     * throwOnFailure(false) is set.
     */
    public Response request(String xml) {
        Response response = transact(xml);
        if (throwOnFailure && !response.isSuccess()) {
            // The message names the SUBJECT when the registry identified one. On a command carrying five names,
            // "EPP 2302: Object exists" leaves the reader to work out which of the five, and the answer is
            // sitting in extValue unread.
            String subject = null;
            for (Map<String, Object> ext : response.extValues()) {
                Object text = ext.get("text");
                if (text != null && !text.toString().isEmpty()) {
                    subject = text.toString();
                    break;
                }
            }
            String message = "EPP " + response.code() + ": "
                    + (response.message() == null ? "command failed" : response.message());
            if (subject != null) {
                message += " ('" + subject + "')";
            }
            throw CommandException.forCode(response.code(), message, response);
        }
        return response;
    }

    // --- internals -----------------------------------------------------------------------------------------

    private Response transact(String xml) {
        if (!connection.isOpen()) {
            throw new ConnectionException("Not connected - call connect() first");
        }
        logDebug("EPP >> request " + redact(xml));
        connection.writeFrame(xml);
        String raw = connection.readFrame();
        logDebug("EPP << response " + redact(raw));
        Response response = Response.fromXml(raw);
        assertBelongsToThisCommand(xml, response);
        if (logger != null) {
            String line = "EPP result " + response.code() + " (svTRID=" + response.svTRID()
                    + " clTRID=" + response.clTRID() + ")";
            if (response.isSuccess()) {
                logger.info(line);
            } else {
                logger.warning(line);
            }
        }
        return response;
    }

    /**
     * Verify the reply echoes the clTRID this command sent. Checking it turns a desynchronised stream - a stray
     * unsolicited frame, or two commands in flight at once - from a silent mix-up into a loud failure. Without
     * it a reply belonging to the previous command is indistinguishable from this one's, and for a renew or a
     * create that means booking the wrong domain as done and billing both.
     *
     * The connection is closed, not just reported: once the offsets disagree, every later frame is suspect too.
     *
     * Compared against the CLAMPED form of what was sent, because epp-1.0's trIDStringType is 3..64 characters
     * and a server that echoes a caller-supplied clTRID may legitimately return a truncated or padded value.
     * Raw equality would reject those valid replies - the check must catch a WRONG transaction, not a
     * normalised one.
     */
    private void assertBelongsToThisCommand(String xml, Response response) {
        Matcher sent = CLTRID_RE.matcher(xml);
        if (!sent.find()) {
            return;
        }
        String echoed = response.clTRID();
        if (echoed == null || echoed.isEmpty()) {
            return;
        }
        String sentTrid = sent.group(1);
        String clean = CONTROL_RE.matcher(sentTrid).replaceAll("").trim();
        if (clean.length() > 64) {
            clean = clean.substring(0, 64);
        }
        String expected = clean;
        while (expected.length() < 3) {
            expected = expected + "-";
        }
        if (!echoed.equals(sentTrid) && !echoed.equals(expected)) {
            connection.close();
            throw new ConnectionException("Response does not belong to this command (sent clTRID " + sentTrid
                    + ", received " + echoed + ") - the connection was desynchronised and has been closed.");
        }
    }

    private void logDebug(String message) {
        if (logger != null) {
            logger.debug(message);
        }
    }

    /**
     * The operating system, as RFC 8807 asks for it: architecture, name and version.
     *
     * Section 3.1 wants the system "with version if available", such as "x86_64 Mac OS X 10.15.2", and the
     * registrar manual shows the same shape, so the version is not dropped: it is what a support desk asking
     * "which client, on what" is reading the field for. The four libraries cannot produce the same STRING here -
     * on one Windows box the four runtimes report the architecture as amd64, AMD64 and x64, and the version as
     * 10.0, 11 and 10.0.28000 - so the frame-parity tool masks this element, the way it masks the two beside it.
     * Each part is skipped when the JVM does not report it rather than padded, so the value never carries an
     * empty field.
     */
    static String osDescription() {
        StringBuilder out = new StringBuilder();
        for (String key : new String[]{"os.arch", "os.name", "os.version"}) {
            String value = System.getProperty(key);
            if (value != null && !value.trim().isEmpty()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(value.trim());
            }
        }
        return out.length() == 0 ? "unknown" : out.toString();
    }

    /** Mask passwords and authInfo (any namespace) before a frame is logged. */
    static String redact(String xml) {
        return REDACT.matcher(xml).replaceAll("$1***$3");
    }

    private String nextCltrid() {
        tridCounter++;
        // A client transaction id that is easy to correlate in logs: prefix, a UTC timestamp, the per-process
        // token and a monotonic counter.
        //
        // Locale.ROOT on both, and this is the worst of the locale defaults rather than the most obvious. Without
        // it the calendar and the digits are the JVM's: on a Thai default SimpleDateFormat writes the Buddhist year
        // 2569 for 2026 and String.format writes the counter in Thai digits, and the result is a clTRID that PASSES
        // the schema and reaches the registry. A refused frame would at least be a failure; this one succeeds while
        // destroying the only thing a clTRID is for, which is matching a registry's record of a transform against
        // yours - the reconciliation nobody runs until a charge is disputed.
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return String.format(Locale.ROOT, "%s-%s-%s-%04d",
                config.clTRIDPrefix, fmt.format(new Date()), processToken, tridCounter);
    }
}
