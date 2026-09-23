# Hosts

Host (nameserver) objects follow **RFC 5732**. Where a registry delegates domains by reference, a
nameserver has to exist as a host object before a [domain](domains.md) can point at it with
`<domain:hostObj>`. Where a registry takes the glue inlined instead, you may never create a host
object at all — see [the two nameserver models](domains.md#nameservers-the-two-models).

Every host command is reached through `client.host()` and returns a [`Response`](responses.md).
Everything here assumes a connected, logged-in client — see [Session](session.md).

## The methods

| Method | EPP command |
|---|---|
| `check(List<String> names)` | `<check>` |
| `info(String name)` | `<info>` |
| `create(String name, List<String> addresses)` · `create(name)` | `<create>` |
| `update(String name, Map<String,Object> options)` | `<update>` |
| `updateBuilder(String name)` → `HostUpdateBuilder` | builds an `<update>` |
| `delete(String name, boolean force)` · `delete(name)` | `<delete>`, optionally with the forced-detach extension |

There is no `renew` and no `transfer` for a host: RFC 5732 defines neither. A host follows the domain
it lives under, and there is nothing to bill for it.

## Subordinate and external hosts

The distinction decides whether a host may carry addresses at all, and it is the source of most
first-run refusals:

| | lives under | glue addresses |
|---|---|---|
| **subordinate** | a domain in a zone this registry serves (`ns1.example.com.ua` under `example.com.ua`) | **required** — without one the create is `2003` |
| **external** | a domain elsewhere (`ns1.acme.example`) | **refused** — its addresses live at its own registry, so sending one is `2306` |

A client that always emits an address must omit it for external hosts, which is why
`create(name)` exists beside `create(name, addresses)`. Addresses must be public Internet addresses,
and registries cap how many one host may carry — ask yours what the limit is; beyond it the frame is
refused.

---

## check

```java
Response check(List<String> names);
```

**On the wire:** `<command><check><host:check><host:name>…` — RFC 5732 §3.1.1.

```java
Response r = client.host().check(Arrays.asList("ns1.example.com.ua", "ns2.example.com.ua"));

r.availability();                       // {ns1.example.com.ua=false, ns2.example.com.ua=true}
r.isAvailable("ns2.example.com.ua");    // Boolean.TRUE | Boolean.FALSE | null
r.unavailableReason("ns1.example.com.ua");
```

An `avail` of false means the host object already exists at the registry — which is frequently what
you want, since a host you were about to create is one you can simply reference. Host objects are a
registry-wide namespace: a nameserver another registrar created is visible to you and referenced by
name.

**Result codes:** `1000` for any well-formed check; `2005` names a syntactically invalid host name.
An **empty list** is refused too, with a
`ValidationException`: the frame it would build has no child at all, and the schema requires one — so
a loop over a query string or a basket that turned out to be empty fails here, by name, instead of
spending a round trip.

---

## info

```java
Response info(String name);
```

**On the wire:** `<command><info><host:info><host:name>` — RFC 5732 §3.1.2. There is no `authInfo`
argument: a host object carries no transfer secret of its own.

```java
Response h = client.host().info("ns1.example.com.ua");

h.objectName();       // "ns1.example.com.ua"
h.roid();             // the registry's own object id
h.statuses();         // [ok], [linked], [clientUpdateProhibited], …
h.sponsor();          // clID
h.createdBy();        // crID          h.createdDate();  // crDate
h.updatedBy();        // upID, or null h.updatedDate();  // upDate

for (Map<String, String> addr : h.hostAddresses()) {
    System.out.println(addr.get("version") + " " + addr.get("ip"));   // "v4 203.0.113.10"
}
```

`hostAddresses()` returns a `List<Map<String, String>>` of `{ip=…, version=…}`. **An empty list is a
normal answer for an external host**, not a missing one: only a host inside a zone the registry
serves carries glue.

The `linked` status means at least one domain uses this host as a nameserver. That is what stands
between you and a [delete](#delete).

**Result codes:** `1000`; `2303` (no such host).

---

## create

```java
Response create(String name, List<String> addresses);
Response create(String name);
```

**On the wire:** `<command><create><host:create>` with one `<host:addr ip="v4|v6">` per address —
RFC 5732 §3.2.1.

The IP version is detected from the literal, so you pass a flat list and `v4` and `v6` end up
correctly labelled:

```java
// Subordinate host: glue is required.
Response r = client.host().create("ns1.example.com.ua", Arrays.asList("203.0.113.10", "2001:db8::10"));

System.out.println(r.objectName() + " created " + r.createdDate());

// External host: no addresses at all.
client.host().create("ns1.acme.example");
```

A complete first delegation — create the hosts, then point the domain at them:

```java
Map<String, String> glue = new LinkedHashMap<String, String>();
glue.put("ns1.example.com.ua", "203.0.113.10");
glue.put("ns2.example.com.ua", "203.0.113.11");

for (Map.Entry<String, String> entry : glue.entrySet()) {
    Response free = client.host().check(Arrays.asList(entry.getKey()));
    if (Boolean.TRUE.equals(free.isAvailable(entry.getKey()))) {
        client.host().create(entry.getKey(), Arrays.asList(entry.getValue()));
    }
}

Map<String, Object> add = new LinkedHashMap<String, Object>();
add.put("ns", Arrays.asList("ns1.example.com.ua", "ns2.example.com.ua"));
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("add", add);
client.domain().update("example.com.ua", options);

System.out.println(String.join(", ", client.domain().info("example.com.ua").nameservers()));
```

**Result codes:** `1000`; `2001` (more addresses than the per-host limit); `2003` (a subordinate host
with no address); `2005` (a malformed address or name); `2302` (the host already exists); `2306` (an
address on an external host).

---

## update

```java
Response update(String name, Map<String, Object> options);
```

**On the wire:** `<command><update><host:update>` — RFC 5732 §3.2.5. Like every EPP update this is a
**delta**: what you do not mention is left alone.

| key | value | wire |
|---|---|---|
| `addAddresses` | a `List` of IP literals | `<host:addr>` inside `<host:add>` |
| `remAddresses` (also spelled `removeAddresses`) | a `List` of IP literals | `<host:addr>` inside `<host:rem>` |
| `addStatuses` | a `List` of statuses | `<host:status s="…">` inside `<host:add>` |
| `remStatuses` (also spelled `removeStatuses`) | a `List` of statuses | `<host:status s="…">` inside `<host:rem>` |

```java
// Renumber a nameserver: add the new address and remove the old one in one command, so the
// host is never left without glue.
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("addAddresses", Arrays.asList("203.0.113.20"));
options.put("remAddresses", Arrays.asList("203.0.113.10"));

client.host().update("ns1.example.com.ua", options);

for (Map<String, String> addr : client.host().info("ns1.example.com.ua").hostAddresses()) {
    System.out.println(addr.get("ip"));
}
```

An address you remove must match what the registry holds. The block for a side you do not use is not
sent at all, so an options map carrying only `addAddresses` emits a `<host:add>` and nothing else.

The statuses you may set are the `client*` family — `clientUpdateProhibited` and
`clientDeleteProhibited`. `linked`, `ok` and the `server*` statuses belong to the registry.

The same address rules as on a create apply here: an external host may not gain addresses (`2306`),
and a subordinate one may not be left with none (`2003`).

**Result codes:** `1000`; `2001` (more addresses than the per-host limit); `2003`; `2303`; `2304` (a
status prohibits it); `2306`.

### Building an update step by step

```java
HostUpdateBuilder updateBuilder(String name);
```

`addAddress` / `addAddresses`, `remAddress` / `remAddresses`, `addStatus`, `remStatus`, then
`send()`. Every step is documented in [Builders](builders.md).

---

## There is no rename

**This library refuses to rename a host object**, and the reason is policy rather than protocol.
RFC 5732 §3.2.5 does define a rename, through `<host:chg><host:name>` — but the registries this was
built against read only the `add` and `rem` blocks of `host:update` and discard a `chg` without
comment. Against one of those, a frame carrying a rename alongside an address change applies the
addresses, drops the rename, and still answers `1000` — leaving you believing a nameserver moved when
it did not. **Ask your registry whether it implements `host:chg`.** Where it does, the three commands
below are still safe, and are what this library offers.

So `update()` refuses a `newName` option outright:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("newName", "ns9.example.com.ua");

client.host().update("ns1.example.com.ua", options);
// ValidationException: host rename is not supported by this registry (host:chg is ignored) -
// create the new host, re-point the domains with domain:update, then delete the old one
```

That sequence is the rename, and it is three steps:

```java
// 1. Create the new host with the same addresses.
Response old = client.host().info("ns1.example.com.ua");
List<String> addresses = new ArrayList<String>();
for (Map<String, String> addr : old.hostAddresses()) {
    addresses.add(addr.get("ip"));
}
client.host().create("ns9.example.com.ua", addresses);

// 2. Repoint every domain that uses the old one. There is no registry-side list of these —
//    it comes from your own records of what you delegated where.
for (String domain : yourDomainsUsingIt) {
    Map<String, Object> add = new LinkedHashMap<String, Object>();
    add.put("ns", Arrays.asList("ns9.example.com.ua"));
    Map<String, Object> rem = new LinkedHashMap<String, Object>();
    rem.put("ns", Arrays.asList("ns1.example.com.ua"));

    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("add", add);
    options.put("rem", rem);
    client.domain().update(domain, options);
}

// 3. Only once nothing references it — otherwise this is a 2305.
client.host().delete("ns1.example.com.ua");
```

Add before removing, in one command per domain, so the domain is never momentarily undelegated.

---

## delete

```java
Response delete(String name, boolean force);
Response delete(String name);
```

**On the wire:** `<command><delete><host:delete>` — RFC 5732 §3.2.2. With `force`, a
`<registry:delete><registry:deleteNS confirm="yes"/>` block rides along in `<extension>`.

A host still used as a nameserver by any domain cannot be deleted: the registry answers **`2305`**.
The `linked` status is the advance warning.

```java
Response h = client.host().info("ns1.example.com.ua");

if (h.statuses().contains("linked")) {
    // Detach it from the domains that use it first, or use the forced delete below.
    return;
}

client.host().delete("ns1.example.com.ua");
```

### Forced delete

```java
client.host().delete("ns1.example.com.ua", true);
```

This removes the host from the nameserver set of **every** domain that referenced it, then deletes
it. The `confirm="yes"` the registry requires is sent for you — which is the point of the flag being
a separate argument rather than a default. It needs the registry's own extension, so against a server
whose greeting offers none this throws `ConfigException` naming what was wanted, rather than sending
an ordinary delete that fails on a host still in use and leaves you wondering why `force` did
nothing.

Understand what it costs before you use it: a domain left with fewer nameservers than the zone
requires goes `inactive` and stops resolving. It is the right tool for a nameserver you are
decommissioning and the wrong one for tidying up. Where you can, repoint the domains first and use
the ordinary delete.

**Result codes:** `1000`; `2303` (no such host); `2305` (still used as a nameserver — the ordinary
delete); `2400` (the forced detach could not complete).

---

## Result codes on this page

| Code | Meaning | Exception |
|---|---|---|
| `1000` | done | — |
| `2001` | the frame is malformed — e.g. more addresses than the per-host limit | `CommandException` |
| `2003` | a subordinate host with no glue address | `CommandException` |
| `2005` | a malformed address or host name | `CommandException` |
| `2302` | the host already exists | `ObjectExistsException` |
| `2303` | no such host | `ObjectDoesNotExistException` |
| `2304` / `2305` | a status prohibits it / still used as a nameserver | `ObjectStatusException` |
| `2306` | policy — e.g. an address on an external host | `PolicyException` |
| `2400` | the registry could not complete it; may be transient | `CommandException` (`isRetryable()`) |

A `newName` option never reaches the registry: it is a `ValidationException`, thrown before the
frame is built. `ResultCode` has a named constant for every code above; the full taxonomy is in
[Errors](errors.md).

---

See also: [Domains](domains.md) · [Contacts](contacts.md) · [Poll](poll.md) ·
[Responses](responses.md) · [Builders](builders.md)

[← Manual index](README.md)
