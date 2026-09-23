# Responses

Every command returns a `com.epptools.sdk.Response`. It wraps the parsed reply and answers questions
about it through named accessors, so you never index into a map by a string you had to guess.

This page lists **every** accessor, grouped by what it answers, with its signature and what it gives
back when the reply carries nothing.

Two rules that hold across the whole class:

- **Dates come back as the registry's own string** — `2027-04-01T09:15:00Z`, or with an offset —
  never an `Instant` or a `Date`. The registry decides which calendar day a renewal lands on, and
  reformatting through a local timezone is how a client ends up displaying, and renewing against, the
  day before.
- **Money comes back as an exact decimal string**, never a `double`. `0.1 + 0.2` is not `0.3` in
  binary floating point, and a balance summed that way drifts. Use `BigDecimal` or integer minor
  units.

An accessor asked about something the reply does not contain answers `null`, an empty collection,
`false` or `0` as its return type allows. That is a legitimate answer — "the registry said nothing
about this" — not an error. It does mean that the `String` accessors are nullable and the boxed
`Boolean` ones are too, so read them with `Boolean.TRUE.equals(…)` and a null check rather than by
unboxing.

Element lookups are by local name and ignore namespaces, so a change in the response's prefixes never
breaks an accessor. Text is read from an element's own direct character data and not from its
descendants, so a container element never returns the concatenated text of everything inside it.

## Result and outcome

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `int code()` | The EPP result code: 1000, 1001, 2303 … | `0` for a greeting or any frame with no `<result>` |
| `boolean isSuccess()` | `true` for any 1xxx code | `false` |
| `boolean isPending()` | `true` only for 1001 — accepted, completing offline | `false` |
| `boolean isGreeting()` | `true` when the frame is a `<greeting>` rather than a `<response>` | `false` |
| `String message()` | The result `<msg>`, in the session language | `null` |
| `String messageLang()` | The language of that message: `en`, `uk`, `ua`, `ru` | `null` |
| `List<String> errorReasons()` | The `<extValue><reason>` prose from a failed command | empty list |
| `List<Map<String,Object>> extValues()` | The `<extValue>` blocks in full — see below | empty list |
| `String clTRID()` | The client transaction id the server echoed | `null` |
| `String svTRID()` | The registry's transaction id. Store it against the object | `null` |

`isSuccess()` being true is not the same as the work being done: 1001 is a success code and means
the registry accepted the command and will finish it offline. Test `isPending()` before you record
anything as complete.

### `List<Map<String,Object>> extValues()`

Where `errorReasons()` gives the prose, this gives **which element** the server objected to — which
is the part you can act on. A check for five domains that fails on one carries that one name here.

```java
for (Map<String, Object> ext : response.extValues()) {
    ext.get("element");    // "name"  — the local name of the offending node, "" if the server named none
    ext.get("namespace");  // "urn:ietf:params:xml:ns:domain-1.0"
    ext.get("text");       // "bad..name" — the node's OWN character data
    ext.get("values");     // children by local name, as a Map<String, String>, when it is a container
    ext.get("xml");        // the node serialised, for a log
    ext.get("reason");     // the server's explanation
    ext.get("lang");       // its language
}
```

`text` is the element's own character data rather than a recursive `getTextContent()`, so a container
never comes back as its children fused into one string that reads like a value and is not.
`CommandException.subject()` reads the first non-empty `text` for you — see [Errors](errors.md).

## Object identity

These answer for a domain, a host or a contact alike.

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `String objectName()` | The domain name, the host name, or the contact **handle** | `null` |
| `String roid()` | The registry's own identifier for the object | `null` |
| `String sponsor()` | `clID` — the registrar the object belongs to now | `null` |
| `String registrarOfRecord()` | The handle the registry's WHOIS and RDAP publish as the registrar | `null` |
| `String createdBy()` | `crID` — the registrar that created it | `null` |
| `String createdDate()` | `crDate`, as the registry wrote it | `null` |
| `String updatedBy()` | `upID` — the registrar that last changed it | `null`, including when it has never been changed |
| `String updatedDate()` | `upDate` | `null` when never changed |
| `String authInfo()` | The `<authInfo><pw>` transfer secret | `null` when the registry withheld it |
| `List<String> statuses()` | Status values from the `s` attribute: `[ok]`, `[clientHold, …]` | empty list |

`objectName()` reads the direct child of the object block. That matters on a contact: a
document-wide search for a `<name>` element finds the person's full name inside the postal address
first, and feeding that back as an id draws a 2303.

`registrarOfRecord()` and `sponsor()` are not the same party for a reseller. `sponsor()` names the
account the object belongs to in your own hierarchy; `registrarOfRecord()` names who the registry
publishes.

`updatedBy()` is sent only to the sponsoring registrar. Pair it with `updatedDate()` when
reconciling: a change you did not make came from the registry side or from a support action, not
from your system.

`authInfo()` is a live credential — the secret that lets any registrar take the domain away from
you. Never log it, never put it in a support ticket, and roll it after you have passed it to a
customer.

## Domain

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `String expiryDate()` | `exDate`, exactly as the registry wrote it | `null` |
| `String registrant()` | The registrant handle | `null` |
| `Map<String,List<String>> contacts()` | Role contacts: `{admin=[c-1], tech=[c-1, c-2]}`. The registrant is **not** included | empty map |
| `List<String> contactsFor(String role)` | The handles in one role, matched case-insensitively | empty list — a legitimate answer, since only the registrant is mandatory everywhere |
| `List<String> adminContacts()` | `contactsFor("admin")` | empty list |
| `List<String> techContacts()` | `contactsFor("tech")` | empty list |
| `List<String> billingContacts()` | `contactsFor("billing")` | empty list |
| `List<String> allContacts()` | Every handle in any capacity, registrant included, de-duplicated | empty list |
| `List<String> nameservers()` | The delegation, lower-cased, in the registry's order | empty list |
| `Map<String,List<Map<String,String>>> nameserverAddresses()` | Inline glue keyed by nameserver name | empty map |
| `List<String> subordinateHosts()` | Host objects living **under** this domain, lower-cased | empty list |
| `Map<String,String> transfer()` | A transfer in full — see below | `null` |
| `String transferStatus()` | `trStatus`: `pending`, `serverApproved`, … | `null` |
| `String transferDate()` | `trDate` — when it last changed hands | `null` if it never has |
| `String license()` | A trademark or licence number, from the registry's own extension | `null` |
| `List<String> rgpStatus()` | RGP status values, e.g. `[redemptionPeriod]` (RFC 3915) | empty list |
| `List<Map<String,Object>> dsRecords()` | DNSSEC DS records (RFC 5910) | empty list when unsigned |
| `List<Map<String,Object>> keyRecords()` | DNSSEC public keys | empty list |
| `boolean isSigned()` | Whether the domain carries any DNSSEC data | `false` |
| `Map<String,Map<String,String>> prices()` | Price hints from the registry's price extension, keyed by operation | empty map |
| `String priceChannel()` | The price channel this domain is billed on | `null` |

`contactsFor()` matches the role case-insensitively, because registries are inconsistent about
`tech` versus `Tech` and a case-sensitive lookup silently reports "no technical contact" for a
domain that has one.

`allContacts()` is the one to use when you care **that** a contact is referenced rather than in
which role — before deleting a contact, or when working out which of your contact objects are still
in use.

`nameservers()` covers both EPP delegation models: `<domain:hostObj>`, a reference to a host object,
and `<domain:hostAttr>`, the name inlined with its glue. A client that reads only one of them sees
an empty list against a registry using the other, and concludes the domain has no nameservers at
all.

`nameserverAddresses()` returns the inline glue only:

```java
Response info = client.domain().info("example.com.ua");

info.nameserverAddresses();
// {ns1.example.com.ua=[{ip=192.0.2.1, version=v4}]}
```

It is populated only where the registry answers with `hostAttr`. Against one that answers with
`hostObj` you get an empty map and fetch the addresses with a `host().info()` per name — so an empty
result here does **not** mean the domain is undelegated. Use `nameservers()` for the list.

`subordinateHosts()` matters before a delete: the registry refuses to delete a domain while
nameserver objects live under it. Check the list, remove or repoint them, then delete.

`prices()` and `priceChannel()` are the registry's own price hints on a `domain:info`, distinct from
the RFC 8748 quotes in [`fees()`](#check-and-money):

```java
Response info = client.domain().info("example.com.ua");

info.prices();
// {renewal={value=180.00, currency=UAH}, …}
info.priceChannel();
// an opaque id matching a row of the registry's published catalogue
```

A domain registered long ago may sit on a different channel from the one a new registration in the
same zone would use, which is why the channel is per domain rather than per zone.

### `Map<String,String> transfer()`

The whole transfer notice, from a transfer response or the `trnData` of a poll message:

```java
Map<String, String> t = response.transfer();
// {
//   status       = pending,
//   requestedBy  = ACME,                     // reID
//   requestedAt  = 2026-08-14T10:00:00Z,     // reDate
//   actingClient = EXAMPLE,                  // acID — who must answer
//   actBy        = 2026-08-19T10:00:00Z,     // acDate — the deadline
//   expiryDate   = 2028-04-01T09:15:00Z      // exDate the transfer would produce
// }
```

`transferStatus()` alone says a transfer is pending without saying whose or how long you have.
`actBy` is the moment after which the registry decides for you.

### DNSSEC records

```java
Response info = client.domain().info("example.com.ua");

info.dsRecords();
// [{keyTag=12345, alg=8, digestType=2, digest=ABCD…}]

info.keyRecords();
// [{flags=257, protocol=3, alg=8, pubKey=AwEAAb…}]

info.isSigned();   // true when either list is non-empty
```

The numeric fields come back as `Integer` inside a `Map<String, Object>`, and the digest and public
key as `String`, so a cast is needed to do arithmetic on a key tag. There is nothing to compute on
one in practice: compare it, log it, or send it back in a `rem` block exactly as it arrived.

## Host

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `List<Map<String,String>> hostAddresses()` | The host object's glue: `[{ip=192.0.2.1, version=v4}, …]` | empty list |

Only a host **inside** the zone it serves carries glue. For an external nameserver the registry
returns none, and that is normal rather than a missing answer.

The list is scoped to the host object itself, so it never fuses in the per-name glue of a domain's
inline delegation — that is `nameserverAddresses()`. A missing `ip` attribute means `v4`, which is
the host schema's own default, so an IPv4-only registry that omits it is reported correctly rather
than as something else.

## Contact

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `Map<String,Map<String,Object>> postalInfo()` | Addresses keyed by form: `int` (ASCII) and `loc` (local script) | empty map |
| `String email()` | The contact's e-mail address | `null` |
| `String voice()` | The voice number, in the EPP `+CC.NNNN` form | `null` |
| `String fax()` | The fax number, same form | `null` |
| `Map<String,Object> disclose()` | The disclosure preference | `null` when the contact carries none and registry policy alone applies |

```java
Response c = client.contact().info("C-0001");

c.postalInfo();
// {int={name=ACME LLC, org=ACME LLC, street=[1 Main St],
//       city=Kyiv, sp=, pc=01001, cc=UA},
//  loc={…}}
```

A contact may carry either form or both; parts it does not carry are `""` rather than absent, so a
lookup inside a block that exists never returns `null` — but the block itself may be missing, which
is what `containsKey("loc")` is for. Read `int` when you need something you can safely print
anywhere, `loc` when you want the address as the registrant actually wrote it. Inside a block,
`street` is a `List<String>` and everything else is a `String`, which is why the value type is
`Object`.

```java
client.contact().info("C-0001").disclose();
// {flag=false, elements=[email, voice]}
```

`flag` is a `Boolean`: true means the listed elements **may** be published, false means they must be
withheld. The elements that are *not* listed take the opposite of the flag, so the list is
meaningless without it. An element that exists once per postal form appears with its type, as
`name:int` or `addr:loc`.

## Check and money

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `Map<String,Boolean> availability()` | The whole `*:check` map: name or id to `Boolean` | empty map |
| `Boolean isAvailable(String name)` | One name, matched case-insensitively | `null` — "the answer said nothing about it" |
| `String unavailableReason(String name)` | Why a name is not available: `In use`, `Reserved` | `null` when it is available or no reason was given |
| `Map<String,Object> fees()` | Per-name RFC 8748 quotes, plus `_currency` | empty map |
| `String feeFor(String name, String operation, int years)` · `feeFor(name, operation)` | One quote, as a decimal string | `null` when the answer carried no such quote |
| `String feeClass(String name)` · `feeClass()` | The registry's fee class: `premium`, `standard` | `null` |
| `boolean isPremium(String name)` · `isPremium()` | Whether the name is priced outside the standard list | `false` |
| `Map<String,String> chargedFee()` | What a transform actually charged: `{currency=UAH, fee=100.00}` | `null` |
| `String feeAmount()` | The amount from `chargedFee()` | `null` |
| `String feeCurrency()` | The currency from `chargedFee()` | `null` |
| `Map<String,String> balance()` | The whole balance block | `null` when this is not a balance response |
| `String creditLimit()` | Your credit limit | `null` |
| `String currentBalance()` | Your current balance | `null` |
| `String availableCredit()` | What you can still spend: balance plus any credit limit | `null` |

`isAvailable()` returning `null` is the reason it exists. Reading `availability()` by hand gives
`null` for both "taken" and "you misspelled the key", and those two answers must not look the same
on the line before a registration. It returns a boxed `Boolean` for exactly that reason, so compare
it rather than unboxing it:

```java
Response check = client.domain().check(Arrays.asList("example.com.ua", "taken.com.ua"));

check.availability();                       // {example.com.ua=true, taken.com.ua=false}
check.isAvailable("EXAMPLE.com.ua");        // Boolean.TRUE
check.isAvailable("never-asked.com.ua");    // null
check.unavailableReason("taken.com.ua");    // "In use"
```

### `fees()` and `feeFor()`

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

r.fees();
// {
//   _currency      = UAH,
//   example.com.ua = {
//       avail    = true,                  // false means read "reason" (zone not served, currency…)
//       reason   = null,
//       class    = premium,               // present only when the registry sent a class
//       commands = {create={years=1, fee=100.00}},
//       periods  = [{op=create, years=1, fee=100.00},
//                   {op=create, years=2, fee=200.00},
//                   {op=create, years=5, fee=480.00}]
//   }
// }

r.feeFor("example.com.ua", "create", 5);    // "480.00"
```

Asking one operation at several periods brings back one quote per period. The `commands` map holds
one entry per operation — the first period you asked for — so **read `feeFor()` or `periods`
whenever you asked for more than one**. `transfer` and `restore` are one-year operations however
many years you ask for, so read those back at one year.

Amounts here are illustrative, not the registry's tariff. Everything about asking and capping
prices is on [Balance](balance.md).

### `isPremium()` and `feeClass()`

```java
r.feeClass("example.com.ua");   // "premium" | "standard" | null
r.isPremium("example.com.ua");  // true when the class is present and is not "standard"
```

Both have a no-argument overload; with no name, they answer for the first name in the reply that
carries a class. A `false` from `isPremium()` is not a promise of the standard price — it means the
answer declared no special class. Charge from `fees()`, and cap the fee on the transform itself.

### What was actually charged

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");

Response create = client.domain().create("example.com.ua", options);

create.chargedFee();    // {currency=UAH, fee=100.00} or null
create.feeAmount();     // "100.00"
create.feeCurrency();   // "UAH"
```

The registry echoes this on a successful transform that carried a fee agreement. Record
`feeAmount()` against the order rather than the price you quoted from a `check`: between the two,
the tariff can have moved.

### The balance block

```java
Response b = client.balance();

b.balance();           // {creditLimit=…, balance=…, availableCredit=…}, or null
b.creditLimit();
b.currentBalance();    // named for what it is, because balance() returns the whole block
b.availableCredit();
b.threshold();         // only on a low-balance notice
```

The figures are decimal strings in your account currency. `balance()` answers `null` when the response
is not a balance answer at all, and it reads the figures from a balance `infData` only — never from the
`<fee:balance>` a create or a renew echoes back, which is a fee receipt and not a report.
`threshold()` is the one to test for a low-balance notice: its **presence** is what tells a warning
from a report, and on a plain report it is `null`. See [Balance](balance.md).

## Poll

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `String messageId()` | The queued message id, to pass to `poll().ack()` | `null` — the queue is empty |
| `int messageCount()` | How many messages remain in the queue | `0` |
| `String queueMessage()` | The **notice** text, from `<msgQ><msg>` | `null` |
| `String queueMessageLang()` | The notice's language: `uk`, `ru`, `en` | `null` |
| `String queueDate()` | When the notice was queued | `null` |
| `Map<String,String> change()` | What the registry did to your object without you asking (RFC 8590) | `null` |
| `Map<String,Object> pendingActionData()` | The outcome of an operation the registry processed offline | `null` for an ordinary notice or an empty queue |

**`queueMessage()`, not `message()`.** `message()` returns the command-result banner — "Command
completed successfully; ack to dequeue" — which is a constant string. The notice's real content is
in `queueMessage()`, and reading the wrong one hands you the banner while the content is discarded
and an ack destroys it at the registry irreversibly.

The notice carries its own language, set per registrar, independent of the session language that
`messageLang()` reports.

`change()` is the RFC 8590 block: `operation`, `op`, `state`, `date`, `svTRID`, `who` and `reason`,
all as strings. `state` is `before` or `after` and decides whether the object beside it describes
what it was or what it now is — the whole of it is in [Poll](poll.md#a-change-the-registry-made-to-your-object-rfc-8590).

### `Map<String,Object> pendingActionData()`

This is how a deferred command finally reports back (RFC 5731 §3.3, RFC 5733 §3.3). You send a
create, get **1001** and a `svTRID`; later a poll message carries the verdict.

```java
Map<String, Object> pan = notice.pendingActionData();
// {
//   object  = example.com.ua,
//   success = true,                        // the paResult attribute, as a Boolean
//   clTRID  = JAVA-SDK-…-0007,             // from paTRID: the ORIGINAL command
//   svTRID  = SRV-…-00042,
//   date    = 2026-08-14T10:05:00Z         // paDate: when the action completed
// }
```

- **`success` is the only thing that says whether it worked.** The surrounding
  `<result code="1301">` means "here is a message", not "your operation succeeded". Reading that
  instead is the classic mistake: every poll answer then looks like a success. Read it with
  `Boolean.TRUE.equals(pan.get("success"))`, so a missing verdict is treated as failure — because a
  missing verdict is not a yes.
- **`svTRID` matches it back** to the command you were given the 1001 for. Do not assume it is the
  most recent one; poll is a queue.
- **`date`** is when the action completed, not when you polled.

The block is matched by local name across every object namespace, so a `domain:panData` and a
`contact:panData` both come back.

## Session and security

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `List<Map<String,String>> securityEvents()` | RFC 8807 login security events about this session | empty list — a healthy session |
| `List<String> serviceObjUris()` | Greeting: the object services the server advertises | empty list |
| `List<String> serviceExtUris()` | Greeting: the extension services it advertises | empty list |

```java
for (Map<String, String> event : client.login().securityEvents()) {
    event.get("text");       // always present
    event.get("type");       // certificate | cipher | tlsProtocol | password | newPW | stat | custom
    event.get("level");      // "warning" | "error"
    event.get("exDate");     // a certificate's expiry, for type=certificate
}
```

Every key but `text` is absent when the event did not carry it, and a `Map` lookup for a missing key
returns `null`. The server returns these only to a client that took part in the extension. Full
treatment in [Session](session.md#login-security-rfc-8807).

## Raw access

For anything the named accessors do not model — an extension of your own, a field a future server
adds.

| Accessor | Returns | When the answer carries nothing |
|---|---|---|
| `String value(String localName)` | The first element anywhere with that local name, trimmed, in any namespace | `null` |
| `List<String> values(String localName)` | Every element with that local name, trimmed | empty list |
| `Element resData()` | The `<resData>` element, for custom parsing | `null` |
| `String raw()` | The response XML exactly as it arrived | never empty — it is the string the parser was handed |
| `Document dom()` | The parsed `org.w3c.dom.Document` | — |
| `static Response fromXml(String xml)` | Parses a frame into a `Response`. Throws `ConnectionException` on malformed XML | — |

`value()` and `values()` match on the **local** name, ignoring the namespace, which is what makes
them useful against an extension you have not modelled. Two traps worth naming:

- A status lives in the `s` **attribute**, so `values("status")` returns a list of blanks. Use
  `statuses()`.
- `ns` is only the wrapper around a delegation and carries no text of its own, so `values("ns")`
  returns a list of **empty** strings — not the nameserver names fused together, which is the more
  hopeful reading. Use `nameservers()`, or `values("hostObj")`.

There is no XPath helper on `Response`, and no table of bound prefixes, because there is nothing
useful to bind: your registry's own extension URI is not known until the greeting is read, and every
accessor on this page already finds extension data **by local name**, so a licence or a price is read
the same way whatever namespace it arrived under.

```java
Response info = client.domain().info("example.com.ua");

info.value("license");      // the registry's own extension — no namespace needed
info.values("price");
```

Where you want the tree rather than a value, `dom()` and `resData()` give you the DOM the library
parsed, and the namespace-aware lookups the JDK already has:

```java
Response info = client.domain().info("example.com.ua");

NodeList names = info.dom().getElementsByTagNameNS(Namespaces.DOMAIN, "hostName");
for (int i = 0; i < names.getLength(); i++) {
    System.out.println(names.item(i).getTextContent());
}
```

`javax.xml.xpath` is in the JDK if you would rather query by expression; it needs a
`NamespaceContext` of your own, and the URIs to put in it are the constants in
`Namespaces` plus whatever `client.registryExtUri()` discovered.

`raw()` is the unmasked frame. If you store it, mask `<pw>`, `<newPW>` and `<authInfo>` yourself —
the library masks them in its own logs for the same reason.

`Response.fromXml()` is how the library builds every response, and it is public so you can parse a
frame you captured. Malformed XML throws `ConnectionException` rather than returning a half-built
object, and a frame carrying a DOCTYPE is refused outright — which also closes the XXE and
entity-expansion doors a hostile endpoint would otherwise have.

---

[← Manual index](README.md)
