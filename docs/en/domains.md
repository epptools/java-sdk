# Domains

Domain objects follow **RFC 5731**, with DNSSEC from **RFC 5910**, the redemption-period restore from
**RFC 3915**, prices from **RFC 8748**, and the registry's own extension where it has one.
Every domain command is reached through `client.domain()`, and every one of them returns a
[`Response`](responses.md).

Everything on this page assumes a connected, logged-in client — see [Session](session.md) for how to
get one, and [Commands](commands.md) for what a command and a response are in general.

Two habits to carry through the whole page:

- **Dates come back as the registry's own string** (`2027-04-01T09:15:00Z`), never an `Instant` or a
  `Date`. The registry decides which calendar day a renewal lands on; re-formatting through a local
  timezone is how a client ends up displaying — and renewing against — the day before.
- **Money comes back as an exact decimal string**, never a `double`. A balance summed through binary
  floating point drifts. Use `BigDecimal` or integer minor units.

## The methods

| Method | EPP command |
|---|---|
| `check(List<String> names, Map<String,Object> fee, String currency)` · `check(names)` | `<check>` + optional `<fee:check>` |
| `info(String name, String authInfo, String hosts)` · `info(name)` | `<info>` |
| `create(String name, Map<String,Object> options)` | `<create>` |
| `createBuilder(String name)` → `DomainCreateBuilder` | builds a `<create>` |
| `update(String name, Map<String,Object> options)` | `<update>` |
| `updateBuilder(String name)` → `DomainUpdateBuilder` | builds an `<update>` |
| `delete(String name)` | `<delete>` |
| `renew(String name, String curExpDate, int years, Object fee)` · `renew(name, curExpDate, years)` · `renew(name, curExpDate)` | `<renew>` |
| `transfer(String op, String name, String authInfo, Integer years, Object fee)` · `transfer(op, name, authInfo)` · `transfer(op, name)` | `<transfer op="…">` |
| `restore(String name, Object fee)` · `restore(name)` | `<update>` + `<rgp:restore op="request"/>` |

`create()` and `update()` take a `Map<String, Object>` of options. **An option key this library does
not understand is refused with a `ValidationException` before any frame is built**, naming the
closest key it knows. That matters more than it looks: a silently ignored `"secdns"` registers the
domain unsigned and the registry still answers `1000`, because as far as it is concerned you never
asked.

---

## check

```java
Response check(List<String> names, Map<String, Object> fee, String currency);
Response check(List<String> names);
```

**On the wire:** `<command><check><domain:check><domain:name>…` — RFC 5731 §3.1.1. Each name in
`names` becomes one `<domain:name>`. When `fee` or `currency` is given, a `<fee:check>` block
(RFC 8748) rides along in `<extension>`; see [Balance & prices](balance.md) for the whole fee
surface.

Availability is carried in the payload, not in the result code: a check that answers "taken" is a
**successful** command.

```java
Response r = client.domain().check(Arrays.asList("example.com.ua", "taken.com.ua"));

r.availability();                        // {example.com.ua=true, taken.com.ua=false}
r.isAvailable("example.com.ua");         // Boolean.TRUE | Boolean.FALSE | null
r.unavailableReason("taken.com.ua");     // "In use", or null when it is available
```

`isAvailable()` returns a `Boolean`, and `null` when the answer said nothing about that name. Prefer
it to reading `availability()` by hand, which gives you `null` both for "taken" and for "you
misspelled the key" — two answers that must not look the same on the line that registers the name.
Compare with `Boolean.TRUE.equals(…)` rather than unboxing it, so an unknown name is a "no" and not
a `NullPointerException`:

```java
for (Map.Entry<String, Boolean> entry : client.domain().check(candidates).availability().entrySet()) {
    if (Boolean.TRUE.equals(entry.getValue())) {
        register.add(entry.getKey());
    }
}
```

An availability answer is a snapshot, not a reservation. Between the check and the create somebody
else can take the name, and you find out from a `2302` on the create — which is the authoritative
answer.

**Result codes:** `1000` for any well-formed check. `2005` names a syntactically invalid domain name,
`2307` a zone this registry does not serve, `2306` a fee rider the registry's policy refuses. A
fee query with more than 20 entries is refused by this library with a `ValidationException` before
it is sent, and so is a currency named with no operations at all — a currency on its own prices
nothing. An **empty list** is refused too: the frame it would build has no child at all, and the
schema requires one — so a loop over a query string or a basket that turned out to be empty fails
here, by name, instead of spending a round trip.

---

## info

```java
Response info(String name, String authInfo, String hosts);
Response info(String name);
```

**On the wire:** `<command><info><domain:info><domain:name hosts="all">` — RFC 5731 §3.1.2. Pass
`authInfo` and it goes out as `<domain:authInfo><domain:pw>`, which is how a registrar that does
**not** sponsor the domain reads the full record. `null` omits the element.

`hosts` selects which hosts the answer lists, and is the RFC 5731 `hosts` attribute. The
one-argument overload sends `all`.

| value | the answer lists |
|---|---|
| `all` (the default) | delegated nameservers and subordinate hosts |
| `del` | delegated nameservers only |
| `sub` | subordinate hosts only |
| `none` | neither |

Those four and nothing else — RFC 5731’s `hostsType` is a closed enumeration, so a `hosts` outside them
is a `ValidationException` naming the set rather than a frame the registry refuses without naming the
attribute.

```java
Response info = client.domain().info("example.com.ua");

info.objectName();          // "example.com.ua"
info.roid();                // the registry's own object id
info.statuses();            // [ok] or [clientHold, clientTransferProhibited, …]
info.expiryDate();          // "2027-04-01T09:15:00Z" — the registry's own string
info.createdDate();         // crDate            info.createdBy();   // crID
info.updatedDate();         // upDate, or null   info.updatedBy();   // upID, or null
info.sponsor();             // clID — the account it belongs to now
info.registrarOfRecord();   // the handle the registry's WHOIS/RDAP publishes, when it differs
info.transferDate();        // when it last changed hands, or null

info.registrant();          // the registrant handle
info.contacts();            // {admin=[acme-01], tech=[acme-01, acme-02]}
info.adminContacts();       // just that role — also techContacts() / billingContacts()
info.contactsFor("tech");   // any role, matched case-insensitively; empty when nobody holds it
info.allContacts();         // every handle including the registrant, de-duplicated

info.nameservers();         // names, whether the registry answered hostObj or hostAttr
info.nameserverAddresses(); // inline glue keyed by nameserver name, when the registry sends it
info.subordinateHosts();    // hosts living UNDER this domain

info.authInfo();            // the transfer secret — see the warning below
info.license();             // a trademark or licence number, or null
info.rgpStatus();           // [redemptionPeriod] etc., or empty
info.isSigned();            // does the domain carry any DNSSEC data
info.dsRecords();           // [{keyTag=…, alg=…, digestType=…, digest=…}, …]
info.keyRecords();          // [{flags=…, protocol=…, alg=…, pubKey=…}, …]
info.prices();              // {renewal={value=180.00, currency=UAH}, …}
info.priceChannel();        // which catalogue row those prices came from, or null
```

`authInfo()` is the secret that lets **any** registrar take the domain away from you. It comes back
only to the sponsoring registrar. Never log it, never paste it into a support ticket, and roll it
once a customer has had it — see [Revoking a leaked transfer code](#revoking-a-leaked-transfer-code).

Two accessors are worth reading together. `nameservers()` gives the names under either EPP model, so
use it for the list; `nameserverAddresses()` is populated only where the registry answers with inline
glue, so an empty result there does **not** mean the domain is undelegated. Where the registry
answers with host-object references you fetch the addresses with one [`host().info()`](hosts.md#info)
per name.

`subordinateHosts()` is what to check before a delete: the registry refuses to delete a domain while
hosts still live under it.

**Result codes:** `1000`; `2202` (wrong `authInfo` as a non-sponsor); `2303` (no such domain).

---

## create

```java
Response create(String name, Map<String, Object> options);
```

**On the wire:** `<command><create><domain:create>` — RFC 5731 §3.2.1, plus `<secDNS:create>`
(RFC 5910), `<registry:create><registry:license>` and `<fee:create>` (RFC 8748) in `<extension>` where you ask
for them. **The create fee is charged on success.**

### Every option

| key | value | wire |
|---|---|---|
| `years` | `int` | `<domain:period unit="y">` — omit to take the registry's default term |
| `registrant` | handle | `<domain:registrant>` |
| `contacts` | a `Map` of role to handle, or of role to `List` of handles | one `<domain:contact type="…">` per handle |
| `nameservers` (also spelled `nameServers`) | `List<String>`, or a `List` of `Map` with `name` and `addresses` | `<domain:ns>` holding `<domain:hostObj>` or `<domain:hostAttr>` |
| `authInfo` | `String` | `<domain:authInfo><domain:pw>` |
| `license` | `String` | `<registry:license>` inside `<registry:create>` |
| `secDNS` | a `Map` with `maxSigLife` and **either** `dsData` **or** `keyData` — not both | `<secDNS:create>` |
| `fee` | `"100.00"`, or a `Map` with `amount` and `currency` | `<fee:create>` — the cap you agree to |

### A first registration

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.EppException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RegisterOne {

    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .caFile("/path/to/registry-ca.pem")
                .build();

        try (Client client = new Client(config)) {
            client.connect();
            client.login();

            Map<String, Object> contacts = new LinkedHashMap<String, Object>();
            contacts.put("admin", "acme-01");
            contacts.put("tech", Arrays.asList("acme-01", "acme-02"));

            Map<String, Object> options = new LinkedHashMap<String, Object>();
            options.put("years", 1);
            options.put("registrant", "acme-01");
            options.put("contacts", contacts);
            options.put("nameservers", Arrays.asList("ns1.acme.example", "ns2.acme.example"));
            options.put("authInfo", "D0main-Pw");

            Response r = client.domain().create("example.com.ua", options);

            // Read the reply. A create answers with the name and the dates the registry assigned.
            System.out.println(r.objectName() + " created " + r.createdDate());
            System.out.println("expires: " + or(r.expiryDate(), "-"));
            System.out.println("charged: " + or(r.feeAmount(), "-") + " " + or(r.feeCurrency(), ""));

            if (r.isPending()) {
                // 1001: the registry queued the registration. It is NOT registered yet, and the
                // outcome arrives later as a poll notice — see poll.md.
                System.out.println("queued for offline processing (svTRID " + r.svTRID() + ")");
            }

            client.logout();
        } catch (EppException e) {
            System.err.println("EPP error: " + e.getMessage());
        }
    }

    private static String or(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
```

### Contacts: one handle per role, or several

A role takes a single handle or a `List` of them, and each handle becomes its own
`<domain:contact type="…">`, which is what RFC 5731 allows. How many a role may hold is registry
policy.

```java
Map<String, Object> contacts = new LinkedHashMap<String, Object>();
contacts.put("admin", "acme-01");
contacts.put("tech", Arrays.asList("acme-01", "acme-02"));
contacts.put("billing", "acme-03");
```

The registrant is **not** one of these — it is its own element with its own meaning, and it is set
with the `registrant` key.

### Nameservers: the two models

A nameserver is either a **name** — a reference to a [host object](hosts.md) that already exists at
the registry — or a name **with its glue addresses inlined**. Ask your registry which model it takes.

```java
// Host-object references (<domain:hostObj>): create the hosts first.
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList("ns1.acme.example", "ns2.acme.example"));
```

```java
// Inline glue (<domain:hostAttr>): the addresses travel with the name.
Map<String, Object> ns1 = new LinkedHashMap<String, Object>();
ns1.put("name", "ns1.example.com.ua");
ns1.put("addresses", Arrays.asList("203.0.113.1", "2001:db8::1"));
Map<String, Object> ns2 = new LinkedHashMap<String, Object>();
ns2.put("name", "ns2.example.com.ua");
ns2.put("addresses", Arrays.asList("203.0.113.2"));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList(ns1, ns2));
```

The IP version is detected from the literal, so `v4` and `v6` end up correctly labelled without you
saying which is which.

RFC 5731 makes `<domain:ns>` a *choice* between the two, so one command uses one model or the other.
A mixture is a `ValidationException` here rather than a bare `2001` from the registry naming no
field:

```java
Map<String, Object> glue = new LinkedHashMap<String, Object>();
glue.put("name", "ns2.acme.example");
glue.put("addresses", Arrays.asList("203.0.113.2"));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList("ns1.acme.example", glue));
client.domain().create("example.com.ua", options);
// ValidationException: nameservers must be all names or all name-with-glue, not a mixture
```

Registering with no `nameservers` at all is legitimate — the domain is undelegated and the registry
reports it as `inactive`, which is a state and not an error.

### authInfo

`<domain:authInfo>` is mandatory on a create, so the element always goes out. Give the `authInfo`
option and your value travels in it; leave it out and an empty `<domain:pw/>` goes instead, which
hands the choice to the registry's own per-zone policy — many zones then mint a code for you, and
you read it back with `info()`. A code you supply must satisfy the zone's strength policy or the
create is refused with `2306`.

### secDNS on create (RFC 5910)

```java
Map<String, Object> ds = new LinkedHashMap<String, Object>();
ds.put("keyTag", 12345);
ds.put("alg", 13);
ds.put("digestType", 2);
ds.put("digest", "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6");
// Optional: the DNSKEY the digest was computed from, as a nested keyData map. A registry that
// accepts it can verify the digest for you; one that does not answers 2306 rather than ignoring it.
//   Map<String, Object> key = new LinkedHashMap<String, Object>();
//   key.put("flags", 257); key.put("protocol", 3); key.put("alg", 13); key.put("pubKey", "AwEAA…");
//   ds.put("keyData", key);

Map<String, Object> secDns = new LinkedHashMap<String, Object>();
secDns.put("maxSigLife", 1209600);
secDns.put("dsData", Arrays.asList(ds));
// Or bare public keys instead of DS records, where the registry takes those:
//   secDns.put("keyData", Arrays.asList(key));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "acme-01");
options.put("secDNS", secDns);

client.domain().create("example.com.ua", options);
```

The `secDNS` map accepts `dsData`, `keyData` and `maxSigLife` and nothing else, and `dsData` and
`keyData` are **alternatives**: RFC 5910 §2 and §4 say the two "MUST NOT be mixed", and
`secDNS-1.1.xsd` makes them an XSD choice, so a block carrying both is refused outright — taking the
whole DNSSEC change with it. A map holding both is refused here instead, with a `ValidationException`.
To send a DS record together with the DNSKEY it was computed from, nest the key **inside** the record
(the `keyData` key of a `dsData` entry), which is the one nesting RFC 5910 allows. A `secDNS` map
holding neither `dsData` nor `keyData` sends no DNSSEC block at all, because a childless
`<secDNS:create/>` fails the registry’s schema check.

### licence (where the registry requires one)

Some registries will not register certain names without a trademark or licence number — commonly the
short, valuable ones directly under the TLD. Where that applies, pass it as `license`:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "acme-01");
options.put("license", "TM-2026-000123");   // goes out as <registry:license> inside <registry:create>

client.domain().create("example.com.ua", options);
```

It travels in the registry's **own** extension, whose namespace the client reads from the
`<greeting>` — see [Commands](commands.md#your-registrys-own-extensions). Against a registry that
advertises no such extension, this throws `ConfigException` rather than sending a frame the server
would ignore.

Which names need one is the registry's policy, not the protocol's, so ask yours. Two refusals tell
you that you guessed wrong: a name that requires a licence and did not get one is usually refused
with `2003` (a required parameter is missing), and a licence sent where none is wanted with `2306`
(parameter value policy error).

### fee: capping what you agree to pay

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();

options.put("fee", "100.00");                       // "I agree to pay up to 100.00"

// Or the same cap with an explicit currency. Both forms live under the one key, so a second
// put REPLACES the first - set one or the other, not both as written here.
Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "100.00");
cap.put("currency", "UAH");
options.put("fee", cap);                            // …in this currency
```

This is a **cap, not a price you set**. If the real price is higher — a tariff change, a premium
name, a stale cache on your side — the registry refuses the command with `2004` and charges nothing,
instead of silently billing you more. Without the key the command proceeds and the registry's own
price is charged. Full treatment in [Balance & prices](balance.md).

**Result codes:** `1000`; `1001` when the registry queues the registration; `2003` / `2004` / `2005`
/ `2306` (validation and policy, including a `fee` cap below the real price); `2104` (insufficient
funds — [stop the batch](errors.md)); `2302` (already registered); `2103` (DNSSEC not offered on this
zone); `2307` (zone not served).

### Building a create step by step

```java
DomainCreateBuilder createBuilder(String name);
```

Same command, same frame, same result — the builder calls `create()`. What changes is that a
misspelling becomes a method that does not exist, which the compiler tells you about. Every step is
documented in [Builders](builders.md).

---

## update

```java
Response update(String name, Map<String, Object> options);
```

**On the wire:** `<command><update><domain:update>` — RFC 5731 §3.2.5, with `<secDNS:update>`,
`<rgp:update>`, `<registry:update>` and `<fee:update>` in `<extension>` as needed.

**An EPP update is a delta, not a replacement.** What you do not mention is left exactly as it is.
The block a change lands in *is* the semantics of the command:

| block | meaning |
|---|---|
| `add` | keep what is there and add these |
| `rem` (also spelled `remove`) | take these away, leave the rest |
| `chg` (also spelled `change`) | replace this single-valued field |

| key | value |
|---|---|
| `add` / `rem` | a `Map` with `ns`, `contacts` (role to handle or to a `List` of handles) and `statuses` |
| `chg` | a `Map` with `registrant`, `authInfo`, `clearAuthInfo` |
| `secDNS` | a `Map` with `add`, `rem`, `remAll`, `maxSigLife` |
| `restore` | `Boolean.TRUE` — see [restore](#restore) |
| `license` | `String` — replaces the trademark or licence number |
| `fee` | the cap you agree to, when the change is billable |

```java
Map<String, Object> addContacts = new LinkedHashMap<String, Object>();
addContacts.put("tech", "acme-02");

Map<String, Object> add = new LinkedHashMap<String, Object>();
add.put("ns", Arrays.asList("ns3.acme.example"));
add.put("contacts", addContacts);
add.put("statuses", Arrays.asList("clientTransferProhibited"));

Map<String, Object> rem = new LinkedHashMap<String, Object>();
rem.put("ns", Arrays.asList("ns2.acme.example"));
rem.put("statuses", Arrays.asList("clientHold"));

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("registrant", "acme-09");
chg.put("authInfo", "New-D0main-Pw");

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("add", add);
options.put("rem", rem);
options.put("chg", chg);

Response r = client.domain().update("example.com.ua", options);

System.out.println(r.code() + " " + r.message());   // 1000, or 1001 if the registry queued it

// An update answers with a result, not with the object. Read the new state back when you
// need to store it:
Response after = client.domain().info("example.com.ua");
System.out.println(String.join(", ", after.nameservers()));
System.out.println(String.join(", ", after.statuses()));
```

The statuses you may set are the `client*` family — `clientHold`, `clientUpdateProhibited`,
`clientTransferProhibited`, `clientDeleteProhibited`, `clientRenewProhibited`. The `server*` ones
belong to the registry and an attempt on them comes back `2304`. `ok` and `inactive` are computed
and belong to nobody.

An `add` or `rem` block you leave empty is not sent at all. An update that carries no `add`, no
`rem` and no `chg` — and no extension change either — is an empty command, and the registry refuses
it with `2003`.

### secDNS on update (RFC 5910)

An update is a delta here too, and it is a different shape from the create block:

```java
// Rotate a key: remove the old DS and add the new one in the same command, so there is no
// window in which the domain is unsigned.
Map<String, Object> oldDs = new LinkedHashMap<String, Object>();
oldDs.put("keyTag", 12345);
oldDs.put("alg", 13);
oldDs.put("digestType", 2);
oldDs.put("digest", "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6");

Map<String, Object> newDs = new LinkedHashMap<String, Object>();
newDs.put("keyTag", 54321);
newDs.put("alg", 13);
newDs.put("digestType", 2);
newDs.put("digest", "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00");

Map<String, Object> remBlock = new LinkedHashMap<String, Object>();
remBlock.put("dsData", Arrays.asList(oldDs));
Map<String, Object> addBlock = new LinkedHashMap<String, Object>();
addBlock.put("dsData", Arrays.asList(newDs));

Map<String, Object> secDns = new LinkedHashMap<String, Object>();
secDns.put("rem", remBlock);
secDns.put("add", addBlock);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", secDns);
client.domain().update("example.com.ua", options);
```

```java
// Unsign the domain entirely:
Map<String, Object> unsign = new LinkedHashMap<String, Object>();
unsign.put("remAll", Boolean.TRUE);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", unsign);
client.domain().update("example.com.ua", options);
```

```java
// Replace the whole key set in one operation: remAll, plus the records to put back.
Map<String, Object> newDs = new LinkedHashMap<String, Object>();
newDs.put("keyTag", 54321);
newDs.put("alg", 13);
newDs.put("digestType", 2);
newDs.put("digest", "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00");
Map<String, Object> addBlock = new LinkedHashMap<String, Object>();
addBlock.put("dsData", Arrays.asList(newDs));

Map<String, Object> replace = new LinkedHashMap<String, Object>();
replace.put("remAll", Boolean.TRUE);
replace.put("add", addBlock);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", replace);
client.domain().update("example.com.ua", options);
```

```java
// Change only the signature lifetime:
Map<String, Object> lifetime = new LinkedHashMap<String, Object>();
lifetime.put("maxSigLife", 1209600);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", lifetime);
client.domain().update("example.com.ua", options);
```

A record named in `rem` must match what the registry holds in every field, not just the key tag.
`remAll` and `rem` are alternatives — where both are present, `remAll` is what goes out; the
[update builder](builders.md) refuses the combination outright rather than choosing for you.

A `secDNS` map carrying none of `add`, `rem`, `remAll` or `maxSigLife` sends no DNSSEC block at
all: a childless `<secDNS:update/>` is a `2003` at the registry for what reads like a no-op, and the
DNSSEC change you meant would be lost with the command still reported as failed.

### Revoking a leaked transfer code

```java
// The code has gone somewhere it should not have. Remove it outright:
Map<String, Object> clear = new LinkedHashMap<String, Object>();
clear.put("clearAuthInfo", Boolean.TRUE);
Map<String, Object> revoke = new LinkedHashMap<String, Object>();
revoke.put("chg", clear);
client.domain().update("example.com.ua", revoke);

// Later, when the customer needs one again:
Map<String, Object> fresh = new LinkedHashMap<String, Object>();
fresh.put("authInfo", "Fresh-D0main-Pw");
Map<String, Object> reissue = new LinkedHashMap<String, Object>();
reissue.put("chg", fresh);
client.domain().update("example.com.ua", reissue);
```

`clearAuthInfo` sends `<domain:authInfo><domain:null/></domain:authInfo>`, which **removes** the
secret. Setting `authInfo` to `""` is not the same thing and is not a fix: an empty password is a
value the holder can still present, so the domain stays exactly as movable as it was.

The two are mutually exclusive — the schema has no way to express both — so asking for both in one
`chg` throws a `ValidationException` before anything is sent.

**Result codes:** `1000`; `1001` when queued; `2003` / `2004` / `2005` / `2306`; `2303` (no such
domain); `2304` (a status prohibits it); `2305` (an association prohibits it); `2103` (DNSSEC not
offered here).

### Building an update step by step

```java
DomainUpdateBuilder updateBuilder(String name);
```

The update builder names the block each change lands in — `addNameserver`, `remStatus`,
`changeRegistrant`, `clearAuthInfo` — for the same reason the map does. See
[Builders](builders.md).

---

## delete

```java
Response delete(String name);
```

**On the wire:** `<command><delete><domain:delete>` — RFC 5731 §3.2.2.

```java
Response before = client.domain().info("example.com.ua");
if (!before.subordinateHosts().isEmpty()) {
    // The registry refuses the delete while hosts live under this domain (2305).
    throw new IllegalStateException("remove " + String.join(", ", before.subordinateHosts()) + " first");
}

Response r = client.domain().delete("example.com.ua");
System.out.println(r.code() + " " + r.message());
```

What a delete does depends on where the domain is in its lifecycle: inside the add-grace window it
is removed immediately, and otherwise it enters `redemptionPeriod`, from which it can be
[restored](#restore) until the window closes and the name is purged. Read `rgpStatus()` on a later
`info()` to see which happened.

**Result codes:** `1000`; `1001` when queued; `2303`; `2304` (e.g. `clientDeleteProhibited`); `2305`
(subordinate hosts still exist).

---

## renew

```java
Response renew(String name, String curExpDate, int years, Object fee);
Response renew(String name, String curExpDate, int years);
Response renew(String name, String curExpDate);
```

**On the wire:** `<command><renew><domain:renew>` with `<domain:name>`, `<domain:curExpDate>` and
`<domain:period unit="y">` — RFC 5731 §3.2.3. **The renew fee is charged on success.** `years`
defaults to 1 in the short overload.

`curExpDate` must equal the domain's **current** expiry date. It is not a formality: it is what
stops a duplicate or replayed renew from adding a second year. Read it from the registry rather than
from your own cache.

**Pass `expiryDate()` straight in.** The two elements are different XML types — `<domain:exDate>` is
a timestamp, `<domain:curExpDate>` is a date — and the library takes the date part for you:

```java
Response info = client.domain().info("example.com.ua");
// info.expiryDate() is "2027-04-01T09:15:00.0Z"; the wire gets "2027-04-01".

Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "90.00");
cap.put("currency", "UAH");

Response r = client.domain().renew("example.com.ua", info.expiryDate(), 1, cap);

System.out.println("new expiry: " + r.expiryDate());   // the registry's own string — store it as-is
System.out.println("charged:    " + r.feeAmount() + " " + r.feeCurrency());
```

The date is taken **as the server wrote it**, with no parsing and no timezone conversion. That is
deliberate: EPP timestamps are UTC and the registry's expiry date is the UTC one, so a client that
reformats through a local zone lands a day either side for every domain expiring near midnight — and
then renews against a date the registry does not hold. If you need local time, convert it where you
display it, never before sending it back.

A mismatch on `curExpDate` comes back as `2105`, and that is the answer to trust: it means the
domain's expiry is not what you thought, so re-read it before doing anything else. A `2105` is never
a reason to retry the same frame.

**Result codes:** `1000`; `2105` (`curExpDate` mismatch, or not renewable); `2104` (insufficient
funds); `2303`; `2304`; `2306`. A period outside RFC 5731’s 1-to-99 bound never reaches the registry —
this library refuses it with a `ValidationException` naming the value, so the `2004` the schema would
have produced is one you will not see here. A registry can still answer `2004` or `2306` for a period
it accepts in principle but does not offer in that zone.

---

## transfer

```java
Response transfer(String op, String name, String authInfo, Integer years, Object fee);
Response transfer(String op, String name, String authInfo);
Response transfer(String op, String name);
```

**On the wire:** `<command><transfer op="…"><domain:transfer>` — RFC 5731 §3.2.4 (and §3.1.3 for
`query`). `op` is one of `request`, `query`, `approve`, `reject`, `cancel`, and **nothing else** — RFC
5730 closes that set, so a sixth name is a `ValidationException` naming the five rather than a frame
the registry refuses while a transfer window keeps running.

| `op` | who sends it | what it does |
|---|---|---|
| `request` | the gaining registrar | asks for the domain, with the current `authInfo` |
| `query` | either side | reports where the request has got to, changing nothing |
| `approve` | the current sponsor | accepts a pending request |
| `reject` | the current sponsor | refuses a pending request |
| `cancel` | the requesting registrar | withdraws its own request |

`years` is an `Integer` rather than an `int` so that it can be absent: it is emitted as
`<domain:period unit="y">` **only when you pass a number**, and `null` omits the element entirely.
Which of the two a zone wants is registry policy: zones that bundle a mandatory one-year renewal
into the transfer take `1` (or the omitted default), and zones where a transfer is free and changes
nothing want the element left out altogether. Ask your registry which applies to the zone you are
moving.

### Requesting a transfer in

```java
Response r = client.domain()
        .transfer("request", "example.com.ua", "the-code-from-the-losing-registrar", 1, null);

r.code();               // 1001 — accepted and pending, not done
r.transferStatus();     // "pending"

Map<String, String> t = r.transfer();      // the whole trnData block
// {
//   status=pending,
//   requestedBy=EXAMPLE,                 // reID
//   requestedAt=2026-04-01T09:15:00Z,    // reDate
//   actingClient=DELTA,                  // acID — who must answer
//   actBy=2026-04-06T09:15:00Z,          // acDate — the deadline
//   expiryDate=2028-04-01T09:15:00Z      // the expiry that will apply
// }
```

`transferStatus()` alone tells you a transfer is pending without saying whose it is or how long
anyone has to act. `transfer()` gives you `actBy`, and that date is the one that matters: **silence
completes a transfer.** Past the deadline the registry decides, and on many zones it approves. A
sponsor that files the poll notice instead of answering it loses the domain.

### Answering a transfer as the losing registrar

The request reaches you as a [poll notice](poll.md) carrying a `trnData`. Answer it:

```java
client.poll().drain(notice -> {
    Map<String, String> t = notice.transfer();
    if (t == null || !"pending".equals(t.get("status"))) {
        return;
    }
    String name = notice.objectName();

    if (customerAuthorisedTheMove(name)) {
        client.domain().transfer("approve", name);
    } else {
        client.domain().transfer("reject", name);
    }
});
```

### Checking and withdrawing

```java
client.domain().transfer("query", "example.com.ua");    // 2300 pending, 2301 nothing pending
client.domain().transfer("cancel", "example.com.ua");   // withdraw your own request
```

While a domain is `pendingTransfer` no other operation on it is accepted, including the automatic
ones.

**Result codes:** `1000` / `1001`; `2201` (not your object to act on); `2202` (wrong `authInfo`);
`2300` (already pending); `2301` (nothing pending to approve, reject, cancel or query); `2304`;
`2306`; `2106` (not transferable).

---

## restore

```java
Response restore(String name, Object fee);
Response restore(String name);
```

**On the wire:** an `<update>` carrying `<domain:chg/>` and nothing else, plus
`<rgp:update><rgp:restore op="request"/>` in the extension — RFC 3915. This is exactly
`update(name, options)` with `restore` set to `Boolean.TRUE`, and the two are interchangeable.
**The restore fee is charged on success**, and it is usually the most expensive operation in the
catalogue.

A restore **changes nothing else**, but the update it rides on must still carry a block. RFC 3915
§4.2.5: "at least one empty `<domain:add>`, `<domain:rem>`, or `<domain:chg>` element MUST be present
if this extension is specified within an `<update>` command", and the RFC's own example carries
`<domain:chg/>`. The library emits that empty element for you. So do not send a change of your own
with a restore — apply it afterwards, in a second command — but do not expect the frame to be an
update with no blocks at all, because an update carrying nothing but a name is a no-op some
registries answer 2003 for, and a restore that did not happen is a domain that leaves redemption by
being deleted.

```java
Response info = client.domain().info("example.com.ua");

if (info.rgpStatus().contains("redemptionPeriod")) {
    Response r = client.domain().restore("example.com.ua", "1000.00");   // your cap, not a published price

    System.out.println(r.code());                        // 1000 restored, or 1001 queued
    System.out.println("charged: " + r.feeAmount());

    Response after = client.domain().info("example.com.ua");
    System.out.println("rgp:     " + String.join(", ", after.rgpStatus()));
    System.out.println("expires: " + after.expiryDate());
}
```

Read `rgpStatus()` and not `statuses()`: the redemption states arrive in the `<extension>` as
`<rgp:infData>`, so a client reading only `<domain:status>` sees a domain days from deletion
reporting a plain `ok`.

A restore is possible only inside the redemption window. After it the name is released and there is
nothing to restore.

**Result codes:** `1000`; `1001` when the restore completes asynchronously; `2104` (insufficient
funds); `2303`; `2304` (the domain is not in a restorable state); `2306`.

---

## When a transform fails and you do not know whether it happened

A read timeout or a dropped connection in the middle of a `create`, a `renew` or a `transfer` leaves
a genuinely unknown outcome: the registry may have carried the command out and billed you before the
reply was lost. Neither this library nor the exception can tell the difference.

**Do not simply retry.** A blind retry is how a domain gets registered — and paid for — twice. Ask
the registry what is true instead: `info()` for a create, and `expiryDate()` compared against what
you expected for a renew. Retry only if the object really is in the state you started from. The
whole rule, with the exception taxonomy, is in [Errors](errors.md).

---

## Result codes on this page

| Code | Meaning | Exception |
|---|---|---|
| `1000` | done | — |
| `1001` | accepted, completing offline; the outcome arrives via [poll](poll.md) | — |
| `2003` | a required parameter is missing | `CommandException` |
| `2004` | a value is out of range — including a `fee` cap below the real price | `CommandException` |
| `2005` | a value is syntactically invalid | `CommandException` |
| `2103` | the extension is not supported for this zone | `CommandException` |
| `2104` | insufficient funds; nothing was registered or charged | `InsufficientFundsException` |
| `2105` | `curExpDate` mismatch, or not renewable | `CommandException` |
| `2106` | not eligible for transfer | `CommandException` |
| `2201` | not yours to act on | `AuthorizationException` |
| `2202` | wrong `authInfo` | `AuthorizationException` |
| `2300` / `2301` | already pending transfer / not pending transfer | `CommandException` |
| `2302` | already registered | `ObjectExistsException` |
| `2303` | no such domain | `ObjectDoesNotExistException` |
| `2304` / `2305` | a status or an association prohibits it | `ObjectStatusException` |
| `2306` / `2308` | registry policy refuses this value | `PolicyException` |
| `2307` | zone not served | `CommandException` |

`ResultCode` has a named constant for every one of them. Full taxonomy, retry rules and the
`throwOnFailure(false)` alternative in [Errors](errors.md).

---

See also: [Contacts](contacts.md) · [Hosts](hosts.md) · [Poll](poll.md) ·
[Balance & prices](balance.md) · [Responses](responses.md) · [Builders](builders.md)

[← Manual index](README.md)
