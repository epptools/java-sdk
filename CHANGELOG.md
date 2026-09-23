# Changelog

All notable changes to this library are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [1.1.1]

First public release, and the only entry below. The four EppTools SDKs — PHP, Node.js, Python and
Java — are one product in four languages and carry one version between them, so Java joins at the
version the other three are at rather than starting again at 1.0.0: the same commands, the same
option names, the same response accessors and the same result codes. There is no 1.0.x or 1.1.0
history here because this library did not exist then. What those releases fixed in the other three —
the postal-block rules of 1.0.1 and 1.0.2, `change()` in 1.1.0 — is present here from the first
release, which is why nothing below is a `Fixed`.

### The library

- **EPP over TLS with no dependencies at all** — `javax.net.ssl` for the transport and `javax.xml`
  for DOM, both in the JDK, and nothing else. Compiled with `--release 8`, so one jar runs on Java 8
  through the current release. Domains, contacts and hosts (RFC 5730–5733), DNSSEC (RFC 5910),
  redemption and restore (RFC 3915), prices and fee agreements (RFC 8748) and login security
  (RFC 8807).
- **A registry's own extensions are discovered from its `<greeting>`**, not compiled in. This library
  ships no registry's URIs, so it works against a registry it has never seen — and keeps working when
  one changes its namespaces. `Client.registryExtUri()` / `registryBalanceUri()` report what was
  found; `Config` can override both for a registry whose naming discovery cannot guess.
- **Responses are read by local element name**, never by namespace prefix, so extension data stays
  readable whatever namespace it arrived under and whatever prefix the server chose.
- **Commands that need an extension the server does not offer fail loudly.** `domain().create()` with
  a licence, `host().delete(name, true)` and `balance()` throw `ConfigException` naming what was
  wanted and listing what the server advertised — because an extension sent under a namespace the
  server does not recognise is ignored rather than rejected, so the alternative is a `1000 OK` with
  the value silently unset.
- **Misspelt option keys are refused, not dropped.** A key this library does not understand throws
  `ValidationException` with the nearest accepted spelling, instead of building a frame that omits
  what you asked for and comes back successful. `secdns` for `secDNS` would otherwise register the
  domain unsigned behind a 1000.
- **Passwords never reach a log or a `toString()`.** Frame logging redacts `<pw>` and `<newPW>` in any
  namespace — which covers `<domain:authInfo>`, whose secret is a `pw` element — `Config.toString()`
  omits the password and the key passphrase, and a password too long for the RFC 5730 `<pw>` element
  is either carried by RFC 8807 or refused before a socket is opened.
- **A reply that does not belong to the command is a loud failure.** Every response is checked against
  the `clTRID` that went out, and a mismatch closes the connection instead of handing back the
  previous command's answer: on a renew or a create that would mean booking the wrong domain as done.
- **Builders** for the commands with the most options — `domain().createBuilder()`,
  `updateBuilder()`, `contact().createBuilder()`, `updateBuilder()` and `host().updateBuilder()` — so
  a long call site reads as a sequence of decisions, and a misspelling is a method that does not
  exist rather than a map key nobody reads.
- **Every exception is unchecked** and extends `EppException`, so one `catch` handles everything and
  no signature has to carry a `throws`. A subclass exists where the right next step differs and
  nowhere else.

### The build and the documentation

- A Maven build (`pom.xml`) and an equivalent Gradle build (`build.gradle`), both producing
  `io.github.epptools:epptools-sdk:1.1.1` for Java 8, with the sources jar, the javadoc jar and the
  signatures Maven Central requires.
- A README covering install, a quickstart, every command, every response accessor, the builders, TLS,
  logging and error handling, plus `examples/Quickstart.java`. The example names no real registry: the
  endpoint is `epp.registry.example`, under a TLD RFC 2606 reserves, so a copied example cannot reach
  somebody's server, and the login is `EXAMPLE`.
