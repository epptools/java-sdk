# Quickstart

Install the library, open a session, ask the registry three real questions and close the session
cleanly. One program, then a walk through every line of it.

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

No build tool at all? Clone the repository, pinned to a release tag, and compile the sources
straight into your own tree — there is nothing to resolve:

```bash
git clone --branch v1.1.2 https://github.com/epptools/java-sdk
javac --release 8 -d out $(find java-sdk/src/main/java -name '*.java')
```

**Requires Java 8 or newer.** TLS comes from `javax.net.ssl` and XML from `javax.xml`, both part of
the JDK, so there is no other dependency to add.

## What you need before you run it

| What | Where it comes from |
|---|---|
| Host and port | Your account. The port is `700` unless you were told otherwise. |
| Your clID | Your registrar identifier, e.g. `EXAMPLE`. |
| Password | Issued with the account, changed through `login()` — see [Session](session.md). |
| The registry CA certificate | Only where the endpoint presents a certificate from the registry's own private CA. Many registries present an ordinary browser-trusted one and then there is nothing to configure. Ask which applies, and for the `.pem` bundle if it does. |
| An allow-listed source address | Many registries require your server's public IP to be registered against the clID, or the login is refused with 2200. |

## The whole program

Save it as `EppFirstRun.java`, fill in the values at the top, and run it with `java EppFirstRun` on
a classpath that has the jar on it.

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.EppException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EppFirstRun {

    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .lang("en")                              // result messages in this language, if the greeting lists it
                // .caFile("/etc/epp/registry-ca.pem")   // only for a private-CA or self-signed certificate
                .build();

        // Client implements AutoCloseable and close() disconnects, so try-with-resources releases the
        // socket on every way out of the block — including the paths where logout() was never reached.
        try (Client client = new Client(config)) {
            Response greeting = client.connect();
            System.out.println("server: " + or(greeting.value("svID"), "unnamed"));

            Response login = client.login();
            for (Map<String, String> event : login.securityEvents()) {
                System.err.println("login " + or(event.get("level"), "warning")
                        + " (" + or(event.get("type"), "custom") + "): " + event.get("text"));
            }

            Map<String, Object> feeQuery = new LinkedHashMap<String, Object>();
            feeQuery.put("create", 1);
            Response check = client.domain().check(Arrays.asList("example.com.ua"), feeQuery, null);

            if (Boolean.TRUE.equals(check.isAvailable("example.com.ua"))) {
                System.out.println("example.com.ua is available");
                System.out.println("  create, 1 year: "
                        + or(check.feeFor("example.com.ua", "create", 1), "not quoted")
                        + " " + or(text(check.fees().get("_currency")), ""));
            } else {
                System.out.println("example.com.ua is taken: "
                        + or(check.unavailableReason("example.com.ua"), "no reason given"));

                Response info = client.domain().info("example.com.ua");
                System.out.println("  sponsor: " + or(info.sponsor(), "-"));
                System.out.println("  expires: " + or(info.expiryDate(), "-"));
                System.out.println("  status:  " + join(info.statuses()));
                System.out.println("  ns:      " + join(info.nameservers()));
            }

            Response balance = client.balance();
            System.out.println("available credit: " + or(balance.availableCredit(), "-"));

            client.logout();
        } catch (EppException e) {
            System.err.println("EPP error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String or(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static String text(Object value) {
        return value == null ? null : value.toString();
    }

    private static String join(List<String> values) {
        return values.isEmpty() ? "-" : String.join(", ", values);
    }
}
```

Expected output against a live account, when the name is registered by someone else:

```
server: Registry EPP Server
example.com.ua is taken: In use
  sponsor: ACME
  expires: 2027-04-01T09:15:00Z
  status:  ok
  ns:      ns1.example.com.ua, ns2.example.com.ua
available credit: 12500.00
```

Nothing in this program changes anything or costs anything. `check`, `info` and the balance query
are all read-only, which is what makes them the right first commands: they are the only ones that
are safe to repeat blindly.

## Line by line

### `Config.builder(host, clid, password)`

The connection settings, built one named step at a time and finished with `build()`. Only those
three are required; every other field has an RFC-sensible default, so a short chain is enough to
start:

- **host**, **clid** and **password** have no useful defaults, which is why they are arguments to
  `builder()` rather than steps you can forget. An empty **host** or **clid**, and a missing
  password, raise a `ConfigException` from `build()`. An **empty password** raises one from
  `login()`, and so does one shorter than 6 characters or longer than 128 — still before a socket is
  opened, but from the call that needs it rather than from the one that stores it.
- **`caFile`** is needed only where the endpoint presents a certificate issued by the registry's own
  private CA — that CA is in no trust store, so with peer verification left at its correct default
  the handshake fails without it. With no `caFile` the client trusts the JDK's own store, which is
  right for a browser-trusted certificate.
- **`port`** defaults to 700. `lang` defaults to `en`. Everything else is covered field by field in
  [Session](session.md).

`Config` is immutable and its fields are public and final, so anything you did not pass can be read
back off the object. Do not put the password in the source. Read it from the environment or from
your secret store; the example spells it out only so the program is complete.

### `new Client(config)`

Constructing the client opens nothing. It gives you the object; the socket comes next. A second
optional argument takes a custom transport and a third takes a logger — see
[Session](session.md).

### `try (Client client = new Client(config))`

`Client` implements `AutoCloseable` and its `close()` disconnects, so try-with-resources closes the
socket whatever happened, including on the paths where `logout()` was never reached. Java has no
destructor to fall back on, so this — or an explicit `disconnect()` in a `finally` — is the only
thing that releases the session. An unclosed session counts against your concurrent-session limit
until the registry times it out.

### `Response greeting = client.connect();`

Opens the TLS socket and reads the server's unsolicited `<greeting>`, which is the first thing an
EPP server sends (RFC 5730). The greeting lists the object and extension namespaces this server
supports, and `login()` mirrors them back so the session is never refused for announcing a service
the server does not offer.

`connect()` returns the greeting as a [`Response`](responses.md). `greeting.value("svID")` reads
the server's name out of it. If the first frame is not a greeting, `connect()` throws a
`ConnectionException` rather than treating whatever arrived as the service list.

### `Response login = client.login();`

Sends `<login>` with your clID and password, the protocol version, the language and the service
list taken from the greeting. It returns the login response.

### The `securityEvents()` loop

Where the server offers the Login Security extension (RFC 8807), the login carries a small block
identifying this client, and the server answers with anything it wants you to fix about the
session: a client certificate approaching expiry, an obsolete TLS version, a cipher suite that is
not AEAD.

The list is empty on a healthy session, so treat any entry as something to act on. Each entry
always carries `text`; `type` and `level` are there when the server sent them, hence the fallbacks
in the example — a `Map` lookup for a key the event did not carry returns `null`.

The commonest event is a certificate expiring in the next 30 days — the alternative to hearing
about it here is finding out on the morning logins stop.

### `client.domain().check(Arrays.asList("example.com.ua"), feeQuery, null)`

`domain()` returns the domain command handler; `check()` sends `domain:check` (RFC 5731) and
returns the answer. The second argument rides an RFC 8748 price query along with it, so one round
trip answers both "is it free" and "what would it cost"; the third names a currency, and `null`
takes the registry's own. The one-argument `check(names)` sends the plain command with no price
query at all.

The fee query is a `Map<String, Object>` because a value is either a period or a list of periods —
`feeQuery.put("renew", Arrays.asList(1, 2, 5))` asks one operation at several periods in one
command. Asking the price is free and changes nothing. See [Balance](balance.md) for the full price
table form, and for how to turn the same extension into a **cap** on what a transform may charge
you.

### `check.isAvailable("example.com.ua")`

Returns `Boolean.TRUE`, `Boolean.FALSE`, or `null` when the answer said nothing about that name.
The three-way result is the point: looking a name up in an availability map by a key you misspelled
also produces `null`, and those two answers must not look the same on the line before a
registration. Compare it with `Boolean.TRUE.equals(...)` rather than unboxing it, so a `null` is a
"no" and never a `NullPointerException`.

The comparison is case-insensitive, so a name you typed in mixed case still matches.

### `check.feeFor("example.com.ua", "create", 1)`

The quoted price for one operation at one period, as an exact decimal **String**, or `null` when
the answer carried no such quote. Never parse it into a `double` before doing arithmetic — use
`BigDecimal` or integer minor units. `fees().get("_currency")` is the currency the whole quote is
in.

The amounts in this manual are illustrative, not the registry's tariff.

### `check.unavailableReason("example.com.ua")`

The registry's own words for why a name is not available — `In use`, `Reserved` — or `null` when
it is available or the registry gave no reason.

### `client.domain().info("example.com.ua")`

`domain:info` returns the full record for a name you are allowed to see. The program only sends it
in the branch where the name is taken: an `info` for a name that does not exist is refused with
2303, which arrives as an `ObjectDoesNotExistException`.

`sponsor()`, `expiryDate()`, `statuses()` and `nameservers()` are named accessors, so you never
index into a map by a string you had to guess. `expiryDate()` gives back the registry's own
string, exactly as it was sent. Every accessor is listed in [Responses](responses.md).

`nameservers()` covers both EPP delegation models — a reference to a host object, or the name with
its glue inlined — so the list is right whichever one this registry uses.

### `client.balance()`

The registrar account balance, through the registry's balance extension. It hangs off the client
itself rather than off a command handler, because it is about the account rather than about an
object. `availableCredit()` is what you can still spend: the balance plus any credit limit, again as
an exact decimal string. See [Balance](balance.md).

### `client.logout();`

Ends the session. The server answers 1500 and closes the link. A session that is dropped without a
logout still counts against your concurrent-session limit until it times out, so log out on the way
past.

### `catch (EppException e)`

Every failure this library throws extends `EppException`, which extends `RuntimeException`, so one
catch handles everything: a bad argument, a TLS failure, a refusal from the registry. Catch the
subclasses where the right next step differs — a 2104 means stop the batch and top up, a 2302 means
pick another name. The full taxonomy is in [Errors](errors.md).

Because every one of them is unchecked, nothing forces you to write that catch. Write it anyway:
the alternative is a provisioning worker that dies on a 2303.

## Where to go next

- [Session](session.md) — every `Config` field, TLS diagnosis, password rotation, logging
- [Commands](commands.md) — transaction ids, `throwOnFailure`, custom frames
- [Domains](domains.md) — register, renew, transfer, restore
- [Errors](errors.md) — what to do about each refusal, and the unknown-outcome rule

Build against a test identity before you build against the live one. Your registry account manager
issues it; the API is identical and nothing is billed.

---

[← Manual index](README.md)
