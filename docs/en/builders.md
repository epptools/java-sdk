# Builders

The commands that take an option map can also be assembled one named step at a time.

```java
Response response = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .adminContact("C-0001")
        .techContact("C-0002")
        .nameservers("ns1.acme.example", "ns2.acme.example")
        .authInfo("D0main-Pw")
        .maxFee("100.00", "UAH")
        .send();
```

**Same command, same frame, same result.** A builder builds no XML of its own: `send()` hands its
options to the ordinary method, so a builder and the equivalent map produce an identical frame and
every check that applies to one applies to the other.

What changes is where a mistake surfaces. An option map accepts any key, so a key spelled `yeras` is
caught only because the library was taught the whole list of keys and refuses what is not on it. A
builder has no key to misspell: `.yeras(1)` is a method that does not exist, and the compiler says so
before you run anything.

Everything here assumes a connected, logged-in client — see [Session](session.md).

## The five builders

| Class | Obtained from | Sends |
|---|---|---|
| `DomainCreateBuilder` | `client.domain().createBuilder(String name)` | `domain:create` |
| `DomainUpdateBuilder` | `client.domain().updateBuilder(String name)` | `domain:update` |
| `ContactCreateBuilder` | `client.contact().createBuilder(String id, String email)` | `contact:create` |
| `ContactUpdateBuilder` | `client.contact().updateBuilder(String id)` | `contact:update` |
| `HostUpdateBuilder` | `client.host().updateBuilder(String name)` | `host:update` |

They live in `com.epptools.sdk.builder` and share the abstract base `Builder`. You never construct
one directly — the handler does, so the builder already knows which client to send through.

Every step returns the builder's own type rather than the base class, so a chain keeps its type all
the way down and the steps available at each link are the ones that command has.

There is deliberately no builder for `check`, `info`, `renew`, `transfer`, `delete` or `restore`.
Those take positional arguments, which the compiler already checks; a builder would add ceremony
without removing a class of mistake.

---

## Four rules that hold for every builder

### 1. Every list step accumulates

Passing several at once, calling the step again, or both, are the same thing:

```java
client.domain().createBuilder("example.com.ua")
        .techContact("C-0002", "C-0003");

client.domain().createBuilder("example.com.ua")
        .techContact("C-0002").techContact("C-0003");   // identical
```

The list steps take a varargs `String...`, so an array or a one-by-one call both fit. That is what
makes a builder read the way it behaves inside a loop or behind a condition:

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");

for (String host : nameservers) {
    builder.nameserver(host);        // each call adds one
}
if (needsDnssec) {
    builder.dsRecord(12345, 13, 2, digest);
}

builder.send();
```

Note that the loop ignores the return value. The steps return `this` for chaining, not a new builder,
so calling one for its effect and dropping the result is correct — there is no copy left behind
holding the earlier state.

Single-valued steps **replace** instead: `.years(1).years(2)` leaves `2`, the way assigning a
variable twice does. The tables below say which is which.

Every list step — nameservers, contacts, statuses on any object, glue addresses — trims its input and
drops an empty or whitespace-only value rather than sending it as an empty element, so a loop over a
list with a blank in it does not produce `<domain:hostObj/>`.

### 2. Nothing is sent until `send()`

Until then a builder is an ordinary value. Keep it, pass it to another method, build it in one
place and dispatch it in another.

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");
// …nothing has reached the registry…
Response response = builder.send();     // now it has
```

`send()` returns the [`Response`](responses.md), exactly as the direct call does.

### 3. `toOptions()` gives back exactly what the direct call takes — as a copy

```java
Map<String, Object> toOptions();
```

Available on every builder, from the shared base class.

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .techContact("C-0002");

builder.toOptions();
// {years=1, registrant=C-0001, contacts={tech=[C-0002]}}

// So this is the same command, by another road:
client.domain().create("example.com.ua", builder.toOptions());
```

Two properties matter:

- **It is exactly the map the direct method takes.** That makes a builder queueable: serialise
  `toOptions()`, put it on a queue, and have a worker call `create()` with it.
- **It is a deep copy.** Handing back the live map would let it change under the caller every time
  another step was added, so what you logged and what you sent could differ. What you get is a value
  that is finished — nested maps and lists included, so a `put` into one of them cannot reach the
  builder.

Calling it sends nothing and does not spend the builder.

### 4. A builder sends once

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");

builder.send();
builder.send();
// ValidationException: DomainCreateBuilder has already been sent. A builder carries one command;
//                      build another rather than re-sending this one.
```

A second `send()` on a create is a second registration and a second charge, and it is never what the
caller meant — a retry after a failure is not the same thing as replaying an object that has already
gone out. Build a new builder; they cost nothing. If the first `send()` failed in a way that leaves
the outcome unknown, read [Errors](errors.md#when-a-transform-fails-and-you-do-not-know-whether-it-happened)
before doing anything at all.

---

## DomainCreateBuilder

```java
DomainCreateBuilder createBuilder(String name);
```

Sends `domain:create` (RFC 5731 §3.2.1). Every option of [`domain().create()`](domains.md#create)
has a step here.

| Step | Sets | Accumulates? |
|---|---|---|
| `years(int years)` | `years` — `<domain:period unit="y">`. Omit it and the registry applies its own default term | replaces |
| `registrant(String handle)` | `registrant` — the holder of the domain | replaces |
| `contact(String role, String... handles)` | `contacts[role]` — one `<domain:contact type="…">` per handle | accumulates |
| `adminContact(String... handles)` | `contacts["admin"]` | accumulates |
| `techContact(String... handles)` | `contacts["tech"]` | accumulates |
| `billingContact(String... handles)` | `contacts["billing"]` | accumulates |
| `nameserver(String host)` | `nameservers` — one host-object reference (`<domain:hostObj>`) | accumulates |
| `nameservers(String... hosts)` | `nameservers` — the same, several at a time | accumulates |
| `nameserverWithGlue(String host, String... addresses)` | `nameservers` as a map of `name` and `addresses` — inline glue (`<domain:hostAttr>`) | accumulates |
| `authInfo(String password)` | `authInfo` — the transfer secret | replaces |
| `license(String number)` | `license` — a trademark or licence number, where the registry requires one | replaces |
| `maxFee(String amount, String currency)` · `maxFee(amount)` | `fee` — the most you agree to pay (RFC 8748) | replaces |
| `dsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS["dsData"]` | accumulates |
| `dsRecordWithKey(int keyTag, int alg, int digestType, String digest, int flags, int protocol, int keyAlg, String pubKey)` | `secDNS["dsData"]` with the DNSKEY it was computed from | accumulates |
| `keyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS["keyData"]` | accumulates — the **alternative** to `dsRecord`, never beside it |
| `maxSigLife(int seconds)` | `secDNS["maxSigLife"]` | replaces |
| `send()` → `Response` | calls `domain().create(name, options)` | terminal |

### A registration

```java
try {
    Response r = client.domain().createBuilder("example.com.ua")
            .years(1)
            .registrant("C-0001")
            .adminContact("C-0001")
            .techContact("C-0002", "C-0003")     // one role, two handles
            .nameservers("ns1.acme.example", "ns2.acme.example")
            .authInfo("D0main-Pw")
            .maxFee("100.00", "UAH")
            .send();

    System.out.println(r.objectName() + " created " + r.createdDate());
    System.out.println("expires: " + r.expiryDate());
    System.out.println("charged: " + r.feeAmount() + " " + r.feeCurrency());

    if (r.isPending()) {
        // 1001 — the registry queued it. Not registered yet; the verdict arrives via poll.
        markPending(r.svTRID());
    }
} catch (EppException e) {
    System.err.println("EPP error: " + e.getMessage());
}
```

`contact()` names the role in one argument, which is all `adminContact()`, `techContact()` and
`billingContact()` do for you — useful when the role comes from a variable:

```java
client.domain().createBuilder("example.com.ua")
        .contact("admin", "C-0009")
        .contact("billing", "C-0010");
```

The role is `admin`, `billing` or `tech`, and **nothing else**. RFC 5731 makes
`domain:contactAttrType` a closed enumeration, so a zone does not get a fourth role by the registry
recognising one: `type="reseller"` is a frame the schema refuses, and it takes the whole command with
it — every contact in it, and the registration itself. A role outside the three throws a
`ValidationException` naming the accepted set, and so does an empty one, which would emit
`<domain:contact type="">` and draw a `2005` naming nothing useful. If your registry tracks a
reseller, it does so through its own extension, not through this element.

### The two delegation models

```java
// Host-object references: create the host objects first (see hosts.md).
client.domain().createBuilder("example.com.ua")
        .nameserver("ns1.acme.example").nameserver("ns2.acme.example");

// Inline glue: the addresses travel with the name. IPv4 and IPv6 are told apart from the literal.
client.domain().createBuilder("example.com.ua")
        .nameserverWithGlue("ns1.example.com.ua", "203.0.113.1", "2001:db8::1")
        .nameserverWithGlue("ns2.example.com.ua", "203.0.113.2");
```

RFC 5731 makes `<domain:ns>` a choice between the two, so one command uses one model or the other.
Mixing them is refused when the frame is built — a `ValidationException` naming the problem, rather
than a bare `2001` from the registry naming no field. Ask your registry which model it takes.

`nameserverWithGlue()` with an empty name throws immediately: a nameserver with no name is not
something to discover from the reply.

### DNSSEC on a create

```java
client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .dsRecord(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6")
        .dsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .maxSigLife(1209600)
        .send();
```

`dsRecordWithKey()` sends the DNSKEY the digest was computed from alongside the DS record. A registry
that accepts it can verify the digest against the key for you, catching a mistyped digest before it
reaches the zone; one that does not accept key data refuses the command rather than ignoring the
extra element, so trying costs nothing but a `2306`.

```java
client.domain().createBuilder("example.com.ua")
        .dsRecordWithKey(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6",
                257, 3, 13, "AwEAAb…");
```

The eight arguments are the DS record first — key tag, algorithm, digest type, digest — then the
DNSKEY: flags, protocol, the key's own algorithm, public key. The two algorithm numbers are normally
the same; they are separate arguments because the protocol keeps them separate, and a mismatch is
something the registry will tell you about.

`keyRecord()` signs with a bare public key instead of a DS record, where the registry takes those.
An empty digest or an empty public key throws a `ValidationException`.

`maxSigLife()` is only meaningful alongside a DS or key record.

---

## DomainUpdateBuilder

```java
DomainUpdateBuilder updateBuilder(String name);
```

Sends `domain:update` (RFC 5731 §3.2.5).

**An EPP update is a delta, not a replacement.** What you do not mention is left exactly as it is,
and *which block a change lands in is the whole semantics of the command*:

| Block | Means |
|---|---|
| `add` | keep what is there and add these |
| `rem` | take these away, leave the rest |
| `chg` | replace this single-valued field |

Sending a nameserver in `add` when you meant `rem` does not fail — it delegates the domain to a
server you were trying to remove, and the registry answers `1000`. That is why each step names its
block. Read the prefix of the method and you have read the semantics.

| Step | Block | Sets |
|---|---|---|
| `addNameserver(String host)` | `add` | `add["ns"]` — delegate to one more, accumulates |
| `addNameservers(String... hosts)` | `add` | `add["ns"]` — several at a time, accumulates |
| `remNameserver(String host)` | `rem` | `rem["ns"]` — stop delegating to one, accumulates |
| `remNameservers(String... hosts)` | `rem` | `rem["ns"]`, accumulates |
| `addContact(String role, String... handles)` | `add` | `add["contacts"][role]`, accumulates |
| `remContact(String role, String... handles)` | `rem` | `rem["contacts"][role]`, accumulates |
| `addStatus(String... statuses)` | `add` | `add["statuses"]`, accumulates |
| `remStatus(String... statuses)` | `rem` | `rem["statuses"]`, accumulates |
| `changeRegistrant(String handle)` | `chg` | `chg["registrant"]`, replaces |
| `changeAuthInfo(String password)` | `chg` | `chg["authInfo"]` — replace the transfer secret, replaces |
| `clearAuthInfo()` | `chg` | `chg["clearAuthInfo"]` — **remove** the transfer secret |
| `restore()` | — | `restore` — RGP restore request (RFC 3915) |
| `license(String number)` | — | `license` — a trademark or licence number |
| `maxFee(String amount, String currency)` · `maxFee(amount)` | — | `fee` — the cap, when the change is billable |
| `addDsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS.add` | `secDNS["add"]["dsData"]`, accumulates |
| `remDsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS.rem` | `secDNS["rem"]["dsData"]`, accumulates |
| `addKeyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS.add` | `secDNS["add"]["keyData"]`, accumulates — the **alternative** to `addDsRecord` in that block |
| `remKeyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS.rem` | `secDNS["rem"]["keyData"]`, accumulates — the **alternative** to `remDsRecord` in that block |
| `removeAllDnssec()` | `secDNS.rem` | `secDNS["remAll"]` — unsign the domain entirely |
| `maxSigLife(int seconds)` | `secDNS.chg` | `secDNS["maxSigLife"]`, replaces |
| `send()` → `Response` | — | calls `domain().update(name, options)` |

### Changing a delegation

```java
Response r = client.domain().updateBuilder("example.com.ua")
        .addNameserver("ns3.acme.example")
        .remNameserver("ns2.acme.example")
        .addStatus("clientTransferProhibited")
        .remStatus("clientHold")
        .changeRegistrant("C-0009")
        .send();

System.out.println(r.code() + " " + r.message());     // 1000, or 1001 when the registry queues it

// An update answers with a result, not with the object. Read the new state back if you store it:
Response after = client.domain().info("example.com.ua");
System.out.println(String.join(", ", after.nameservers()));
```

The statuses you may set are the `client*` family. The `server*` ones belong to the registry and an
attempt on them comes back `2304`.

`changeRegistrant()` is a change of holder, which many registries treat as its own procedure with its
own paperwork — a refusal there is usually policy rather than a malformed command.

### Revoking a leaked transfer code

```java
// The code has gone somewhere it should not have:
client.domain().updateBuilder("example.com.ua").clearAuthInfo().send();

// Later, when the customer needs one again:
client.domain().updateBuilder("example.com.ua").changeAuthInfo("Fresh-D0main-Pw").send();
```

`clearAuthInfo()` sends `<domain:authInfo><domain:null/></domain:authInfo>`, which **removes** the
secret. It is not the same as setting an empty one: an empty password is still a value the holder can
present, so the domain would stay exactly as movable as it was. The two are mutually exclusive — the
schema has no way to express both — so asking for both throws a `ValidationException` before anything
is sent.

### DNSSEC on an update

```java
// Rotate a key with no window in which the domain is unsigned:
client.domain().updateBuilder("example.com.ua")
        .remDsRecord(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6")
        .addDsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .send();

// Unsign entirely:
client.domain().updateBuilder("example.com.ua").removeAllDnssec().send();

// Replace the whole key set in one operation:
client.domain().updateBuilder("example.com.ua")
        .removeAllDnssec()
        .addDsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .send();
```

A record named in `remDsRecord()` must match what the registry holds in **every** field, not just the
key tag.

`removeAllDnssec()` and `remDsRecord()`/`remKeyRecord()` are mutually exclusive: the protocol cannot
express "remove everything and also remove this one", and a frame carrying both is refused. The
builder refuses the combination itself, in whichever order you write it, with a message that says
which two steps are in conflict.

### A restore through the update builder

```java
client.domain().updateBuilder("example.com.ua")
        .restore()
        .maxFee("1000.00", "UAH")       // your cap, not a published price
        .send();
```

The same command as [`domain().restore("example.com.ua", "1000.00")`](domains.md#restore), with the
currency named as well. Do not send a change of your own with a restore — apply it afterwards, in a
second command — but the frame is not blockless either: RFC 3915 §4.2.5 requires an empty
`<domain:add>`, `<domain:rem>` or `<domain:chg>` in an update carrying the extension, and the library
emits `<domain:chg/>` for you. See [restore](domains.md#restore).

---

## ContactCreateBuilder

```java
ContactCreateBuilder createBuilder(String id, String email);
```

Sends `contact:create` (RFC 5733 §3.2.1).

**The id and the e-mail are arguments to the factory method, not steps**, because the registry
requires both. A builder that lets you forget a mandatory field has moved the error from your
compiler to the wire.

Pass `com.epptools.sdk.command.Contact.AUTO_ID` as the id to have the registry
[mint the handle](contacts.md#letting-the-registry-choose-the-handle) and read it back with
`objectName()`.

| Step | Sets | Accumulates? |
|---|---|---|
| `internationalAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` · `internationalAddress(name, city, countryCode)` | `postalInfos` with `type` `int` | accumulates |
| `localizedAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` · `localizedAddress(name, city, countryCode)` | `postalInfos` with `type` `loc` | accumulates |
| `voice(String number)` | `voice` — the EPP `+CC.NNNNNNNNN` form, optionally `x` and an extension | replaces |
| `fax(String number)` | `fax` — same form | replaces |
| `authInfo(String password)` | `authInfo` — the contact's transfer secret | replaces |
| `publish(String... fields)` | `disclose` with the flag set — consent to publish these | replaces |
| `withhold(String... fields)` | `disclose` with the flag clear — withhold these | replaces |
| `send()` → `Response` | calls `contact().create(id, options)` | terminal |

```java
Response r = client.contact().createBuilder("C-0001", "contact@example.com")
        .internationalAddress("Ivan Petrenko", "Kyiv", "UA",
                Arrays.asList("1 Khreschatyk St"), "ACME LLC", null, "01001")
        .localizedAddress("Іван Петренко", "Київ", "UA",
                Arrays.asList("вул. Хрещатик 1"), "ТОВ «АКМЕ»", null, "01001")
        .voice("+380.441234567")
        .authInfo("C0nt@ct-Pw")
        .withhold("voice", "email")
        .send();

System.out.println(r.objectName());      // "C-0001" — the handle
```

Both address steps have two forms because the arguments after the third are a row of strings whose
meaning you would otherwise have to count out. The short form takes only the three the schema
requires — name, city, country code — and is what an ASCII contact with nothing else usually needs.
The long form takes the rest in this order: **street, org, stateProvince, postalCode**, with `null`
for any you are not setting. Where more than one of them is in play, a local variable per argument
reads better than a row of literals:

```java
List<String> street = Arrays.asList("1 Khreschatyk St");
String org = "ACME LLC";
String stateProvince = null;
String postalCode = "01001";

client.contact().createBuilder("C-0001", "contact@example.com")
        .internationalAddress("Ivan Petrenko", "Kyiv", "UA", street, org, stateProvince, postalCode);
```

At least one address form is required. Give `internationalAddress()` unless you have a reason not to:
it is the form that survives being printed, e-mailed and read by a system that knows no Cyrillic, and
Cyrillic inside an `int` block is refused with `2005`. The localized form is additional, not
alternative — send both when you have both, and nothing is discarded. Compile such a source file as
UTF-8 (`javac -encoding UTF-8`) or the local script is mangled before the library sees it.

### publish and withhold

RFC 5733 disclosure. The field names are `name`, `org`, `addr`, `voice`, `fax` and `email`; anything
else throws a `ValidationException` naming the six.

```java
client.contact().createBuilder("C-0001", "contact@example.com")
        .withhold("voice", "email");     // these are withheld; everything else takes the opposite

client.contact().createBuilder("C-0002", "contact@example.com")
        .publish("name", "org");         // these may be published; everything else is withheld
```

**They are two ways of saying the same thing, and the second call replaces the first.** Pick the one
that matches how you think about the preference and do not call both — the flag is the whole meaning
of the list, so a block assembled from both halves says something neither call intended.

`name`, `org` and `addr` exist once per postal form, so naming one of them covers **both** forms.
Withholding only the ASCII form while the local one stayed public would be a privacy setting that
reads as applied and is not.

---

## ContactUpdateBuilder

```java
ContactUpdateBuilder updateBuilder(String id);
```

Sends `contact:update` (RFC 5733 §3.2.5). What you do not mention is left alone.

| Step | Block | Sets |
|---|---|---|
| `changeInternationalAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` · `changeInternationalAddress(name, city, countryCode)` | `chg` | `chg["postalInfos"]` with `type` `int` |
| `changeLocalizedAddress(…same parameters…)` | `chg` | `chg["postalInfos"]` with `type` `loc` |
| `changeVoice(String number)` | `chg` | `chg["voice"]` |
| `changeFax(String number)` | `chg` | `chg["fax"]` |
| `changeEmail(String email)` | `chg` | `chg["email"]` |
| `changeAuthInfo(String password)` | `chg` | `chg["authInfo"]` — replace the transfer secret |
| `publish(String... fields)` | `chg` | `chg["disclose"]` with the flag set |
| `withhold(String... fields)` | `chg` | `chg["disclose"]` with the flag clear |
| `addStatus(String... statuses)` | `add` | `addStatuses`, accumulates |
| `remStatus(String... statuses)` | `rem` | `remStatuses`, accumulates |
| `send()` → `Response` | — | calls `contact().update(id, options)` |

```java
client.contact().updateBuilder("C-0001")
        .changeEmail("new-contact@example.com")
        .changeVoice("+380.441234500")
        .addStatus("clientUpdateProhibited")
        .send();
```

`changeVoice(null)` and `changeFax(null)` are the way to clear a number: they send an empty element,
which is what removes it. That is the one place a `null` argument is a value rather than an omission,
and it is why they read `change…` rather than `set…`.

### An address is REPLACED, not merged

The block you pass **replaces** the one the registry holds. It is not merged field by field, so
anything you leave out is deleted:

| What you write | What happens |
|---|---|
| pass a value | the field is set to it |
| pass `""` | the field is **cleared** — the way to remove `org`, `stateProvince` or `postalCode` |
| pass `null`, or use the three-argument form | the field is not sent — and the registry deletes what it held |

RFC 5733 can be read as "leave it out and the registry keeps its value", since every child of
`chgPostalInfoType` is optional, but that reading is not safe. Against a registry that replaces — and
it answers **1000** while doing it — a complete block sent without its `org` comes back with the
organisation gone. A block carrying only an `org` would leave the contact with no postal address at
all: name, street, city, postal code and country. This library refuses that second one before it is
sent, which is why it is a refusal you can read rather than a 1000 you cannot.

`name`, `city` and `countryCode` are required in every address change for that reason, and `send()`
refuses to build a frame without them — a `ValidationException` naming the part that is missing. They
keep the frame valid; they cannot restore a field you did not pass. **Read the block first and pass it
back with your change applied:**

```java
Map<String, Object> current = client.contact().info("C-0001").postalInfo().get("int");

@SuppressWarnings("unchecked")
List<String> street = (List<String>) current.get("street");

// Move the contact to Lviv and clear the organisation, keeping everything else as it was.
client.contact().updateBuilder("C-0001")
        .changeInternationalAddress((String) current.get("name"), "Lviv", "UA",
                street, "", (String) current.get("sp"), (String) current.get("pc"))
        .send();
```

The cast on `street` is the one rough edge: `postalInfo()` hands back a `Map<String, Object>` because
one of its values is a list and the rest are strings. Cast it once, where you read it, rather than
rebuilding the list by hand.

The form you do not mention — local or international — is untouched: the two are addressed
separately.

### There is no clearAuthInfo() here

RFC 5731 gives a domain a nullable form, `<domain:authInfo><domain:null/>`; RFC 5733 defines no
equivalent for a contact. So a contact's transfer secret can be **replaced but not removed**. Do not
reach for an empty password as a substitute: an empty value is still a value the holder can present.
Set a fresh secret with `changeAuthInfo()` instead.

---

## HostUpdateBuilder

```java
HostUpdateBuilder updateBuilder(String name);
```

Sends `host:update` (RFC 5732 §3.2.5).

| Step | Block | Sets |
|---|---|---|
| `addAddress(String ip)` | `add` | `addAddresses` — one glue address, accumulates |
| `addAddresses(String... ips)` | `add` | `addAddresses` — several, accumulates |
| `remAddress(String ip)` | `rem` | `remAddresses`, accumulates |
| `remAddresses(String... ips)` | `rem` | `remAddresses`, accumulates |
| `addStatus(String... statuses)` | `add` | `addStatuses`, accumulates |
| `remStatus(String... statuses)` | `rem` | `remStatuses`, accumulates |
| `send()` → `Response` | — | calls `host().update(name, options)` |

```java
client.host().updateBuilder("ns1.example.com.ua")
        .addAddresses("192.0.2.10", "2001:db8::10")
        .remAddress("192.0.2.9")
        .send();
```

IPv4 and IPv6 are told apart from the literal, so `v4` and `v6` end up correctly labelled without you
saying which is which.

**There is no rename step.** Not because the protocol lacks one — RFC 5732 defines `host:chg` — but
because the registries this was built against discard it, so a rename here would report a success that
did not happen. See [Hosts](hosts.md#there-is-no-rename) for the three commands that do the job
instead, and ask your registry whether it implements `host:chg` at all.

Adding and removing the same address in one command is a contradiction the registry resolves however
it chooses. Send one or the other.

---

## What a builder does not change

A builder is a facade over the option map, so everything the map is subject to still applies:

- **The same validation.** `send()` calls the ordinary method, which checks its options exactly as it
  would for a hand-written map. A builder cannot produce an unknown key, but it can produce a
  combination the command refuses.
- **The same result codes.** A `2302`, a `2104`, a `1001` mean what they mean; see
  [Errors](errors.md).
- **The same `throwOnFailure` behaviour.** With throwing off, `send()` returns the refusal as a
  `Response` instead of throwing.
- **The same secret handling.** `authInfo()` sets a live credential. It is masked in the library's own
  logs — but `toOptions()` is your map, and if you log or queue it, it carries the password in clear.
  Mask it yourself before it reaches a log.

## Which steps throw before anything is sent

All of these are a `ValidationException`, and in every case no frame was built:

| Step | Throws when |
|---|---|
| any `contact(…)` / `addContact(…)` / `remContact(…)` | the role is empty or whitespace; or, at `send()`, it is not one of admin, billing, tech |
| `nameserverWithGlue(…)` | the nameserver name is empty |
| `dsRecord(…)`, `dsRecordWithKey(…)`, `addDsRecord(…)`, `remDsRecord(…)` | the digest is empty or whitespace |
| `keyRecord(…)`, `addKeyRecord(…)`, `remKeyRecord(…)`, `dsRecordWithKey(…)` | the public key is empty or whitespace |
| `removeAllDnssec()` after `remDsRecord()`/`remKeyRecord()`, or either after it | the two are mutually exclusive |
| `dsRecord(…)`/`addDsRecord(…)`/`remDsRecord(…)` beside `keyRecord(…)`/`addKeyRecord(…)`/`remKeyRecord(…)` in one block | RFC 5910 makes `dsData` and `keyData` a choice; nest the key in the record with `dsRecordWithKey(…)` |
| `maxSigLife(…)` | the lifetime is under 1 second — `secDNS-1.1.xsd` sets minInclusive 1 |
| `maxFee(…)` | the amount is not a plain decimal like `100.00` |
| `publish(…)` / `withhold(…)` | a field is not one of name, org, addr, voice, fax, email |
| any address step, at `send()` | name, city or the country code is missing from the block |
| `send()` | the builder has already been sent |

A malformed fee agreement is checked here rather than on the wire because it otherwise draws a bare
`2001` that names no field — and it arrives after the command has been attempted.

---

## When the map is the better tool

The builders and the maps are the same thing, so use whichever fits:

- Building from a config file, a database row or a queue payload that is already a map: pass it
  straight to `create()`/`update()`. Turning it into a chain of calls only to have the builder turn it
  back into a map adds nothing.
- Writing a command out in code, especially an update: use the builder. `.remStatus("clientHold")`
  says which block the change lands in, in a place you cannot get wrong, and it is a dozen lines
  shorter than the nested maps it replaces.

`toOptions()` is the bridge between the two, and it works in both directions: build with the fluent
API, store the map, replay it later with the direct call.

---

See also: [Domains](domains.md) · [Contacts](contacts.md) · [Hosts](hosts.md) ·
[Balance & prices](balance.md) · [Responses](responses.md) · [Errors](errors.md)

[← Manual index](README.md)
