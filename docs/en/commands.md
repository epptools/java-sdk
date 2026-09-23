# Commands

Everything after the login is a command and its response. This page is the command surface as a
whole: how you reach a command, what one gives back, how transactions are labelled, how to turn
exceptions off, and how to send a frame the library does not model.

The per-object detail lives on its own page: [Domains](domains.md), [Contacts](contacts.md),
[Hosts](hosts.md), [Poll](poll.md), [Balance](balance.md).

## Reaching a command

Four handlers hang off the client, plus the balance query, which is a single method:

```java
client.domain();    // com.epptools.sdk.command.Domain   — RFC 5731
client.contact();   // com.epptools.sdk.command.Contact  — RFC 5733
client.host();      // com.epptools.sdk.command.Host     — RFC 5732
client.poll();      // com.epptools.sdk.command.Poll     — RFC 5730 §2.9.2.3
client.balance();   // the registry's balance extension — a Response, not a handler
```

Each handler is created once per client and returned again on every call, so
`client.domain().check(…)` costs nothing extra inside a loop.

## The whole surface

Every method returns `Response` unless the table says otherwise. Where a command has several
overloads the fullest is listed first, then the shorter forms that default the rest.

| Method | Sends | Documented in |
|---|---|---|
| `domain().check(List<String> names, Map<String,Object> fee, String currency)` · `check(names)` | `domain:check`, optionally with `fee:check` | [Domains](domains.md), [Balance](balance.md) |
| `domain().info(String name, String authInfo, String hosts)` · `info(name)` | `domain:info` | [Domains](domains.md) |
| `domain().create(String name, Map<String,Object> options)` | `domain:create` | [Domains](domains.md) |
| `domain().update(String name, Map<String,Object> options)` | `domain:update` | [Domains](domains.md) |
| `domain().renew(String name, String curExpDate, int years, Object fee)` · `renew(name, curExpDate, years)` · `renew(name, curExpDate)` | `domain:renew` | [Domains](domains.md) |
| `domain().restore(String name, Object fee)` · `restore(name)` | `domain:update` with `rgp:restore` (RFC 3915) | [Domains](domains.md) |
| `domain().delete(String name)` | `domain:delete` | [Domains](domains.md) |
| `domain().transfer(String op, String name, String authInfo, Integer years, Object fee)` · `transfer(op, name, authInfo)` · `transfer(op, name)` | `domain:transfer` | [Domains](domains.md) |
| `domain().createBuilder(String name)` → `DomainCreateBuilder` | nothing until `send()` | [Builders](builders.md) |
| `domain().updateBuilder(String name)` → `DomainUpdateBuilder` | nothing until `send()` | [Builders](builders.md) |
| `contact().check(List<String> ids)` | `contact:check` | [Contacts](contacts.md) |
| `contact().info(String id, String authInfo)` · `info(id)` | `contact:info` | [Contacts](contacts.md) |
| `contact().create(String id, Map<String,Object> options)` | `contact:create` | [Contacts](contacts.md) |
| `contact().createAuto(Map<String,Object> options)` | `contact:create` with the reserved id `Contact.AUTO_ID` | [Contacts](contacts.md) |
| `contact().update(String id, Map<String,Object> options)` | `contact:update` | [Contacts](contacts.md) |
| `contact().delete(String id)` | `contact:delete` | [Contacts](contacts.md) |
| `contact().transfer(String op, String id, String authInfo)` · `transfer(op, id)` | `contact:transfer` | [Contacts](contacts.md) |
| `contact().createBuilder(String id, String email)` → `ContactCreateBuilder` | nothing until `send()` | [Builders](builders.md) |
| `contact().updateBuilder(String id)` → `ContactUpdateBuilder` | nothing until `send()` | [Builders](builders.md) |
| `host().check(List<String> names)` | `host:check` | [Hosts](hosts.md) |
| `host().info(String name)` | `host:info` | [Hosts](hosts.md) |
| `host().create(String name, List<String> addresses)` · `create(name)` | `host:create` | [Hosts](hosts.md) |
| `host().update(String name, Map<String,Object> options)` | `host:update` | [Hosts](hosts.md) |
| `host().delete(String name, boolean force)` · `delete(name)` | `host:delete`, optionally with the registry's forced-delete extension | [Hosts](hosts.md) |
| `host().updateBuilder(String name)` → `HostUpdateBuilder` | nothing until `send()` | [Builders](builders.md) |
| `poll().request()` | `<poll op="req">` | [Poll](poll.md) |
| `poll().ack(String messageId)` | `<poll op="ack">` | [Poll](poll.md) |
| `poll().drain(Consumer<Response> handler, int limit)` · `drain(handler)` → `int` | request/ack in a loop | [Poll](poll.md) |
| `balance()` | `balance:info` | [Balance](balance.md) |
| `request(Frame frame)` · `request(String xml)` | whatever you built | [below](#custom-frames) |
| `frame()` → `Frame` | nothing; returns a stamped frame | [below](#custom-frames) |

The session methods — `connect()`, `hello()`, `login()`, `logout()`, `disconnect()` — are in
[Session](session.md).

## What a command returns

**Every command returns a [`Response`](responses.md).** Not `null`, not a `Map`, not a `boolean`.
The object wraps the parsed reply and every accessor reads out of it.

```java
Response response = client.domain().check(Arrays.asList("example.com.ua"));

response.code();        // int:  1000
response.isSuccess();   // boolean: true for any 1xxx
response.message();     // String: the server's <msg>, in your session language
response.svTRID();      // String: the registry's transaction id — store it
```

The three outcomes to write code for:

| Code | Meaning | What to do |
|---|---|---|
| `1000` | done | continue |
| `1001` | accepted, completing offline | **do not resend.** The object carries a `pending*` status and the outcome arrives later as a poll notice |
| `2xxx` | refused; nothing was changed | read the code — by default it has already been thrown as an exception |

`1001` is the one that catches people out. It is a success code, so `isSuccess()` is `true` and
nothing is thrown. Test it explicitly:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");

Response response = client.domain().create("example.com.ua", options);

if (response.isPending()) {
    // Store svTRID() against the order. The verdict arrives in the poll queue as
    // pendingActionData(), and its paTRID svTRID is what matches it back to this command.
    orders.markPending(response.svTRID());
}
```

See [Poll](poll.md) for the other half of that exchange.

Two other codes are success and must not be read as failure: **1300** (poll: the queue is empty)
and **1500** (the answer to `logout`).

## Client transaction ids

Every command carries a `clTRID` that you choose and every response carries a `svTRID` that the
registry assigns.

| Identifier | Who sets it | What it is for |
|---|---|---|
| `clTRID` | this client | matching a reply to the request that caused it |
| `svTRID` | the registry | the registry's own record of that operation |

The library stamps a unique `clTRID` on every frame it builds. The shape is

```
JAVA-SDK-20260816103000-24191-0007
   │            │          │    └── a counter, monotonic within this client instance
   │            │          └──────── the OS process id, so two workers never collide
   │            └─────────────────── a UTC timestamp, YYYYMMDDHHMMSS: when
   └──────────────────────────────── the clTRIDPrefix from Config
```

Where the JVM will not report a process id, the middle segment is a short generated token instead,
which keeps two workers apart just the same.

Set `clTRIDPrefix` to something that identifies your system. It is a human-correlatable label, not
a secret, and it is what the registry's support desk will read back to you.

**Store the `svTRID` against the object the command was about.** It is the one value support can
look an operation up by; a `clTRID` means nothing to anyone but you. Log both, on every command,
including the ones that succeeded — those are what you compare against when a later one does not.

### The echo check

The server echoes your `clTRID` back (RFC 5730 §2.5). Because this client generates a unique one per
command, comparing them turns any desynchronisation of the stream from a silent mis-attribution
into a loud failure: without it, a reply belonging to the previous command is indistinguishable
from this one's, and for a renew or a create that means booking the wrong domain as done.

A mismatch throws `ConnectionException` **and closes the connection** — once the offsets disagree,
every later frame on that stream is suspect too. The comparison allows for a server that normalises
the value, since the schema's transaction-id type is 3–64 characters: a legitimately truncated or
padded echo is accepted, a wrong one is not.

## The `throwOnFailure` switch

By default any result code of 2000 or more is thrown as a `CommandException` (or the subclass that
fits the code). That is what makes a straight-line integration correct by default: you cannot
forget to check a code you never see. It matters more here than in a language with checked
exceptions — every exception this library throws is unchecked, so nothing in a signature reminds you
that a command can be refused.

```java
client.throwOnFailure(false);   // and (true) to turn it back on
```

With it off, a refusal comes back as an ordinary `Response` and you branch on `code()` yourself:

```java
client.throwOnFailure(false);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");

Response response = client.domain().create("example.com.ua", options);
if (response.code() == ResultCode.OBJECT_EXISTS) {
    taken.add("example.com.ua");
} else if (!response.isSuccess()) {
    throw new IllegalStateException(response.message() != null ? response.message() : "create failed");
}
```

The switch returns the client, so it chains, and it applies to every later command on that client
until you change it back.

What it does **not** turn off:

- `ConnectionException` — the server never answered, so there is no code to read.
- `ValidationException` and `ConfigException` — nothing was sent at all.
- `AuthenticationException` from `login()`. A login that failed is not a session you can carry on
  in, so it is thrown whatever the switch says.
- The refusal `poll().drain()` throws when a poll reply is neither a notice nor an empty queue.
  With throwing off, that reply reaches the loop instead of being thrown, and the loop throws it
  explicitly rather than reading a refusal as a drained queue.

`com.epptools.sdk.ResultCode` has a named constant for every code — see the table in
[Errors](errors.md#result-codes).

## Option keys are checked before the frame is built

The option-heavy commands take a `Map<String, Object>`, and refuse a key they do not understand,
naming the closest one they do:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("secdns", new LinkedHashMap<String, Object>());

client.domain().create("example.com.ua", options);
// ValidationException: domain:create does not accept 'secdns' (did you mean 'secDNS'?).
// Accepted: authInfo, contacts, fee, license, nameServers, nameservers, registrant, secDNS, years.
```

This is a deliberate trade. A map otherwise accepts anything: a key that is misspelled, in the wrong
case, or left over from an older integration is simply never read. The command still goes out, the
registry still answers 1000, and the part you asked for is missing — `"secdns"` for `"secDNS"`
registers the domain **unsigned**, a misspelled `"nameservers"` registers it with **no delegation**.
Nothing in the response says so, because as far as the registry is concerned you never asked.

A map is what carries them because the option sets are heterogeneous — an `int`, a `String`, a list
of names, a nested block — and because several of them nest. The types buy nothing here that the key
check does not, and the key check is what catches the mistake that costs money.

Where two spellings are both defensible, both are accepted: `nameservers` and `nameServers` on a
domain create are the same option. So are EPP's own abbreviations and their plain words — `remove`
for `rem`, `change` for `chg`, `removeAll` for `remAll`, `removeStatuses` for `remStatuses`,
`removeAddresses` for `remAddresses`. Neither spelling is deprecated, passing both is not an error,
and the plain word wins. Nested blocks are checked too — the `add`, `rem`, `chg` and `secDNS` blocks
of an update each have their own list.

Use `LinkedHashMap` rather than `HashMap` where the reading order of a block matters to you. It
makes no difference on the wire — the library emits the elements in the order the schema fixes — but
it makes `toString()` on a map you are debugging read the way you wrote it.

The [builders](builders.md) remove the class of mistake entirely: a misspelled step is a method that
does not exist, and the compiler says so before you run anything.

## Custom frames

Anything the high-level API does not cover can be assembled with `com.epptools.sdk.Frame` and sent
through `Client.request()`.

```java
Frame frame = client.frame();                    // a <command> with a clTRID already stamped
Element check = frame.ns(frame.verb("check"), Namespaces.DOMAIN, "domain:check");
frame.ns(check, Namespaces.DOMAIN, "domain:name", "example.com.ua");

Response response = client.request(frame);
response.availability();
```

`Client.frame()` is the entry point you want: it returns a `Frame` with a generated `clTRID`, so
the [echo check](#the-echo-check) still protects the exchange. `Frame.command(clTRID)` builds one
with an id of your own, and then the uniqueness is your responsibility.

### The `Frame` API

| Method | What it does |
|---|---|
| `static Frame command(String clTRID)` | Start a `<command>` frame with this transaction id. |
| `Element verb(String name)` | Append the command verb — `check`, `info`, `create`, `update`, `renew`, `transfer`, `delete`, `poll`, `login`, `logout` — and return it to hang content on. |
| `Element extension()` | The `<extension>` element, created on first call and returned again afterwards, so several extensions share one block. |
| `Element epp(Element parent, String name, String text, Map<String,?> attrs)` · `epp(parent, name, text)` · `epp(parent, name)` | Append an element in the base `epp-1.0` namespace, with no prefix. |
| `Element ns(Element parent, String nsUri, String qname, String text, Map<String,?> attrs)` · `ns(parent, nsUri, qname, text)` · `ns(parent, nsUri, qname)` | Append a namespaced element carrying its prefix, e.g. `domain:name`. |
| `Element root()` | The root `<epp>` element, for a caller building by hand. Its `getOwnerDocument()` is the underlying `org.w3c.dom.Document`. |
| `String toXml()` | Serialise. The `clTRID` is written as the final child of `<command>`, which is the order RFC 5730 fixes. |

Text passed to `epp()` and `ns()` is set on an element node, never string-concatenated, so `&` and
`<` in a value are escaped for you and escaping is the DOM's problem rather than yours. `toXml()` is
safe to call more than once — log the frame, then send it — and the result carries exactly one
`clTRID`, because a `<command>` holding two draws a bare 2001.

Each element carries its own prefix in the DOM, so there is no global prefix table to keep in step
while a frame is being assembled.

### `Response request(Frame frame)` and `Response request(String xml)`

Sends a frame and returns the parsed response. It accepts a `Frame` or raw XML:

```java
Response fromFrame = client.request(client.frame());
Response fromXml = client.request("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><hello/></epp>");
```

Everything an ordinary command gets, a custom frame gets: the length framing, the logging with
secrets masked, the `clTRID` echo check, and the `throwOnFailure` behaviour.

### An extension the library does not model

The pattern for riding an extension along with a standard command — here a made-up namespace on a
`domain:info`:

```java
String thing = "urn:example:params:xml:ns:thing-1.0";

Frame frame = client.frame();
Element info = frame.ns(frame.verb("info"), Namespaces.DOMAIN, "domain:info");
Map<String, Object> attrs = new LinkedHashMap<String, Object>();
attrs.put("hosts", "all");
frame.ns(info, Namespaces.DOMAIN, "domain:name", "example.com.ua", attrs);

Element block = frame.ns(frame.extension(), thing, "thing:info");
frame.ns(block, thing, "thing:detail", "full");

Response response = client.request(frame);
for (String detail : response.values("detail")) {
    System.out.println(detail);
}
```

Two things to get right. Announce the extension's URI at login — through `extUris` on the config, or
by letting the greeting supply it — or the server has no reason to return that extension's data. And
read the answer through `value()` and `values()`, or through `dom()` if you want the tree, since the
named accessors only know the extensions the library models; see
[Responses](responses.md#raw-access).

### Namespace constants

`com.epptools.sdk.Namespaces` holds the exact strings that go on the wire:

| Constant | URI | Defined by |
|---|---|---|
| `EPP` | `urn:ietf:params:xml:ns:epp-1.0` | RFC 5730 |
| `DOMAIN` | `urn:ietf:params:xml:ns:domain-1.0` | RFC 5731 |
| `HOST` | `urn:ietf:params:xml:ns:host-1.0` | RFC 5732 |
| `CONTACT` | `urn:ietf:params:xml:ns:contact-1.0` | RFC 5733 |
| `SECDNS` | `urn:ietf:params:xml:ns:secDNS-1.1` | RFC 5910 (DNSSEC) |
| `RGP` | `urn:ietf:params:xml:ns:rgp-1.0` | RFC 3915 (restore) |
| `FEE` | `urn:ietf:params:xml:ns:epp:fee-1.0` | RFC 8748 (prices) |
| `LOGINSEC` | `urn:ietf:params:xml:ns:epp:loginSec-1.0` | RFC 8807 (login security) |
| `CHANGEPOLL` | `urn:ietf:params:xml:ns:changePoll-1.0` | RFC 8590 (changes you did not make) |
| `XSI` | `http://www.w3.org/2001/XMLSchema-instance` | XML Schema |

Three more are values rather than namespaces: `LOGINSEC_SENTINEL` is the reserved `[LOGIN-SECURITY]`
string that RFC 8807 puts in `<pw>` when the real password travels in the extension, and
`DEFAULT_OBJ_URIS` and `DEFAULT_EXT_URIS` are the service lists used only if a greeting arrived with
no service menu at all.

### Your registry's own extensions

Every URI above is defined by an RFC and is the same string at every registry on earth. A registry's
OWN extensions — a trademark licence, a price, an account balance — are not, and there is no constant
for them here, because there is no value that would be right for more than one registry.

They are **discovered from the `<greeting>`**. Every server lists what it supports before you send
anything, so after `connect()` the client already knows:

```java
client.connect();

client.registryExtUri();      // e.g. "http://registry.example/epp/registry-1.0", or null
client.registryBalanceUri();  // e.g. "http://registry.example/epp/balance-1.0", or null
```

`null` means that server advertises no such extension — a fact about the server, not an error. The
commands that need one say so instead of guessing: `domain:create` with a `license`, `host:delete`
with `force` and `balance()` all throw `ConfigException` naming what was wanted and listing what the
server did offer. That refusal is the point. An extension sent under a namespace a server does not
recognise is **ignored, not rejected**, so a guess would come back `1000 OK` with the licence
silently unset.

`requireRegistryExtUri(String what)` is the same lookup with that refusal built in, for a command of
your own that cannot proceed without the namespace.

Discovery matches the last segment of an advertised URI — `.../registry-1.0`, `urn:…:balance` —
which is the convention registries follow, not a rule anyone enforces. For a registry that names its
extensions something else, set them yourself and the greeting is not consulted:

```java
Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
        .registryExtUri("urn:example:params:xml:ns:myreg-1.0")
        .registryBalanceUri("urn:example:params:xml:ns:myreg-balance-1.0")
        .build();
```

## One command at a time

Send a command, read its reply, then send the next. Do not pipeline commands on one connection
expecting the replies to line up, and do not share one `Client` between threads: a `Client` is one
session on one socket. If you need throughput, open more sessions rather than overlapping commands
inside one — and mind the concurrent-session limit, which arrives as a 2502.

Two commands in flight on one connection would leave the byte stream at an unknown offset. The
library notices — it checks that every reply carries the `clTRID` that went out, and closes the
connection rather than hand you the previous command's answer — but the command is still lost, and
for a renew or a create that means an unknown outcome.

---

[← Manual index](README.md)
