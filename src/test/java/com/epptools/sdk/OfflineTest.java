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
 *     javac --release 8 -d out $(find src -name "*.java")
 *     java -cp out com.epptools.sdk.OfflineTest
 *
 * Or tools/run-matrix.sh, which does that with the newest JDK on the box and then runs the result on every
 * runtime it finds - the jar targets Java 8 and that is a claim about six of them.
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
        transferPeriod();
        contactPostalClearing();
        transportRunawayFrame();
        extValuePayloads();
        loginSecSentinelMatrix();
        objectAccessors();
        checkResponseExtras();
        aliasSpellings();
        nestedOptionKeys();
        helloRefusesANonGreeting();
        secdnsChoice();
        blanksFromADirectCall();
        maxSigLifeBound();
        feeAgreementCurrency();
        feeQueryNeedsAnOperation();
        emptyServiceLists();
        enumArguments();
        checkNeedsAName();
        checkRefusesABlankName();
        updateMustAskForSomething();
        discloseFormAsABareString();
        availReadsFalse();
        balanceIsScopedToABalanceAnswer();
        defaultLocaleIndependence();
        configCopiesItsLists();
        reopeningClosesTheOldSocket();
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
        System.out.println("domain:update restore (rgp, with the empty domain:chg RFC 3915 requires)");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok()));
        s.client.connect();
        s.client.domain().restore("redeem.com.ua");
        Xp ux = xp(s.fake.written.get(0));
        check("restore rgp op=request", ux.count("//e:extension/rgp:update/rgp:restore[@op=\"request\"]") == 1);
        // These two assertions used to say the opposite - that no domain:chg travels with a restore - and so pinned
        // the defect in place. RFC 3915 section 4.2.5: "at least one empty <domain:add>, <domain:rem>, or
        // <domain:chg> element MUST be present if this extension is specified within an <update> command", and the
        // RFC's own restore example carries <domain:chg/>. Without it the frame is a <domain:update> holding
        // nothing but a name, which a registry may read as a no-op and answer 2003 for - and a restore that does
        // not happen is a domain that leaves redemption by being deleted.
        check("restore carries the empty domain:chg RFC 3915 requires", ux.count("//domain:chg") == 1);
        check("and it IS empty - a restore changes nothing else", ux.count("//domain:chg/*") == 0);
        check("restore has no domain:add", ux.count("//domain:add") == 0);

        // A chg of the caller's own already satisfies the rule, and domain:updateType allows ONE chg, so a second
        // empty one would be the schema refusal this fix exists to avoid.
        Map<String, Object> both = map("restore", Boolean.TRUE, "chg", map("registrant", "REG-0007"));
        s.client.domain().update("redeem.com.ua", both);
        Xp bx = xp(s.fake.written.get(1));
        check("a restore beside a real chg emits exactly one domain:chg", bx.count("//domain:chg") == 1);
        check("and it is the caller's, not an empty one",
                "REG-0007".equals(bx.firstText("//domain:chg/domain:registrant")));
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

        // THE LOW-BALANCE NOTICE IS THE ONE FRAME A REGISTRAR CANNOT RE-REQUEST, and balance-1.0.xsd makes
        // every field optional so that such a notice may carry only <balance> and <threshold>. Reading only
        // creditLimit and availableCredit made balance() return null for exactly that frame: the caller's
        // branch never ran, the notice fell through to whatever handles the unrecognised, and once acked it
        // is gone - the registry keeps no copy. The threshold is also the only thing that says WHY it fired.
        String lowXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1301\"><msg>Command completed successfully; ack to dequeue</msg></result>"
                + "<msgQ count=\"1\" id=\"7\"><qDate>2026-09-23T04:00:00Z</qDate><msg>Low balance</msg></msgQ>"
                + "<resData><balance:infData xmlns:balance=\"http://sandbox.invalid/epp/balance-1.0\">"
                + "<balance:balance>120.50</balance:balance><balance:threshold>500.00</balance:threshold>"
                + "</balance:infData></resData><trID><svTRID>SRV-9</svTRID></trID></response></epp>";
        Response low = Response.fromXml(lowXml);
        check("a notice carrying only balance and threshold is still a balance answer", low.balance() != null);
        check("and its figure is readable", "120.50".equals(low.currentBalance()));
        check("and the threshold that fired it is readable", "500.00".equals(low.threshold()));
        check("a plain balance answer has no threshold", bal.threshold() == null);

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
        // And <os> carries the SHAPE RFC 8807 section 3.1 asks for: the system "with version if available",
        // as arch SP name SP version. The four runtimes name the same machine differently - amd64 / AMD64 /
        // x64 - so the exact string cannot be asserted here or compared across the libraries. What can be
        // asserted is that it is not a single token: Node's process.platform says win32, a build target that
        // names neither the architecture nor the version.
        String osText = Client.osDescription();
        check("and <os> carries more than one token, as RFC 8807 asks",
                osText.trim().indexOf(' ') > 0 && !osText.contains("  "));

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

    private static void transferPeriod() {
        System.out.println("period: 0 years means one thing on a transfer and nothing anywhere else");
        // A zone whose transfers are free states its policy as "the term does not move", and a registry whose
        // validator wants that says so in its error: "transfer period for this zone must be 0". But RFC 5731's
        // periodType is 1..99, so <domain:period>0</domain:period> is a frame the schema refuses - the value the
        // operator asks for cannot be written down. Both are satisfied by leaving the element out, which is what
        // an absent period already means: apply the zone's own policy.
        Session z = makeClient(Arrays.asList(GREETING, ok()));
        z.client.connect();
        z.client.domain().transfer("request", "free.biz.ua", "auth-1", Integer.valueOf(0), null);
        check("transfer with 0 years omits the period rather than writing an invalid one",
                !z.fake.written.get(0).contains("domain:period"));
        check("transfer with 0 years still carries the name and the secret",
                z.fake.written.get(0).contains("<domain:name>free.biz.ua</domain:name>")
                        && z.fake.written.get(0).contains("<domain:pw>auth-1</domain:pw>"));

        Session one = makeClient(Arrays.asList(GREETING, ok()));
        one.client.connect();
        one.client.domain().transfer("request", "paid.com.ua", "auth-1", Integer.valueOf(1), null);
        check("a zone that bundles a renewal still gets its period",
                one.fake.written.get(0).contains("<domain:period unit=\"y\">1</domain:period>"));

        // The bound is the schema's, so it is refused for every command that carries a period - and a create or
        // a renew has no reading of 0 under which the caller meant something.
        final Client v = makeClient(Arrays.asList(GREETING)).client;
        check("100 years on a transfer",
                argFails(() -> v.domain().transfer("request", "x.ua", "a", Integer.valueOf(100), null)));
        check("0 years on a create", argFails(() -> v.domain().create("x.ua", map("years", 0))));
        check("0 years on a renew", argFails(() -> v.domain().renew("x.ua", "2027-04-01", 0)));

        // An e-mail is not among the clearable contact fields: RFC 5733 types it minTokenType (minLength 1).
        // contact:create refused an empty one from the start; contact:update emitted it, and an empty
        // <contact:email/> is a schema-invalid frame answered with a bare 2001 that names no element.
        check("clearing a contact e-mail on update",
                argFails(() -> v.contact().update("C-1", map("chg", map("email", "")))));
        check("and through the builder, which is the same command",
                argFails(() -> v.contact().updateBuilder("C-1").changeEmail("").send()));

        // A blank status is not a status. statusValueType is an enumeration, so "" is not a member of it and
        // <contact:status s=""/> is a schema-invalid frame - which is why every list step in every builder
        // drops blanks.
        Session st = makeClient(Arrays.asList(GREETING, ok()));
        st.client.connect();
        st.client.contact().updateBuilder("C-1").addStatus("", "  ", "clientUpdateProhibited").send();
        // secDNS on a create carries records; on an update it carries a delta. Each command must refuse the
        // OTHER's keys, because accepting them is worse than refusing them: a create that accepted "add"
        // would read neither dsData nor keyData and register the domain UNSIGNED behind a 1000.
        check("an update-only DNSSEC key on a create",
                argFails(() -> v.domain().create("x.ua", map("secDNS", map("add", map("dsData", list()))))));
        check("a create-only DNSSEC key on an update",
                argFails(() -> v.domain().update("x.ua", map("secDNS", map("dsData", list())))));
        check("a blank contact status contributes nothing",
                st.fake.written.get(0).split("<contact:status", -1).length - 1 == 1
                        && st.fake.written.get(0).contains("s=\"clientUpdateProhibited\""));
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

    // A MISSPELLED KEY INSIDE A NESTED MAP IS REFUSED, NOT DROPPED.
    //
    // Options.check was applied around each of these maps and never inside it, so the promise that an unrecognised
    // key is refused stopped one level above the fields that decide what the registry stores. Every frame named
    // below was built, was SCHEMA-VALID, and was answered 1000 - so nothing anywhere said the value had gone.
    private static void nestedOptionKeys() {
        System.out.println("nested option maps are key-checked too");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok(), ok(), ok(), ok(), ok(), ok()));
        s.client.connect();
        final Client c = s.client;

        // Without this: <contact:disclose flag="0"/> with no children. The registrant asked for their e-mail to be
        // withheld, the registry answered 1000, and the address stayed published.
        check("a misspelled disclose field is refused", argFails(() -> c.contact().create("REG-0001",
                map("name", "Jane", "city", "Lviv", "cc", "UA", "email", "a@b.ua",
                        "disclose", map("flag", Boolean.FALSE, "emial", Boolean.TRUE)))));

        // Without this: <secDNS:keyTag>0</secDNS:keyTag> and an empty digest, which the schema ACCEPTS. The domain
        // is published with a delegation signer matching no key, and once the parent zone carries it every
        // validating resolver answers SERVFAIL for the name.
        check("a misspelled dsData key is refused", argFails(() -> c.domain().create("ds.com.ua",
                map("registrant", "REG-0001", "secDNS", map("dsData",
                        list(map("key_tag", 12345, "alg", 8, "digestType", 2, "digest", "ABCD")))))));

        check("a misspelled keyData key is refused", argFails(() -> c.domain().create("kd.com.ua",
                map("registrant", "REG-0001", "secDNS", map("keyData",
                        list(map("flags", 257, "protocol", 3, "alg", 8, "pubkey", "AwEAAaTz")))))));

        // Without this: the currency is dropped and the cap is applied in whatever the registry prices in, so the
        // one protection a registrar has against a premium price bit at another figure.
        check("a misspelled fee-agreement key is refused", argFails(() -> c.domain().create("fee.com.ua",
                map("registrant", "REG-0001", "fee", map("amount", "340.20", "curency", "UAH")))));

        // Without this: a <domain:hostAttr> whose hostName is the string "null", or the glue silently discarded.
        check("a misspelled glue key is refused", argFails(() -> c.domain().create("glue.com.ua",
                map("registrant", "REG-0001", "nameservers",
                        list(map("nmae", "ns1.x.ua", "addresses", list("192.0.2.1")))))));
        check("glue with no name at all is refused", argFails(() -> c.domain().create("glue2.com.ua",
                map("registrant", "REG-0001", "nameservers", list(map("addresses", list("192.0.2.1")))))));

        // Without this: on an update the block REPLACES the stored one, so the registrant's postcode was deleted.
        check("a misspelled postalInfo key is refused", argFails(() -> c.contact().update("REG-0001",
                map("chg", map("postalInfo",
                        map("name", "A", "city", "Kyiv", "cc", "UA", "postalCode", "01001"))))));

        // The message names the nearest accepted spelling, as every other refused key does.
        String said = null;
        try {
            c.contact().create("REG-0001", map("name", "A", "city", "Kyiv", "cc", "UA", "email", "a@b.ua",
                    "disclose", map("flag", Boolean.FALSE, "emial", Boolean.TRUE)));
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("and names the nearest spelling", said != null && said.contains("did you mean 'email'"));
        check("and says which block it was checking", said != null && said.contains("contact disclose"));

        // The flat contact-create form spreads the postal fields across the top level beside email and voice, so
        // only the postal keys may be lifted into the block check - or every non-postal option would be refused.
        c.contact().create("REG-0002", map("name", "Jane", "city", "Lviv", "cc", "UA", "email", "a@b.ua",
                "voice", "+380.441234567", "authInfo", "pw", "type", "int"));
        Xp flat = xp(s.fake.written.get(0));
        check("the flat form still builds one postalInfo", flat.count("//contact:postalInfo") == 1);
        check("and the non-postal options beside it survive",
                "+380.441234567".equals(flat.firstText("//contact:voice")));

        // Given both spellings, the eight flat keys are never read. Accepted and dropped is the one outcome the key
        // check exists to make impossible, so the combination is refused rather than half-honoured.
        check("flat postal keys beside postalInfos are refused", argFails(() -> c.contact().create("REG-0003",
                map("email", "a@b.ua", "city", "Lviv", "postalInfos",
                        list(map("type", "int", "name", "A", "city", "Kyiv", "cc", "UA"))))));
    }

    // hello() IS THE KEEP-ALIVE, SO IT IS THE CALL THAT MUST NOT STORE A NON-GREETING.
    //
    // connect() was fixed to refuse one; this was the same defect unfixed, on the call a long-lived worker makes on
    // a timer. After one bad hello the stored greeting was a <response>, which advertises no services, so the next
    // login fell back to the DEFAULT service list - announcing services the server may not offer and losing the
    // ones it does, the registry's own among them.
    private static void helloRefusesANonGreeting() {
        System.out.println("hello() refuses a frame that is not a greeting and keeps the one it has");
        Session s = makeClient(Arrays.asList(GREETING, ok(2400), GREETING));
        s.client.connect();
        Response first = s.client.greeting();
        check("the greeting read at connect advertises the registry extension",
                EXT_REGISTRY.equals(s.client.registryExtUri()));

        boolean threw = false;
        String message = null;
        try {
            s.client.hello();
        } catch (ConnectionException e) {
            threw = true;
            message = e.getMessage();
        }
        check("a <response> in reply to hello throws ConnectionException", threw);
        check("and the message says what arrived instead", message != null && message.contains("not an EPP"));
        check("and names the endpoint, as connect() does", message != null && message.contains("epp.example:700"));
        // The stored greeting is the one that matters: had the response replaced it, registryExtUri() would answer
        // null from here on and a licence create, a forced host delete and balance() would all begin throwing.
        check("the previous greeting is still the stored one", s.client.greeting() == first);
        check("so discovery still works after a bad hello", EXT_REGISTRY.equals(s.client.registryExtUri()));

        Response fresh = s.client.hello();
        check("a real greeting IS stored", fresh.isGreeting() && s.client.greeting() == fresh);

        // Every other frame is logged; this one bypassed the request helper and so was logged nowhere.
        final List<String> lines = new ArrayList<String>();
        Session logged = makeClient(Arrays.asList(GREETING, GREETING));
        logged.client.setLogger(new Client.Logger() {
            public void debug(String m) {
                lines.add(m);
            }

            public void info(String m) {
            }

            public void warning(String m) {
            }
        });
        logged.client.connect();
        int before = lines.size();
        logged.client.hello();
        boolean sawRequest = false;
        boolean sawReply = false;
        for (int i = before; i < lines.size(); i++) {
            sawRequest = sawRequest || lines.get(i).contains("<hello/>");
            sawReply = sawReply || lines.get(i).contains("<greeting>");
        }
        check("the hello frame reaches the debug log", sawRequest);
        check("and so does the greeting it read back", sawReply);
    }

    // dsData AND keyData ARE ALTERNATIVES, NOT A PAIR.
    //
    // RFC 5910 sections 2 and 4, and secDNS-1.1.xsd, which makes dsOrKeyType and remType an XSD choice. Five paths
    // emitted both lists from one helper and so built a frame the schema refuses outright - and a refused secDNS
    // block takes the whole DNSSEC change with it.
    private static void secdnsChoice() {
        System.out.println("secDNS refuses dsData mixed with keyData, and keeps the legal nested form");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok()));
        s.client.connect();
        final Client c = s.client;
        final String pubKey = "AwEAAaTz2fqJ4W1Zz9kQ0fH8yQ==";
        final String digest = repeat("AB", 32);

        check("the direct option map is refused", argFails(() -> c.domain().create("mix.com.ua",
                map("registrant", "REG-0001", "secDNS", map(
                        "dsData", list(map("keyTag", 1, "alg", 8, "digestType", 2, "digest", digest)),
                        "keyData", list(map("flags", 257, "protocol", 3, "alg", 8, "pubKey", pubKey)))))));
        check("createBuilder().dsRecord().keyRecord() is refused",
                argFails(() -> c.domain().createBuilder("mixb.com.ua").registrant("REG-0001")
                        .dsRecord(1, 8, 2, digest).keyRecord(257, 3, 8, pubKey).send()));
        check("updateBuilder().addDsRecord().addKeyRecord() is refused",
                argFails(() -> c.domain().updateBuilder("mixb.com.ua")
                        .addDsRecord(1, 8, 2, digest).addKeyRecord(257, 3, 8, pubKey).send()));
        check("and the same for rem", argFails(() -> c.domain().updateBuilder("mixb.com.ua")
                .remDsRecord(1, 8, 2, digest).remKeyRecord(257, 3, 8, pubKey).send()));
        check("dsRecordWithKey() plus a separate keyRecord() is refused too",
                argFails(() -> c.domain().createBuilder("mixc.com.ua").registrant("REG-0001")
                        .dsRecordWithKey(1, 8, 2, digest, 257, 3, 8, pubKey)
                        .keyRecord(257, 3, 8, pubKey).send()));

        String said = null;
        try {
            c.domain().create("mix2.com.ua", map("registrant", "REG-0001", "secDNS", map(
                    "dsData", list(map("keyTag", 1, "alg", 8, "digestType", 2, "digest", digest)),
                    "keyData", list(map("flags", 257, "protocol", 3, "alg", 8, "pubKey", pubKey)))));
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("the message cites the RFC", said != null && said.contains("RFC 5910"));
        check("and points at the nesting that IS allowed", said != null && said.contains("dsRecordWithKey"));

        // The nested form is the one legal way to send a DS record with the DNSKEY it came from, and it must keep
        // working: registries that accept it can verify the digest for you.
        c.domain().createBuilder("nested.com.ua").registrant("REG-0001")
                .dsRecordWithKey(12345, 8, 2, digest, 257, 3, 8, pubKey).send();
        Xp nx = xp(s.fake.written.get(0));
        check("dsRecordWithKey() alone still builds one dsData", nx.count("//secDNS:create/secDNS:dsData") == 1);
        check("with the key INSIDE it, which is the nesting RFC 5910 allows",
                nx.count("//secDNS:dsData/secDNS:keyData/secDNS:pubKey") == 1);
        check("and no keyData beside it", nx.count("//secDNS:create/secDNS:keyData") == 0);

        // One list at a time is unremarkable and must stay so.
        c.domain().create("keyed.com.ua", map("registrant", "REG-0001", "secDNS",
                map("keyData", list(map("flags", 257, "protocol", 3, "alg", 8, "pubKey", pubKey)))));
        check("keyData on its own is fine",
                xp(s.fake.written.get(1)).count("//secDNS:create/secDNS:keyData") == 1);
    }

    // A DIRECT CALL TRIMS AND DROPS BLANKS, THE WAY EVERY BUILDER LIST STEP ALWAYS DID.
    //
    // Each frame below was verified schema-REFUSED before this: domain:status s="" and s="null", contact:status and
    // host:status the same, an empty domain:hostObj (minLength 1) and an empty host:addr (minLength 3). The refusal
    // is a bare 2001 that names no element, and it takes the good entries in the same block with it.
    private static void blanksFromADirectCall() {
        System.out.println("blanks and nulls do not reach the wire from a direct call");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok(), ok(), ok(), ok(), ok(), ok(), ok()));
        s.client.connect();

        s.client.domain().update("b.com.ua", map("add", map(
                "statuses", list("", "  ", "clientHold", null),
                "ns", list("", "ns1.example.net"),
                "contacts", map("tech", list("", "TEC-0001")))));
        Xp dx = xp(s.fake.written.get(0));
        check("a blank domain status is dropped", dx.texts("//domain:status/@s").equals(Arrays.asList("clientHold")));
        check("a blank hostObj is dropped", dx.texts("//domain:hostObj").equals(Arrays.asList("ns1.example.net")));
        check("a blank contact handle is dropped", dx.texts("//domain:contact").equals(Arrays.asList("TEC-0001")));

        // A list of NOTHING but blanks leaves the update with no delta at all, and RFC 5731 section 3.2.5 requires
        // at least one of add/rem/chg unless the command is extended - so the whole command is refused rather than
        // sent as a <domain:update> carrying only a name. Nothing is written, which is why every frame index below
        // is one lower than the call order suggests.
        int writtenBefore = s.fake.written.size();
        boolean onlyBlanksThrew = false;
        try {
            s.client.domain().update("b.com.ua", map("add", map("statuses", list("", null))));
        } catch (ValidationException e) {
            onlyBlanksThrew = true;
        }
        check("a statuses list of only blanks leaves nothing to ask for", onlyBlanksThrew);
        check("and no frame is sent for it", s.fake.written.size() == writtenBefore);

        s.client.contact().update("REG-0001", map("addStatuses", list("", "clientUpdateProhibited"),
                "remStatuses", list("", null)));
        Xp cx = xp(s.fake.written.get(1));
        check("a blank contact status is dropped",
                cx.texts("//contact:add/contact:status/@s").equals(Arrays.asList("clientUpdateProhibited")));
        check("and a rem block of only blanks is not opened", cx.count("//contact:rem") == 0);

        s.client.host().create("ns9.example.net", Arrays.asList("", "192.0.2.1", "  "));
        check("a blank host address is dropped on create",
                xp(s.fake.written.get(2)).texts("//host:addr").equals(Arrays.asList("192.0.2.1")));

        s.client.host().update("ns1.example.net", map("addAddresses", list("", "192.0.2.2"),
                "addStatuses", list("", "clientUpdateProhibited"), "remAddresses", list("", null)));
        Xp hx = xp(s.fake.written.get(3));
        check("a blank host address is dropped on update",
                hx.texts("//host:add/host:addr").equals(Arrays.asList("192.0.2.2")));
        check("a blank host status is dropped",
                hx.texts("//host:add/host:status/@s").equals(Arrays.asList("clientUpdateProhibited")));
        check("and a rem block of only blanks is not opened", hx.count("//host:rem") == 0);

        // Glue addresses, from the direct call and from the one builder step that had no filter of its own.
        s.client.domain().create("g2.com.ua", map("registrant", "REG-0001", "nameservers",
                list(map("name", "ns1.g2.com.ua", "addresses", list("", "192.0.2.1")))));
        check("a blank glue address is dropped from a direct call",
                xp(s.fake.written.get(4)).texts("//domain:hostAddr").equals(Arrays.asList("192.0.2.1")));
        s.client.domain().createBuilder("g3.com.ua").registrant("REG-0001")
                .nameserverWithGlue("ns1.g3.com.ua", "", "192.0.2.1", "  ").send();
        check("and from nameserverWithGlue(), the one builder step that had no filter",
                xp(s.fake.written.get(5)).texts("//domain:hostAddr").equals(Arrays.asList("192.0.2.1")));

        // A nameserver list of nothing but blanks must not open a childless <domain:ns/>, which the schema refuses
        // too: domain:nsType is a choice of at least one hostObj or at least one hostAttr.
        s.client.domain().create("g4.com.ua", map("registrant", "REG-0001", "nameservers", list("", "  ")));
        check("a nameserver list of only blanks emits no domain:ns",
                xp(s.fake.written.get(6)).count("//domain:ns") == 0);
    }

    // maxSigLife IS BOUNDED, THE WAY THE PERIOD BESIDE IT IS.
    //
    // secDNS-1.1.xsd restricts maxSigLifeType to minInclusive 1, so a 0 is not a short lifetime: it is a frame the
    // schema refuses, and a non-numeric value was coerced to 0 and became the same refusal wearing a typo.
    // A BLANK NAME IN A check() IS REFUSED, NOT QUIETLY DROPPED.
    //
    // The opposite treatment to a blank status in a list of statuses, for the opposite reason. Dropping a blank NAME
    // answers a different question than the one asked: fifty names in, forty-nine answers out, and the caller's own
    // loop over their fifty finds nothing under the blank - which reads as "not available" rather than as "never
    // asked". A wrong answer, further from its cause than the 2001 the frame would have earned.
    private static void checkRefusesABlankName() {
        System.out.println("check() with a blank name is refused, not filtered");
        Session s = makeClient(Arrays.asList(GREETING, ok()));
        s.client.connect();
        final Client c = s.client;
        check("domain:check with a blank name",
                argFails(() -> c.domain().check(Arrays.asList("ok.com.ua", ""))));
        check("contact:check with a blank handle",
                argFails(() -> c.contact().check(Arrays.asList("ACME-0001", "  "))));
        check("host:check with a blank name",
                argFails(() -> c.host().check(Arrays.asList("ns1.ok.com.ua", ""))));
        String said = null;
        try {
            c.domain().check(Arrays.asList("a.com.ua", "b.com.ua", ""));
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("the message names the position and the element",
                said != null && said.contains("entry 3 of 3") && said.contains("domain:name"));
        // Whitespace around a real name is trimmed rather than refused: a copy-paste artefact, not a missing
        // question.
        c.domain().check(Arrays.asList("  ok.com.ua  "));
        check("a name with spaces around it is trimmed, not refused",
                xp(s.fake.written.get(0)).texts("//domain:name").equals(Arrays.asList("ok.com.ua")));
    }

    // AN UPDATE THAT ASKS FOR NOTHING IS NOT SENT.
    //
    // RFC 5731, 5732 and 5733 each say in section 3.2.5: at least one of add, rem or chg MUST be provided if the
    // command is not being extended. All three schemas make all three elements optional, so they cannot express it -
    // which is why an update describing no change has always been valid XML. The server answers 2003, or worse 1000,
    // and either way the change the caller believes they made was never described. Nobody writes update(name) on
    // purpose; they assemble a delta from variables that all came out empty.
    private static void updateMustAskForSomething() {
        System.out.println("update() with no delta is refused rather than sent as a no-op");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok(), ok()));
        s.client.connect();
        final Client c = s.client;
        check("domain:update with no delta", argFails(() -> c.domain().update("ok.com.ua", map())));
        check("contact:update with no delta", argFails(() -> c.contact().update("ACME-0001", map())));
        check("host:update with no delta", argFails(() -> c.host().update("ns1.ok.com.ua", map())));
        // The same command once its list has been blank-filtered down to nothing.
        check("a status list holding only blanks leaves nothing to do",
                argFails(() -> c.domain().update("ok.com.ua", map("rem", map("statuses", list("", "  "))))));
        check("and the same for a contact",
                argFails(() -> c.contact().update("ACME-0001", map("remStatuses", list("")))));
        check("and for a host",
                argFails(() -> c.host().update("ns1.ok.com.ua", map("remStatuses", list("")))));
        // A key that is present but produces no child is the same case: clearAuthInfo=false asks for nothing, and on
        // its own it would have opened an empty <domain:chg/>.
        check("a chg block whose only key produces no child",
                argFails(() -> c.domain().update("ok.com.ua", map("chg", map("clearAuthInfo", Boolean.FALSE)))));
        // RFC 5731 defines no null form for the registrant, so a domain can change hands but not be left without a
        // holder. A blank one underruns clIDType's minLength 3.
        check("a registrant cleared rather than changed",
                argFails(() -> c.domain().update("ok.com.ua", map("chg", map("registrant", "")))));
        String said = null;
        try {
            c.domain().update("ok.com.ua", map());
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("the message cites the rule", said != null && said.contains("RFC 5731"));

        // The exception the RFC itself names: "All of these elements MAY be omitted if an <update> extension is
        // present." A DNSSEC-only update carries no domain delta and must still go out.
        c.domain().update("ok.com.ua", map("secDNS", map("remAll", Boolean.TRUE)));
        Xp sec = xp(s.fake.written.get(0));
        check("a DNSSEC-only update is still sent", sec.count("//secDNS:update") == 1);
        check("and it carries no empty domain block",
                sec.count("//domain:rem") == 0 && sec.count("//domain:chg") == 0);

        // And a restore, which RFC 3915 requires to carry exactly the empty block the rule above forbids.
        c.domain().restore("ok.com.ua");
        Xp rst = xp(s.fake.written.get(1));
        check("a restore still carries its deliberately empty chg block",
                rst.count("//domain:chg") == 1 && rst.count("//rgp:restore") == 1);

        // A real delta beside a filtered-out one: the empty block is omitted, the real one is not, and the command
        // goes out.
        c.domain().update("ok.com.ua", map("add", map("statuses", list("clientHold")),
                "rem", map("statuses", list(""))));
        Xp mix = xp(s.fake.written.get(2));
        check("an emptied block is dropped while the real one survives",
                mix.texts("//domain:add/domain:status/@s").equals(Arrays.asList("clientHold"))
                        && mix.count("//domain:rem") == 0);
    }

    // ONE DISCLOSED FORM GIVEN AS A BARE STRING IS ONE FORM.
    //
    // The three siblings each accept disclose {addr: 'int'} - PHP casts it to a one-element array, Node wraps it, and
    // this library takes the Arrays.asList branch. Pinned here because the Python sibling iterated the string's
    // CHARACTERS and emitted type="i", type="n", type="t", so the same call was refused in one language out of four.
    private static void discloseFormAsABareString() {
        System.out.println("disclose: one form as a bare string is one form");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok()));
        s.client.connect();
        final Client c = s.client;
        c.contact().update("ACME-0001", map("chg", map("disclose", map("flag", Boolean.FALSE, "addr", "int"))));
        check("a bare string is one disclosed form",
                xp(s.fake.written.get(0)).texts("//contact:disclose/contact:addr/@type")
                        .equals(Arrays.asList("int")));
        c.contact().update("ACME-0001",
                map("chg", map("disclose", map("flag", Boolean.TRUE, "addr", list("int", "loc")))));
        check("and a list of both forms still gives two",
                xp(s.fake.written.get(1)).texts("//contact:disclose/contact:addr/@type")
                        .equals(Arrays.asList("int", "loc")));
        check("a form that is not int or loc is still refused",
                argFails(() -> c.contact().update("ACME-0001",
                        map("chg", map("disclose", map("flag", Boolean.TRUE, "addr", "postal"))))));
    }

    private static void maxSigLifeBound() {
        System.out.println("secDNS maxSigLife is bounded (RFC 5910 minInclusive 1)");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok()));
        s.client.connect();
        final Client c = s.client;
        final String digest = repeat("AB", 32);
        final Map<String, Object> ds = map("dsData", list(
                map("keyTag", 12345, "alg", 8, "digestType", 2, "digest", digest)));

        for (final Object bad : new Object[]{Integer.valueOf(0), Integer.valueOf(-5), "soon", ""}) {
            Map<String, Object> sec = new LinkedHashMap<String, Object>(ds);
            sec.put("maxSigLife", bad);
            final Map<String, Object> options = map("registrant", "REG-0001", "secDNS", sec);
            check("create maxSigLife=" + bad + " is refused",
                    argFails(() -> c.domain().create("msl.com.ua", options)));
        }
        check("update maxSigLife=0 is refused",
                argFails(() -> c.domain().update("msl.com.ua", map("secDNS", map("maxSigLife", 0)))));
        check("and the builder step is refused too", argFails(() -> c.domain().createBuilder("msl.com.ua")
                .registrant("REG-0001").dsRecord(12345, 8, 2, digest).maxSigLife(0).send()));

        String said = null;
        try {
            c.domain().update("msl.com.ua", map("secDNS", map("maxSigLife", 0)));
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("the message names the bound and the RFC",
                said != null && said.contains("1 second or more") && said.contains("RFC 5910"));

        // A real lifetime is untouched, and so is leaving it out.
        Map<String, Object> sec = new LinkedHashMap<String, Object>(ds);
        sec.put("maxSigLife", Integer.valueOf(604800));
        c.domain().create("msl2.com.ua", map("registrant", "REG-0001", "secDNS", sec));
        check("a real lifetime still goes out",
                "604800".equals(xp(s.fake.written.get(0)).firstText("//secDNS:create/secDNS:maxSigLife")));
        c.domain().create("msl3.com.ua", map("registrant", "REG-0001", "secDNS", ds));
        check("and omitting it emits no element",
                xp(s.fake.written.get(1)).count("//secDNS:maxSigLife") == 0);
    }

    // THE FEE AGREEMENT'S CURRENCY IS UPPER-CASED, AS check()'s HAS ALWAYS BEEN.
    //
    // fee:currencyType is pattern [A-Z]{3}, so 'uah' was refused with a bare 2001 at the one point money is
    // involved - and it was refused by the schema, so nothing was charged and nothing said which element was wrong.
    private static void feeAgreementCurrency() {
        System.out.println("the fee agreement's currency is upper-cased");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok(), ok(), ok()));
        s.client.connect();
        s.client.domain().create("cur.com.ua", map("registrant", "REG-0001",
                "fee", map("amount", "340.20", "currency", "uah")));
        check("create: a lower-case agreement currency is upper-cased",
                "UAH".equals(xp(s.fake.written.get(0)).firstText("//fee:create/fee:currency")));
        s.client.domain().renew("cur.com.ua", "2027-01-01", 1, map("amount", "10.00", "currency", "uah"));
        check("renew: the same", "UAH".equals(xp(s.fake.written.get(1)).firstText("//fee:renew/fee:currency")));
        s.client.domain().createBuilder("cur2.com.ua").registrant("REG-0001").maxFee("340.20", "uah").send();
        check("and through the builder", "UAH".equals(xp(s.fake.written.get(2)).firstText("//fee:create/fee:currency")));
        // Locale.ROOT, not the default: a Turkish JVM folds 'ils' to 'İLS', which the pattern refuses just as
        // surely as the lower-case form did.
        s.client.domain().create("cur3.com.ua", map("registrant", "REG-0001",
                "fee", map("amount", "1.00", "currency", "ils")));
        check("and the folding is locale-independent",
                "ILS".equals(xp(s.fake.written.get(3)).firstText("//fee:create/fee:currency")));
    }

    // A FEE QUERY NEEDS AN OPERATION. A CURRENCY ON ITS OWN PRICES NOTHING.
    //
    // fee:checkType requires at least one <fee:command>, so a currency with no operations built a <fee:check>
    // carrying only the currency, which the schema refuses. Refused rather than quietly dropped: the caller asked a
    // question, and silence would look like an answer.
    private static void feeQueryNeedsAnOperation() {
        System.out.println("a fee query with a currency and no operation is refused");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok()));
        s.client.connect();
        final Client c = s.client;
        check("a currency with no operations is refused", argFails(
                () -> c.domain().check(Arrays.asList("example.com.ua"), new LinkedHashMap<String, Object>(), "UAH")));
        String said = null;
        try {
            c.domain().check(Arrays.asList("example.com.ua"), new LinkedHashMap<String, Object>(), "UAH");
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("and the message says a currency alone prices nothing",
                said != null && said.contains("prices nothing"));

        // A plain check with no rider at all is not a fee query and must stay untouched.
        c.domain().check(Arrays.asList("example.com.ua"));
        check("a plain check carries no fee:check", xp(s.fake.written.get(0)).count("//fee:check") == 0);
        // And the ordinary case - operations, with or without a currency - is unchanged.
        c.domain().check(Arrays.asList("example.com.ua"), map("create", 1), "UAH");
        Xp fx = xp(s.fake.written.get(1));
        check("operations plus a currency still build the rider", fx.count("//fee:check/fee:command") == 1);
        check("with the currency in it", "UAH".equals(fx.firstText("//fee:check/fee:currency")));
    }

    // AN EMPTY objUris LIST MEANS "NOT CONFIGURED", NOT "ANNOUNCE NOTHING".
    //
    // epp-1.0.xsd gives loginSvcType an objURI with minOccurs 1, so honouring an empty list wrote no objURI at all
    // and the login was refused outright - the least useful error in EPP, on the one command that has to succeed
    // first. An empty extUris is legitimate, because svcExtension is itself optional, and keeps its meaning.
    private static void emptyServiceLists() {
        System.out.println("an empty objUris falls back to the greeting; an empty extUris still announces nothing");
        Session obj = makeClient(Arrays.asList(GREETING, ok()),
                config("secret").objUris(new ArrayList<String>()).build());
        obj.client.connect();
        obj.client.login();
        Xp ox = xp(obj.fake.written.get(0));
        check("an empty objUris advertises the greeting's objects",
                ox.texts("//e:svcs/e:objURI").equals(
                        Arrays.asList(Namespaces.CONTACT, Namespaces.DOMAIN, Namespaces.HOST)));

        Session ext = makeClient(Arrays.asList(GREETING, ok()),
                config("secret").extUris(new ArrayList<String>()).build());
        ext.client.connect();
        ext.client.login();
        Xp ex = xp(ext.fake.written.get(0));
        check("an empty extUris announces no extensions, which is legitimate",
                ex.count("//e:svcExtension") == 0);
        check("and the objects are still there", ex.count("//e:svcs/e:objURI") == 3);

        // A list the caller DID configure still wins outright, which is what the override is for.
        Session pinned = makeClient(Arrays.asList(GREETING, ok()),
                config("secret").objUris(Arrays.asList(Namespaces.DOMAIN)).build());
        pinned.client.connect();
        pinned.client.login();
        check("a configured objUris still overrides the greeting",
                xp(pinned.fake.written.get(0)).texts("//e:svcs/e:objURI")
                        .equals(Arrays.asList(Namespaces.DOMAIN)));
    }

    // ENUM-SHAPED ARGUMENTS ARE CHECKED AGAINST THE SET THE SCHEMA FIXES.
    //
    // Each of the five was verified schema-refused, and each is documented as a closed set - so the library knew
    // the set while letting the value through. The refusals are bare 2001s and 2306s naming no attribute.
    private static void enumArguments() {
        System.out.println("enum-shaped arguments are refused against the schema's own set");
        Session s = makeClient(Arrays.asList(GREETING, ok(), ok()));
        s.client.connect();
        final Client c = s.client;

        check("a transfer op outside the five is refused",
                argFails(() -> c.domain().transfer("renew", "example.com.ua", "pw")));
        check("and on a contact transfer too",
                argFails(() -> c.contact().transfer("accept", "REG-0001", "pw")));
        check("an info hosts outside all/del/sub/none is refused",
                argFails(() -> c.domain().info("example.com.ua", null, "everything")));
        check("a contact role outside admin/billing/tech is refused",
                argFails(() -> c.domain().create("role.com.ua",
                        map("registrant", "REG-0001", "contacts", map("reseller", "RES-0001")))));
        check("and on an update block too", argFails(() -> c.domain().update("role.com.ua",
                map("add", map("contacts", map("reseller", "RES-0001"))))));
        check("a postalInfo type outside int/loc is refused",
                argFails(() -> c.contact().create("REG-0004", map("email", "a@b.ua", "type", "ascii",
                        "name", "A", "city", "Kyiv", "cc", "UA"))));
        check("a disclosed field's form outside int/loc is refused",
                argFails(() -> c.contact().create("REG-0005", map("email", "a@b.ua",
                        "name", "A", "city", "Kyiv", "cc", "UA",
                        "disclose", map("flag", Boolean.TRUE, "addr", list("ascii"))))));

        String said = null;
        try {
            c.domain().transfer("renew", "example.com.ua", "pw");
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("the message names the accepted set",
                said != null && said.contains("request, approve, reject, cancel, query"));
        check("and the value that was not in it", said != null && said.contains("'renew'"));

        // The legal values are unchanged, including the default hosts scope.
        c.domain().transfer("query", "example.com.ua");
        check("a legal transfer op is unchanged",
                "query".equals(xp(s.fake.written.get(0)).firstText("//e:transfer/@op")));
        c.domain().info("example.com.ua");
        check("and the default hosts scope is still all",
                "all".equals(xp(s.fake.written.get(1)).firstText("//domain:name/@hosts")));
    }

    // check() WITH NO NAMES IS REFUSED, BECAUSE THE FRAME IT WOULD BUILD IS.
    //
    // domain:check, contact:check and host:check each require at least one child, so an empty list built a
    // childless frame - and a caller reaches it by looping over a query string or a basket that turned out empty.
    private static void checkNeedsAName() {
        System.out.println("check() with an empty list is refused");
        Session s = makeClient(Arrays.asList(GREETING));
        s.client.connect();
        final Client c = s.client;
        check("domain:check with no names is refused",
                argFails(() -> c.domain().check(new ArrayList<String>())));
        check("contact:check with no handles is refused",
                argFails(() -> c.contact().check(new ArrayList<String>())));
        check("host:check with no names is refused",
                argFails(() -> c.host().check(new ArrayList<String>())));
        // A list of nothing but blanks is the same thing arriving from a form rather than from a literal.
        check("and a list of nothing but blanks is refused too",
                argFails(() -> c.domain().check(Arrays.asList("", "  "))));
        String said = null;
        try {
            c.domain().check(new ArrayList<String>());
        } catch (ValidationException e) {
            said = e.getMessage();
        }
        check("the message cites the rule", said != null && said.contains("RFC 5731"));
    }

    // fees() READS avail="false" AS UNAVAILABLE.
    //
    // fee-1.0.xsd declares avail as xs:boolean, for which "false" is as legal as "0". Testing for "0" alone read
    // avail="false" as AVAILABLE, so a caller gating a create on the price answer went ahead with a create the
    // registry refuses - while availability() in the same class had the rule right, so the two disagreed on one frame.
    private static void availReadsFalse() {
        System.out.println("fees() reads avail=\"false\" as unavailable, as availability() always did");
        String xml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result><extension>"
                + "<fee:chkData xmlns:fee=\"urn:ietf:params:xml:ns:epp:fee-1.0\">"
                + "<fee:currency>UAH</fee:currency>"
                + "<fee:cd avail=\"false\"><fee:objID>gone.com.ua</fee:objID>"
                + "<fee:reason>Zone is not served</fee:reason></fee:cd>"
                + "<fee:cd avail=\"0\"><fee:objID>zero.com.ua</fee:objID></fee:cd>"
                + "<fee:cd avail=\"true\"><fee:objID>yes.com.ua</fee:objID></fee:cd>"
                + "<fee:cd avail=\"1\"><fee:objID>one.com.ua</fee:objID></fee:cd>"
                + "<fee:cd><fee:objID>absent.com.ua</fee:objID></fee:cd>"
                + "</fee:chkData></extension><trID><svTRID>SRV-1</svTRID></trID></response></epp>";
        Map<String, Object> fees = Response.fromXml(xml).fees();
        check("avail=\"false\" is unavailable", Boolean.FALSE.equals(dig(fees, "gone.com.ua", "avail")));
        check("avail=\"0\" is unavailable too", Boolean.FALSE.equals(dig(fees, "zero.com.ua", "avail")));
        check("avail=\"true\" is available", Boolean.TRUE.equals(dig(fees, "yes.com.ua", "avail")));
        check("avail=\"1\" is available", Boolean.TRUE.equals(dig(fees, "one.com.ua", "avail")));
        // The attribute is declared default="true" and nothing here validates against the schema, so the default
        // has to be applied by hand - absent must not read as unavailable.
        check("an absent avail keeps the schema's default of available",
                Boolean.TRUE.equals(dig(fees, "absent.com.ua", "avail")));
        check("and the reason is still there to show the caller",
                "Zone is not served".equals(dig(fees, "gone.com.ua", "reason")));
    }

    // balance() ANSWERS ONLY ON A BALANCE ANSWER.
    //
    // <fee:balance> and <fee:creditLimit> are legal children of EVERY fee transform result, so an ordinary create
    // carrying a fee echo answered balance() with a block. The manual says a null means "this response is not a
    // balance answer", and availableCredit() is documented as the one to compare a price against - so a caller
    // following the manual treated a create as a balance report.
    private static void balanceIsScopedToABalanceAnswer() {
        System.out.println("balance() reads only a balance infData, not a fee echo on a create");
        String feeCre = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result>"
                + "<resData><domain:creData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                + "<domain:name>prem.com.ua</domain:name></domain:creData></resData>"
                + "<extension><fee:creData xmlns:fee=\"urn:ietf:params:xml:ns:epp:fee-1.0\">"
                + "<fee:currency>UAH</fee:currency><fee:fee>340.20</fee:fee>"
                + "<fee:balance>1200.00</fee:balance><fee:creditLimit>5000.00</fee:creditLimit>"
                + "</fee:creData></extension><trID><svTRID>SRV-1</svTRID></trID></response></epp>";
        Response cre = Response.fromXml(feeCre);
        check("a create carrying a fee echo is not a balance answer", cre.balance() == null);
        check("so availableCredit() says null rather than an empty string", cre.availableCredit() == null);
        check("and threshold() says null too", cre.threshold() == null);
        // The figures are still readable where they belong - as the fee actually charged.
        check("the charged fee is still readable", "340.20".equals(cre.feeAmount()));

        String balXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1000\"><msg>ok</msg></result><resData>"
                + "<balance:infData xmlns:balance=\"" + EXT_BALANCE + "\">"
                + "<balance:creditLimit>5000.00</balance:creditLimit>"
                + "<balance:balance>1200.00</balance:balance>"
                + "<balance:availableCredit>6200.00</balance:availableCredit>"
                + "</balance:infData></resData><trID><svTRID>SRV-1</svTRID></trID></response></epp>";
        Response bal = Response.fromXml(balXml);
        check("a real balance answer still reads", bal.balance() != null);
        check("with its availableCredit", "6200.00".equals(bal.availableCredit()));
        check("and no threshold, because it is a report and not a warning", bal.threshold() == null);

        // The low-balance poll notice carries only balance and threshold, and is the frame that matters most: an
        // acked notice is gone, because the registry keeps no copy.
        String lowXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                + "<result code=\"1301\"><msg>Command completed successfully; ack to dequeue</msg></result>"
                + "<msgQ count=\"1\" id=\"7\"><msg>Balance below threshold</msg></msgQ><resData>"
                + "<balance:infData xmlns:balance=\"" + EXT_BALANCE + "\">"
                + "<balance:balance>90.00</balance:balance>"
                + "<balance:threshold>100.00</balance:threshold>"
                + "</balance:infData></resData><trID><svTRID>SRV-1</svTRID></trID></response></epp>";
        Response low = Response.fromXml(lowXml);
        check("a threshold-only notice is still a balance answer", low.balance() != null);
        check("its balance reads", "90.00".equals(low.currentBalance()));
        check("and the threshold is what tells a warning from a report", "100.00".equals(low.threshold()));

        // A frame with no balance block anywhere - the ordinary case - still says null.
        check("an ordinary info is not a balance answer", Response.fromXml(ok()).balance() == null);
    }

    // THE LIBRARY BEHAVES THE SAME WHATEVER LOCALE THE JVM WAS STARTED IN.
    //
    // Java's no-argument toLowerCase/toUpperCase, SimpleDateFormat and String.format all read the DEFAULT locale,
    // and three of the uses here were wire-visible. The suite forces the two locales that break them rather than
    // trusting the developer's own: tools/run-matrix.sh runs on six runtimes, all of them in one locale.
    private static void defaultLocaleIndependence() {
        System.out.println("no wire value depends on the JVM's default locale");
        java.util.Locale original = java.util.Locale.getDefault();
        try {
            // Turkish: the dotless i. "NS1.INTERNIC.NET".toLowerCase() becomes "ns1.ınternıc.net", and a name read
            // back and handed to an update then removes nothing - the nameserver keeps answering for the zone.
            java.util.Locale.setDefault(new java.util.Locale("tr", "TR"));
            String infoXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                    + "<result code=\"1000\"><msg>ok</msg></result><resData>"
                    + "<domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                    + "<domain:name>example.com.ua</domain:name>"
                    + "<domain:ns><domain:hostObj>NS1.INTERNIC.NET</domain:hostObj></domain:ns>"
                    + "<domain:host>SUB.EXAMPLE.COM.UA</domain:host>"
                    + "</domain:infData></resData><trID><svTRID>SRV-1</svTRID></trID></response></epp>";
            Response info = Response.fromXml(infoXml);
            check("nameservers() folds ASCII, not the Turkish alphabet",
                    info.nameservers().equals(Arrays.asList("ns1.internic.net")));
            check("subordinateHosts() too",
                    info.subordinateHosts().equals(Arrays.asList("sub.example.com.ua")));
            String glueXml = "<?xml version=\"1.0\"?><epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><response>"
                    + "<result code=\"1000\"><msg>ok</msg></result><resData>"
                    + "<domain:infData xmlns:domain=\"urn:ietf:params:xml:ns:domain-1.0\">"
                    + "<domain:ns><domain:hostAttr><domain:hostName>NS1.INTERNIC.NET</domain:hostName>"
                    + "<domain:hostAddr ip=\"v4\">192.0.2.1</domain:hostAddr>"
                    + "</domain:hostAttr></domain:ns></domain:infData></resData>"
                    + "<trID><svTRID>SRV-1</svTRID></trID></response></epp>";
            check("and nameserverAddresses() keys the glue by a name that matches",
                    Response.fromXml(glueXml).nameserverAddresses().containsKey("ns1.internic.net"));

            Session tr = makeClient(Arrays.asList(GREETING, ok(), ok()));
            tr.client.connect();
            // 'ils' upper-cases to 'İLS' under a Turkish default, which [A-Z]{3} refuses.
            tr.client.domain().check(Arrays.asList("example.com.ua"), map("create", 1), "ils");
            check("a check currency folds to ASCII",
                    "ILS".equals(xp(tr.fake.written.get(0)).firstText("//fee:check/fee:currency")));
            // A misspelling must still find its suggestion: normalise() folds authInfo, and a dotless i there means
            // auth_info stops matching and the reader is told their key is unknown with no suggestion at all.
            String said = null;
            try {
                tr.client.domain().create("x.com.ua", map("registrant", "REG-0001", "auth_info", "pw"));
            } catch (ValidationException e) {
                said = e.getMessage();
            }
            check("and a misspelled key still finds authInfo", said != null && said.contains("authInfo"));

            // Thai: a Buddhist-era calendar and Thai digits. This is the worst of them, because the result PASSES
            // the schema and reaches the registry - a clTRID carrying the year 2569 and Thai numerals is accepted
            // and then matches nothing, and correlating a disputed charge against the registry's record is the
            // whole reason the field exists.
            java.util.Locale.setDefault(new java.util.Locale("th", "TH"));
            Session th = makeClient(Arrays.asList(GREETING, ok()));
            th.client.connect();
            th.client.domain().check(Arrays.asList("example.com.ua"));
            String trid = xp(th.fake.written.get(0)).firstText("//e:clTRID");
            check("a clTRID is ASCII throughout", trid != null && trid.matches("^[\\x20-\\x7E]+$"));
            check("and carries a Gregorian year, not a Buddhist one", trid != null && trid.contains("-20"));
            check("and its counter is in ASCII digits", trid != null && trid.matches(".*-\\d{4}$"));
        } finally {
            java.util.Locale.setDefault(original);
        }
    }

    // A Config DOES NOT CHANGE UNDER ITS OWNER.
    //
    // Collections.unmodifiableList is a VIEW: it stops the Config being changed through the field and does nothing
    // about the caller still holding the list they passed in. A caller who builds a list, calls build(), then clears
    // or reuses it for the next tenant changed what this Config said - and the next login advertised the wrong
    // services, or, once an empty objUris meant "not configured", quietly a different set.
    private static void configCopiesItsLists() {
        System.out.println("Config copies the service-URI lists it is given");
        List<String> objs = new ArrayList<String>(Arrays.asList(Namespaces.DOMAIN, Namespaces.CONTACT));
        List<String> exts = new ArrayList<String>(Arrays.asList(Namespaces.SECDNS));
        Config cfg = config("secret").objUris(objs).extUris(exts).build();
        objs.clear();
        exts.add("http://example.invalid/injected-1.0");
        check("clearing the caller's objUris list leaves the Config alone",
                cfg.objUris.equals(Arrays.asList(Namespaces.DOMAIN, Namespaces.CONTACT)));
        check("and adding to their extUris list does not reach it",
                cfg.extUris.equals(Arrays.asList(Namespaces.SECDNS)));

        // And the login built from it advertises what was configured, not what the caller's list became.
        Session s = makeClient(Arrays.asList(GREETING, ok()), cfg);
        s.client.connect();
        s.client.login();
        Xp lx = xp(s.fake.written.get(0));
        check("the login advertises the configured objects",
                lx.texts("//e:svcs/e:objURI").equals(Arrays.asList(Namespaces.DOMAIN, Namespaces.CONTACT)));
        check("and not the injected extension",
                lx.count("//e:extURI[text()=\"http://example.invalid/injected-1.0\"]") == 0);

        // The field is still unmodifiable, so neither half of the promise is traded for the other.
        boolean frozen = false;
        try {
            cfg.objUris.add("http://example.invalid/late-1.0");
        } catch (UnsupportedOperationException e) {
            frozen = true;
        }
        check("and the Config's own list still refuses to be modified", frozen);
    }

    // open() CLOSES WHAT IT IS REPLACING.
    //
    // It is documented as the way to start a fresh connection after a failure, and on a healthy connection it
    // replaced the socket without closing it: the old one was left to the garbage collector, and with it the
    // registry-side session, which counts against the per-registrar session limit until the server times it out. A
    // few of those and the next login is refused 2502 for connections nobody is using.
    private static void reopeningClosesTheOldSocket() {
        System.out.println("Connection.open() closes the socket it replaces");
        Connection conn = new Connection(config("secret").connectTimeout(0.05).build());
        SSLSocket stale;
        try {
            // An unconnected SSLSocket stands in for a live one, as it does in transportRunawayFrame().
            stale = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            Field sockField = Connection.class.getDeclaredField("sock");
            sockField.setAccessible(true);
            sockField.set(conn, stale);
        } catch (Exception e) {
            throw new RuntimeException("cannot stand a fake socket into Connection", e);
        }
        check("the connection starts out open", conn.isOpen());
        check("and its stand-in socket starts out unclosed", !stale.isClosed());
        try {
            // The host does not resolve, so open() fails after doing its cleanup - which is the half being tested.
            conn.open();
        } catch (ConnectionException expected) {
            // Reaching the network is not the point; what happened to the old socket first is.
        }
        check("the socket that was there is CLOSED, not left to the collector", stale.isClosed());
        check("and the connection no longer reports itself open", !conn.isOpen());
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
        // A following digit disqualifies a match so 1.1.10 is not read as 1.1.1; a following dot does not,
        // because epptools-sdk-1.1.1.jar in an install line is exactly the stale version this looks for.
        Pattern looksLikeOurs = Pattern.compile("(?<![\\d.])1\\.\\d+\\.\\d+(?!\\d)");
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

        // THE CHANGELOG IS SCANNED FOR INSTALL COORDINATES ONLY, and it is scanned because it drifted: its
        // build section named epptools-sdk:1.1.1 while everything else said 1.1.2, and 298 assertions passed
        // over it because the scan above skips the file by name.
        //
        // Skipping it wholesale was not wrong, it was too broad. A changelog's job is to name OLD versions -
        // this one discusses 1.0.1, 1.0.2, 1.1.0 and 1.1.1 as history in four separate sentences, and the
        // Keep a Changelog link carries a 1.1.0 of its own - so the loose pattern above would report five
        // false positives and be turned off again within a release. What CANNOT go stale is an install
        // coordinate: a groupId:artifactId:version, a <version> element, a jar filename or a vN.N.N tag. Those
        // are instructions a reader copies, and every one of them must name this release.
        //
        // Each form is anchored to the text around it, not matched loose. The release-tag form especially: a bare
        // vN.N.N also matches the Semantic Versioning link in this changelog's own header, semver.org/spec/v2.0.0,
        // and a check that reports a cited specification as a stale coordinate is a check that gets switched off.
        Pattern coordinate = Pattern.compile(
                "epptools-sdk[:-](\\d+\\.\\d+\\.\\d+)"
                + "|<version>(\\d+\\.\\d+\\.\\d+)</version>"
                + "|--branch v(\\d+\\.\\d+\\.\\d+)"
                + "|<tag>v(\\d+\\.\\d+\\.\\d+)</tag>");
        List<File> withChangelog = new ArrayList<File>(docs);
        withChangelog.add(new File(root, "CHANGELOG.md"));
        List<String> staleCoordinates = new ArrayList<String>();
        int coordinates = 0;
        for (File doc : withChangelog) {
            if (!doc.isFile()) {
                continue;
            }
            Matcher m = coordinate.matcher(readFile(doc));
            while (m.find()) {
                String found = null;
                for (int g = 1; g <= m.groupCount() && found == null; g++) {
                    found = m.group(g);
                }
                coordinates++;
                if (!found.equals(Version.VERSION)) {
                    staleCoordinates.add(doc.getName() + ": " + m.group());
                }
            }
        }
        check("install coordinates are stated somewhere, CHANGELOG.md included", coordinates > 0);
        check("and every one of them names " + Version.VERSION
                + (staleCoordinates.isEmpty() ? ""
                        : " - stale: " + staleCoordinates.subList(0, Math.min(4, staleCoordinates.size()))),
                staleCoordinates.isEmpty());
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
