# EppTools — EPP SDK for Java

A small, **dependency-free** Java client for **any** EPP domain registry — standard
**RFC 5730–5734** EPP over TLS, conventionally on port 700. It speaks the wire protocol directly
(no framework, no server-side code), so you can drop it into any Java 8+ project.
Every command frame is standard, schema-valid EPP.

- TLS transport with correct RFC 5734 framing (4-byte length prefix, UTF-8 byte-safe).
- Session: `connect` / `login` / `logout`, with the login services taken from the server
  greeting automatically (never rejected for an unsupported service).
- Full object commands: **domain**, **contact**, **host** (check / info / create / update /
  delete / transfer / renew), plus **poll**.
- Extensions: **secDNS** (RFC 5910), **RGP restore** (RFC 3915), **fees** (RFC 8748:
  prices in `check`, fee agreement on transforms) and **login security** (RFC 8807).
- **Your registry's own extensions, without configuring anything.** No registry's namespaces are
  compiled in: they are read from the `<greeting>` the server sends before you say a word, so this
  works against a registry it has never seen — and keeps working when one changes its URIs. Override
  them in `Config` for a registry whose naming discovery cannot guess.
- Clean `Response` objects (result code, message, availability map, value getters) and typed
  exceptions, every one of them unchecked.

## Install

Maven:

```xml
<dependency>
  <groupId>io.github.epptools</groupId>
  <artifactId>epptools-sdk</artifactId>
  <version>1.1.2</version>
</dependency>
```

Gradle:

```groovy
implementation 'io.github.epptools:epptools-sdk:1.1.2'
```

Kotlin DSL:

```kotlin
implementation("io.github.epptools:epptools-sdk:1.1.2")
```

No build tool at all? Clone the repo, pinned to a release tag, and compile the sources straight into
your own tree — there is nothing to resolve:

```bash
git clone --branch v1.1.2 https://github.com/epptools/java-sdk
javac --release 8 -d out $(find java-sdk/src/main/java -name '*.java')
```

**Requires Java 8 or newer.** The published jar is compiled with `--release 8`, so one artifact runs
on 8, 11, 17, 21 and every later release. It uses `javax.net.ssl` for TLS and `javax.xml` for DOM,
both part of the JDK, and depends on nothing else — no XML library, no logging facade, no HTTP
client.

## Quick start

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.EppException;

import java.util.Arrays;
import java.util.Map;

Config config = Config.builder("epp.registry.example", "your-clid", "your-secret")
        .port(700)                          // the EPP convention; some registries differ
        .lang("uk")                         // result-message language, from the greeting's <lang> list
        // .caFile("/path/to/ca.pem")       // only for a private-CA or self-signed certificate
        .build();

// close() disconnects, so try-with-resources releases the socket on every way out of the block.
try (Client client = new Client(config)) {
    client.connect();   // TLS + read <greeting>
    client.login();

    Map<String, Boolean> avail = client.domain().check(Arrays.asList("example.com.ua")).availability();
    //  => {example.com.ua=true}

    Response info = client.domain().info("example.com.ua");
    System.out.println(info.value("exDate"));

    client.logout();
} catch (EppException e) {
    System.err.println("EPP error: " + e.getMessage());
}
```

`Client.connectAndLogin(config)` does the first two steps in one call, for a short script.

A `Client` is one EPP session on one socket, so use one from a single thread at a time and give each
thread its own. Two commands in flight on one connection would leave the byte stream at an unknown
offset; the library notices — it checks that every reply carries the `clTRID` that went out, and
closes the connection rather than hand you the previous command's answer — but the command is still
lost, and for a renew or a create that means an unknown outcome.

## TLS notes

| Scenario | Config |
|---|---|
| Public, browser-trusted certificate | nothing — the defaults (`verifyPeer(true)`, `verifyPeerName(true)`) are correct |
| Private-CA or self-signed certificate | `caFile(...)` → the PEM bundle of the CA that signed the **server** certificate |
| Mutual TLS (the registry requires a client certificate) | `clientCert(...)` + `clientKey(...)` (+ `clientKeyPassphrase(...)` if the key is encrypted) |
| Hostname mismatch (development only) | `verifyPeerName(false)` |

**Which of these applies is your registry's choice, so ask them.** Many present an ordinary
browser-trusted certificate, and then there is nothing to configure: with no `caFile` the client
trusts the JDK's own store. Others run their own CA, whose certificate is in that store nowhere:
`caFile` must point at the bundle or the handshake fails with verification errors.

Authentication is clID + password inside TLS. A client certificate is needed only where the registry
requires mutual TLS, and many additionally restrict access to registered source addresses — that
part is policy, not protocol.

Two things are specific to Java and worth knowing before you spend an afternoon on them:

- **A client key must be PKCS#8.** `clientKey` reads a `-----BEGIN PRIVATE KEY-----` block (RSA or
  EC); the older `BEGIN RSA PRIVATE KEY` form is PKCS#1 and the JDK cannot read it. Convert it once:
  `openssl pkcs8 -topk8 -in registrar.key -out registrar.key.pem`. The certificate and the key may
  live in one file — leave `clientKey` unset and the chain's file is read for both.
- **Only TLS 1.2 and 1.3 are offered.** Anything the JDK supports below that is switched off on the
  socket rather than left to the platform default.

`connectTimeout` and `readTimeout` are in **seconds** (10 and 30 by default) and take a fraction if
you want one. The socket's read deadline is never set below one second, however small a value you
pass, because a sub-second deadline on a create or a renew gives up while the registry is still
working.

### When the handshake fails

The commonest first-run failure is certificate verification, and it looks like this:

```
ConnectionException: TLS handshake with epp.registry.example:700 failed - PKIX path building failed:
sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path
to requested target
```

That almost always means `caFile` is unset or points at the wrong bundle. Check it before anything
else:

```bash
openssl s_client -connect epp.registry.example:700 -CAfile /path/to/registry-ca.pem </dev/null
# "Verify return code: 0 (ok)" means the bundle is right; anything else means it is not.
```

**Do not reach for `verifyPeer(false)`.** It installs a trust manager that verifies nothing, and
leaves you sending your clID, your password and every transfer secret to whatever answers on that
address, with no way to tell. If the handshake will not verify, the bundle is wrong — ask the
registry for the current one. `verifyPeerName(false)` is a narrower loosening (right certificate,
wrong hostname) and is occasionally reasonable in development; `verifyPeer(false)` is not reasonable
anywhere.

## Commands

The option-heavy commands take a `Map<String, Object>`, and an unrecognised key is refused rather
than ignored: a key nobody reads never reaches the registry, and the registry answers 1000 for the
command it did receive, so `secdns` for `secDNS` would register the domain **unsigned** and nothing
would say so. Where a command is assembled in pieces, the builders further down read better and
produce the identical frame.

```java
// Session
client.connect(); client.login(); client.logout(); client.disconnect();
client.login("new-password");    // rotate the EPP password during login (6-16 characters, or up to
                                 // 128 where the server offers RFC 8807)
client.hello();                  // re-read the greeting / keep-alive
client.isConnected(); client.isLoggedIn(); client.greeting();

// Domain
client.domain().check(Arrays.asList("example1.com.ua", "example2.com.ua"));
client.domain().info("example1.com.ua");
client.domain().info("example1.com.ua", "pw", "all");   // hosts: all (default) | del | sub | none

Map<String, Object> contacts = new LinkedHashMap<>();
contacts.put("admin", "ADM-0001");
contacts.put("tech", Arrays.asList("TEC-0001", "TEC-0002"));        // one handle in a role, or several

Map<String, Object> create = new LinkedHashMap<>();
create.put("years", 1);
create.put("registrant", "REG-0001");
create.put("contacts", contacts);
create.put("nameservers", Arrays.asList("ns1.example.net", "ns2.example.net"));
// Or with the glue inlined, where the registry wants the addresses with the name rather than a
// reference to a host object you created first. A command uses one model or the other — a mixture is
// a ValidationException here rather than a 2001 from the registry:
//   Map<String, Object> ns1 = new LinkedHashMap<>();
//   ns1.put("name", "ns1.example.net");
//   ns1.put("addresses", Arrays.asList("203.0.113.1", "2001:db8::1"));
//   create.put("nameservers", Arrays.asList(ns1));
create.put("authInfo", "pw");
create.put("license", "TM-123");                        // where your registry requires one
Map<String, Object> ds = new LinkedHashMap<>();
ds.put("keyTag", 12345); ds.put("alg", 8); ds.put("digestType", 2); ds.put("digest", "ABCD...");
Map<String, Object> secDns = new LinkedHashMap<>();
secDns.put("dsData", Arrays.asList(ds));                // dsData OR keyData — RFC 5910 makes them
                                                        // a choice and a block carrying both is
                                                        // refused. To send a DS record with the
                                                        // DNSKEY it came from, nest the key inside
                                                        // the record: ds.put("keyData", key)
secDns.put("maxSigLife", 604800);                       // seconds, 1 or more (RFC 5910)
create.put("secDNS", secDns);
client.domain().create("example1.com.ua", create);

Map<String, Object> add = new LinkedHashMap<>();
add.put("ns", Arrays.asList("ns3.example.net"));
add.put("statuses", Arrays.asList("clientHold"));
Map<String, Object> change = new LinkedHashMap<>();
change.put("registrant", "REG-0009");
change.put("authInfo", "newpw");                        // or clearAuthInfo=true to REMOVE it
Map<String, Object> update = new LinkedHashMap<>();
update.put("add", add);
update.put("change", change);                           // "chg" is accepted too, and so is "rem"
                                                        // for "remove" — see the note below
client.domain().update("example1.com.ua", update);

client.domain().renew("example1.com.ua", "2027-01-15", 1);
client.domain().renew("example1.com.ua", info.expiryDate(), 1);  // a full exDate is trimmed for you
client.domain().restore("example1.com.ua");             // RGP restore (op="request")
client.domain().delete("example1.com.ua");
client.domain().transfer("request", "example1.com.ua", "pw");
client.domain().transfer("request", "example1.com.ua", "pw", 1, "120.00");   // years, fee cap

// Prices (RFC 8748 fee extension) — every fee argument below is OPTIONAL. Without one the command
// goes out plainly and the registry's own price is charged. Two independent uses: ASK the price in
// check(); CAP what you agree to pay on a transform — if the actual price is HIGHER (tariff change,
// premium name, stale cache) the command is refused (2004) and nothing is charged, instead of
// silently billing you more.
Map<String, Object> feeQuery = new LinkedHashMap<>();
feeQuery.put("create", 1);
// A whole price table in ONE round trip: a LIST of years asks the same operation at each period.
// Up to 20 entries per frame; transfer and restore are one-year operations however many you ask.
feeQuery.put("renew", Arrays.asList(1, 2, 3, 5, 10));
Response quote = client.domain().check(Arrays.asList("example1.com.ua"), feeQuery, "UAH");
quote.fees();                                     // per-name prices, {} when the answer carried none
quote.feeFor("example1.com.ua", "renew", 5);       // "480.00", or null with a reason in fees()
create.put("fee", "100.00");                       // "I agree to pay up to 100.00" — not a price you set
Map<String, Object> cap = new LinkedHashMap<>();
cap.put("amount", "90.00");
cap.put("currency", "UAH");
client.domain().renew("example1.com.ua", "2027-01-15", 1, cap);
client.domain().restore("example1.com.ua", "500.00");    // your cap, not a published price

// Contact
client.contact().check(Arrays.asList("REG-0001"));
client.contact().info("REG-0001", "pw");
Map<String, Object> contact = new LinkedHashMap<>();
contact.put("name", "ACME");
contact.put("city", "Kyiv");
contact.put("cc", "UA");
contact.put("email", "contact@example.com");             // required by RFC 5733
contact.put("authInfo", "pw");
// contact.put("postalInfos", Arrays.asList(intBlock, locBlock));   // int + localized, each a Map
// contact.put("disclose", discloseBlock);                          // RFC 5733 privacy
client.contact().create("REG-0001", contact);
// No naming scheme of your own? Let the registry choose the handle and read it back. Every call
// mints a fresh one, so a repeat is a second contact rather than a 2302 collision.
String handle = client.contact().createAuto(contact).objectName();  // appears HERE and nowhere else
Map<String, Object> postal = new LinkedHashMap<>();
// A postalInfo is REPLACED, not merged. It carries name, city and cc whenever you touch it, because
// the schema makes them required and a registry that replaces stores exactly what you sent — and for
// the same reason a field you LEAVE OUT is deleted, not preserved. Send the whole block every time:
// read the current one with contact().info() and apply your change to it. A field given as "" is
// cleared explicitly, which is the only way to remove org, sp or pc.
postal.put("name", "New Name");
postal.put("city", "Lviv");
postal.put("cc", "UA");
postal.put("org", "");
Map<String, Object> contactChange = new LinkedHashMap<>();
contactChange.put("email", "new-contact@example.com");
contactChange.put("postalInfo", postal);
Map<String, Object> contactUpdate = new LinkedHashMap<>();
contactUpdate.put("change", contactChange);
contactUpdate.put("addStatuses", Arrays.asList("clientUpdateProhibited"));
client.contact().update("REG-0001", contactUpdate);
client.contact().delete("REG-0001");
client.contact().transfer("request", "REG-0001", "pw");

// Host
client.host().check(Arrays.asList("ns1.example.net"));
client.host().info("ns1.example.net");
client.host().create("ns1.example.net", Arrays.asList("203.0.113.10", "2001:db8::1"));  // v4/v6 auto-detected
Map<String, Object> hostChange = new LinkedHashMap<>();
hostChange.put("addAddresses", Arrays.asList("203.0.113.11"));
client.host().update("ns1.example.net", hostChange);
// No rename: host:chg is ignored by the registries this was built against, so update() refuses a
// "newName" option with a ValidationException instead of answering 1000 with nothing changed. Create
// the new host, repoint the domains, then delete the old one.
client.host().delete("ns1.example.net");
client.host().delete("ns1.example.net", true);      // force: detach from every domain first — needs
                                                    // the registry's own extension, and says so when
                                                    // the greeting offers none

// Poll & balance
Response msg = client.poll().request();             // 1301 with a message, 1300 when empty
if (msg.messageId() != null) {                      // messageCount() = how many remain
    msg.queueMessage();                             // the NOTICE text (<msgQ><msg>) — read this
    msg.queueMessageLang();                         // its language: "uk" | "ru" | "en"
    msg.queueDate();                                // when it was queued
    msg.change();                                   // RFC 8590: what the registry DID to your object,
                                                    // as data rather than as the sentence above
    client.poll().ack(msg.messageId());             // ack DESTROYS it at the registry
}
Map<String, String> b = client.balance().balance();  // creditLimit, balance, availableCredit
```

`rem` and `chg` are EPP's own abbreviations, and both are accepted wherever the plain word is:
`remove` for `rem`, `change` for `chg`, `removeAll` for `remAll`, `removeAddresses` for
`remAddresses`, `removeStatuses` for `remStatuses`. Neither spelling is deprecated, and passing both
is not an error — they are two names for one key, and the plain word wins.

## Responses

Every command returns a `Response`:

```java
r.code();            // int EPP result code (1000, 1001, 2303, ...)
r.isSuccess();       // true for 1xxx
r.isPending();       // true for 1001 (registry resolves via a poll message)
r.message();         // human-readable <msg>
r.messageLang();     // "en" | "uk" | "ua" | "ru"
r.availability();    // Map<String, Boolean> for *:check
r.statuses();        // ["ok"] or ["clientHold", ...] — from the status `s` attribute
r.value("exDate");   // first element with that local name
r.values("hostObj"); // all elements with that local name (nameservers are <domain:hostObj>)
r.balance();         // Map: creditLimit, balance, availableCredit — or null when this response is
                     // not a balance answer. Read only from a balance infData, never from the
                     // <fee:balance> a create or a renew echoes back
r.threshold();       // the figure whose crossing queued a low-balance notice, or null on a plain
                     // balance report — its presence is what tells a warning from a report
r.prices();          // domain:info hint: {renewal={value=…, currency=UAH}, ...}
r.fees();            // check+fee: per-name RFC 8748 prices (see above), empty when absent
r.chargedFee();      // transform echo: {currency=UAH, fee=100.00} or null
r.priceChannel();    // domain:info: which price channel those prices belong to, or null
r.license();         // a trademark or licence number, or null
r.rgpStatus();       // ["redemptionPeriod"], ...
r.transferStatus();  // "pending" | "serverApproved" | ... or null
r.dsRecords();       // [{keyTag=…, alg=…, digestType=…, digest=…}, ...]
r.keyRecords();      // [{flags=…, protocol=…, alg=…, pubKey=…}, ...]
r.isSigned();        // boolean: any DNSSEC data present
r.messageId();       // poll: id to pass to poll().ack(); messageCount() = queue size
r.queueMessage();    // poll: the NOTICE text (<msgQ><msg>), NOT the result banner
r.queueMessageLang();// poll: the notice's language ("uk" | "ru" | "en")
r.queueDate();       // poll: when the notice was queued
r.change();          // poll, RFC 8590: what the registry did to your object, or null —
                     // {operation=delete, op=, state=before, date=…, svTRID=…, who=Registry,
                     //  reason=deleted}
r.pendingActionData();// poll: the outcome of a 1001 the registry processed offline — object,
                     // success, the ORIGINAL command's clTRID and svTRID, date. null when none
r.errorReasons();    // extra <extValue><reason> text on a failed command
r.svTRID();          // server transaction id;  r.clTRID() = the one your client sent
r.raw();             // the response exactly as it arrived on the wire
r.dom();             // the parsed org.w3c.dom.Document, and resData() its <resData> element,
                     // for anything bespoke
r.isGreeting();      // and serviceObjUris() / serviceExtUris(): what the server advertised
```

### Reacting to changes you did not make (RFC 8590)

Some poll notices describe something that happened to one of your objects without you asking: it
stopped existing at the registry, or it left on a transfer. Those are the notices you have to act on
automatically — stop billing it, tell your customer, drop it from your own store — and the `<msg>`
they carry is a sentence written in your account's notification language, so there is nothing there
a program can rely on.

`change()` is the same event as data. The object itself is in the response as usual, so the ordinary
accessors work on it:

```java
Response msg = client.poll().request();
Map<String, String> chg = msg.change();
if (chg != null) {
    chg.get("operation");   // "delete" | "transfer" | "renew" | "update" | "restore" | "autoRenew" | …
    chg.get("who");         // who did it. "Registry" = the registry, not you
    chg.get("reason");      // the registry's finer name for the event, where it has one
    msg.objectName();       // …and the object it happened to
}
```

**`state` matters.** It says whether the object beside the change describes it **`before`** the
change or **`after`** it. A domain that no longer exists can only be described as it last was, so
those notices read `before` — writing such a block into your store as the object's *current* state
is how a deleted domain comes back to life in your own records. An absent attribute means `after`,
which is the schema's default, so `state` is never empty.

To receive this at all, announce `urn:ietf:params:xml:ns:changePoll-1.0` at login. This library
mirrors the server's greeting into `<svcs>`, so a server that offers it is announced automatically;
use `Config.builder(...).extUris(...)` if you pin your own service list. A server sends `changeData`
only to a client that asked for it, and `change()` returns `null` where there is none.

### Reading an object without touching XML

The getters above return the frame's shape; these return the answer. Everything an `info`, `check`
or `transfer` response carries has a named accessor, so you never index into a map by a string you
had to guess.

```java
// Any object
r.objectName();     // the domain name, the host name or the contact HANDLE
r.roid();           // the registry's own identifier
r.sponsor();        // clID — the registrar it belongs to now
r.createdBy();      // crID          r.createdDate();  // crDate
r.updatedBy();      // upID, or null when never changed   r.updatedDate();
r.authInfo();       // <authInfo><pw> — the transfer secret; never log it

// Domain
r.expiryDate();            // exDate, exactly as the registry wrote it (see the note below)
r.registrant();            // the registrant handle
r.contacts();              // {admin=[c-1], tech=[c-1, c-2]}
r.techContacts();          // just that role — also adminContacts() / billingContacts()
r.contactsFor("tech");     // any role, matched case-insensitively; empty when nobody holds it
r.allContacts();           // every handle including the registrant, de-duplicated
r.nameservers();           // names, whether the registry sent hostObj or hostAttr
r.nameserverAddresses();   // hostAttr glue, keyed by nameserver name
r.subordinateHosts();      // hosts living UNDER this domain — they block a delete
r.transfer();              // status, requestedBy, requestedAt, actingClient, actBy, expiryDate
r.transferDate();          // when it last changed hands, or null
r.registrarOfRecord();     // the handle the registry's own WHOIS/RDAP publishes as the registrar
                           // — which for a reseller is not the same party as sponsor()

// Host
r.hostAddresses();  // [{ip=192.0.2.1, version=v4}, ...]

// Contact
r.postalInfo();     // {int={...}, loc={...}} — name, org, street (a List), city, sp, pc, cc
r.email();  r.voice();  r.fax();
r.disclose();       // {flag=false, elements=[email, voice]} or null

// Check + money
r.isAvailable("example.com.ua");       // Boolean: true | false | null ("the answer said nothing")
r.unavailableReason("taken.com.ua");   // "In use", or null when it is available
r.isPremium("rare.com.ua");            // priced outside the standard list
r.feeClass("rare.com.ua");             // "premium" | "standard" | null
r.creditLimit();  r.currentBalance();  r.availableCredit();
r.feeAmount();    r.feeCurrency();     // what this transform actually charged
r.extValues();    // per-<extValue>: which ELEMENT the registry rejected, plus the reason
```

Two things worth knowing before you build on these:

- **Dates come back as the registry's own string** (`2027-04-01T09:15:00Z`), never a `Date` or an
  `Instant`. The registry decides which calendar day a renewal lands on; re-formatting through a
  local timezone is how a client ends up displaying — and renewing against — the day before. Where a
  command needs the calendar date instead of the timestamp, as `domain:curExpDate` does, the library
  trims it for you: pass what `expiryDate()` returned.
- **Money comes back as an exact decimal string**, never a `double`. `0.1 + 0.2` is not `0.3` in
  binary floating point, and a balance summed that way drifts. Use `BigDecimal`.

## Building a command step by step

The commands that take an option map can also be assembled one named step at a time. Same command,
same frame, same result — the builder calls the ordinary method. What changes is that a misspelling
is a method that does not exist, which the compiler tells you about, instead of a map key nobody
reads.

```java
Response response = client.domain().createBuilder("your-brand.com.ua")
        .years(1)
        .registrant("acme-01")
        .adminContact("acme-01")
        .techContact("acme-ns1").techContact("acme-ns2")   // accumulates
        .nameserver("ns1.acme.example").nameserver("ns2.acme.example")
        // or, where the registry wants the glue inlined instead of a host object:
        // .nameserverWithGlue("ns1.acme.example", "203.0.113.1", "2001:db8::1")
        .authInfo("D0main-Pw")
        .maxFee("180.00", "UAH")      // a cap you consent to, not a price you set
        .send();
```

Available on `domain().createBuilder(name)` / `updateBuilder(name)`,
`contact().createBuilder(id, email)` / `updateBuilder(id)`, and `host().updateBuilder(name)`. Three
things worth knowing:

- **Every list step accumulates.** `.techContact("a").techContact("b")` and `.techContact("a", "b")`
  are the same thing, so building in a loop or behind an `if` reads the way it behaves.
- **Nothing is sent until `send()`.** Until then the builder is an ordinary value you can keep, pass
  around, or inspect with `toOptions()` — which returns exactly the map the direct call takes, deeply
  copied, so what you logged cannot change under you afterwards.
- **A builder sends once.** Sending twice would be two registrations and two charges, so the second
  `send()` is refused. Build another; they are cheap.

An update builder names the block each change lands in — `addNameserver`, `remStatus`,
`changeRegistrant` — because an EPP update is a delta, and which block a change belongs to is the
whole semantics of the command.

The steps, in full:

- `domain().createBuilder(name)`: `years`, `registrant`, `contact(role, …)`, `adminContact`,
  `techContact`, `billingContact`, `nameserver`, `nameservers`, `nameserverWithGlue`, `authInfo`,
  `license`, `maxFee`, `dsRecord`, `dsRecordWithKey`, `keyRecord`, `maxSigLife`. The role a
  `contact(role, …)` takes is `admin`, `billing` or `tech` and nothing else — RFC 5731 closes that
  set, so a fourth name refuses the whole command rather than adding a contact the registry ignores.
- `domain().updateBuilder(name)`: `addNameserver(s)`, `remNameserver(s)`, `addContact`, `remContact`,
  `addStatus`, `remStatus`, `changeRegistrant`, `changeAuthInfo`, `clearAuthInfo`, `restore`,
  `license`, `maxFee`, `addDsRecord`, `remDsRecord`, `addKeyRecord`, `remKeyRecord`,
  `removeAllDnssec`, `maxSigLife`.
- `contact().createBuilder(id, email)`: `internationalAddress`, `localizedAddress`, `voice`, `fax`,
  `authInfo`, `publish`, `withhold`. Pass `Contact.AUTO_ID` as the id to have the registry mint the
  handle.
- `contact().updateBuilder(id)`: `changeInternationalAddress`, `changeLocalizedAddress`,
  `changeVoice`, `changeFax`, `changeEmail`, `changeAuthInfo`, `publish`, `withhold`, `addStatus`,
  `remStatus`.
- `host().updateBuilder(name)`: `addAddress(es)`, `remAddress(es)`, `addStatus`, `remStatus`.

`clearAuthInfo()` is not `changeAuthInfo("")`. It sends
`<domain:authInfo><domain:null/></domain:authInfo>`, which removes the transfer secret; an empty
password stores the empty string, which a holder can still present, so the domain stays as movable as
it was. After a leak that is the difference that matters. Asking for both in one update is refused
rather than resolved, because the schema cannot express both and guessing which you meant is not the
library's call.

## Reading the message queue

```java
int processed = client.poll().drain(notice ->
        store(notice.queueMessage(), notice.pendingActionData()));
```

The order matters and is the reason this helper exists: each notice is acknowledged only **after**
your callback returns. An ack deletes the notice at the registry permanently, so a loop that acks
first and processes second loses every notice whose processing fails — a transfer request, the
outcome of a pending create — with nothing left to retry from. If your callback throws, the notice
stays in the queue and the exception reaches you.

Delivery is at least once: if the ack itself is lost, the next drain hands you the same notice again.
Make the callback idempotent and use `messageId()` as the de-duplication key. `drain(handler, limit)`
stops after that many notices; the default of 0 means "until the queue is empty", and only a 1300
counts as empty — any other reply carrying no notice is raised rather than read as a drained queue.

## Session security warnings (RFC 8807)

Where the server offers the Login Security extension, the login carries a small block identifying
this client, and the server answers with anything it wants you to fix about the session:

```java
for (Map<String, String> event : client.login().securityEvents()) {
    // type: certificate | cipher | tlsProtocol | password | newPW | stat | custom
    // level: "warning" or "error";  text: a sentence to show an operator
    alert(event.get("level"), event.get("type"), event.get("text"), event.get("exDate"));
}
```

The list is empty on a healthy session, so treat any entry as something to act on. The commonest one
is a client certificate approaching its expiry date — the alternative to hearing about it here is
finding out on the morning it stops working.

A server sends these only to a client that took part in the extension, because announcing a URI is
not evidence of supporting it. That is why the block goes out even when nothing needs to travel in
it. If you would rather stay off the extension, set `loginSecurity(false)` in the config; it is
still used for a password longer than the 16 characters the base `<pw>` element can carry, since
there is nowhere else for that to go.

## Logging

`Client.Logger` is three methods and no dependency, so it wraps whatever your application already
uses:

```java
Client client = new Client(config, null, new Client.Logger() {
    @Override public void debug(String message)   { log.debug(message); }
    @Override public void info(String message)    { log.info(message); }
    @Override public void warning(String message) { log.warn(message); }
});
// or later: client.setLogger(logger);
```

Every request and response frame is logged at `debug` and each result at `info` or `warning`, with
**passwords and authInfo masked** — `<pw>` and `<newPW>` in any namespace, which is where a transfer
secret lives too — so secrets never reach your logs. `Config.toString()` leaves out the password and
the key passphrase for the same reason.

## Error handling

Every failure extends `EppException`, which extends `RuntimeException`, so one `catch` handles
everything and no method signature carries a `throws`. Beyond that, a class exists where the right
next step differs — and nowhere else:

| Catch | When | What to do |
|---|---|---|
| `ValidationException` | a value in THIS call is unusable; nothing was sent | fix the arguments |
| `ConfigException` | the client is set up wrong: no host, no credentials, an extension the server does not offer | fix the deployment; every call fails until then |
| `ConnectionException` | TLS, timeout, framing, a desynchronised stream | see the TLS notes above; the connection is closed |
| `InsufficientFundsException` | 2104 | **stop the batch**, top up, resume — every later billable command fails the same way |
| `AuthorizationException` | 2201 / 2202 | not yours, or the wrong authInfo |
| `ObjectExistsException` | 2302 | already registered |
| `ObjectDoesNotExistException` | 2303 | stale handle or typo |
| `ObjectStatusException` | 2304 / 2305 | clear the status or association, then repeat |
| `PolicyException` | 2306 / 2308 | the registry's rules refuse this value |
| `SessionException` | 2500–2502 | reconnect and log in again |
| `AuthenticationException` | 2200 | the login itself failed |
| `CommandException` | any other ≥ 2000 | branch on `eppCode()` |

```java
import com.epptools.sdk.exception.CommandException;
import com.epptools.sdk.exception.InsufficientFundsException;
import com.epptools.sdk.exception.ObjectExistsException;

for (String name : namesToRegister) {
    try {
        client.domain().createBuilder(name).years(1).registrant("acme-01").send();
    } catch (InsufficientFundsException e) {
        // Not this name's problem — the account's. Carrying on would produce the same failure for
        // every remaining name.
        alertBilling(e.getMessage());
        break;
    } catch (ObjectExistsException e) {
        taken.add(e.subject() != null ? e.subject() : name);   // which one the registry objected to
    } catch (CommandException e) {
        if (!e.isRetryable()) {
            throw e;                                           // retrying cannot change the answer
        }
        retryLater.add(name);
    }
}
```

`isRetryable()` is true only for failures about the moment rather than the request (2400, and the
2500-family after you reconnect). It is deliberately false for everything else: retrying a 2302
cannot make the name free, and a loop that treats every failure as transient turns one refusal into
a rate-limit ban.

Every `CommandException` carries `eppCode()`, the full `response()`, `subject()` — the one name in a
batch of five the registry objected to, when it said which — and `reasons()`, the extra diagnostic
text. `ResultCode` has named constants for every code, and `throwOnFailure(false)` on the client
turns throwing off entirely if you would rather read `response.code()` yourself.

### When a transform fails and you do not know whether it happened

A read timeout, a dropped connection or a `ConnectionException` in the middle of a `create`, `renew`
or `transfer` leaves a genuinely unknown outcome: the registry may have carried the command out and
billed you before the reply was lost. This library cannot tell the difference, and neither can you
from the exception.

**Do not simply retry.** A blind retry is how a domain gets registered — and paid for — twice.
Instead, ask the registry what is true: `domain().info()` for a create, and compare `expiryDate()`
against what you expected for a renew. Reconcile from that, then retry only if the object really is
in the state you started from. A failure whose outcome you cannot determine deserves an operator's
attention, not an automatic second attempt.

## Custom frames

Anything the high-level API doesn't cover can be built with `Frame` and sent raw. `client.frame()`
returns one with a client transaction id already stamped:

```java
import com.epptools.sdk.Frame;
import com.epptools.sdk.Namespaces;
import org.w3c.dom.Element;

Frame frame = client.frame();                 // or Frame.command("my-trid-1") for an id of your own
Element check = frame.ns(frame.verb("check"), Namespaces.DOMAIN, "domain:check");
frame.ns(check, Namespaces.DOMAIN, "domain:name", "example3.com.ua");
Response resp = client.request(frame);        // or client.request(rawXmlString)
```

Text is set on element nodes rather than concatenated into a string, so escaping is the DOM's problem
and not yours, and `toXml()` is safe to call more than once — log the frame, then send it.

## The same SDK in other languages

The four libraries are one product and carry one version between them: the same commands, the same
option names, the same response accessors, the same result codes.

| Language | Install | Source |
|---|---|---|
| PHP | `composer require epptools/sdk` | https://github.com/epptools/php-sdk |
| Node.js | `npm install @epptools/sdk` | https://github.com/epptools/node-sdk |
| Python | `pip install epptools` | https://github.com/epptools/python-sdk |
| Java | `io.github.epptools:epptools-sdk` | https://github.com/epptools/java-sdk |

## Support

Questions about the library, a frame the registry rejected, or a bug: **https://github.com/epptools/java-sdk/issues**.

When reporting a problem, include the **svTRID** from the response (`svTRID()`) and the clTRID your
client sent (`clTRID()`) — together they identify the exact transaction in the registry's logs, which
is what makes a report answerable without a round trip. Send the frames too if you can, but **redact
`<pw>`, `<newPW>` and `<authInfo>` first**: those are live credentials, and the library masks them
in its own logs for the same reason.

Account, billing and registration questions go to your registry account manager, not here — this
address is for the client libraries.

## License

MIT — see [LICENSE](LICENSE).
