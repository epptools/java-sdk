# Contacts

Contact objects follow **RFC 5733**. A contact is a person or an organisation with an address, a
phone number and an e-mail; a [domain](domains.md) references contacts by handle for the registrant
and for each role the zone requires. So contacts come first: create them, keep their handles, then
register domains against them.

Every contact command is reached through `client.contact()` and returns a
[`Response`](responses.md). Everything here assumes a connected, logged-in client — see
[Session](session.md).

## The methods

| Method | EPP command |
|---|---|
| `check(List<String> ids)` | `<check>` |
| `info(String id, String authInfo)` · `info(id)` | `<info>` |
| `create(String id, Map<String,Object> options)` | `<create>` |
| `createAuto(Map<String,Object> options)` | `<create>` with the reserved id |
| `createBuilder(String id, String email)` → `ContactCreateBuilder` | builds a `<create>` |
| `update(String id, Map<String,Object> options)` | `<update>` |
| `updateBuilder(String id)` → `ContactUpdateBuilder` | builds an `<update>` |
| `delete(String id)` | `<delete>` |
| `transfer(String op, String id, String authInfo)` · `transfer(op, id)` | `<transfer op="…">` |

One constant: `Contact.AUTO_ID` — the reserved id that asks the registry to
[mint the handle](#letting-the-registry-choose-the-handle).

`create()` and `update()` take a `Map<String, Object>` of options, and **an option key this library
does not understand is refused with a `ValidationException` before any frame is built**, naming the
closest key it knows. A silently ignored key would send a command the registry answers `1000` to,
with the part you asked for missing.

---

## check

```java
Response check(List<String> ids);
```

**On the wire:** `<command><check><contact:check><contact:id>…` — RFC 5733 §3.1.1. This asks whether
an **identifier** is free, not whether a person exists.

```java
Response r = client.contact().check(Arrays.asList("acme-01", "acme-02"));

r.availability();               // {acme-01=false, acme-02=true}
r.isAvailable("acme-02");       // Boolean.TRUE | Boolean.FALSE | null ("the answer said nothing")
r.unavailableReason("acme-01"); // "In use", or null when the id is free
```

Handles are a shared namespace across the whole registry, so a scheme of your own with a prefix you
control is worth more than a check-then-create loop. If you have no scheme,
[let the registry mint the handle](#letting-the-registry-choose-the-handle) and skip the collision
question entirely.

**Result codes:** `1000` for any well-formed check; `2005` names a syntactically invalid id. An
**empty list** is refused too, with a
`ValidationException`: the frame it would build has no child at all, and the schema requires one — so
a loop over a query string or a basket that turned out to be empty fails here, by name, instead of
spending a round trip.

---

## info

```java
Response info(String id, String authInfo);
Response info(String id);
```

**On the wire:** `<command><info><contact:info><contact:id>` — RFC 5733 §3.1.2. Pass `authInfo` and
it goes out as `<contact:authInfo><contact:pw>`, which is how a registrar that does not sponsor the
contact reads the full record.

```java
Response c = client.contact().info("acme-01");

c.objectName();     // "acme-01" — the HANDLE, not the person's name
c.roid();           // the registry's own object id
c.statuses();       // [linked], [ok], [clientUpdateProhibited], …
c.sponsor();        // clID
c.createdBy();      // crID          c.createdDate();   // crDate
c.updatedBy();      // upID, or null c.updatedDate();   // upDate
c.authInfo();       // the transfer secret — never log it

c.email();          // "contact@example.com"
c.voice();          // "+380.441234567" — the EPP +CC.NNNN form
c.fax();            // likewise, or null

c.postalInfo();     // {int={…}, loc={…}}
c.disclose();       // {flag=false, elements=[email, voice]} or null
```

`objectName()` gives the handle. Reading the person's name means going into the postal block:

```java
Map<String, Map<String, Object>> postal = client.contact().info("acme-01").postalInfo();

postal.get("int").get("name");      // "ACME LLC"
postal.get("int").get("street");    // [1 Khreschatyk St] — a List, up to 3 lines
postal.get("int").get("city");      // "Kyiv"
postal.get("int").get("cc");        // "UA"

// The local-script form, when the contact carries one.
Object localName = postal.containsKey("loc") ? postal.get("loc").get("name") : null;
```

Each entry holds `name`, `org`, `street` (a `List`), `city`, `sp`, `pc`, `cc`, with missing parts as
`""`. A contact may carry the `int` form, the `loc` form, or both — check with `containsKey` before
reaching into one.

`disclose()` returns `null` when the contact expresses no preference and registry policy alone
applies. When it is present, `flag` decides what the list means: `true` says the listed elements may
be published, `false` says they must be withheld, and everything **not** listed takes the opposite.
The list is meaningless without the flag, so never read one without the other.

**Result codes:** `1000`; `2202` (wrong `authInfo` as a non-sponsor); `2303` (no such handle).

---

## create

```java
Response create(String id, Map<String, Object> options);
```

**On the wire:** `<command><create><contact:create>` — RFC 5733 §3.2.1.

### Every option

| key | value | wire |
|---|---|---|
| `type` | `"int"` (default) or `"loc"` | the `type` attribute of the single flat block |
| `name` | `String` | `<contact:name>` |
| `org` | `String` | `<contact:org>` — sent only when non-empty |
| `street` | a `List` of up to 3 lines | one `<contact:street>` per line |
| `city` | `String` | `<contact:city>` |
| `sp` | `String` | `<contact:sp>` — sent only when non-empty |
| `pc` | `String` | `<contact:pc>` — sent only when non-empty |
| `cc` | 2-letter country code | `<contact:cc>` |
| `postalInfos` | a `List` of blocks, each shaped like the flat keys above | one `<contact:postalInfo>` per entry |
| `voice` | `+CC.NNNN` | `<contact:voice>` — sent only when non-empty |
| `fax` | `+CC.NNNN` | `<contact:fax>` — sent only when non-empty |
| `email` | `String` | `<contact:email>` — **required** |
| `authInfo` | `String` | `<contact:authInfo><contact:pw>` |
| `disclose` | see [the disclose block](#the-disclose-block) | `<contact:disclose flag="0\|1">` |

`email` is required by RFC 5733 and an empty one throws a `ValidationException` here rather than
travelling to the registry as an empty element and coming back as an opaque `2005`.

`<contact:authInfo>` is always emitted. Give the `authInfo` option and your value travels in it;
leave it out and an empty `<contact:pw/>` goes instead, which hands the choice to the registry's own
policy.

### The two postal forms

The flat keys build **one** block. Pass `postalInfos` instead to send both forms in one command:

```java
// int: ASCII / Latin only. This is the form the registry can show to any party, so at least one
// of these is needed. Cyrillic here is refused with 2005.
Map<String, Object> international = new LinkedHashMap<String, Object>();
international.put("type", "int");
international.put("name", "Ivan Petrenko");
international.put("org", "ACME LLC");
international.put("street", Arrays.asList("1 Khreschatyk St"));
international.put("city", "Kyiv");
international.put("pc", "01001");
international.put("cc", "UA");

// loc: the local script, as the registrant actually wrote it.
Map<String, Object> localized = new LinkedHashMap<String, Object>();
localized.put("type", "loc");
localized.put("name", "Іван Петренко");
localized.put("org", "ТОВ «АКМЕ»");
localized.put("street", Arrays.asList("вул. Хрещатик 1"));
localized.put("city", "Київ");
localized.put("pc", "01001");
localized.put("cc", "UA");

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("postalInfos", Arrays.asList(international, localized));
options.put("voice", "+380.441234567");
options.put("email", "contact@example.com");
options.put("authInfo", "C0nt@ct-Pw");

Response r = client.contact().create("acme-01", options);

System.out.println(r.objectName() + " created " + r.createdDate());   // "acme-01"
```

Send both forms whenever you have both. Nothing is discarded, and `info()` returns everything you
sent. Source files carrying a `loc` block must be compiled as UTF-8 — `javac -encoding UTF-8` — or
the local script is mangled before the library ever sees it.

The short form, for an ASCII-only contact:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("name", "ACME LLC");
options.put("city", "Kyiv");
options.put("cc", "UA");
options.put("email", "contact@example.com");

client.contact().create("acme-02", options);
```

`type` defaults to `int`, which is what you want for a Latin-script address. Set `type` to `"loc"`
when the single block you are sending is in the local script.

### The disclose block

RFC 5733 privacy preferences. `name`, `org` and `addr` are per-form, so they take a list of the
forms they apply to — `int`, `loc`, or both, and nothing else — while `voice`, `fax` and `email` are
bare flags. A field name outside the six, or a form outside the two, is a `ValidationException`
naming the accepted set: a misspelling here is a privacy instruction that reads as applied and is
not, because the frame goes out as a childless `<contact:disclose flag="0"/>` and the registry
answers 1000.

```java
Map<String, Object> disclose = new LinkedHashMap<String, Object>();
disclose.put("flag", Boolean.FALSE);                   // false: withhold what is listed. true: publish it.
disclose.put("addr", Arrays.asList("int"));            // the international address block
disclose.put("name", Arrays.asList("int", "loc"));
disclose.put("voice", Boolean.TRUE);
disclose.put("email", Boolean.TRUE);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("disclose", disclose);
```

The flag is the whole meaning of the list. A block with `flag` false and `email` true **withholds**
the e-mail address; the same block with `flag` true consents to publishing it. Elements you do not
list take the opposite of the flag, so a block with a flag and nothing in it says something too.

Every flag is read the way you wrote it rather than the way Java would coerce it, so the `String`
`"0"` means withhold, not publish — which is what a value arriving from a form or a JSON payload
needs.

**Result codes:** `1000`; `2003` (no postal block, or no e-mail); `2005` (bad syntax — a malformed
e-mail, or Cyrillic in an `int` block); `2302` (the id is taken); `2306` (policy, e.g. an `authInfo`
below the zone's strength rule).

---

## Letting the registry choose the handle

```java
Response createAuto(Map<String, Object> options);
public static final String AUTO_ID = "autonic";
```

`createAuto()` sends the reserved id `autonic` in place of a handle, and the registry mints one for
you. The reply is the **only** place the minted handle appears, so store what `objectName()` gives
you:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("name", "ACME LLC");
options.put("city", "Kyiv");
options.put("cc", "UA");
options.put("email", "contact@example.com");

// e.g. "c-9f4b2ad10e" — persist this before doing anything else
String handle = client.contact().createAuto(options).objectName();

Map<String, Object> domain = new LinkedHashMap<String, Object>();
domain.put("years", 1);
domain.put("registrant", handle);
client.domain().create("example.com.ua", domain);
```

Use it when you have no naming scheme of your own, or when you would otherwise be retrying around
`2302` because somebody else took the handle first. **Every call mints a fresh handle**, so a repeat
is a second contact rather than a collision — which also means a retry after an unclear failure
creates a duplicate. If a `createAuto()` call ends in an unknown outcome, reconcile before calling
it again.

`Contact.AUTO_ID` is the constant behind it, and passing it to `create()` or `createBuilder()` does
the same thing:

```java
import com.epptools.sdk.command.Contact;

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("name", "ACME LLC");
options.put("city", "Kyiv");
options.put("cc", "UA");
options.put("email", "contact@example.com");

client.contact().create(Contact.AUTO_ID, options);
```

The reserved value is never stored as a handle, so it stays usable by everyone.

---

## update

```java
Response update(String id, Map<String, Object> options);
```

**On the wire:** `<command><update><contact:update>` — RFC 5733 §3.2.5.

| key | value |
|---|---|
| `chg` (also spelled `change`) | a `Map` with `postalInfo`, `postalInfos`, `voice`, `fax`, `email`, `authInfo`, `disclose` |
| `addStatuses` | a `List` of client-side statuses to set |
| `remStatuses` (also spelled `removeStatuses`) | a `List` of client-side statuses to clear |

```java
Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("email", "new-contact@example.com");
chg.put("voice", "+380.441234500");

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("chg", chg);
options.put("addStatuses", Arrays.asList("clientUpdateProhibited"));

client.contact().update("acme-01", options);
```

All the statuses in `addStatuses` go into one `<contact:add>` block and all of `remStatuses` into
one `<contact:rem>`, which is what the RFC 5733 schema allows — one of each, holding up to seven
statuses.

### The update rule: a postal block is REPLACED, not merged

Send a postal block in `chg` and the registry **replaces** the block it holds with the one you sent.
The two are not merged field by field, so whatever you leave out is gone.

RFC 5733 can be read the other way: in `chgPostalInfoType` name, org and addr are each optional,
which looks like "leave it out and the registry keeps what it holds". That reading is not safe.
Against a registry that replaces, the first two of these answer **1000**, and the third never leaves
this library:

| what the `chg` carried | what the contact had afterwards |
|---|---|
| the complete block with `org` set to `""` | organisation removed, address untouched |
| the complete block with no `org` key | organisation **also removed** |
| `org` set to `""` and nothing else | refused here, with a `ValidationException` — on the wire it would have left **no postal block at all**: name, street, city, postal code and country gone |

So there is no such thing as changing one field of an address, and the failure is silent: the command
succeeds and the data is gone. `name`, `city` and `cc` are required in every postal change and this
library refuses the call without them, naming the one that is missing — but that guard only keeps the
frame schema-valid. It cannot put back an `org`, an `sp` or a `pc` you did not send.

**Read the block, apply your change, send it back whole:**

```java
Map<String, Object> current = client.contact().info("acme-01").postalInfo().get("int");

// Change the city and clear the organisation, keeping everything else exactly as it was.
Map<String, Object> postal = new LinkedHashMap<String, Object>(current);
postal.put("type", "int");
postal.put("city", "Lviv");
postal.put("org", "");

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("postalInfo", postal);
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("chg", chg);

client.contact().update("acme-01", options);
```

Copying the map the accessor returned is what makes that correct: `postalInfo()` hands back the
block with every part the registry holds, including the `street` list, and a copy with your change
applied on top is the whole block again rather than a fragment of it.

One thing the replacement does *not* reach is the other postal form: `int` and `loc` are addressed
separately, so replacing one leaves the other exactly as it was.

Inside the block you send, an empty string is still what clears an optional field:

| what you write | what happens |
|---|---|
| the key holds a value | the field is set to that value |
| the key holds `""` | the field is **cleared** — the way to remove `org`, `sp` or `pc` |
| the key is absent | the field is not sent — and the registry deletes what it held |

`name` and `city` cannot be cleared at all and `cc` is exactly two characters: RFC 5733 gives those
schema types a minimum length, so an empty one is an invalid frame and the library refuses it here
rather than letting it come back as a bare `2001` naming no element.

Change both forms in one command with `postalInfos`:

```java
Map<String, Object> international = new LinkedHashMap<String, Object>();
international.put("type", "int");
international.put("name", "Ivan Petrenko");
international.put("city", "Lviv");
international.put("cc", "UA");

Map<String, Object> localized = new LinkedHashMap<String, Object>();
localized.put("type", "loc");
localized.put("name", "Іван Петренко");
localized.put("city", "Львів");
localized.put("cc", "UA");

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("postalInfos", Arrays.asList(international, localized));
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("chg", chg);

client.contact().update("acme-01", options);
```

The form you do not mention is left alone.

### Changing the transfer secret

```java
Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("authInfo", "Fresh-C0nt@ct-Pw");
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("chg", chg);

client.contact().update("acme-01", options);
```

A contact's transfer secret can be **replaced but not removed**: RFC 5731 gives a domain a nullable
form and RFC 5733 defines no equivalent for a contact. Do not reach for an empty password as a
substitute — an empty value is still a value the holder can present. Set a fresh secret instead.

### Changing disclosure

```java
Map<String, Object> disclose = new LinkedHashMap<String, Object>();
disclose.put("flag", Boolean.FALSE);
disclose.put("email", Boolean.TRUE);
disclose.put("voice", Boolean.TRUE);

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("disclose", disclose);
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("chg", chg);

client.contact().update("acme-01", options);
```

The block is sent whole, so state the complete preference each time rather than the difference from
the last one.

**Result codes:** `1000`; `2303` (no such handle); `2304` (a status prohibits it); `2306` (policy);
`2308` (the change would remove something the registry requires).

### Building an update step by step

```java
ContactCreateBuilder createBuilder(String id, String email);
ContactUpdateBuilder updateBuilder(String id);
```

The id and the e-mail are arguments to `createBuilder()` rather than steps, because the registry
requires both and a step is something you can forget. On the update builder the same
presence-decides rule appears as "pass the argument to change it, pass `""` to clear it, omit the
whole step to leave it alone". Every step is documented in [Builders](builders.md).

---

## delete

```java
Response delete(String id);
```

**On the wire:** `<command><delete><contact:delete>` — RFC 5733 §3.2.2.

A contact still referenced by a domain cannot be deleted; the registry answers **`2305`**. The
`linked` status in `statuses()` is the registry saying so in advance:

```java
Response c = client.contact().info("acme-01");

if (c.statuses().contains("linked")) {
    // Still in use. Repoint the domains that reference it first — allContacts() on a
    // domain:info tells you which handles a domain holds.
    return;
}

client.contact().delete("acme-01");
```

**Result codes:** `1000`; `2303`; `2305` (still linked to a domain).

---

## transfer

```java
Response transfer(String op, String id, String authInfo);
Response transfer(String op, String id);
```

**On the wire:** `<command><transfer op="…"><contact:transfer>` — RFC 5733 §3.2.4 (and §3.1.3 for
`query`). `op` is one of `request`, `query`, `approve`, `reject`, `cancel`, with the same meanings
as for a [domain transfer](domains.md#transfer) — `request` and `cancel` belong to the gaining
registrar, `approve` and `reject` to the current sponsor.

```java
Response r = client.contact().transfer("request", "acme-01", "the-code");

r.code();             // 1000, or 1001 when the sponsor has to answer
r.transferStatus();   // "pending"
r.transfer();         // {status=…, requestedBy=…, requestedAt=…, actingClient=…, actBy=…, expiryDate=…}
```

The request reaches the sponsor as a [poll notice](poll.md) carrying a `trnData`. As the sponsor:

```java
client.contact().transfer("approve", "acme-01");
// or
client.contact().transfer("reject", "acme-01");
```

`query` reports where a request has got to — `2300` while one is pending, `2301` when none is.

**Result codes:** `1000` / `1001`; `2201` (not yours to act on); `2202` (wrong `authInfo`); `2300`
(already pending); `2301` (nothing pending); `2303`; `2304`.

---

## Result codes on this page

| Code | Meaning | Exception |
|---|---|---|
| `1000` | done | — |
| `1001` | accepted, completing offline; the outcome arrives via [poll](poll.md) | — |
| `2003` | a required parameter is missing (postal block, e-mail) | `CommandException` |
| `2005` | a value is syntactically invalid (e-mail, Cyrillic in an `int` block) | `CommandException` |
| `2201` | not yours to act on | `AuthorizationException` |
| `2202` | wrong `authInfo` | `AuthorizationException` |
| `2300` / `2301` | already pending transfer / not pending transfer | `CommandException` |
| `2302` | the id is taken | `ObjectExistsException` |
| `2303` | no such handle | `ObjectDoesNotExistException` |
| `2304` / `2305` | a status prohibits it / still linked to a domain | `ObjectStatusException` |
| `2306` / `2308` | registry policy refuses this value | `PolicyException` |

`ResultCode` has a named constant for every one of them; the full taxonomy is in
[Errors](errors.md).

---

See also: [Domains](domains.md) · [Hosts](hosts.md) · [Poll](poll.md) ·
[Balance & prices](balance.md) · [Responses](responses.md) · [Builders](builders.md)

[← Manual index](README.md)
