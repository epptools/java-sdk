# Changelog

All notable changes to this library are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

Waiting for the next release rather than earning one of its own. The four SDKs are one product in
four languages and carry one version between them, so a change to this library alone does not move
the number — it travels with whatever the four release together.

- `Builder.deepCopy()` carried a `@SuppressWarnings("unchecked")` and performs no unchecked cast:
  they are all in `deepCopyValue()`, which has its own. javac has no lint category for a redundant
  suppression, so nothing in the build said so. A suppression that covers nothing trains the reader
  to skim past the annotation, and the next real one reads as noise too.

## [1.1.2]

First public release, and the only entry below. The four EppTools SDKs — PHP, Node.js, Python and
Java — are one product in four languages and carry one version between them, so Java joins at the
version the other three are at rather than starting again at 1.0.0: the same commands, the same
option names, the same response accessors and the same result codes. There is no 1.0.x, 1.1.0 or
1.1.1 history here because this library did not exist then. What those releases fixed in the other
three — the postal-block rules of 1.0.1 and 1.0.2, `change()` in 1.1.0, the documented versions of
1.1.1 — is present here from the first release, and so are the three frame-level fixes 1.1.2 makes
elsewhere: a transfer period of 0 leaves the element out instead of writing one the schema refuses,
`contact:update` refuses to clear an e-mail RFC 5733 requires, and `<loginSec:os>` carries the shape
RFC 8807 asks for. That is why nothing below is a `Fixed`.

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

- **A blank name in a `check` was quietly dropped, so the answer was shorter than the question.** An
  empty `<domain:name>` is a frame the registry refuses, and three of these libraries filtered the
  blank out instead: fifty names in, forty-nine answers out. A caller then looks up the blank in the
  result and finds nothing, which reads as "not available" rather than as "never asked" — a wrong
  answer further from its cause than the refusal would have been. A blank entry is now refused,
  naming its position and the element. Whitespace around a real name is still trimmed.

- **An update that asked for nothing was sent, and could come back `1000`.** RFC 5731, 5732 and 5733
  each say in section 3.2.5 that at least one of `add`, `rem` or `chg` MUST be provided unless the
  command carries an extension. None of the three schemas can express it, because all three elements
  are optional there — so an update describing no change has always been valid XML, answered with a
  `2003` naming nothing, or with a `1000` for a change that was never described. Nobody writes that
  update on purpose: they assemble a delta from variables that all turn out empty, or a list of
  statuses that filters down to nothing. Three things are fixed together: a block that filters down
  to nothing is no longer opened at all, a `chg` whose only key produces no child is no longer opened,
  and a command with no delta and no extension is refused with the rule quoted. An update carrying
  only an extension — a DNSSEC change, an RGP restore — is still sent, which is what the RFC allows.

- **A registrant could be cleared, which RFC 5731 has no way to express.** `authInfo` has a nullable
  form and the registrant has none, so a domain can be moved to another holder but not left without
  one. A blank one reached the wire as an empty `<domain:registrant/>`, which `clIDType` refuses for
  its minimum length of three. Refused here now, with the reason.

- **One disclosed postal form given as a bare string became one element per letter.** `{addr: 'int'}`
  is a reasonable thing to write, and two of these libraries iterated the string's characters and
  emitted `type="i"`, `type="n"`, `type="t"` — three elements the schema refuses, for a privacy
  preference nobody got wrong. A bare string is now one form in all four, and a list of both still
  gives both.

- **The two `<extension>` children of a `domain:update` came out in different orders in different
  languages.** A fee agreement and a DNSSEC delta on the same update produced `fee` first in one
  library and `secDNS` first in the others. Both are valid — `epp-1.0.xsd` does not order the
  extension block — but a customer comparing two of these libraries against one another should not
  find a difference, and no test reached the case. The order is now the same in all four, and the
  shared frame fixture carries a scenario that holds it there.

### The build and the documentation

- A Maven build (`pom.xml`) and an equivalent Gradle build (`build.gradle`), both producing
  `io.github.epptools:epptools-sdk:1.1.2` for Java 8, with the sources jar, the javadoc jar and the
  signatures Maven Central requires, and both running the offline suite as part of the build.
- A README covering install, a quickstart, every command, every response accessor, the builders, TLS,
  logging and error handling, plus `examples/Quickstart.java`. The example names no real registry: the
  endpoint is `epp.registry.example`, under a TLD RFC 2606 reserves, so a copied example cannot reach
  somebody's server, and the login is `EXAMPLE`.
