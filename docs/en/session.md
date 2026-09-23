# Session

An EPP session is a TLS connection with a login on it. This page covers everything from the socket
to the logout: configuring the client, verifying the server's certificate, opening and closing the
session, rotating the password, reading the server's security warnings, and logging frames without
leaking credentials.

The shape of it never changes:

```
connect()  →  <greeting>  →  login()  →  commands …  →  logout()  →  disconnect()
```

`connect()` opens the socket and reads the greeting. `login()` authenticates. Commands run.
`logout()` ends the session at the registry, and `disconnect()` closes the socket.

## Config

`com.epptools.sdk.Config` holds the connection settings. It is immutable, and built through
`Config.builder(host, clid, password)` — those three have no useful default, so they are arguments
rather than steps you can forget — followed by a step per field and a `build()`:

```java
Config config = Config.builder("epp.registry.example", "EXAMPLE", System.getenv("EPP_PASSWORD"))
        .caFile("/etc/epp/registry-ca.pem")
        .build();
```

A step you spell wrong is a method that does not exist, and the compiler says so. The fields are
`public final`, so anything you did not pass can be read back off the object:
`config.port`, `config.lang`, `config.verifyPeer`.

### Every field

| Step | Default | What it is, and what happens if it is wrong |
|---|---|---|
| `builder(host, …)` | — | The registry hostname. Empty raises `ConfigException` from `build()`. It is also the name used for SNI and for hostname verification, so an IP address here fails name verification against a certificate issued to a hostname. |
| `builder(…, clid, …)` | — | Your registrar identifier, 3–16 characters of Latin letters, digits and hyphen. Empty raises `ConfigException` from `build()`. A well-formed but wrong value is refused by the server with 2200. |
| `builder(…, password)` | — | Your EPP password. `null` raises `ConfigException` from `build()`; empty raises one from `login()`. Shorter than 6 characters, or longer than 128, raises `ConfigException` from `login()` before the socket opens. Longer than 16 is possible only where the server offers RFC 8807 — see [Long passwords](#long-passwords) below. |
| `port(int)` | `700` | The EPP port. Change it only if you were given a different endpoint. A wrong port produces a `ConnectionException` from `connect()`. |
| `lang(String)` | `"en"` | The language of the server's result messages for this session. The greeting lists the languages the server offers; a value it does not offer fails the login with 2102. This is the language of `message()`, not of the poll notices, which carry their own. |
| `connectTimeout(double)` | `10.0` | **Seconds** to wait for the TCP connect and TLS handshake. Fractions are honoured. Too low on a slow link produces a `ConnectionException` that reads like a refused connection. |
| `readTimeout(double)` | `30.0` | **Seconds** to wait for a reply frame. Fractions are honoured (`2.5` means two and a half seconds). The socket deadline is never set below one second however small a value you pass, because a sub-second deadline on a create or a renew gives up while the registry is still working. A read that times out throws `ConnectionException` and **closes the connection** — a half-read frame leaves the byte stream at an unknown offset, and the next command would read the middle of this one's reply. |
| `verifyPeer(boolean)` | `true` | Verify the server certificate chain. Leave it on. See [TLS](#tls-and-certificate-verification). |
| `verifyPeerName(boolean)` | `true` | Verify that the certificate matches the host. Turning it off is a narrow loosening (right certificate, wrong hostname) that is occasionally reasonable in development. |
| `caFile(String)` | `null` | Path to the PEM bundle of the CA that signed the **server** certificate. Needed against an endpoint that uses the registry's own private CA, whose certificate is in no trust store; unset or wrong, the handshake fails with a verification error. With no `caFile` the client trusts the JDK's own store, which is right for a browser-trusted certificate. |
| `clientCert(String)` | `null` | Path to **your** client certificate, PEM. Only needed if your endpoint requires mutual TLS. |
| `clientKey(String)` | `null` | Path to your client private key, PEM, **PKCS#8**. May be omitted when the key is bundled into `clientCert`, in which case that file is read for both. |
| `clientKeyPassphrase(String)` | `null` | Passphrase for an encrypted client key. Wrong, and the handshake fails while loading the key rather than while verifying the peer. |
| `objUris(List<String>)` | `null` | The object services to announce in `<login>`. `null` means "exactly what the greeting offered", which is why a default session is never refused for an unsupported service, and an **empty list means the same thing** — `epp-1.0.xsd` requires at least one `objURI`, so honouring an empty one would build a login refused outright. Override it and any URI this server does not serve fails the login with 2307. The base `epp-1.0` URI is never sent as an object service, whether or not the greeting lists it. |
| `extUris(List<String>)` | `null` | The extension services to announce. `null` means "exactly what the greeting offered", as for `objUris` — but an **empty list here announces nothing**, and that is legitimate: `svcExtension` is itself optional, so it is how you ask for a plain RFC session. Announcing fewer extensions than you use means the registry will not return that extension’s data. |
| `clTRIDPrefix(String)` | `"JAVA-SDK"` | The first segment of every auto-generated client transaction id. Set it to something that identifies your system in the registry's logs. See [Commands](commands.md#client-transaction-ids). |
| `registryExtUri(String)` | `null` | The namespace of **this registry's own** object extension. `null` — the normal case — reads it from the greeting. Set it only for a registry whose extension is not named `…/registry-<version>`, which is what discovery matches on. A wrong value is not rejected: an extension in a namespace the server does not know is IGNORED, so the data goes missing silently. See [Commands](commands.md#your-registrys-own-extensions). |
| `registryBalanceUri(String)` | `null` | The same, for the account-balance extension. |
| `loginSecurity(boolean)` | `true` | Take part in RFC 8807 where the server offers it. Setting it false stops the server's security events reaching you. See [Login security](#login-security-rfc-8807). |

### Reading the settings back

There is no method that dumps the whole configuration, and that is deliberate: it would print the
password. `toString()` gives the fields worth seeing in a log and leaves out the password and the key
passphrase:

```java
Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret").build();

System.out.println(config);
// Config{host=epp.registry.example, clid=EXAMPLE, port=700, lang=en, verifyPeer=true, loginSecurity=true}
System.out.println(config.caFile);       // null — every field is public and final
```

Three things about the socket are fixed by the library rather than configured, and they are the ones
worth knowing when a handshake will not come up:

- **Only TLS 1.2 and TLS 1.3 are offered.** Anything the JDK supports below that is switched off on
  the socket rather than left to the platform default.
- **Hostname verification is on** while both `verifyPeer` and `verifyPeerName` are true: the socket
  is given the `HTTPS` endpoint identification algorithm, which checks the certificate against the
  host you configured.
- **A client key must be PKCS#8.** `clientKey` reads a `-----BEGIN PRIVATE KEY-----` block, RSA or
  EC; the older `BEGIN RSA PRIVATE KEY` form is PKCS#1 and the JDK cannot read it. Convert it once
  with `openssl pkcs8 -topk8 -in registrar.key -out registrar.key.pem`.

## TLS and certificate verification

| Scenario | Config |
|---|---|
| Public, browser-trusted certificate | nothing — the defaults (`verifyPeer(true)`, `verifyPeerName(true)`) are correct |
| Private-CA or self-signed certificate | `caFile(...)` → the PEM bundle of the CA that signed the **server** certificate |
| Mutual TLS (the registry requires a client certificate) | `clientCert(...)` + `clientKey(...)` (+ `clientKeyPassphrase(...)` if the key is encrypted) |
| Hostname mismatch in development | `verifyPeerName(false)` |

**Which of these applies is your registry's choice, so ask them.** Many present an ordinary
browser-trusted certificate, and then there is nothing to configure: with no `caFile` the client
trusts the JDK's own store. Others run their own CA, whose certificate is in that store nowhere, and
`caFile` must point at the bundle or the handshake fails.

Authentication itself is clID plus password inside TLS. A client certificate is needed only where
the registry requires mutual TLS, and many additionally restrict access to registered source
addresses — that part is policy, not protocol.

### When the handshake fails

The commonest first-run failure is certificate verification, and it looks like this:

```
ConnectionException: TLS handshake with epp.registry.example:700 failed - PKIX path building failed:
sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path
to requested target
```

The exception carries the reason the JDK reported, not a generic "unknown error", so read the tail of
the message first. Then check the bundle from the command line:

```bash
openssl s_client -connect epp.registry.example:700 -CAfile /path/to/registry-ca.pem </dev/null
# "Verify return code: 0 (ok)" means the bundle is right; anything else means it is not.
```

A short diagnosis list, in the order worth trying:

| Message | Usual cause |
|---|---|
| `PKIX path building failed` / `unable to find valid certification path` | `caFile` unset, or pointing at the wrong bundle |
| `No subject alternative DNS name matching … found` | the host differs from the name on the certificate — an IP address, or an alias |
| `Cannot connect to … - Connection refused` / `connect timed out` | wrong port, or your source address is not allow-listed at the firewall |
| `no PRIVATE KEY block found in …` | `clientKey` points at a file with no PEM private key in it — a certificate on its own, or a DER file |
| `unsupported client key type (expected a PKCS#8 RSA or EC key)` | the key is PKCS#1 (`BEGIN RSA PRIVATE KEY`); convert it with `openssl pkcs8 -topk8` |
| `client key is encrypted but no clientKeyPassphrase was given` | exactly that |

**Do not reach for `verifyPeer(false)`.** It installs a trust manager that verifies nothing, and
leaves you sending your clID, your password and every transfer secret to whatever answers on that
address, with no way to tell. If the handshake will not verify, the bundle is wrong — ask the
registry for the current one. `verifyPeerName(false)` is the narrower loosening and is occasionally
reasonable in development; `verifyPeer(false)` is not reasonable anywhere.

## Opening the session

### `Client(Config config)`, `Client(Config, Transport)`, `Client(Config, Transport, Client.Logger)`

Constructing the client opens nothing. The second argument replaces the transport (see
[Transport](#transport)); the third attaches a logger. Pass `null` for either to take the default.

```java
Client client = new Client(config);
```

### `Response connect()`

Opens the TLS socket and reads the server's unsolicited `<greeting>` (RFC 5730 §2.4), returning it
as a [`Response`](responses.md).

```java
Response greeting = client.connect();
greeting.serviceObjUris();   // the object namespaces this server manages
greeting.serviceExtUris();   // the extensions it supports
greeting.value("svID");      // its name
greeting.value("svDate");    // its clock, as its own string
```

**One thing it refuses to do:** a first frame that is not a greeting raises `ConnectionException`.
Accepting whatever arrived instead would let a stray response, or a middlebox banner, stand in for the
service list, and the login would then advertise services this server does not offer while losing the
extensions it does. There is no host check on this side — `build()` refuses an empty host and a
`Config` is the only way in, so a second guard here could never fire, and one that cannot fire reads
as protection while proving nothing.

Calling `connect()` on an already-open connection does not reopen the socket; it reads the next
frame as a greeting. Use `hello()` for that instead.

### `Response greeting()`

The last greeting read, or `null` before `connect()`. Useful for deciding, without a round trip,
whether an extension is available:

```java
Response greeting = client.greeting();
boolean fees = greeting != null && greeting.serviceExtUris().contains(Namespaces.FEE);
```

`Namespaces` is `com.epptools.sdk.Namespaces`, and holds the RFC URIs as constants — the whole list is
in [Commands](commands.md#namespace-constants).

### `Response hello()`

Sends `<hello>` and reads the fresh `<greeting>` the server answers with. Two uses: re-reading the
service menu, and keeping an idle session alive — the registry closes a session that has been idle
too long, and a `hello` is the cheapest frame that resets that clock. The greeting it returns
replaces the stored one.

### `Response login()` and `Response login(String newPassword)`

Authenticates. It builds `<login>` with your clID and password, `<version>1.0</version>`, the
configured `<lang>`, and the service list — by default exactly the services the greeting offered.

```java
Response response = client.login();
response.code();   // 1000
```

If you call `login()` without having called `connect()`, it connects first.

Checks that happen before the frame is built, each raising `ConfigException`:

- an empty clID or password;
- a password outside 6–128 characters;
- a password longer than 16 characters when this server does not advertise RFC 8807 — the base
  `<pw>` element cannot carry it, and the server would answer a bare 2001 naming no field.

The first two constants are on the client, so a form that validates a password before storing it can
use the same bounds the library will: `Client.PW_MIN`, `Client.PW_MAX` and
`Client.PW_MAX_LOGINSEC`.

Only result code 2200 raises `AuthenticationException`. A login refused for any other reason
arrives as its own class with its own remedy: 2502 (session limit), 2501 (server closing), 2307 (a
service in `<svcs>` is not offered), 2002 (this connection is already logged in), 2100 (protocol
version). Calling them all an authentication failure sends you to rotate a password that was never
the problem. See [Errors](errors.md).

### `static Client connectAndLogin(Config config)`

Static shortcut: constructs the client, connects and logs in, returning the ready client.

```java
try (Client client = Client.connectAndLogin(config)) {
    client.domain().info("example.com.ua");
    client.logout();
}
```

It uses the default transport and no logger. Where you need either, use the constructor.

### `boolean isConnected()` and `boolean isLoggedIn()`

`isConnected()` reports whether the socket is open and has not latched a fatal error.
`isLoggedIn()` reports whether a login has succeeded and neither `logout()` nor `disconnect()` has
run since.

Neither one polls the server, so a session the registry closed on its own — an idle timeout, a
restart — can still read as logged in until the next command fails. Treat them as bookkeeping, not
as a health check; a `hello()` is the health check.

## Closing the session

### `Response logout()`

Sends `<logout>`. The server answers **1500** and closes the link. 1500 is a success code, so this
does not throw.

### `void disconnect()` and `void close()`

`disconnect()` closes the socket. It is safe to call when nothing is open, which is why it belongs in
a `finally`. `close()` is the same thing under the name `AutoCloseable` wants, so a `Client` is a
try-with-resources resource:

```java
try (Client client = new Client(config)) {
    client.connect();
    client.login();
    // …
    client.logout();
}
```

The equivalent written out, for a client whose lifetime does not fit one block:

```java
Client client = new Client(config);
try {
    client.connect();
    client.login();
    // …
    client.logout();
} finally {
    client.disconnect();
}
```

**Write one of the two.** Java has no destructor and this library registers no finalizer or cleaner,
so nothing closes the socket for you: a client that goes out of scope unclosed leaves a session the
registry has to time out, and that session counts against your concurrent-session limit until it
does. `logout()` on the way past and a `close()` on every exit path are the two you write.

Note what `close()` does *not* do: it closes the **socket** and does not send a `<logout>`. On the
paths that reach it after a failure the frame may have nowhere to go. The registry sees an abandoned
connection rather than a clean end of session, which is why `logout()` is worth calling explicitly
on the happy path.

## Password rotation

Pass the new password to `login()` and it goes out as `<newPW>` alongside the old one, in the same
frame (RFC 5730 §2.9.1.1). The change takes effect only if the login succeeds.

```java
Response response = client.login("N3w-Secret-Pw");
// From here on, this session is authenticated. Every LATER session must use the new password.
```

The order to do this in matters, because there is no way to ask the registry which password is
current:

1. Write the new password to your secret store **first**, marked as pending.
2. Call `login(newPassword)`.
3. On success, promote it to current. On failure, the old password is still the live one — the
   registry applies the change only on a successful login.

Both passwords are bounds-checked before the socket opens. 6–16 characters is the base `<pw>`
schema limit; 6–128 is available where the server offers RFC 8807, and rotating **across** that
boundary is handled for you — changing a short password to a long one relocates only the new one.

### Long passwords

A password longer than 16 characters cannot travel in the base `<pw>` element. Where the server
advertises RFC 8807, the library sends the reserved sentinel `[LOGIN-SECURITY]` in `<pw>` and the
real password in `<loginSec:pw>`; the sentinel is decided **per element**, so an element still
carrying its own value never claims its value is somewhere else.

Two consequences worth knowing before you set one:

- A long password authenticates only on an endpoint that offers the extension. Any of your software
  connecting to an endpoint that does not will start failing authentication.
- Because the sentinel is reserved, `[LOGIN-SECURITY]` can never itself be a password. It is
  `Namespaces.LOGINSEC_SENTINEL` if you want to check a candidate against it.

`loginSecurity(false)` does not control this. A relocated password sends the block regardless,
having no other way to travel.

## Login security (RFC 8807)

Where the server offers the Login Security extension, the login carries a small block identifying
this client — the library's name and version, the JVM version, the OS name — and the server answers
with anything it wants you to fix about the session.

```java
for (Map<String, String> event : client.login().securityEvents()) {
    // type:  certificate | cipher | tlsProtocol | password | newPW | stat | custom
    // level: "warning" or "error"
    // text:  a sentence to show an operator
    alert(event.get("level"), event.get("type"), event.get("text"), event.get("exDate"));
}
```

`securityEvents()` returns a `List<Map<String, String>>`. Every entry carries `text`; `type`,
`name`, `level`, `exDate`, `value`, `duration` and `lang` appear when the event carries them, so a
lookup for one the event did not send returns `null`. The list is empty on a healthy session — treat
any entry as something to act on.

| `type` | Raised when |
|---|---|
| `certificate` | your client certificate expires soon; `exDate` carries the exact moment |
| `tlsProtocol` | the session negotiated an obsolete TLS version; `name` carries it |
| `cipher` | the session negotiated a cipher suite that is not AEAD; `name` carries it |
| `password` / `newPW` | something about the credential itself, such as a length that limits where it can be used |
| `stat` | session statistics the server wants you to see |
| `custom` | server-specific; `name` identifies it |

A server sends these only to a client that **took part** in the extension, because announcing a URI
is not evidence of supporting it — many clients build `<svcExtension>` by echoing the greeting back.
That is why the block goes out even when nothing needs to travel in it.

Call `loginSecurity(false)` on the builder to stay off the extension. You then get no security
events, and the commonest one is a client certificate approaching its expiry date: the alternative
to hearing about it here is finding out on the morning it stops working.

The client version sent in the block is `Version.VERSION` in the copy you installed. Quote it in a
support ticket.

## Logging

`Client.Logger` is three methods and no dependency, so it wraps whatever your application already
uses:

```java
Client.Logger logger = new Client.Logger() {
    @Override public void debug(String message)   { System.err.println("[debug] " + message); }
    @Override public void info(String message)    { System.err.println("[info]  " + message); }
    @Override public void warning(String message) { System.err.println("[warn]  " + message); }
};

Client client = new Client(config, null, logger);
// or later, and null to detach:
client.setLogger(logger);
```

Over a logging facade the three bodies are one line each — `log.debug(message)`,
`log.info(message)`, `log.warn(message)` — which is the reason the interface exists rather than a
dependency on any one of them.

What is written:

| Level | Event |
|---|---|
| `debug` | every request frame and every response frame |
| `info` | each successful result — code, `svTRID`, `clTRID` |
| `warning` | each failed result, same fields |

**Secrets are masked before a frame is logged.** Every `<pw>` and `<newPW>` element is replaced with
`***`, in any namespace prefix and whatever attributes the opening tag carries. That covers the
login password, the `<newPW>` of a rotation, the `<loginSec:pw>` of a long password, and the `<pw>`
inside every `<authInfo>` block — which is the transfer secret, the one credential that lets any
registrar take a domain away from you.

Two things the masking does not reach, because they are not the logger's output:

- `Response.raw()` returns the frame as it arrived, unmasked. If you store it, mask it yourself.
- Anything you log yourself. `authInfo()` returns a live credential; never log it, never put it in
  a support ticket, and roll it after you have passed it to a customer.

## Transport

`Client` speaks to the world through the `com.epptools.sdk.Transport` interface — `open()`,
`isOpen()`, `writeFrame(String xml)`, `readFrame()`, `close()`. The default implementation,
`com.epptools.sdk.Connection`, is the TLS socket plus RFC 5734 framing: each message is prefixed
with a 4-byte big-endian length that **includes** the four header bytes, counted in UTF-8 **bytes**
and never in characters, so a Cyrillic or IDN payload is framed correctly.

Supplying your own is how you drive the client against a recorded exchange in tests.
`RecordedTransport` below is a class of yours, not one of this library's: five methods over
whatever you are replaying from.

```java
Client client = new Client(config, new RecordedTransport());
```

Two rules the default transport enforces, and any replacement should:

- A frame length below 4 bytes or above 1 MiB is refused, and the connection is **closed**. The
  length bytes have already been consumed at that point, so the stream sits at an unknown offset.
- A partial read is terminal for the same reason. Continuing would make the next command read this
  command's reply — an off-by-one across billable transforms, where a renew returns 1000 carrying
  another name's expiry date.

`Connection` latches that failure: once a read or a write has failed, every later call throws rather
than resuming on a stream that cannot be trusted, and `isOpen()` reports false. Call `open()` to
start a fresh connection.

That is also why a `ConnectionException` mid-command leaves the outcome genuinely unknown. Do not
retry blindly; see [Errors](errors.md#when-a-transform-fails-and-you-do-not-know-whether-it-happened).

## Session hygiene

- **One command at a time.** Send a command, read its reply, then send the next. A `Client` is one
  EPP session on one socket: use one from a single thread at a time and give each thread its own.
  For throughput, open more sessions rather than overlapping commands inside one.
- **Sessions end.** By your `logout()`, by an idle timeout, by a maintenance restart. Nothing you
  have already been told about is lost — a 1000 you have read is final. Reconnect and continue.
- **Watch the concurrent-session limit.** A 2502 arrives as a `SessionException`; the remedy is to
  close an idle session, not to retry immediately.

---

[← Manual index](README.md)
