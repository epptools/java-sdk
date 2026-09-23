package com.epptools.sdk;

import com.epptools.sdk.builder.ContactUpdateBuilder;
import com.epptools.sdk.builder.DomainCreateBuilder;
import com.epptools.sdk.command.Contact;
import com.epptools.sdk.exception.AuthenticationException;
import com.epptools.sdk.exception.AuthorizationException;
import com.epptools.sdk.exception.CommandException;
import com.epptools.sdk.exception.ConfigException;
import com.epptools.sdk.exception.ConnectionException;
import com.epptools.sdk.exception.EppException;
import com.epptools.sdk.exception.InsufficientFundsException;
import com.epptools.sdk.exception.ObjectDoesNotExistException;
import com.epptools.sdk.exception.ObjectExistsException;
import com.epptools.sdk.exception.ObjectStatusException;
import com.epptools.sdk.exception.PolicyException;
import com.epptools.sdk.exception.SessionException;
import com.epptools.sdk.exception.ValidationException;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

/**
 * Offline self-test: exercises frame building and response parsing with a fake in-memory transport - no server,
 * no network. It is the Java port of the PHP suite in php-sdk/tests/offline_test.php, assertion for assertion and
 * in the same order, so the two outputs can be read side by side. Compile the whole tree and run:
 *
 * <pre>
 * javac --release 8 -d out $(find src -name "*.java")
 * java -cp out com.epptools.sdk.OfflineTest
 * </pre>
 *
 * There is no test framework here on purpose: the library has no dependencies and neither does its suite.
 */
public final class OfflineTest {
    private OfflineTest() {
    }

    private static int pass;
    private static int fail;

    private static void check(String label, boolean ok) {
        System.out.println((ok ? "  ok  " : " FAIL ") + label);
        if (ok) {
            pass++;
        } else {
            fail++;
        }
    }

    /** A transport that records what was written and replays queued responses. */
    static final class FakeTransport implements Transport {
        final List<String> written = new ArrayList<String>();
        final List<String> queue = new ArrayList<String>();
        private boolean open;

        @Override
        public void open() {
            open = true;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void writeFrame(String xml) {
            written.add(xml);
        }

        @Override
        public String readFrame() {
            if (queue.isEmpty()) {
                throw new RuntimeException("FakeTransport: no queued response");
            }
            return queue.remove(0);
        }

        @Override
        public void close() {
            open = false;
        }
    }

    /** A client and the wire it writes to, the pair the PHP fixture returns from makeClient(). */
    static final class Session {
        final Client client;
        final FakeTransport fake;

        Session(Client client, FakeTransport fake) {
            this.client = client;
            this.fake = fake;
        }
    }

    /**
     * The extension namespaces of the fictional registry these fixtures simulate.
     *
     * They are NOT constants of the library, and there is no equivalent there to compare them against: the library
     * knows the RFC namespaces and discovers a registry's own from its greeting. So these belong to the fixture,
     * the way a hostname or a password in a fixture does.
     *
     * Deliberately a registry no version of this code has ever named. A fixture written with the URIs the library
     * used to hard-code would keep passing if discovery quietly regressed to a constant - the strings would still
     * line up - and would prove only that the code agrees with itself. Under a URI that appears nowhere in
     * src/main, these tests can pass only by actually reading the greeting.
     */
    private static final String EXT_REGISTRY = "http://registry.example/epp/registry-1.0";
    private static final String EXT_BALANCE = "http://registry.example/epp/balance-1.0";

    // Local-script fixture text, written as escapes so the file compiles whatever source encoding javac assumes.
    private static final String ACME_LOCAL = "АКМЕ";
    private static final String KYIV_LOCAL = "Київ";
    private static final String ACME_LLC_LOCAL = "ТОВ АКМЕ";
    private static final String IVAN_LOCAL = "Іван Петренко";
    private static final String OK_MESSAGE_LOCAL =
            "Команду виконано "
                    + "успішно";
    private static final String DOMAIN_GONE_LOCAL = "Домен більше "
            + "не існує в реєстрі.";

    private static final String GREETING =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><greeting>"
            + "<svID>Registry EPP</svID><svDate>2026-06-15T00:00:00Z</svDate><svcMenu><version>1.0</version>"
            + "<lang>en</lang>"
            + "<objURI>urn:ietf:params:xml:ns:epp-1.0</objURI><objURI>urn:ietf:params:xml:ns:contact-1.0</objURI>"
            + "<objURI>urn:ietf:params:xml:ns:domain-1.0</objURI><objURI>urn:ietf:params:xml:ns:host-1.0</objURI>"
            + "<svcExtension><extURI>urn:ietf:params:xml:ns:secDNS-1.1</extURI>"
            + "<extURI>urn:ietf:params:xml:ns:rgp-1.0</extURI>"
            + "<extURI>http://registry.example/epp/registry-1.0</extURI>"
            + "<extURI>http://registry.example/epp/balance-1.0</extURI>"
            + "</svcExtension></svcMenu></greeting></epp>";

    /** The same greeting plus the Login Security extension (RFC 8807). */
    private static final String GREETING_LOGINSEC = GREETING.replace("</svcExtension>",
            "<extURI>" + Namespaces.LOGINSEC + "</extURI></svcExtension>");

    private static String ok(int code) {
        return "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"" + code + "\"><msg>ok</msg></result>"
                + "<trID><svTRID>SRV-1</svTRID></trID></response></epp>";
    }

    private static String ok() {
        return ok(1000);
    }

    private static Config.Builder config(String password) {
        return Config.builder("epp.example", "EXAMPLE", password);
    }

    private static Session makeClient(List<String> responses) {
        return makeClient(responses, config("secret").build());
    }

    private static Session makeClient(List<String> responses, String password) {
        return makeClient(responses, config(password).build());
    }

    private static Session makeClient(List<String> responses, Config cfg) {
        FakeTransport fake = new FakeTransport();
        fake.queue.addAll(responses);
        return new Session(new Client(cfg, fake), fake);
    }

    // --- inspection helpers --------------------------------------------------------------------------------

    /** A written frame, loaded for inspection with the SDK's namespace prefixes registered. */
    static final class Xp {
        private final Document doc;
        private final XPath xpath;
        private final Map<String, String> prefixes = new LinkedHashMap<String, String>();

        Xp(String xml) {
            try {
                DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
                f.setNamespaceAware(true);
                doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                throw new RuntimeException("written frame is not well-formed XML", e);
            }
            xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(new NamespaceContext() {
                @Override
                public String getNamespaceURI(String prefix) {
                    String uri = prefixes.get(prefix);
                    return uri != null ? uri : XMLConstants.NULL_NS_URI;
                }

                @Override
                public String getPrefix(String namespaceUri) {
                    return null;
                }

                @Override
                public java.util.Iterator getPrefixes(String namespaceUri) {
                    return prefixes.keySet().iterator();
                }
            });
            register("e", Namespaces.EPP);
            register("domain", Namespaces.DOMAIN);
            register("contact", Namespaces.CONTACT);
            register("host", Namespaces.HOST);
            register("secDNS", Namespaces.SECDNS);
            register("rgp", Namespaces.RGP);
            register("registry", EXT_REGISTRY);
            register("fee", Namespaces.FEE);
        }

        Xp register(String prefix, String uri) {
            prefixes.put(prefix, uri);
            return this;
        }

        NodeList query(String expression) {
            try {
                return (NodeList) xpath.evaluate(expression, doc, XPathConstants.NODESET);
            } catch (Exception e) {
                throw new RuntimeException("bad XPath: " + expression, e);
            }
        }

        int count(String expression) {
            return query(expression).getLength();
        }

        /** First matching node's text, or null - the equivalent of the PHP fixture's firstText(). */
        String firstText(String expression) {
            NodeList nodes = query(expression);
            return nodes.getLength() == 0 ? null : nodes.item(0).getTextContent();
        }

        List<String> texts(String expression) {
            List<String> out = new ArrayList<String>();
            NodeList nodes = query(expression);
            for (int i = 0; i < nodes.getLength(); i++) {
                out.add(nodes.item(i).getTextContent());
            }
            return out;
        }
    }

    private static Xp xp(String xml) {
        return new Xp(xml);
    }

    /** A LinkedHashMap from alternating key/value arguments - the option map a PHP array literal becomes. */
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return out;
    }

    private static List<Object> list(Object... items) {
        return new ArrayList<Object>(Arrays.asList(items));
    }

    /** Walk nested maps and lists, answering null at the first missing step - PHP's ?? null chains. */
    @SuppressWarnings("unchecked")
    private static Object dig(Object root, Object... path) {
        Object current = root;
        for (Object key : path) {
            if (current instanceof Map) {
                current = ((Map<Object, Object>) current).get(key);
            } else if (current instanceof List && key instanceof Integer) {
                List<Object> items = (List<Object>) current;
                int index = ((Integer) key).intValue();
                current = index >= 0 && index < items.size() ? items.get(index) : null;
            } else {
                return null;
            }
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private static int size(Object collection) {
        if (collection instanceof List) {
            return ((List<?>) collection).size();
        }
        if (collection instanceof Map) {
            return ((Map<?, ?>) collection).size();
        }
        return -1;
    }

    /** The PHP fixture trims the text it compares against a sentinel, so this does the same. */
    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String repeat(String unit, int times) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < times; i++) {
            out.append(unit);
        }
        return out.toString();
    }

    /** True when the call was refused with a ValidationException - a bad argument, not a bad configuration. */
    private static boolean argFails(Runnable call) {
        try {
            call.run();
            return false;
        } catch (ValidationException e) {
            return true;
        }
    }

    public static void main(String[] args) {
        greetingAndLogin();
        namespaceDiscovery();
        passwordRotation();
        domainCheckAvailability();
        domainCreateLicenceSecdns();
        inlineGlue();
        createWithoutAuthInfo();
        contactCreateWithoutEmail();
        domainRestore();
        domainRenewDates();
        errorHandling();
        domainUpdateSecdns();
        pollMessages();
        infoStatuses();
        errorReasonsAndResultCode();
        contactCreatePostal();
        contactUpdateOrg();
        contactAutoId();
        contactUpdateChange();
        contactUpdateStatuses();
        emptySecdnsOnCreate();
        configGuards();
        logRedaction();
        responseAccessors();
        domainInfoHostsSub();
        feeFrames();
        feeResponses();
        frameIdempotence();
        loginPasswords();
        connectFirstFrame();
        hostRename();
        emptySecdnsOnUpdate();
        redactionWithAttributes();
        feeMultiPeriod();
        loginErrorCodes();
        errorClasses();
        secdnsDsWithKey();
        authInfoClearing();
        pollDrain();
        builderParity();
        argumentErrors();
        contactPostalClearing();
        transportRunawayFrame();
        extValuePayloads();
        loginSecSentinelMatrix();
        objectAccessors();
        checkResponseExtras();
        aliasSpellings();
        documentedVersions();

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }

    private static void greetingAndLogin() {
        System.out.println("greeting + login");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        Response greeting = s.client.connect();
        check("greeting parsed", greeting.isGreeting());
        check("serviceObjUris has domain", greeting.serviceObjUris().contains(Namespaces.DOMAIN));
        check("serviceExtUris has the registry extension", greeting.serviceExtUris().contains(EXT_REGISTRY));
        s.client.login();
        Xp loginXp = xp(s.fake.written.get(0));
        check("login carries clID", "EXAMPLE".equals(loginXp.firstText("//e:login/e:clID")));
        check("login version 1.0", "1.0".equals(loginXp.firstText("//e:options/e:version")));
        List<String> objUris = loginXp.texts("//e:svcs/e:objURI");
        check("login objURIs exclude epp base", !objUris.contains(Namespaces.EPP));
        check("login objURIs include domain/contact/host",
                objUris.equals(Arrays.asList(Namespaces.CONTACT, Namespaces.DOMAIN, Namespaces.HOST)));
        check("login mirrors the registry extension back",
                loginXp.count("//e:svcExtension/e:extURI[text()=\"" + EXT_REGISTRY + "\"]") == 1);
    }

    private static void namespaceDiscovery() {
        System.out.println("namespace discovery from the greeting");
        Session s = makeClient(Arrays.asList(GREETING));
        s.client.connect();
        check("registry extension discovered", EXT_REGISTRY.equals(s.client.registryExtUri()));
        check("balance extension discovered", EXT_BALANCE.equals(s.client.registryBalanceUri()));

        // Discovery must key on the last segment and nothing else: a registry's URI can be any string, and the
        // only part of it this library is entitled to assume is the extension's name.
        String oddRegistry = "https://epp.other.example/xml/schemas/registry-1.2";
        String oddBalance = "urn:example:other:balance";
        String oddGreeting = GREETING
                .replace("http://registry.example/epp/registry-1.0", oddRegistry)
                .replace("http://registry.example/epp/balance-1.0", oddBalance);
        s = makeClient(Arrays.asList(oddGreeting));
        s.client.connect();
        check("a differently-shaped registry URI is found", oddRegistry.equals(s.client.registryExtUri()));
        check("a non-http registry URI is found too", oddBalance.equals(s.client.registryBalanceUri()));

        // RFC extensions are skipped by prefix, and this is the case that makes it necessary: fee-1.0 is an IETF
        // extension whose last segment would match a search for an extension named "fee".
        String feeOnly = GREETING.replace(
                "<extURI>http://registry.example/epp/registry-1.0</extURI>"
                        + "<extURI>http://registry.example/epp/balance-1.0</extURI>",
                "<extURI>urn:ietf:params:xml:ns:epp:fee-1.0</extURI>");
        Session fee = makeClient(Arrays.asList(feeOnly));
        fee.client.connect();
        check("a registry advertising no extension of its own reports none", fee.client.registryExtUri() == null);
        check("and no balance extension either", fee.client.registryBalanceUri() == null);

        // Absence must be REPORTED, not guessed around. Sending an invented URI would not be rejected - an
        // extension the server does not recognise is ignored - so the licence would silently not be set.
        String threw = null;
        try {
            fee.client.requireRegistryExtUri("domain:create with a licence");
        } catch (ConfigException e) {
            threw = e.getMessage();
        }
        check("asking for a missing extension throws", threw != null);
        check("and the message says what was wanted", threw != null && threw.contains("domain:create with a licence"));
        check("and lists what the server did advertise",
                threw != null && threw.contains("urn:ietf:params:xml:ns:epp:fee-1.0"));

        // balance() has nothing to fall back on, so it must refuse rather than send a frame that cannot work.
        String threwBal = null;
        try {
            fee.client.balance();
        } catch (ConfigException e) {
            threwBal = e.getMessage();
        }
        check("balance() refuses when the server offers no balance extension", threwBal != null);

        // The config override exists for a registry that names its extension something discovery cannot guess. It
        // must win outright - including over a greeting that advertises a different URI.
        s = makeClient(Arrays.asList(GREETING), config("secret")
                .registryExtUri("urn:example:custom:registry")
                .registryBalanceUri("urn:example:custom:balance").build());
        s.client.connect();
        check("a configured registry URI overrides the greeting",
                "urn:example:custom:registry".equals(s.client.registryExtUri()));
        check("a configured balance URI overrides the greeting",
                "urn:example:custom:balance".equals(s.client.registryBalanceUri()));

        // Before connect() there is no greeting. Discovery must return null rather than fail, so that a caller who
        // set the URIs in config can work without ever reading one.
        Client noGreeting = new Client(Config.builder("h", "EXAMPLE", "secret").build(), new FakeTransport());
        check("no greeting read yet discovers nothing", noGreeting.registryExtUri() == null);
    }

    private static void passwordRotation() {
        System.out.println("login password rotation (newPW)");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.login("BrandNewPass1");
        Xp lx = xp(s.fake.written.get(0));
        check("login carries newPW", "BrandNewPass1".equals(lx.firstText("//e:login/e:newPW")));
    }

    private static void domainCheckAvailability() {
        System.out.println("domain:check + availability");
        String checkResp = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData><domain:chkData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:cd><domain:name avail=\"1\">free.com.ua</domain:name></domain:cd>"
                + "<domain:cd><domain:name avail=\"0\">taken.com.ua</domain:name>"
                + "<domain:reason>in use</domain:reason></domain:cd>"
                + "</domain:chkData></resData><trID><svTRID>SRV-2</svTRID></trID></response></epp>";
        Session s = makeClient(Arrays.asList(GREETING, checkResp));
        s.client.connect();
        Response resp = s.client.domain().check(Arrays.asList("free.com.ua", "taken.com.ua"));
        Map<String, Boolean> avail = resp.availability();
        check("check avail: free=true", Boolean.TRUE.equals(avail.get("free.com.ua")));
        check("check avail: taken=false", Boolean.FALSE.equals(avail.get("taken.com.ua")));

        // A fee rider rides along with EVERY check, and the registry answers an unserved zone / non-UAH request
        // with avail="0" on the fee:cd BLOCK, whose children are fee:objID + fee:reason. Keying on "any element
        // with @avail" turned that into a junk entry sitting next to the real names.
        String feeNoisyXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData><domain:chkData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:cd><domain:name avail=\"1\">free.com.ua</domain:name></domain:cd>"
                + "</domain:chkData></resData>"
                + "<extension><fee:chkData xmlns:fee=\"urn:ietf:params:xml:ns:epp:fee-1.0\">"
                + "<fee:cd avail=\"0\"><fee:objID>bad.zz</fee:objID><fee:reason>Zone is not served</fee:reason>"
                + "</fee:cd></fee:chkData></extension>"
                + "</response></epp>";
        Map<String, Boolean> noisy = Response.fromXml(feeNoisyXml).availability();
        check("availability ignores fee:cd", noisy.size() == 1 && Boolean.TRUE.equals(noisy.get("free.com.ua")));
        Xp checkXp = xp(s.fake.written.get(0));
        check("check frame has 2 domain:name", checkXp.count("//domain:check/domain:name") == 2);
    }

    private static void domainCreateLicenceSecdns() {
        System.out.println("domain:create with licence + secDNS");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().create("brand.ua", map(
                "years", 2,
                "registrant", "C1",
                "contacts", map("admin", "C1", "tech", "C2"),
                "nameservers", list("ns1.brand.ua", "ns2.brand.ua"),
                "authInfo", "Sup3r&Secret<>",
                "license", "TM-12345",
                "secDNS", map("maxSigLife", 604800,
                        "dsData", list(map("keyTag", 12345, "alg", 8, "digestType", 2, "digest", "ABCDEF")))));
        // Loading the frame also proves it is well-formed despite the & < > in authInfo.
        Xp cx = xp(s.fake.written.get(0));
        check("create name", "brand.ua".equals(cx.firstText("//domain:create/domain:name")));
        check("create period unit=y", "2".equals(cx.firstText("//domain:period[@unit=\"y\"]")));
        check("create hostObj x2", cx.count("//domain:ns/domain:hostObj") == 2);
        check("create contact type=admin", "C1".equals(cx.firstText("//domain:contact[@type=\"admin\"]")));
        check("create authInfo escaped round-trip",
                "Sup3r&Secret<>".equals(cx.firstText("//domain:authInfo/domain:pw")));
        check("create licence wrapper registry:create>license",
                "TM-12345".equals(cx.firstText("//e:extension/registry:create/registry:license")));
        check("create secDNS dsData keyTag",
                "12345".equals(cx.firstText("//secDNS:create/secDNS:dsData/secDNS:keyTag")));
        check("create secDNS maxSigLife", "604800".equals(cx.firstText("//secDNS:create/secDNS:maxSigLife")));
    }

    private static void inlineGlue() {
        System.out.println("domain:create/update with inline glue (hostAttr)");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().create("glue.ua", map(
                "years", 1,
                "registrant", "C1",
                "nameservers", list(
                        map("name", "ns1.glue.ua", "addresses", list("192.0.2.1", "2001:db8::1")),
                        map("name", "ns2.glue.ua", "addresses", list("192.0.2.2")))));
        Xp gx = xp(s.fake.written.get(0));
        check("glue hostAttr x2", gx.count("//domain:ns/domain:hostAttr") == 2);
        check("glue hostName", "ns1.glue.ua".equals(gx.firstText("//domain:hostAttr[1]/domain:hostName")));
        check("glue v4 addr tagged ip=v4",
                "192.0.2.1".equals(gx.firstText("//domain:hostAttr[1]/domain:hostAddr[@ip=\"v4\"]")));
        check("glue v6 addr tagged ip=v6",
                "2001:db8::1".equals(gx.firstText("//domain:hostAttr[1]/domain:hostAddr[@ip=\"v6\"]")));
        check("glue emits no hostObj", gx.count("//domain:ns/domain:hostObj") == 0);

        // A nameserver may be added to an existing domain with its glue, too.
        s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().update("glue.ua", map("add",
                map("ns", list(map("name", "ns3.glue.ua", "addresses", list("192.0.2.3"))))));
        Xp gux = xp(s.fake.written.get(0));
        check("glue on update add",
                "ns3.glue.ua".equals(gux.firstText("//domain:add/domain:ns/domain:hostAttr/domain:hostName")));

        // RFC 5731 makes domain:ns a choice, so a mixture is refused here rather than at the registry.
        String mixed = null;
        try {
            Session mix = makeClient(Arrays.asList(GREETING, ok()));
            mix.client.connect();
            mix.client.domain().create("mix.ua", map("nameservers",
                    list("ns1.mix.ua", map("name", "ns2.mix.ua", "addresses", list("192.0.2.9")))));
        } catch (ValidationException e) {
            mixed = e.getMessage();
        }
        check("mixed hostObj + hostAttr refused",
                mixed != null && mixed.contains("all names or all name-with-glue"));
    }

    private static void createWithoutAuthInfo() {
        System.out.println("domain create WITHOUT authInfo still emits the RFC-mandatory <authInfo><pw/>");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().create("noauth.ua", map(
                "years", 1, "registrant", "C1", "contacts", map("admin", "C1", "tech", "C2"),
                "nameservers", list("ns1.noauth.ua")));
        Xp nx = xp(s.fake.written.get(0));
        check("create without authInfo still emits <domain:authInfo>",
                nx.count("//domain:create/domain:authInfo") == 1);
        String noauthPw = nx.firstText("//domain:authInfo/domain:pw");
        check("create without authInfo emits an empty <domain:pw>", noauthPw == null || noauthPw.isEmpty());
    }

    private static void contactCreateWithoutEmail() {
        System.out.println("contact create WITHOUT email throws a clear client-side error (not an opaque server 2005)");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        boolean emailThrew = false;
        try {
            s.client.contact().create("C9", map("name", "Jane", "city", "Lviv", "cc", "UA")); // email omitted
        } catch (EppException e) {
            // Inside the SDK hierarchy the README promises: a web caller catching EppException returns its
            // documented JSON error instead of an uncaught argument exception and an HTTP 500.
            emailThrew = e instanceof ValidationException;
        }
        check("contact create without email throws ValidationException (an EppException)", emailThrew);
    }

    private static void domainRestore() {
        System.out.println("domain:update restore (rgp, no add/rem/chg)");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().restore("redeem.com.ua");
        Xp ux = xp(s.fake.written.get(0));
        check("restore rgp op=request", ux.count("//e:extension/rgp:update/rgp:restore[@op=\"request\"]") == 1);
        check("restore has no domain:chg", ux.count("//domain:chg") == 0);
        check("restore has no domain:add", ux.count("//domain:add") == 0);
    }

    private static void domainRenewDates() {
        System.out.println("domain:renew accepts an exDate timestamp for curExpDate");
        // The value that reaches a caller's hands is exDate, an xs:dateTime; the value the wire wants is
        // curExpDate, an xs:date. Passing the first straight to renew() is the obvious thing to write, and before
        // this it produced a 2105 whose message mentions neither element.
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok(), ok(), ok()));
        s.client.connect();
        s.client.domain().renew("example.com.ua", "2027-01-15", 1);
        check("a plain date is sent unchanged",
                "2027-01-15".equals(xp(s.fake.written.get(0)).firstText("//domain:curExpDate")));

        s.client.domain().renew("example.com.ua", "2027-01-15T09:15:00.0Z", 1);
        check("a full timestamp is reduced to its date",
                "2027-01-15".equals(xp(s.fake.written.get(1)).firstText("//domain:curExpDate")));

        s.client.domain().renew("example.com.ua", "2027-01-15T23:30:00.0Z", 1);
        check("and the date is the one the server wrote, never a local-timezone shift of it",
                "2027-01-15".equals(xp(s.fake.written.get(2)).firstText("//domain:curExpDate")));

        s.client.domain().renew("example.com.ua", "not-a-date", 1);
        check("an unrecognised value goes to the server unchanged",
                "not-a-date".equals(xp(s.fake.written.get(3)).firstText("//domain:curExpDate")));
    }

    private static void errorHandling() {
        System.out.println("error handling");
        Session s = makeClient(Arrays.asList(GREETING, ok(2303)));
        s.client.connect();
        boolean threw = false;
        try {
            s.client.domain().info("nope.com.ua");
        } catch (CommandException e) {
            threw = e.eppCode() == 2303;
        }
        check("2303 throws CommandException with eppCode", threw);

        s = makeClient(Arrays.asList(GREETING, ok(2303)));
        s.client.connect();
        s.client.throwOnFailure(false);
        Response resp = s.client.domain().info("nope.com.ua");
        check("throwOnFailure(false): returns Response", resp.code() == 2303 && !resp.isSuccess());
    }

    private static void domainUpdateSecdns() {
        System.out.println("domain:update secDNS (add / rem-all / maxSigLife)");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().update("dnssec.ua", map("secDNS", map(
                "add", map("dsData", list(map("keyTag", 1, "alg", 8, "digestType", 2, "digest", "AA"))),
                "remAll", Boolean.TRUE,
                "maxSigLife", 1209600)));
        Xp sx = xp(s.fake.written.get(0));
        check("secDNS:update rem all=true", "true".equals(sx.firstText("//secDNS:update/secDNS:rem/secDNS:all")));
        check("secDNS:update add dsData keyTag",
                "1".equals(sx.firstText("//secDNS:update/secDNS:add/secDNS:dsData/secDNS:keyTag")));
        check("secDNS:update chg maxSigLife",
                "1209600".equals(sx.firstText("//secDNS:update/secDNS:chg/secDNS:maxSigLife")));
    }

    private static final String POLL_RESP =
            "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
            + "<result code=\"1301\"><msg>ack to dequeue</msg></result>"
            + "<msgQ count=\"3\" id=\"12\"><qDate>2026-06-15T00:00:00Z</qDate><msg>Domain transferred</msg></msgQ>"
            + "<trID><svTRID>SRV-9</svTRID></trID></response></epp>";

    private static void pollMessages() {
        System.out.println("poll messageId/count + ack");
        Session s = makeClient(Arrays.asList(GREETING, POLL_RESP, ok()));
        s.client.connect();
        Response poll = s.client.poll().request();
        check("poll messageId", "12".equals(poll.messageId()));
        check("poll messageCount", poll.messageCount() == 3);
        s.client.poll().ack(poll.messageId());
        Xp ax = xp(s.fake.written.get(1));
        check("pollAck carries msgID", ax.count("//e:poll[@op=\"ack\"][@msgID=\"12\"]") == 1);

        System.out.println("poll panData - the outcome of an offline operation");
        // A deferred command reports back this way: you sent domain:create, got 1001 and an svTRID, and the answer
        // arrives later as a poll message. The result code 1301 means "here is a message", NOT "your operation
        // succeeded" - paResult is the only thing that says that, and reading the result code instead makes every
        // poll answer look like a success.
        String panResp = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1301\"><msg>ack to dequeue</msg></result>"
                + "<msgQ count=\"1\" id=\"11\"><qDate>1970-01-01T00:00:12Z</qDate><msg>Domain registered</msg></msgQ>"
                + "<resData><domain:panData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name paResult=\"1\">example.com.ua</domain:name>"
                + "<domain:paTRID><clTRID>my-create-1</clTRID><svTRID>SRV-19700101000000-1-00042</svTRID>"
                + "</domain:paTRID>"
                + "<domain:paDate>1970-01-01T00:00:12Z</domain:paDate>"
                + "</domain:panData></resData>"
                + "<trID><svTRID>SRV-9</svTRID></trID></response></epp>";
        s = makeClient(Arrays.asList(GREETING, panResp));
        s.client.connect();
        Map<String, Object> pan = s.client.poll().request().pendingActionData();
        check("panData object", "example.com.ua".equals(dig(pan, "object")));
        check("panData success from paResult", Boolean.TRUE.equals(dig(pan, "success")));
        // The svTRID of the ORIGINAL command - this is how a client knows WHICH of its pending operations the
        // message is about. Poll is a queue; it is not necessarily the most recent one.
        check("panData original svTRID", "SRV-19700101000000-1-00042".equals(dig(pan, "svTRID")));
        check("panData original clTRID", "my-create-1".equals(dig(pan, "clTRID")));
        check("panData paDate", "1970-01-01T00:00:12Z".equals(dig(pan, "date")));

        // paResult="0" is a REFUSAL, and the response code is still 1301.
        String panFail = panResp.replace("paResult=\"1\"", "paResult=\"0\"");
        s = makeClient(Arrays.asList(GREETING, panFail));
        s.client.connect();
        check("panData failure from paResult=0",
                Boolean.FALSE.equals(s.client.poll().request().pendingActionData().get("success")));

        // An ordinary notice has no panData at all: null, not an empty map pretending to be an outcome.
        s = makeClient(Arrays.asList(GREETING, POLL_RESP));
        s.client.connect();
        check("no panData on a plain notice", s.client.poll().request().pendingActionData() == null);

        // contact:panData must work too - the accessor matches by local name across every object namespace, so
        // binding to domain-1.0 would have returned null on a contact transfer.
        String panContact = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1301\"><msg>ack to dequeue</msg></result>"
                + "<msgQ count=\"1\" id=\"12\"><qDate>1970-01-01T00:00:12Z</qDate><msg>Contact transferred</msg>"
                + "</msgQ>"
                + "<resData><contact:panData xmlns:contact=\"urn:ietf:params:xml:ns:contact-1.0\">"
                + "<contact:id paResult=\"true\">CH-151</contact:id>"
                + "<contact:paTRID><clTRID>my-xfer-1</clTRID><svTRID>SRV-19700101000000-1-00043</svTRID>"
                + "</contact:paTRID>"
                + "<contact:paDate>1970-01-01T00:00:12Z</contact:paDate>"
                + "</contact:panData></resData>"
                + "<trID><svTRID>SRV-9</svTRID></trID></response></epp>";
        s = makeClient(Arrays.asList(GREETING, panContact));
        s.client.connect();
        Map<String, Object> cpan = s.client.poll().request().pendingActionData();
        check("contact panData id", "CH-151".equals(dig(cpan, "object")));
        check("paResult=\"true\" is also success", Boolean.TRUE.equals(dig(cpan, "success")));
    }

    private static void infoStatuses() {
        System.out.println("info statuses");
        String infoResp = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData><domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>example3.com.ua</domain:name><domain:status s=\"ok\"/>"
                + "<domain:status s=\"clientHold\"/>"
                + "<domain:exDate>2027-01-15T00:00:00+02:00</domain:exDate></domain:infData></resData>"
                + "<trID><svTRID>SRV-7</svTRID></trID></response></epp>";
        Session s = makeClient(Arrays.asList(GREETING, infoResp));
        s.client.connect();
        Response info = s.client.domain().info("example3.com.ua");
        check("statuses from @s", info.statuses().equals(Arrays.asList("ok", "clientHold")));
        check("value exDate", "2027-01-15T00:00:00+02:00".equals(info.value("exDate")));
    }

    private static void errorReasonsAndResultCode() {
        System.out.println("error reasons + ResultCode");
        String errResp = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"2306\"><msg>Policy error</msg><extValue><value>x.closed.ua</value>"
                + "<reason>Zone is closed for new registrations</reason></extValue></result>"
                + "<trID><svTRID>SRV-8</svTRID></trID></response></epp>";
        Session s = makeClient(Arrays.asList(GREETING, errResp));
        s.client.connect();
        s.client.throwOnFailure(false);
        Response r = s.client.domain().create("x.closed.ua", map("years", 1));
        check("ResultCode constant matches", r.code() == ResultCode.PARAMETER_VALUE_POLICY_ERROR);
        check("errorReasons parsed", r.errorReasons().equals(Arrays.asList("Zone is closed for new registrations")));
    }

    private static void contactCreatePostal() {
        System.out.println("contact create: postalInfo (int+loc) + disclose");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.contact().create("c1", map(
                "postalInfos", list(
                        map("type", "int", "name", "ACME", "street", list("1 St"), "city", "Kyiv", "cc", "UA"),
                        map("type", "loc", "name", ACME_LOCAL, "city", KYIV_LOCAL, "cc", "UA")),
                "email", "contact@example.com",
                "authInfo", "pw",
                "disclose", map("flag", Boolean.FALSE, "addr", list("int"),
                        "voice", Boolean.TRUE, "email", Boolean.TRUE)));
        Xp kx = xp(s.fake.written.get(0));
        check("contact 2 postalInfo blocks", kx.count("//contact:create/contact:postalInfo") == 2);
        check("contact postalInfo loc name",
                ACME_LOCAL.equals(kx.firstText("//contact:postalInfo[@type=\"loc\"]/contact:name")));
        check("contact disclose flag=0", kx.count("//contact:disclose[@flag=\"0\"]") == 1);
        check("contact disclose addr type=int", kx.count("//contact:disclose/contact:addr[@type=\"int\"]") == 1);
        check("contact disclose voice flag present", kx.count("//contact:disclose/contact:voice") == 1);
    }

    private static void contactUpdateOrg() {
        // Removing an organisation is expressed by an EMPTY element, and the difference between "empty" and
        // "absent" is the whole mechanism: an empty contact:org means take it away, no element at all means leave
        // it alone. Get that backwards in either direction and it fails silently - an omitted clear leaves a
        // former organisation in the public WHOIS, while a phantom clear wipes one that was never touched.
        System.out.println("contact update: an EMPTY org removes it, an ABSENT org says nothing");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.contact().update("c1", map("chg", map("postalInfo",
                map("type", "loc", "name", "Ivan Petrenko", "org", "", "city", "Kyiv", "cc", "UA"))));
        Xp kx = xp(s.fake.written.get(0));
        check("org emitted for a clear", kx.count("//contact:chg/contact:postalInfo/contact:org") == 1);
        check("and it is empty", "".equals(kx.firstText("//contact:chg/contact:postalInfo/contact:org")));

        s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.contact().update("c1", map("chg", map("postalInfo",
                map("type", "loc", "name", "Ivan Petrenko", "city", "Lviv", "cc", "UA"))));
        kx = xp(s.fake.written.get(0));
        check("no org element when the caller never mentioned it",
                kx.count("//contact:chg/contact:postalInfo/contact:org") == 0);

        // On a create there is nothing to remove, so an empty org is simply not an element.
        s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.contact().create("c1", map(
                "postalInfos", list(map("type", "int", "name", "ACME", "org", "", "city", "Kyiv", "cc", "UA")),
                "email", "contact@example.com", "authInfo", "pw"));
        kx = xp(s.fake.written.get(0));
        check("create never emits an empty org", kx.count("//contact:create/contact:postalInfo/contact:org") == 0);
    }

    private static void contactAutoId() {
        System.out.println("contact create: the reserved id asks the registry to mint the handle");
        String creData = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>Command completed successfully</msg></result>"
                + "<resData><contact:creData xmlns:contact=\"urn:ietf:params:xml:ns:contact-1.0\">"
                + "<contact:id>C0000042-EXAMPLE</contact:id><contact:crDate>2026-08-16T10:00:00.0Z</contact:crDate>"
                + "</contact:creData></resData><trID><svTRID>SRV-1</svTRID></trID></response></epp>";
        Session s = makeClient(Arrays.asList(GREETING, creData));
        s.client.connect();
        Response minted = s.client.contact().createAuto(map(
                "name", "ACME", "city", "Kyiv", "cc", "UA", "email", "contact@example.com"));
        Xp ax = xp(s.fake.written.get(0));
        check("reserved id sent verbatim", "autonic".equals(ax.firstText("//contact:create/contact:id")));
        check("reserved id constant", "autonic".equals(Contact.AUTO_ID));
        // The minted handle arrives in creData and nowhere else, so objectName() must read the id - not the
        // person's postal name, which also sits under a <name> element in a contact response.
        check("minted handle read back from creData", "C0000042-EXAMPLE".equals(minted.objectName()));
    }

    private static void contactUpdateChange() {
        System.out.println("contact update: chg postalInfo + disclose");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.contact().update("c1", map("chg", map(
                "postalInfo", map("type", "int", "name", "New Name", "city", "Lviv", "cc", "UA"),
                "email", "new-contact@example.com",
                "disclose", map("flag", Boolean.TRUE, "email", Boolean.TRUE))));
        Xp ucx = xp(s.fake.written.get(0));
        check("contact chg postalInfo name",
                "New Name".equals(ucx.firstText("//contact:chg/contact:postalInfo/contact:name")));
        check("contact chg disclose flag=1", ucx.count("//contact:chg/contact:disclose[@flag=\"1\"]") == 1);
    }

    private static void contactUpdateStatuses() {
        System.out.println("contact update: multiple statuses collapse into a single add/rem block");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.contact().update("c1", map(
                "addStatuses", list("clientUpdateProhibited", "clientDeleteProhibited"),
                "remStatuses", list("clientTransferProhibited", "clientUpdateProhibited")));
        Xp mcx = xp(s.fake.written.get(0));
        check("contact update: single add wrapper", mcx.count("//contact:add") == 1);
        check("contact update: both statuses inside add", mcx.count("//contact:add/contact:status") == 2);
        check("contact update: single rem wrapper", mcx.count("//contact:rem") == 1);
        check("contact update: both statuses inside rem", mcx.count("//contact:rem/contact:status") == 2);
    }

    private static void emptySecdnsOnCreate() {
        System.out.println("domain create: empty secDNS array emits no childless secDNS:create");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().create("nosec.ua", map(
                "years", 1, "registrant", "REG1", "contacts", map("admin", "A1", "tech", "T1"),
                "nameservers", list("ns1.example.net"), "secDNS", map()));
        Xp nsx = xp(s.fake.written.get(0));
        check("empty secDNS -> no secDNS:create element", nsx.count("//secDNS:create") == 0);
    }

    private static void configGuards() {
        System.out.println("config guards (fail fast, no network)");
        FakeTransport fakePw = new FakeTransport();
        Client badPw = new Client(Config.builder("h", "SRV-1", "").build(), fakePw);
        fakePw.queue.add(GREETING);
        badPw.connect();
        boolean threwPw = false;
        try {
            badPw.login();
        } catch (ConfigException e) {
            threwPw = true;
        }
        check("empty password -> ConfigException", threwPw);
        check("no login frame sent", fakePw.written.isEmpty());

        // Java refuses an empty host in Config.build() rather than in connect(), so both are inside the try: the
        // assertion is about the exception a caller sees, which is the same ConfigException either way.
        boolean threwHost = false;
        try {
            Client badHost = new Client(Config.builder("", "x", "y").build(), new FakeTransport());
            badHost.connect();
        } catch (ConfigException e) {
            threwHost = true;
        }
        check("empty host -> ConfigException", threwHost);
    }

    private static void logRedaction() {
        System.out.println("log redaction");
        String masked = Client.redact(
                "<pw>topsecret</pw><domain:pw>auth123</domain:pw><domain:name>keep.ua</domain:name>");
        check("pw masked", !masked.contains("topsecret") && !masked.contains("auth123"));
        check("non-secret kept", masked.contains("keep.ua"));
    }

    private static void responseAccessors() {
        System.out.println("response accessors: balance / price / licence / rgp / lang");

        String balanceXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg lang=\"uk\">" + OK_MESSAGE_LOCAL + "</msg></result>"
                + "<resData><balance:infData xmlns:balance=\"http://registry.example/epp/balance-1.0\">"
                + "<balance:creditLimit>0.00</balance:creditLimit><balance:balance>1234.56</balance:balance>"
                + "<balance:availableCredit>1234.56</balance:availableCredit></balance:infData></resData>"
                + "<trID><svTRID>SRV-2</svTRID></trID></response></epp>";
        Response bal = Response.fromXml(balanceXml);
        Map<String, String> b = bal.balance();
        check("balance() creditLimit", b != null && "0.00".equals(b.get("creditLimit")));
        check("balance() balance", b != null && "1234.56".equals(b.get("balance")));
        check("balance() availableCredit", b != null && "1234.56".equals(b.get("availableCredit")));
        check("messageLang() reads uk", "uk".equals(bal.messageLang()));

        String infoXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg lang=\"en\">Command completed successfully</msg></result>"
                + "<resData><domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>example.com.ua</domain:name><domain:status s=\"ok\"/></domain:infData></resData>"
                + "<extension>"
                + "<registry:infData xmlns:registry=\"http://registry.example/epp/registry-1.0\">"
                + "<registry:license>TM-123</registry:license></registry:infData>"
                + "<registry:priceData xmlns:registry=\"http://registry.example/epp/registry-1.0\" channel=\"7\">"
                + "<registry:price operation=\"renewal\" currency=\"UAH\">180.00</registry:price>"
                + "<registry:price operation=\"restore\" currency=\"UAH\">1200.00</registry:price>"
                + "</registry:priceData>"
                + "<registry:registrar xmlns:registry=\"http://registry.example/epp/registry-1.0\">EXAMPLE"
                + "</registry:registrar>"
                + "<rgp:infData xmlns:rgp=\"urn:ietf:params:xml:ns:rgp-1.0\">"
                + "<rgp:rgpStatus s=\"redemptionPeriod\"/></rgp:infData>"
                + "</extension><trID><svTRID>SRV-3</svTRID></trID></response></epp>";
        Response info = Response.fromXml(infoXml);
        check("license() reads the .ua licence", "TM-123".equals(info.license()));
        Map<String, Map<String, String>> prices = info.prices();
        check("prices() renewal value", "180.00".equals(dig(prices, "renewal", "value")));
        check("prices() renewal currency", "UAH".equals(dig(prices, "renewal", "currency")));
        check("prices() restore value", "1200.00".equals(dig(prices, "restore", "value")));
        // The prices belong to a channel; without its id they cannot be matched to a catalogue row, and a domain
        // kept on an older channel prices differently from a new registration in the same zone.
        check("priceChannel() reads the channel the prices belong to", "7".equals(info.priceChannel()));
        // sponsor() is the account; this is the handle the registry itself publishes as the registrar.
        check("registrarOfRecord() reads the registry-side handle", "EXAMPLE".equals(info.registrarOfRecord()));
        check("rgpStatus() reads redemptionPeriod", info.rgpStatus().equals(Arrays.asList("redemptionPeriod")));
        check("balance() null on a non-balance response", info.balance() == null);

        Response plainInfo = Response.fromXml(
                "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result><resData>"
                + "<domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>plain.com.ua</domain:name></domain:infData></resData>"
                + "<trID><svTRID>SRV-4</svTRID></trID></response></epp>");
        check("priceChannel() is null when no price data came back", plainInfo.priceChannel() == null);
        check("registrarOfRecord() is null when the registry sent none", plainInfo.registrarOfRecord() == null);

        String trnXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1001\"><msg>ok</msg></result>"
                + "<resData><domain:trnData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>example.com.ua</domain:name><domain:trStatus>pending</domain:trStatus>"
                + "</domain:trnData></resData>"
                + "<trID><svTRID>SRV-4</svTRID></trID></response></epp>";
        check("transferStatus() reads pending", "pending".equals(Response.fromXml(trnXml).transferStatus()));

        System.out.println("response accessors: secDNS read-back");
        String secXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData><domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>signed.com.ua</domain:name></domain:infData></resData>"
                + "<extension><secDNS:infData xmlns:secDNS=\"urn:ietf:params:xml:ns:secDNS-1.1\">"
                + "<secDNS:dsData><secDNS:keyTag>12345</secDNS:keyTag><secDNS:alg>13</secDNS:alg>"
                + "<secDNS:digestType>2</secDNS:digestType><secDNS:digest>ABCDEF0123</secDNS:digest>"
                + "</secDNS:dsData>"
                + "<secDNS:keyData><secDNS:flags>257</secDNS:flags><secDNS:protocol>3</secDNS:protocol>"
                + "<secDNS:alg>13</secDNS:alg><secDNS:pubKey>AwEAAb</secDNS:pubKey></secDNS:keyData>"
                + "</secDNS:infData></extension><trID><svTRID>SRV-5</svTRID></trID></response></epp>";
        Response sec = Response.fromXml(secXml);
        List<Map<String, Object>> dsr = sec.dsRecords();
        check("dsRecords() count", dsr.size() == 1);
        check("dsRecords() keyTag (int)", !dsr.isEmpty() && Integer.valueOf(12345).equals(dsr.get(0).get("keyTag")));
        check("dsRecords() digestType (int)",
                !dsr.isEmpty() && Integer.valueOf(2).equals(dsr.get(0).get("digestType")));
        check("dsRecords() digest", !dsr.isEmpty() && "ABCDEF0123".equals(dsr.get(0).get("digest")));
        List<Map<String, Object>> kr = sec.keyRecords();
        check("keyRecords() flags (int)", !kr.isEmpty() && Integer.valueOf(257).equals(kr.get(0).get("flags")));
        check("keyRecords() pubKey", !kr.isEmpty() && "AwEAAb".equals(kr.get(0).get("pubKey")));
        check("isSigned() true when signed", sec.isSigned());
        check("isSigned() false on a non-DNSSEC info", !info.isSigned());
    }

    private static void domainInfoHostsSub() {
        System.out.println("domain info hosts=sub");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok()));
        s.client.connect();
        s.client.login();
        s.client.domain().info("example.com.ua", null, "sub");
        Xp hsFrame = xp(s.fake.written.get(s.fake.written.size() - 1));
        NodeList hostsAttr = hsFrame.query("//domain:info/domain:name/@hosts");
        check("info hosts=sub attribute",
                hostsAttr.getLength() == 1 && "sub".equals(hostsAttr.item(0).getNodeValue()));
    }

    private static void feeFrames() {
        System.out.println("RFC 8748 fee: check request + create agreement (frame building)");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().check(Arrays.asList("prem.com.ua"), map("create", 1, "renew", 2), null);
        Xp fcx = xp(s.fake.written.get(0));
        check("fee:check present", fcx.count("//e:extension/fee:check") == 1);
        check("fee:command create", fcx.count("//fee:check/fee:command[@name=\"create\"]") == 1);
        check("fee:period years for renew",
                "2".equals(fcx.firstText("//fee:command[@name=\"renew\"]/fee:period[@unit=\"y\"]")));

        s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().create("prem.com.ua", map(
                "years", 1, "registrant", "C1", "contacts", map("admin", "C1", "tech", "C2"),
                "nameservers", list("ns1.example.net"), "fee", map("amount", "500.00", "currency", "UAH")));
        Xp fcr = xp(s.fake.written.get(0));
        check("fee:create agreement amount", "500.00".equals(fcr.firstText("//e:extension/fee:create/fee:fee")));
        check("fee:create agreement currency", "UAH".equals(fcr.firstText("//fee:create/fee:currency")));
    }

    private static void feeResponses() {
        System.out.println("RFC 8748 fee: response fees() + chargedFee() (response parsing)");
        String feeChkXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData><domain:chkData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:cd><domain:name avail=\"1\">prem.com.ua</domain:name></domain:cd></domain:chkData>"
                + "</resData>"
                + "<extension><fee:chkData xmlns:fee=\"urn:ietf:params:xml:ns:epp:fee-1.0\">"
                + "<fee:cd><fee:objID>prem.com.ua</fee:objID>"
                + "<fee:command name=\"create\"><fee:period unit=\"y\">1</fee:period><fee:fee>500.00</fee:fee>"
                + "</fee:command>"
                + "<fee:command name=\"renew\"><fee:period unit=\"y\">1</fee:period><fee:fee>450.00</fee:fee>"
                + "</fee:command>"
                + "</fee:cd></fee:chkData></extension><trID><svTRID>SRV-F1</svTRID></trID></response></epp>";
        Response feeResp = Response.fromXml(feeChkXml);
        Map<String, Object> fees = feeResp.fees();
        check("fees() has the checked name", fees.containsKey("prem.com.ua"));
        check("fees() create price", "500.00".equals(dig(fees, "prem.com.ua", "commands", "create", "fee")));
        check("fees() renew price", "450.00".equals(dig(fees, "prem.com.ua", "commands", "renew", "fee")));

        String feeCreXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData><domain:creData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>prem.com.ua</domain:name>"
                + "<domain:crDate>2026-06-15T00:00:00Z</domain:crDate>"
                + "<domain:exDate>2027-06-15T00:00:00Z</domain:exDate></domain:creData></resData>"
                + "<extension><fee:creData xmlns:fee=\"urn:ietf:params:xml:ns:epp:fee-1.0\">"
                + "<fee:currency>UAH</fee:currency><fee:fee>500.00</fee:fee></fee:creData></extension>"
                + "<trID><svTRID>SRV-F2</svTRID></trID></response></epp>";
        Map<String, String> charged = Response.fromXml(feeCreXml).chargedFee();
        check("chargedFee() currency", charged != null && "UAH".equals(charged.get("currency")));
        check("chargedFee() amount", charged != null && "500.00".equals(charged.get("fee")));
    }

    private static void frameIdempotence() {
        System.out.println("frame: toXml() is idempotent (exactly one clTRID, always last)");
        Frame idem = Frame.command("T-1");
        idem.ns(idem.verb("check"), Namespaces.DOMAIN, "domain:check");
        String firstXml = idem.toXml();
        String secondXml = idem.toXml();
        check("a second toXml() returns the same frame", firstXml.equals(secondXml));
        Xp ix = xp(secondXml);
        check("exactly one clTRID", ix.count("//e:command/e:clTRID") == 1);
        NodeList last = ix.query("//e:command/*[last()]");
        check("clTRID is the last child of <command>",
                last.getLength() == 1 && "clTRID".equals(last.item(0).getLocalName()));
    }

    private static void loginPasswords() {
        System.out.println("login: a password the <pw> schema type cannot carry fails fast");
        // GREETING advertises no loginSec extension, so 18 characters cannot be carried to this server.
        Session s = makeClient(Arrays.asList(GREETING));
        s.client.connect();
        boolean longThrew = false;
        try {
            s.client.login("an-18-char-passwd!"); // 18 chars: pwType allows 6-16
        } catch (ConfigException e) {
            longThrew = true;
        }
        check("18-char newPW -> ConfigException", longThrew);
        check("no login frame sent for an unusable password", s.fake.written.isEmpty());

        System.out.println("login: RFC 8807 carries a password longer than the <pw> element allows");
        // Same greeting plus the Login Security extension. The long password now travels in loginSec:pw, and <pw>
        // carries the sentinel that points at it.
        Session ls = makeClient(Arrays.asList(GREETING_LOGINSEC, ok()), repeat("p", 40));
        ls.client.login();
        String lsXml = ls.fake.written.isEmpty() ? "" : ls.fake.written.get(0);
        Xp lx = xp(lsXml);
        check("<pw> carries the sentinel",
                Namespaces.LOGINSEC_SENTINEL.equals(trim(lx.firstText("//e:login/e:pw"))));
        lx.register("ls", Namespaces.LOGINSEC);
        check("the real password is in <loginSec:pw>",
                repeat("p", 40).equals(trim(lx.firstText("//ls:loginSec/ls:pw"))));
        check("the extension is announced in <svcs>",
                lsXml.contains("<extURI>" + Namespaces.LOGINSEC + "</extURI>"));
        check("a userAgent identifies the client", lx.count("//ls:userAgent/ls:app") == 1);

        System.out.println("login: a short password takes part in the extension without travelling in it");
        // Participation and relocation are separate decisions. The block goes out so the server will return its
        // security events - it sends those only to a client that sent the block - while the password itself stays
        // in <pw>, because it fits there and the sentinel would point at nothing.
        Session shortSession = makeClient(Arrays.asList(GREETING_LOGINSEC, ok()));
        shortSession.client.login();
        String shortXml = shortSession.fake.written.isEmpty() ? "" : shortSession.fake.written.get(0);
        Xp sx = xp(shortXml);
        sx.register("ls", Namespaces.LOGINSEC);
        check("<pw> carries the password itself", shortXml.contains("<pw>secret</pw>"));
        check("the block is sent so the server will answer with its events", sx.count("//ls:loginSec") == 1);
        check("but the password is NOT relocated into it", sx.count("//ls:loginSec/ls:pw") == 0);
        check("the userAgent names app, tech and os", sx.count("//ls:userAgent/ls:app") == 1
                && sx.count("//ls:userAgent/ls:tech") == 1
                && sx.count("//ls:userAgent/ls:os") == 1);

        System.out.println("login: loginSecurity=false stays off the extension entirely");
        Session off = makeClient(Arrays.asList(GREETING_LOGINSEC, ok()),
                config("secret").loginSecurity(false).build());
        off.client.login();
        String offXml = off.fake.written.isEmpty() ? "" : off.fake.written.get(0);
        check("no loginSec block when the caller opted out", !offXml.contains("loginSec:loginSec"));
        check("<pw> still carries the password itself", offXml.contains("<pw>secret</pw>"));

        System.out.println("login: the server's security events are readable");
        String eventReply = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>Command completed successfully</msg></result>"
                + "<extension><loginSec:loginSecData xmlns:loginSec=\"" + Namespaces.LOGINSEC + "\">"
                + "<loginSec:event type=\"certificate\" level=\"warning\" exDate=\"2026-09-15T00:00:00Z\">"
                + "Your client certificate expires in 30 day(s).</loginSec:event>"
                + "<loginSec:event type=\"cipher\" name=\"AES128-SHA\" level=\"warning\">Weak cipher suite."
                + "</loginSec:event>"
                + "</loginSec:loginSecData></extension>"
                + "<trID><svTRID>SRV-1</svTRID></trID></response></epp>";
        Session ev = makeClient(Arrays.asList(GREETING_LOGINSEC, eventReply));
        List<Map<String, String>> events = ev.client.login().securityEvents();
        check("both events are read", events.size() == 2);
        check("the certificate event keeps its expiry date",
                "2026-09-15T00:00:00Z".equals(dig(events, Integer.valueOf(0), "exDate")));
        check("the certificate event keeps its level", "warning".equals(dig(events, Integer.valueOf(0), "level")));
        Object firstText = dig(events, Integer.valueOf(0), "text");
        check("the event text is the human sentence",
                firstText != null && firstText.toString().contains("expires in 30 day(s)"));
        check("the cipher event keeps the suite name",
                "AES128-SHA".equals(dig(events, Integer.valueOf(1), "name")));
        check("a healthy login reports no events", shortSession.client.greeting().securityEvents().isEmpty());

        // The same guard covers the configured password itself - no network is touched.
        Client shortPw = new Client(Config.builder("h", "SRV-1", "short").build(), new FakeTransport());
        boolean shortThrew = false;
        try {
            shortPw.login();
        } catch (ConfigException e) {
            shortThrew = true;
        }
        check("5-char Config::$password -> ConfigException", shortThrew);
    }

    private static void connectFirstFrame() {
        System.out.println("connect: the first frame must be the <greeting>");
        FakeTransport fakeNg = new FakeTransport();
        Client notGreeting = new Client(Config.builder("h", "SRV-1", "secret").build(), fakeNg);
        fakeNg.queue.add(ok(2500)); // a <response>, e.g. left over from a half-open session
        boolean ngThrew = false;
        try {
            notGreeting.connect();
        } catch (ConnectionException e) {
            // Storing it silently downgraded login() to the DEFAULT service list.
            ngThrew = e.getMessage() != null && e.getMessage().contains("not an EPP <greeting>");
        }
        check("a <response> as the first frame -> ConnectionException", ngThrew);
        check("greeting not remembered after the failure", notGreeting.greeting() == null);
    }

    private static void hostRename() {
        System.out.println("host:update cannot rename (this registry ignores host:chg)");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        boolean renameThrew = false;
        try {
            s.client.host().update("ns1.example.net",
                    map("addAddresses", list("192.0.2.9"), "newName", "ns2.example.net"));
        } catch (ValidationException e) {
            renameThrew = true;
        }
        check("newName -> ValidationException instead of a discarded host:chg", renameThrew);
        check("no rename frame sent", s.fake.written.isEmpty());
    }

    private static void emptySecdnsOnUpdate() {
        System.out.println("domain:update with an empty secDNS array emits no childless secDNS:update");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        s.client.domain().update("nosec.ua", map("secDNS", map(), "chg", map("registrant", "C9")));
        Xp esx = xp(s.fake.written.get(0));
        check("empty secDNS -> no secDNS:update element", esx.count("//secDNS:update") == 0);
        check("the rest of the update still went out",
                "C9".equals(esx.firstText("//domain:chg/domain:registrant")));
    }

    private static void redactionWithAttributes() {
        System.out.println("log redaction covers a pw element that carries attributes");
        String maskedAttr = Client.redact("<domain:pw roid=\"D1-EXAMPLE\">auth123</domain:pw>");
        check("pw with attributes masked", !maskedAttr.contains("auth123"));
    }

    private static void feeMultiPeriod() {
        System.out.println("fee: one operation can be priced at several periods in a single command");
        // A price table is one round trip, not five. The registry prices every fee:command separately.
        Session f = makeClient(Arrays.asList(GREETING, ok(), ok()));
        f.client.connect();
        f.client.domain().check(Arrays.asList("example1.com.ua"),
                map("create", list(1, 2, 5), "renew", 1), "UAH");
        Xp fx = xp(f.fake.written.get(0));
        check("every period becomes its own fee:command", fx.count("//fee:check/fee:command") == 4);
        check("three of them are the same operation", fx.count("//fee:command[@name=\"create\"]") == 3);
        check("and the periods keep the order asked",
                fx.texts("//fee:command/fee:period").equals(Arrays.asList("1", "2", "5", "1")));
        check("a named currency is carried", "UAH".equals(fx.firstText("//fee:check/fee:currency")));
        boolean capThrew = false;
        try {
            List<Object> periods = new ArrayList<Object>();
            for (int i = 1; i <= 21; i++) {
                periods.add(Integer.valueOf(i));
            }
            f.client.domain().check(Arrays.asList("example1.com.ua"), map("create", periods), null);
        } catch (ValidationException e) {
            capThrew = true;
        }
        // The registry refuses a 21st entry; refusing locally names the problem instead of spending a call.
        check("a query past the registry cap is refused before it is sent", capThrew);

        String feeReply = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result><resData>"
                + "<domain:chkData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:cd><domain:name avail=\"1\">example1.com.ua</domain:name></domain:cd></domain:chkData>"
                + "</resData>"
                + "<extension><fee:chkData xmlns:fee=\"urn:ietf:params:xml:ns:epp:fee-1.0\">"
                + "<fee:currency>UAH</fee:currency>"
                + "<fee:cd avail=\"1\"><fee:objID>example1.com.ua</fee:objID>"
                + "<fee:command name=\"create\"><fee:period unit=\"y\">1</fee:period><fee:fee>100.00</fee:fee>"
                + "</fee:command>"
                + "<fee:command name=\"create\"><fee:period unit=\"y\">2</fee:period><fee:fee>190.00</fee:fee>"
                + "</fee:command>"
                + "<fee:command name=\"create\"><fee:period unit=\"y\">5</fee:period><fee:fee>450.00</fee:fee>"
                + "</fee:command>"
                + "<fee:command name=\"renew\"><fee:period unit=\"y\">1</fee:period><fee:fee>90.00</fee:fee>"
                + "</fee:command>"
                + "</fee:cd></fee:chkData></extension><trID><svTRID>X</svTRID></trID></response></epp>";
        Response fr = Response.fromXml(feeReply);
        // Keyed by operation alone, three create quotes would collapse to one.
        check("every quote survives the parse", size(dig(fr.fees(), "example1.com.ua", "periods")) == 4);
        check("feeFor() reads one period exactly", "450.00".equals(fr.feeFor("example1.com.ua", "create", 5)));
        check("and a period nobody asked for is null", fr.feeFor("example1.com.ua", "create", 7) == null);
        check("the commands map still answers for the first period",
                "100.00".equals(dig(fr.fees(), "example1.com.ua", "commands", "create", "fee")));
    }

    private static String refuse(int code, String msg) {
        return "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"" + code + "\"><msg>" + msg + "</msg></result>"
                + "<trID><svTRID>X</svTRID></trID></response></epp>";
    }

    private static EppException loginError(int code) {
        Session s = makeClient(Arrays.asList(GREETING, refuse(code, "refused")));
        s.client.connect();
        try {
            s.client.login();
        } catch (EppException e) {
            return e;
        }
        throw new RuntimeException("login did not fail");
    }

    private static void loginErrorCodes() {
        System.out.println("login: only 2200 means the credentials are wrong");
        // A server refuses <login> for several reasons, and they need opposite responses. Calling them all an
        // authentication failure sends the reader to rotate a password that was never the problem.
        check("2200 is an AuthenticationException", loginError(2200) instanceof AuthenticationException);
        // The session cap: the answer is to reconnect, not to change the password.
        check("2502 (session limit) is a SessionException", loginError(2502) instanceof SessionException);
        check("2501 (server closing) is a SessionException", loginError(2501) instanceof SessionException);
        check("2307 is a plain CommandException, not an auth failure",
                !(loginError(2307) instanceof AuthenticationException));
    }

    private static CommandException errFor(int code) {
        String xml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"" + code + "\"><msg>refused</msg>"
                + "<extValue><value>"
                + "<domain:name xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">taken.com.ua</domain:name>"
                + "</value><reason lang=\"en\">Already registered</reason></extValue></result>"
                + "<trID><svTRID>X</svTRID></trID></response></epp>";
        Session s = makeClient(Arrays.asList(GREETING, xml));
        s.client.connect();
        try {
            s.client.domain().check(Arrays.asList("taken.com.ua"));
        } catch (CommandException e) {
            return e;
        }
        throw new RuntimeException("no exception");
    }

    private static void errorClasses() {
        System.out.println("errors: a class exists where the right next step differs");
        check("2104 is InsufficientFundsException", errFor(2104) instanceof InsufficientFundsException);
        check("2202 is AuthorizationException", errFor(2202) instanceof AuthorizationException);
        check("2302 is ObjectExistsException", errFor(2302) instanceof ObjectExistsException);
        check("2303 is ObjectDoesNotExistException", errFor(2303) instanceof ObjectDoesNotExistException);
        check("2305 is ObjectStatusException", errFor(2305) instanceof ObjectStatusException);
        check("2308 is PolicyException", errFor(2308) instanceof PolicyException);
        check("2502 is SessionException", errFor(2502) instanceof SessionException);
        check("2005 stays a plain CommandException", errFor(2005).getClass() == CommandException.class);
        // Retrying a 2302 cannot make the name free; retrying a 2104 cannot pay for it.
        check("only the transient ones are retryable", errFor(2400).isRetryable() && errFor(2502).isRetryable()
                && !errFor(2302).isRetryable() && !errFor(2104).isRetryable());
        check("the message names WHICH object was refused",
                errFor(2302).getMessage().endsWith("('taken.com.ua')"));
        check("subject() returns it too", "taken.com.ua".equals(errFor(2302).subject()));
        check("reasons() carries the extra detail", errFor(2302).reasons().contains("Already registered"));
    }

    private static void secdnsDsWithKey() {
        System.out.println("secDNS: a DS record can carry the DNSKEY it was computed from");
        // RFC 5910 allows the nesting so a registry can verify the digest against the key. The element ORDER
        // inside dsData is fixed by the schema, so the assertion is on structure, not just presence.
        String pubKey = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYnKCkqKywtLi8wMTIzNDU2Nzg5Ojs8PT4/QA==";
        Session k = makeClient(Arrays.asList(GREETING, ok(), ok()));
        k.client.connect();
        k.client.domain().createBuilder("example3.com.ua").years(1).registrant("C1")
                .dsRecordWithKey(12345, 8, 2, repeat("d", 64), 257, 3, 8, pubKey).send();
        Xp kx = xp(k.fake.written.get(0));
        check("the keyData is INSIDE the dsData, not beside it",
                kx.count("//secDNS:dsData/secDNS:keyData") == 1);
        check("and the DS fields are still there", "12345".equals(kx.firstText("//secDNS:dsData/secDNS:keyTag")));
        check("with the public key nested under it",
                pubKey.equals(kx.firstText("//secDNS:dsData/secDNS:keyData/secDNS:pubKey")));
        // A standalone keyData must still land beside the DS records, not inside one.
        k.client.domain().create("y.com.ua", map("secDNS", map("keyData",
                list(map("flags", 257, "protocol", 3, "alg", 8, "pubKey", pubKey)))));
        Xp ky = xp(k.fake.written.get(1));
        check("a standalone keyData stays a sibling", ky.count("//secDNS:create/secDNS:keyData") == 1
                && ky.count("//secDNS:dsData") == 0);
    }

    private static void authInfoClearing() {
        System.out.println("authInfo: clearing is not the same as emptying");
        // After a leak this is the only operation that helps. An empty <pw/> stores the empty string, which the
        // holder can still present - the domain stays exactly as movable as it was.
        Session a = makeClient(Arrays.asList(GREETING, ok(), ok(), ok()));
        a.client.connect();
        a.client.domain().updateBuilder("example3.com.ua").clearAuthInfo().send();
        check("clearAuthInfo() emits <domain:null/>", a.fake.written.get(0).contains("<domain:null/>"));
        check("and no <pw> element at all", !a.fake.written.get(0).contains("<domain:pw>"));
        a.client.domain().update("example3.com.ua", map("chg", map("authInfo", "N3w-Pw")));
        check("an ordinary change still emits <pw>",
                a.fake.written.get(1).contains("<domain:pw>N3w-Pw</domain:pw>"));
        boolean bothThrew = false;
        try {
            a.client.domain().update("example3.com.ua",
                    map("chg", map("authInfo", "a", "clearAuthInfo", Boolean.TRUE)));
        } catch (ValidationException e) {
            bothThrew = true;
        }
        // The schema has one choice: a password, or nothing. Half-applying either would be worse.
        check("setting and clearing at once is refused, not half-applied", bothThrew);
        // RFC 5733 has no nullable form for a contact, so the SDK must not offer one.
        boolean hasClearAuthInfo = true;
        try {
            ContactUpdateBuilder.class.getMethod("clearAuthInfo");
        } catch (NoSuchMethodException e) {
            hasClearAuthInfo = false;
        }
        check("contact:update has no clearAuthInfo", !hasClearAuthInfo);
    }

    private static String notice(String id, String text) {
        return "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1301\"><msg>Command completed successfully; ack to dequeue</msg></result>"
                + "<msgQ count=\"2\" id=\"" + id + "\"><qDate>2026-08-16T09:00:00Z</qDate>"
                + "<msg>" + text + "</msg></msgQ>"
                + "<trID><svTRID>SRV-1</svTRID></trID></response></epp>";
    }

    private static void pollDrain() {
        System.out.println("poll drain: a notice is acknowledged only after it has been handled");
        // An ack DELETES the notice at the registry. A loop that acks first and processes second loses every
        // notice whose processing fails, with nothing left to retry from.
        String emptyQueue = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1300\"><msg>Command completed successfully; no messages</msg></result>"
                + "<trID><svTRID>SRV-1</svTRID></trID></response></epp>";

        Session p = makeClient(Arrays.asList(GREETING, notice("11", "first"), ok(),
                notice("12", "second"), ok(), emptyQueue));
        p.client.connect();
        final List<String> seen = new ArrayList<String>();
        int count = p.client.poll().drain(new Consumer<Response>() {
            @Override
            public void accept(Response n) {
                seen.add(n.queueMessage());
            }
        });
        check("drain returns how many notices were handled", count == 2);
        check("and hands the NOTICE text to the callback, not the result banner",
                seen.equals(Arrays.asList("first", "second")));
        List<String> acked = new ArrayList<String>();
        Pattern msgId = Pattern.compile("msgID=\"(\\d+)\"");
        for (String sent : p.fake.written) {
            Matcher m = msgId.matcher(sent);
            if (m.find()) {
                acked.add(m.group(1));
            }
        }
        check("each notice is acked exactly once, in order", acked.equals(Arrays.asList("11", "12")));
        check("it stops on the empty queue rather than looping", p.fake.written.size() == 5);

        // The property that matters: a failing handler must NOT destroy the notice.
        Session f = makeClient(Arrays.asList(GREETING, notice("21", "boom"), ok(), emptyQueue));
        f.client.connect();
        boolean threw = false;
        try {
            f.client.poll().drain(new Consumer<Response>() {
                @Override
                public void accept(Response n) {
                    throw new RuntimeException("handler failed");
                }
            });
        } catch (RuntimeException e) {
            threw = "handler failed".equals(e.getMessage());
        }
        check("a failing handler surfaces its own exception", threw);
        boolean ackedAfterFailure = false;
        for (String sent : f.fake.written) {
            if (sent.contains("msgID=")) {
                ackedAfterFailure = true;
            }
        }
        check("and the notice is NOT acked, so nothing is lost", !ackedAfterFailure);

        // A queue that fills faster than it drains would otherwise never let the call return.
        Session l = makeClient(Arrays.asList(GREETING, notice("31", "a"), ok(), notice("32", "b"), ok(),
                notice("33", "c"), ok()));
        l.client.connect();
        int limited = l.client.poll().drain(new Consumer<Response>() {
            @Override
            public void accept(Response n) {
            }
        }, 2);
        check("a limit stops the drain early", limited == 2);
    }

    /** The first frame a call writes, with clTRID stripped - it is unique per command by construction. */
    private static String frameOf(Consumer<Client> call) {
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok(), ok()));
        s.client.connect();
        call.accept(s.client);
        String xml = s.fake.written.isEmpty() ? "" : s.fake.written.get(0);
        return xml.replaceAll("<clTRID>[^<]*</clTRID>", "");
    }

    private static void sameFrame(String label, Consumer<Client> viaBuilder, Consumer<Client> viaArray) {
        check(label, frameOf(viaBuilder).equals(frameOf(viaArray)));
    }

    private static void builderParity() {
        System.out.println("builders: the fluent form and the array form are the same command");
        // The whole design rests on send() being a thin facade over the ordinary method. Proved by comparing the
        // FRAMES, not the option maps: an equal map could still be assembled into a different frame, and it is the
        // frame the registry sees.
        sameFrame("domain:create built step by step matches the array call exactly",
                c -> c.domain().createBuilder("example3.com.ua")
                        .years(2).registrant("acme-01")
                        .adminContact("acme-01").techContact("acme-ns1").techContact("acme-ns2")
                        .nameserver("ns1.acme.example").nameserver("ns2.acme.example")
                        .authInfo("D0main-Pw").license("TM-1")
                        .dsRecord(12345, 8, 2, repeat("AB", 32)).maxSigLife(604800)
                        .maxFee("180.00", "UAH").send(),
                c -> c.domain().create("example3.com.ua", map(
                        "years", 2, "registrant", "acme-01",
                        "contacts", map("admin", list("acme-01"), "tech", list("acme-ns1", "acme-ns2")),
                        "nameservers", list("ns1.acme.example", "ns2.acme.example"),
                        "authInfo", "D0main-Pw", "license", "TM-1",
                        "secDNS", map(
                                "dsData", list(map("keyTag", 12345, "alg", 8, "digestType", 2,
                                        "digest", repeat("AB", 32))),
                                "maxSigLife", 604800),
                        "fee", map("amount", "180.00", "currency", "UAH"))));
        sameFrame("domain:create with inline glue matches the array call exactly",
                c -> c.domain().createBuilder("glue.com.ua")
                        .years(1).registrant("acme-01")
                        .nameserverWithGlue("ns1.glue.com.ua", "192.0.2.1", "2001:db8::1")
                        .nameserverWithGlue("ns2.glue.com.ua", "192.0.2.2")
                        .send(),
                c -> c.domain().create("glue.com.ua", map(
                        "years", 1, "registrant", "acme-01",
                        "nameservers", list(
                                map("name", "ns1.glue.com.ua", "addresses", list("192.0.2.1", "2001:db8::1")),
                                map("name", "ns2.glue.com.ua", "addresses", list("192.0.2.2"))))));
        sameFrame("domain:update delta lands in the same add/rem/chg blocks",
                c -> c.domain().updateBuilder("example3.com.ua")
                        .addNameserver("ns3.acme.example").remNameserver("ns1.acme.example")
                        .addStatus("clientHold").remStatus("clientTransferProhibited")
                        .addContact("tech", "acme-ns9")
                        .changeRegistrant("acme-02").changeAuthInfo("N3w-Pw").send(),
                c -> c.domain().update("example3.com.ua", map(
                        "add", map("ns", list("ns3.acme.example"), "statuses", list("clientHold"),
                                "contacts", map("tech", list("acme-ns9"))),
                        "rem", map("ns", list("ns1.acme.example"),
                                "statuses", list("clientTransferProhibited")),
                        "chg", map("registrant", "acme-02", "authInfo", "N3w-Pw"))));
        sameFrame("contact:create with both postal forms matches the array call",
                c -> c.contact().createBuilder("acme-01", "billing@acme.example")
                        .internationalAddress("ACME LLC", "Kyiv", "UA", Arrays.asList("1 Main St"),
                                "ACME LLC", null, "01001")
                        .localizedAddress(ACME_LLC_LOCAL, KYIV_LOCAL, "UA")
                        .voice("+380.441234567").authInfo("C0ntact-Pw").withhold("voice", "email").send(),
                c -> c.contact().create("acme-01", map(
                        "email", "billing@acme.example",
                        "postalInfos", list(
                                map("type", "int", "name", "ACME LLC", "city", "Kyiv", "cc", "UA",
                                        "street", list("1 Main St"), "org", "ACME LLC", "pc", "01001"),
                                map("type", "loc", "name", ACME_LLC_LOCAL, "city", KYIV_LOCAL, "cc", "UA")),
                        "voice", "+380.441234567", "authInfo", "C0ntact-Pw",
                        "disclose", map("flag", Boolean.FALSE, "voice", Boolean.TRUE, "email", Boolean.TRUE))));
        sameFrame("contact:update assembles the same chg block, statuses and disclosure",
                c -> c.contact().updateBuilder("acme-01")
                        .changeEmail("new@acme.example").changeVoice("+380.441234567").changeFax("")
                        .changeInternationalAddress("ACME LLC", "Lviv", "UA", null, "", null, "79000")
                        .changeAuthInfo("N3w-C0ntact-Pw").withhold("voice", "email")
                        .addStatus("clientUpdateProhibited").remStatus("clientDeleteProhibited").send(),
                c -> c.contact().update("acme-01", map(
                        "chg", map(
                                "email", "new@acme.example", "voice", "+380.441234567", "fax", "",
                                "postalInfo", map("type", "int", "name", "ACME LLC", "city", "Lviv", "cc", "UA",
                                        "org", "", "pc", "79000"),
                                "authInfo", "N3w-C0ntact-Pw",
                                "disclose", map("flag", Boolean.FALSE, "voice", Boolean.TRUE,
                                        "email", Boolean.TRUE)),
                        "addStatuses", list("clientUpdateProhibited"),
                        "remStatuses", list("clientDeleteProhibited"))));
        sameFrame("host:update addresses and statuses match the array call",
                c -> c.host().updateBuilder("ns1.acme.example")
                        .addAddress("192.0.2.10").addAddress("2001:db8::10")
                        .remAddress("192.0.2.9").addStatus("clientUpdateProhibited").send(),
                c -> c.host().update("ns1.acme.example", map(
                        "addAddresses", list("192.0.2.10", "2001:db8::10"),
                        "remAddresses", list("192.0.2.9"), "addStatuses", list("clientUpdateProhibited"))));

        // A builder is a command that has not happened yet.
        Session b = makeClient(Arrays.asList(GREETING, ok(), ok()));
        b.client.connect();
        DomainCreateBuilder pending = b.client.domain().createBuilder("example3.com.ua").years(1).registrant("C1");
        check("building sends nothing", b.fake.written.isEmpty()); // connect() reads the greeting, writes nothing
        check("toOptions() shows what would be sent",
                pending.toOptions().equals(map("years", 1, "registrant", "C1")));
        pending.send();
        boolean reSent = false;
        try {
            pending.send();
        } catch (ValidationException e) {
            reSent = true;
        }
        // Sending twice is two registrations and two charges, and the second is never what was meant.
        check("a builder refuses to be sent twice", reSent);
    }

    private static void argumentErrors() {
        System.out.println("errors: a bad argument is not the same as a bad configuration");
        // The distinction has a consequence: a service answering HTTP maps one to the caller's 4xx and the other
        // to its own 5xx. Sharing a class meant an operator's own misconfiguration could be reported to a
        // customer as their mistake.
        Session v = makeClient(Arrays.asList(GREETING));
        final Client client = v.client;
        check("a fee that is not a decimal",
                argFails(() -> client.domain().createBuilder("x.ua").maxFee("100,00")));
        check("a disclose field that does not exist",
                argFails(() -> client.contact().createBuilder("c1", "contact@example.com").withhold("passport")));
        check("removing all DNSSEC and naming records at once",
                argFails(() -> client.domain().updateBuilder("x.ua").removeAllDnssec().remDsRecord(1, 8, 2, "AB")));
        check("an unknown option key", argFails(() -> client.domain().create("x.ua", map("yeras", 1))));
    }

    private static void contactPostalClearing() {
        System.out.println("contact: which postal fields can be CLEARED is the schema's decision, not ours");
        // contact-1.0.xsd: optPostalLineType (org, street, sp) and pcType have no minLength, so those clear by
        // being sent empty. postalLineType (name, city) has minLength 1 and ccType is exactly two characters, so
        // an empty one of those is schema-invalid - and an invalid frame comes back as a bare 2001 that names no
        // element, the least useful error in EPP.
        Session p = makeClient(Arrays.asList(GREETING, ok(), ok()));
        p.client.connect();
        final Client client = p.client;

        check("clearing sp WITHOUT the required parts of <addr> is refused here, not by the server",
                argFails(() -> client.contact().update("C-1",
                        map("chg", map("postalInfo", map("type", "loc", "sp", ""))))));
        boolean namesTheMissingPart = false;
        try {
            client.contact().update("C-1", map("chg", map("postalInfo",
                    map("type", "loc", "name", "Ivan Petrenko", "sp", ""))));
        } catch (ValidationException e) {
            namesTheMissingPart = e.getMessage().contains("city");
        }
        check("and the message names the part that is missing", namesTheMissingPart);
        check("a name cannot be cleared at all - there is no empty postalLineType",
                argFails(() -> client.contact().update("C-1", map("chg", map("postalInfo",
                        map("type", "loc", "name", "", "city", "Lviv", "cc", "UA"))))));

        // The whole point of the guard is that the CORRECT call still works and still clears.
        client.contact().update("C-1", map("chg", map("postalInfo", map(
                "type", "loc", "name", "Ivan Petrenko", "sp", "", "city", "Lviv", "cc", "UA"))));
        Xp px = xp(p.fake.written.get(0));
        check("sp goes out as an empty element, which is what clears it",
                px.count("//contact:addr/contact:sp") == 1 && "".equals(px.firstText("//contact:addr/contact:sp")));
        check("and the required parts travel with it", "Lviv".equals(px.firstText("//contact:addr/contact:city"))
                && "UA".equals(px.firstText("//contact:addr/contact:cc")));

        // CLEARING AN ORG NEEDS THE WHOLE BLOCK TOO, AND THIS TEST USED TO ASSERT THE OPPOSITE.
        //
        // It read "clearing org alone sends no <addr> and needs no city" - built on RFC 5733, where each child of
        // chgPostalInfoType is optional and an omitted one looks like "no change". Against a registry that
        // REPLACES the block rather than merging it, a chg carrying only an empty org comes back 1000 with the
        // contact left holding NO postalInfo at all - name, street, city, pc and cc gone, both blocks. The green
        // test was documenting a way to destroy a registrant's address.
        check("clearing org WITHOUT the rest of the block is refused - a registry that replaces would drop the "
                        + "address",
                argFails(() -> client.contact().update("C-1",
                        map("chg", map("postalInfo", map("type", "loc", "org", ""))))));
        // The BUILDER reaches the same code, and nothing checked that it did. A guard that only covers the raw
        // call leaves the more convenient path - the one the manual leads with - able to do the damage.
        check("and the builder is held to the same rule, not just the raw call",
                argFails(() -> client.contact().updateBuilder("C-1")
                        .changeInternationalAddress(null, "Lviv", "UA", null, "", null, null)
                        .send()));
        client.contact().update("C-1", map("chg", map("postalInfo", map(
                "type", "loc", "name", "Ivan Petrenko", "org", "", "city", "Lviv", "cc", "UA"))));
        Xp ox = xp(p.fake.written.get(1));
        check("the complete form clears org AND carries the address",
                ox.count("//contact:postalInfo/contact:org") == 1
                        && "".equals(ox.firstText("//contact:postalInfo/contact:org"))
                        && "Lviv".equals(ox.firstText("//contact:addr/contact:city"))
                        && "Ivan Petrenko".equals(ox.firstText("//contact:postalInfo/contact:name")));
        check("ValidationException is still an EppException",
                EppException.class.isAssignableFrom(ValidationException.class));
        check("but NOT a ConfigException", !ConfigException.class.isAssignableFrom(ValidationException.class));
    }

    private static void transportRunawayFrame() {
        System.out.println("transport: a runaway frame length is TERMINAL, not just an exception");
        // The four length bytes are already consumed when the check fires, so the stream sits at an unknown
        // offset. Leaving it open means the NEXT command reads from the middle of this frame - the off-by-one
        // across billable transforms that the length check exists to prevent.
        Connection conn = new Connection(Config.builder("h", "SRV-1", "secret").build());
        int length = 99999999;
        byte[] bytes = new byte[4 + 16];
        bytes[0] = (byte) (length >>> 24);
        bytes[1] = (byte) (length >>> 16);
        bytes[2] = (byte) (length >>> 8);
        bytes[3] = (byte) length;
        for (int i = 4; i < bytes.length; i++) {
            bytes[i] = (byte) 'x';
        }
        try {
            // An unconnected SSLSocket stands in for the wire: it satisfies isOpen() and close() without a
            // network, the way the PHP fixture substitutes a php://memory stream.
            Field sockField = Connection.class.getDeclaredField("sock");
            sockField.setAccessible(true);
            sockField.set(conn, (SSLSocket) SSLSocketFactory.getDefault().createSocket());
            Field inField = Connection.class.getDeclaredField("in");
            inField.setAccessible(true);
            inField.set(conn, new DataInputStream(new ByteArrayInputStream(bytes)));
        } catch (Exception e) {
            throw new RuntimeException("cannot stand a fake stream into Connection", e);
        }
        check("the connection starts out open", conn.isOpen());
        boolean lenThrew = false;
        try {
            conn.readFrame();
        } catch (ConnectionException e) {
            lenThrew = e.getMessage() != null && e.getMessage().contains("Invalid EPP frame length");
        }
        check("a runaway length prefix raises ConnectionException", lenThrew);
        check("and the connection is CLOSED, so no command can ride the desynchronised stream", !conn.isOpen());
    }

    private static void extValuePayloads() {
        System.out.println("extValue: a relocated RFC 9038 payload keeps its content");
        // A container's recursive text content fused the children into "120.00500.00" - a string that reads like
        // a figure and is not one. The children must survive by NAME.
        Response relocated = Response.fromXml(
                "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"2005\"><msg>err</msg><extValue><value>"
                + "<balance:infData xmlns:balance=\"http://registry.example/epp/balance-1.0\">"
                + "<balance:balance>120.00</balance:balance><balance:creditLimit>500.00</balance:creditLimit>"
                + "</balance:infData></value><reason lang=\"en\">unhandled namespace</reason></extValue></result>"
                + "<trID><svTRID>X</svTRID></trID></response></epp>");
        Map<String, Object> ev = relocated.extValues().get(0);
        check("a container carries no text of its own", "".equals(ev.get("text")));
        Map<String, Object> expectedValues = map("balance", "120.00", "creditLimit", "500.00");
        Object values = ev.get("values");
        check("and its children survive by name", values instanceof Map && values.equals(expectedValues)
                && new ArrayList<Object>(((Map<?, ?>) values).keySet())
                        .equals(Arrays.asList("balance", "creditLimit")));
        check("the element and its namespace are reported",
                "infData".equals(ev.get("element")) && EXT_BALANCE.equals(ev.get("namespace")));
        check("the payload can be re-parsed from xml", String.valueOf(ev.get("xml")).contains("120.00"));

        // The ordinary case must not regress: a leaf still answers with its value.
        Map<String, Object> leaf = Response.fromXml(
                "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"2005\"><msg>err</msg><extValue><value>"
                + "<domain:name xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">bad..name</domain:name></value>"
                + "<reason lang=\"en\">Invalid label</reason></extValue></result>"
                + "<trID><svTRID>X</svTRID></trID></response></epp>").extValues().get(0);
        check("a leaf still reports which value was rejected", "bad..name".equals(leaf.get("text")));
        check("and has no children", size(leaf.get("values")) == 0);
        check("the reason and its language come through",
                "Invalid label".equals(leaf.get("reason")) && "en".equals(leaf.get("lang")));
    }

    private static Xp loginFrame(String password, String newPassword, boolean loginSecurity) {
        Config cfg = config(password).loginSecurity(loginSecurity).build();
        Session s = makeClient(Arrays.asList(GREETING_LOGINSEC, ok()), cfg);
        s.client.connect();
        s.client.login(newPassword);
        Xp frame = xp(s.fake.written.get(0));
        frame.register("loginSec", Namespaces.LOGINSEC);
        return frame;
    }

    private static void loginSecSentinelMatrix() {
        System.out.println("RFC 8807: the sentinel goes only in the element whose value was relocated");
        // The sentinel means "the real value is in the matching loginSec element". Putting it in an element whose
        // value was NOT relocated points the server at something that is not there - which is what a frame-wide
        // flag did to every rotation across the 16-character boundary.
        String sentinel = Namespaces.LOGINSEC_SENTINEL;
        String longPw = repeat("a", 40);

        // Short -> long: only newPW moves. pw must stay LITERAL, or the server is told to look in an extension
        // element that was never emitted and the login is rejected.
        Xp x = loginFrame("short1", longPw, true);
        check("rotating short -> long keeps <pw> literal", "short1".equals(x.firstText("//e:login/e:pw")));
        check("and marks only <newPW> with the sentinel", sentinel.equals(x.firstText("//e:login/e:newPW")));
        check("the new password travels in loginSec:newPW", longPw.equals(x.firstText("//loginSec:newPW")));
        check("and no loginSec:pw is emitted for a short current password", x.count("//loginSec:pw") == 0);

        // Long -> short: the mirror image. newPW must stay literal, or the account's new password becomes the
        // sentinel string itself.
        x = loginFrame(longPw, "short2", true);
        check("rotating long -> short marks <pw> with the sentinel", sentinel.equals(x.firstText("//e:login/e:pw")));
        check("and keeps <newPW> literal", "short2".equals(x.firstText("//e:login/e:newPW")));
        check("the current password travels in loginSec:pw", longPw.equals(x.firstText("//loginSec:pw")));
        check("and no loginSec:newPW is emitted for a short new password", x.count("//loginSec:newPW") == 0);

        // Long -> long: both relocate.
        x = loginFrame(longPw, repeat("b", 40), true);
        check("long -> long relocates both", sentinel.equals(x.firstText("//e:login/e:pw"))
                && sentinel.equals(x.firstText("//e:login/e:newPW")));
        check("and both loginSec values are present",
                x.count("//loginSec:pw") == 1 && x.count("//loginSec:newPW") == 1);

        // Short -> short: neither value is relocated, so neither loginSec password element appears - even though
        // the block itself does, to take part in the extension.
        x = loginFrame("short1", "short2", true);
        check("short -> short relocates neither password",
                x.count("//loginSec:pw") == 0 && x.count("//loginSec:newPW") == 0);
        check("and both passwords stay literal", "short1".equals(x.firstText("//e:login/e:pw"))
                && "short2".equals(x.firstText("//e:login/e:newPW")));

        // Opting out removes the block outright, so a caller who wants the pre-8807 frame can have it - but a
        // password that cannot fit in <pw> still travels in the extension, since there is nowhere else for it to
        // go and dropping it would send the wrong password rather than none.
        x = loginFrame("short1", "short2", false);
        check("opting out sends no loginSec block for short passwords", x.count("//loginSec:loginSec") == 0);
        x = loginFrame(longPw, null, false);
        check("opting out cannot suppress a password that does not fit <pw>", x.count("//loginSec:pw") == 1);
    }

    private static String infData(String inner) {
        return "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData>" + inner + "</resData><trID><svTRID>X</svTRID></trID></response></epp>";
    }

    private static void objectAccessors() {
        System.out.println("response accessors read every object the registry answers with");
        // One fixture per object type. These are what a customer reaches for first, and the failure they produce
        // is silent: an accessor that finds the wrong element returns a plausible-looking string.
        Response dom = Response.fromXml(infData(
                "<domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>example.com.ua</domain:name><domain:registrant>c-reg</domain:registrant>"
                + "<domain:contact type=\"admin\">c-admin</domain:contact>"
                + "<domain:contact type=\"tech\">c-t1</domain:contact>"
                + "<domain:contact type=\"tech\">c-t2</domain:contact>"
                + "<domain:contact type=\"billing\">c-bill</domain:contact>"
                + "<domain:ns><domain:hostAttr><domain:hostName>NS1.Example.NET</domain:hostName>"
                + "<domain:hostAddr ip=\"v4\">192.0.2.1</domain:hostAddr>"
                + "<domain:hostAddr>198.51.100.7</domain:hostAddr></domain:hostAttr></domain:ns>"
                + "<domain:host>ns1.example.com.ua</domain:host>"
                + "<domain:authInfo><domain:pw>auth-1</domain:pw></domain:authInfo></domain:infData>"));
        check("role contacts are addressable one role at a time",
                dom.techContacts().equals(Arrays.asList("c-t1", "c-t2")));
        check("and admin/billing are separate", dom.adminContacts().equals(Arrays.asList("c-admin"))
                && dom.billingContacts().equals(Arrays.asList("c-bill")));
        // Registries disagree on tech vs Tech; an exact match reports "no technical contact" for a domain that
        // has two.
        check("a role is matched case-insensitively", dom.contactsFor("TECH").equals(Arrays.asList("c-t1", "c-t2")));
        check("a role nobody holds is an empty list, not an error", dom.contactsFor("reseller").isEmpty());
        check("allContacts() includes the registrant", dom.allContacts().contains("c-reg"));
        check("subordinate hosts are listed (they block a delete)",
                dom.subordinateHosts().equals(Arrays.asList("ns1.example.com.ua")));
        Map<String, List<Map<String, String>>> glue = dom.nameserverAddresses();
        check("inline glue is keyed by nameserver, not flattened",
                new ArrayList<String>(glue.keySet()).equals(Arrays.asList("ns1.example.net")));
        check("and an addr with no @ip defaults to v4",
                map("ip", "198.51.100.7", "version", "v4").equals(dig(glue, "ns1.example.net", Integer.valueOf(1))));
        // The bug this pins: a document-wide addr search made a DOMAIN look like a well-addressed host.
        check("hostAddresses() stays empty on a domain", dom.hostAddresses().isEmpty());
        check("authInfo() surfaces the transfer secret", "auth-1".equals(dom.authInfo()));

        Response ct = Response.fromXml(infData(
                "<contact:infData xmlns:contact=\"urn:ietf:params:xml:ns:contact-1.0\">"
                + "<contact:id>c-reg</contact:id>"
                + "<contact:postalInfo type=\"int\"><contact:name>Ivan Petrenko</contact:name>"
                + "<contact:addr><contact:street>1 Main St</contact:street><contact:city>Kyiv</contact:city>"
                + "<contact:cc>UA</contact:cc></contact:addr></contact:postalInfo>"
                + "<contact:postalInfo type=\"loc\"><contact:name>" + IVAN_LOCAL + "</contact:name>"
                + "<contact:addr><contact:city>" + KYIV_LOCAL + "</contact:city><contact:cc>UA</contact:cc>"
                + "</contact:addr></contact:postalInfo>"
                + "<contact:fax>+380.441234568</contact:fax>"
                + "<contact:disclose flag=\"0\"><contact:email/></contact:disclose></contact:infData>"));
        // objectName() searched the whole document for <name> and found the person, so contact:info answered with
        // a full name where the caller asked for the handle - and 2303 on the next command.
        check("objectName() on a contact is the HANDLE, not the postal name", "c-reg".equals(ct.objectName()));
        check("both postal forms are kept apart", IVAN_LOCAL.equals(dig(ct.postalInfo(), "loc", "name")));
        check("the international form stays available for printing anywhere",
                "Kyiv".equals(dig(ct.postalInfo(), "int", "city")));
        check("a missing postal part is empty, never null", "".equals(dig(ct.postalInfo(), "loc", "pc")));
        check("fax is read", "+380.441234568".equals(ct.fax()));
        check("disclose keeps the flag with the list",
                map("flag", Boolean.FALSE, "elements", Arrays.asList("email")).equals(ct.disclose()));
        check("a contact addr container is not read as glue", ct.hostAddresses().isEmpty());

        Response hostRes = Response.fromXml(infData(
                "<host:infData xmlns:host=\"urn:ietf:params:xml:ns:host-1.0\">"
                + "<host:name>ns1.example.com.ua</host:name>"
                + "<host:addr ip=\"v6\">2001:db8::53</host:addr><host:addr>203.0.113.9</host:addr>"
                + "</host:infData>"));
        check("a host object reports its own glue", hostRes.hostAddresses().equals(Arrays.asList(
                map("ip", "2001:db8::53", "version", "v6"),
                map("ip", "203.0.113.9", "version", "v4"))));

        Response trnRes = Response.fromXml(infData(
                "<domain:trnData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>example.com.ua</domain:name><domain:trStatus>pending</domain:trStatus>"
                + "<domain:reID>ACME</domain:reID><domain:acID>EXAMPLE</domain:acID>"
                + "<domain:acDate>2026-08-21T09:00:00Z</domain:acDate></domain:trnData>"));
        // transferStatus() says a transfer is pending without saying whose, or by when it auto-approves.
        check("a transfer notice carries the counterparty and the deadline",
                "ACME".equals(dig(trnRes.transfer(), "requestedBy"))
                        && "2026-08-21T09:00:00Z".equals(dig(trnRes.transfer(), "actBy")));

        // RFC 8590. A notice about something the registry did to your object arrives as a sentence in the
        // language of your account, which no program can rely on; this is the same event as data.
        Response chgRes = Response.fromXml(
                "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1301\"><msg>Command completed successfully; ack to dequeue</msg></result>"
                + "<msgQ count=\"1\" id=\"217\"><qDate>2026-08-27T09:15:00Z</qDate>"
                + "<msg lang=\"uk\">" + DOMAIN_GONE_LOCAL + "</msg></msgQ>"
                + "<resData><domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>gone.com.ua</domain:name><domain:roid>D-9001</domain:roid>"
                + "</domain:infData></resData>"
                + "<extension><changePoll:changeData "
                + "xmlns:changePoll=\"urn:ietf:params:xml:ns:changePoll-1.0\" state=\"before\">"
                + "<changePoll:operation>delete</changePoll:operation>"
                + "<changePoll:date>2026-08-27T09:15:00Z</changePoll:date>"
                + "<changePoll:svTRID>SRV-1</changePoll:svTRID>"
                + "<changePoll:who>Registry</changePoll:who>"
                + "<changePoll:reason>deleted</changePoll:reason>"
                + "</changePoll:changeData></extension>"
                + "<trID><clTRID>C1</clTRID><svTRID>S1</svTRID></trID></response></epp>");
        Map<String, String> chg = chgRes.change();
        check("a change notice says what happened, without reading the sentence",
                chg != null && "delete".equals(chg.get("operation")) && "Registry".equals(chg.get("who"))
                        && "deleted".equals(chg.get("reason")));
        // Which way resData reads. A domain that no longer exists can only be described as it last was, and
        // treating that as its CURRENT state is how a client resurrects a deleted object in its own store.
        check("and which way the object beside it reads", chg != null && "before".equals(chg.get("state"))
                && "gone.com.ua".equals(chgRes.objectName()));

        // The attribute is optional and 'after' is the schema default, so an omitted state is not "unknown".
        Response chgDefault = Response.fromXml(infData(
                "<changePoll:changeData xmlns:changePoll=\"urn:ietf:params:xml:ns:changePoll-1.0\">"
                + "<changePoll:operation op=\"sync\">custom</changePoll:operation>"
                + "<changePoll:date>2026-08-27T09:15:00Z</changePoll:date>"
                + "<changePoll:svTRID>SRV-2</changePoll:svTRID><changePoll:who>CSR</changePoll:who>"
                + "</changePoll:changeData>"));
        check("an omitted state means after, and a custom operation keeps its own verb",
                "after".equals(dig(chgDefault.change(), "state"))
                        && "sync".equals(dig(chgDefault.change(), "op"))
                        && "".equals(dig(chgDefault.change(), "reason")));

        check("a response with no change block reports none", trnRes.change() == null);
    }

    private static void checkResponseExtras() {
        Response chkRes = Response.fromXml(
                "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result><resData>"
                + "<domain:chkData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:cd><domain:name avail=\"1\">free.com.ua</domain:name></domain:cd>"
                + "<domain:cd><domain:name avail=\"0\">taken.com.ua</domain:name>"
                + "<domain:reason>In use</domain:reason></domain:cd>"
                + "</domain:chkData></resData><extension>"
                + "<fee:chkData xmlns:fee=\"urn:ietf:params:xml:ns:epp:fee-1.0\">"
                + "<fee:currency>UAH</fee:currency>"
                + "<fee:cd avail=\"1\"><fee:objID>free.com.ua</fee:objID><fee:class>premium</fee:class>"
                + "<fee:command name=\"create\"><fee:period unit=\"y\">1</fee:period><fee:fee>5000.00</fee:fee>"
                + "</fee:command>"
                + "</fee:cd></fee:chkData></extension><trID><svTRID>X</svTRID></trID></response></epp>");
        check("an unavailable name reports why", "In use".equals(chkRes.unavailableReason("taken.com.ua")));
        check("an available name has no reason", chkRes.unavailableReason("free.com.ua") == null);
        check("a name nobody asked about is null, not a false reason",
                chkRes.unavailableReason("other.com.ua") == null);
        // Charging a premium at the standard price is a loss taken silently on every such registration.
        check("a premium name is flagged",
                chkRes.isPremium("free.com.ua") && "premium".equals(chkRes.feeClass("free.com.ua")));
    }

    /** The base of the alias comparison: the part of the update that both spellings share. */
    private static Map<String, Object> updateOpts(Map<String, Object> extra) {
        Map<String, Object> out = map(
                "add", map("ns", list("ns1.plain.ua"), "statuses", list("clientHold")),
                "secDNS", map("maxSigLife", 604800));
        out.putAll(extra);
        return out;
    }

    private static void aliasSpellings() {
        // PLAIN WORDS AND EPP'S ABBREVIATIONS BUILD THE SAME FRAME.
        //
        // The value of an alias is that it is not a second code path. So this does not check that `remove` works -
        // it checks that the bytes on the wire are IDENTICAL to the ones `rem` produces, which is the only claim
        // that stays true when the frame builder changes.
        //
        // It also pins the direction of precedence: a caller migrating one call at a time will pass both for a
        // while, and the plain word has to win, because that is the spelling they are moving TO.
        Session shortSession = makeClient(Arrays.asList(GREETING, ok()));
        shortSession.client.connect();
        shortSession.client.domain().update("plain.ua", updateOpts(map(
                "rem", map("ns", list("ns9.plain.ua")),
                "chg", map("registrant", "C-1"))));

        Session plain = makeClient(Arrays.asList(GREETING, ok()));
        plain.client.connect();
        plain.client.domain().update("plain.ua", updateOpts(map(
                "remove", map("ns", list("ns9.plain.ua")),
                "change", map("registrant", "C-1"))));

        check("domain:update 'remove'/'change' build the same frame as 'rem'/'chg'",
                shortSession.fake.written.get(0).equals(plain.fake.written.get(0)));

        // Both at once: the plain word wins, so a half-migrated codebase behaves predictably.
        Session both = makeClient(Arrays.asList(GREETING, ok()));
        both.client.connect();
        both.client.domain().update("plain.ua", updateOpts(map(
                "rem", map("ns", list("ns-ignored.plain.ua")),
                "remove", map("ns", list("ns9.plain.ua")),
                "chg", map("registrant", "C-IGNORED"),
                "change", map("registrant", "C-1"))));
        check("when both spellings are sent, the plain word is the one that reaches the wire",
                both.fake.written.get(0).equals(plain.fake.written.get(0)));

        // secDNS is a nested block with its own key check, so it needs its own case: a `remove` there used to be
        // an unknown key, refused before the frame was built.
        Session sec = makeClient(Arrays.asList(GREETING, ok()));
        sec.client.connect();
        sec.client.domain().update("plain.ua", map("secDNS", map("removeAll", Boolean.TRUE)));
        Xp secXp = xp(sec.fake.written.get(0));
        check("domain:update secDNS 'removeAll' reaches the wire as <secDNS:all>",
                "true".equals(secXp.firstText("//secDNS:rem/secDNS:all")));

        // contact and host carry the same vocabulary and were renamed with it.
        Session c1 = makeClient(Arrays.asList(GREETING, ok()));
        c1.client.connect();
        c1.client.contact().update("C-1", map("remStatuses", list("clientDeleteProhibited"),
                "chg", map("email", "contact@example.com")));
        Session c2 = makeClient(Arrays.asList(GREETING, ok()));
        c2.client.connect();
        c2.client.contact().update("C-1", map("removeStatuses", list("clientDeleteProhibited"),
                "change", map("email", "contact@example.com")));
        check("contact:update 'removeStatuses'/'change' build the same frame",
                c1.fake.written.get(0).equals(c2.fake.written.get(0)));

        Session h1 = makeClient(Arrays.asList(GREETING, ok()));
        h1.client.connect();
        h1.client.host().update("ns1.plain.ua", map("remAddresses", list("192.0.2.9"),
                "remStatuses", list("clientUpdateProhibited")));
        Session h2 = makeClient(Arrays.asList(GREETING, ok()));
        h2.client.connect();
        h2.client.host().update("ns1.plain.ua", map("removeAddresses", list("192.0.2.9"),
                "removeStatuses", list("clientUpdateProhibited")));
        check("host:update 'removeAddresses'/'removeStatuses' build the same frame",
                h1.fake.written.get(0).equals(h2.fake.written.get(0)));

        // AND AN UNKNOWN KEY IS STILL REFUSED. The alias must not become a hole in the check that catches
        // 'secdns' for 'secDNS' - the whole reason this library validates option keys at all.
        Throwable refused = null;
        try {
            Session bad = makeClient(Arrays.asList(GREETING, ok()));
            bad.client.connect();
            bad.client.domain().update("plain.ua", map("removes", map("ns", list("x.ua"))));
        } catch (Throwable e) {
            refused = e;
        }
        check("a near-miss spelling is still refused, not silently dropped", refused != null);
    }

    // EVERY VERSION THIS PACKAGE STATES ABOUT ITSELF AGREES WITH ITS OWN VERSION.
    //
    // The install instructions name a version, in the Maven snippet, the Gradle line and the release tag - and
    // so go stale at the next release, silently, in every language at once. Nothing keeps that in step except
    // something that fails when it drifts. Only x.y.z strings that LOOK like this package's version are
    // considered, so the Java baseline, RFC numbers and plugin versions are untouched.
    private static void documentedVersions() {
        System.out.println("documented versions agree with Version.VERSION");
        List<File> docs = new ArrayList<File>();
        File root = new File("").getAbsoluteFile();
        // The suite is run from the project root, but a runner that starts a directory up would otherwise
        // scan nothing and report agreement it never checked.
        if (!new File(root, "README.md").isFile() && new File(root, "java-sdk/README.md").isFile()) {
            root = new File(root, "java-sdk");
        }
        docs.add(new File(root, "README.md"));
        collectMarkdown(new File(root, "docs"), docs);

        List<String> stale = new ArrayList<String>();
        int stated = 0;
        Pattern looksLikeOurs = Pattern.compile("(?<![\\d.])1\\.\\d+\\.\\d+(?![\\d.])");
        for (File doc : docs) {
            if (!doc.isFile()) {
                continue;
            }
            Matcher m = looksLikeOurs.matcher(readFile(doc));
            while (m.find()) {
                stated++;
                if (!m.group().equals(Version.VERSION)) {
                    stale.add(doc.getName() + ": " + m.group());
                }
            }
        }
        // Finding nothing is not agreement. A missing README, a moved docs directory, or install
        // instructions that stopped naming a version would all leave the list below empty, and an empty
        // list of stale versions reads exactly like a clean one.
        check("the documentation states this version somewhere", stated > 0);
        check("every documented version is " + Version.VERSION
                + (stale.isEmpty() ? "" : " - stale: " + stale.subList(0, Math.min(4, stale.size()))),
                stale.isEmpty());
    }

    private static void collectMarkdown(File dir, List<File> into) {
        File[] entries = dir.listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                collectMarkdown(entry, into);
            } else if (entry.getName().endsWith(".md") && !entry.getName().equals("CHANGELOG.md")) {
                into.add(entry);
            }
        }
    }

    private static String readFile(File file) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            DataInputStream in = new DataInputStream(new FileInputStream(file));
            try {
                in.readFully(bytes);
            } finally {
                in.close();
            }
            return new String(bytes, "UTF-8");
        } catch (IOException e) {
            throw new RuntimeException("cannot read " + file, e);
        }
    }
}
