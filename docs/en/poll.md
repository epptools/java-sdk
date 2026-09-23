# Poll

The registry does not call you back. Everything that happens outside your own command flow — a
transfer somebody requested, the outcome of an operation the registry processed offline, an
approaching expiry, a low balance — is put in a per-registrar **message queue**, and you read it with
the `<poll>` command (RFC 5730 §2.9.2.3).

Reached through `client.poll()`. Everything here assumes a connected, logged-in client — see
[Session](session.md).

**Drain the queue on a schedule.** A queue nobody reads is a transfer request nobody answered, and
past its deadline the registry decides for you.

## The methods

| Method | EPP command |
|---|---|
| `request()` → `Response` | `<poll op="req"/>` |
| `ack(String messageId)` → `Response` | `<poll op="ack" msgID="…"/>` |
| `drain(Consumer<Response> handler, int limit)` · `drain(handler)` → `int` | `req` → your callback → `ack`, repeated |

---

## request

```java
Response request();
```

**On the wire:** `<command><poll op="req"/>`. It reads the head of the queue **without removing it**.
Calling it twice returns the same notice twice.

Two success codes answer it, and the difference is the whole protocol:

| code | meaning |
|---|---|
| `1301` | a message is waiting; the notice is in this frame |
| `1300` | the queue is empty |

```java
Response msg = client.poll().request();

if (msg.messageId() != null) {
    msg.messageId();          // "10021" — the id you pass to ack()
    msg.messageCount();       // 3 — how many remain, this one included
    msg.queueMessage();       // "Transfer requested." — the NOTICE text
    msg.queueMessageLang();   // "uk" | "ru" | "en"
    msg.queueDate();          // when the registry queued it
}
```

**Read `queueMessage()`, not `message()`.** They are different elements. `message()` is
`<result><msg>` — the command-result banner, which on every waiting notice reads "Command completed
successfully; ack to dequeue". `queueMessage()` is `<msgQ><msg>`, the notice itself. A client that
logs `message()` records that constant string for every event it ever received and discards the
content, and an ack then destroys the original.

`queueMessageLang()` reports the language the notice was rendered in. It is a property of the notice,
not of your session, so do not assume it matches the language you logged in with.

`messageCount()` counts what is in the queue including the notice you are holding, so it reaches `1`
on the last one and the following `request()` answers `1300`. It returns an `int` and not a
`null`-able boxed value, because a reply with no queue at all has nothing waiting, which is zero.

---

## ack

```java
Response ack(String messageId);
```

**On the wire:** `<command><poll op="ack" msgID="…"/>`.

**An ack deletes the notice at the registry permanently. There is no way to get it back.** The reply
carries the new queue head: `1301` with the next `messageId()` and `messageCount()` while messages
remain, `1300` once the queue is empty.

```java
Response next = client.poll().ack("10021");
System.out.println(next.messageCount() + " remaining");
```

The order is the whole point:

```java
// Correct: the notice is safe on your side before it stops existing on theirs.
Response msg = client.poll().request();
if (msg.messageId() != null) {
    store(msg.queueMessage(), msg.raw());   // if this throws, the notice is still queued
    client.poll().ack(msg.messageId());
}
```

A loop that acks first and processes second loses every notice whose processing fails — a transfer
request, the outcome of a pending create — with nothing left to retry from and no record that
anything was lost. Store first, acknowledge second.

There is no hurry about the second half. A message you have been given stays acknowledgeable even if
the registry's delivery-retention window elapses while you hold it: retention governs delivery, not
your acknowledgement. Read-then-store-then-ack-later is the contract, and "later" is allowed to fall
outside the window.

---

## drain

```java
int drain(Consumer<Response> handler, int limit);
int drain(Consumer<Response> handler);
```

The loop above, written once and correctly. Each notice is handed to your callback and acknowledged
**only after the callback returns**. Returns the number of notices your callback processed
successfully.

```java
int processed = client.poll().drain(notice ->
        store(notice.messageId(), notice.queueMessage(), notice.pendingActionData()));

System.out.println("processed " + processed + " notices");
```

The handler is a `java.util.function.Consumer<Response>`, so a lambda, a method reference or an
anonymous class all fit. It is a `Consumer` and not a function returning a verdict on purpose:
"handled" is what returning normally means and "not handled" is what throwing means, and there is no
third answer that could be mistaken for either.

Four things this guarantees, each of which is a decision:

- **If your callback throws, the notice is not acked.** It stays at the head of the queue and the
  exception reaches you. Fix the cause and drain again; nothing was lost. The corollary is that a
  callback which always throws sees the same notice every time — deliberately, because the
  alternative is discarding it. Note that a `Consumer` cannot declare a checked exception, so a
  callback that has to do I/O wraps it: `throw new RuntimeException(e)` keeps the notice queued,
  while catching and swallowing it acks a notice you did not handle.
- **Delivery is at least once.** If the acknowledgement itself fails — the connection drops between
  your callback returning and the ack landing — the notice is still in the queue and the next drain
  hands it to you again. Make the callback idempotent and use `messageId()` as the de-duplication
  key: it is the registry's own identifier for that notice.
- **Only `1300` ends the loop.** Inferring "empty" from the absence of a notice would make a refusal
  — the session closed, the account suspended — look exactly like a drained queue, and the loop would
  report success while nothing had been read. A reply that is neither a notice nor `1300` throws a
  `CommandException`. That holds even with `throwOnFailure(false)`.
- **`limit` bounds the work.** `drain(handler, 50)` stops after fifty notices. `0` — which is what
  the one-argument overload passes — means "until the queue is empty", which is right for a queue you
  keep up with and wrong for one that fills faster than you drain it: that call would never return.

```java
// A cron-friendly pass: bounded work, and a de-duplication key that survives a redelivery.
client.poll().drain(notice -> {
    if (alreadySeen(notice.messageId())) {
        return;                     // returning normally still acks it — which is what you want
    }
    handle(notice);
    markSeen(notice.messageId());
}, 200);
```

---

## What the notices carry

Every notice has the `<msgQ>` envelope — id, count, date, text — and most carry a structured payload
in `<resData>` as well. The payload is read with the same accessors as the equivalent command
response, so one parser serves both.

| Notice | Payload | Read it with |
|---|---|---|
| Transfer requested / approved / rejected / cancelled | `trnData` | `transfer()`, `transferStatus()`, `objectName()` |
| Domain registered, renewed, deleted; restore outcome | `infData` | `objectName()`, `expiryDate()`, `statuses()`, `rgpStatus()` |
| A registration that reached the registry and is pending THERE | `creData` | `objectName()`, `createdDate()`, `expiryDate()` |
| The outcome of a deferred (`1001`) operation | `panData` | `pendingActionData()` |
| Low balance | `balance:infData` | `balance()`, `currentBalance()` |

### A transfer request

```java
Map<String, String> t = notice.transfer();
// {status=pending, requestedBy=DELTA, requestedAt=2026-04-01T09:15:00Z,
//  actingClient=EXAMPLE, actBy=2026-04-06T09:15:00Z, expiryDate=2028-04-01T09:15:00Z}
```

`actBy` is the deadline, and it is the field that costs money: **silence completes a transfer.** Past
that date the registry approves it. Treat a transfer notice as something to answer — see
[Domains → transfer](domains.md#transfer).

### The outcome of a pending action

This is how an operation that answered `1001` finally reports back. You send a create, get `1001`
and an `svTRID`; some time later a notice carries the verdict.

```java
Map<String, Object> pan = notice.pendingActionData();
// {object=example.com.ua, success=true,
//  clTRID=SRV-20260401091500-24191-0007, svTRID=SRV-…, date=2026-04-01T10:00:00Z}

if (pan != null) {
    if (!Boolean.TRUE.equals(pan.get("success"))) {
        markFailed((String) pan.get("svTRID"));   // the ORIGINAL command's svTRID, not this notice's
        return;
    }
    markCompleted((String) pan.get("svTRID"), (String) pan.get("object"), (String) pan.get("date"));
}
```

Three things about it:

- **`success` is the only field that says whether the operation worked.** It is a `Boolean` in a
  `Map<String, Object>`, so read it with `Boolean.TRUE.equals(…)` and a missing verdict is a failure
  rather than a `NullPointerException` — a missing yes is not a yes. The surrounding
  `<result code="1301">` means "here is a message", not "your operation succeeded". Reading the
  result code instead is the classic mistake: every poll answer then looks like a success.
- **`svTRID` identifies which of your pending operations this is about.** Match it against the one
  you were given with the `1001`. Do not assume it is the most recent: poll is a queue, and notices
  arrive in the order the registry finished the work.
- **`date` is when the action completed**, not when you polled.

### A low-balance notice

```java
if (notice.balance() != null) {
    alertBilling(notice.currentBalance());   // an exact decimal string — never parse it to a double
}
```

Same element as the [balance query](balance.md), so the same accessors read it. Act on it: once the
balance runs out, chargeable commands are refused with `2104` and a registration you were about to
make fails for want of funds rather than for anything wrong with the request.

### A change the registry made to your object (RFC 8590)

Some notices describe something that happened to one of your objects without you asking: it stopped
existing at the registry, or it left on a transfer. These are the ones you have to act on
automatically — stop billing it, tell your customer, drop it from your own store — and the `<msg>`
they carry is written in your account's notification language, so nothing in it is safe to parse.

```java
Map<String, String> chg = notice.change();
if (chg != null) {
    chg.get("operation");   // "delete" | "transfer" | "renew" | "update" | "restore" | "autoRenew" | …
    chg.get("state");       // "before" | "after"
    chg.get("who");         // "Registry"
    chg.get("date");
    chg.get("svTRID");
    chg.get("reason");
    notice.objectName();
}
```

**`state` says which way the object beside it reads.** `after` describes the object as it now is.
`before` describes it as it last was — the only way a domain that no longer exists *can* be
described. Writing a `before` block into your own store as the object's current state is how a
deleted domain comes back to life in your records, so branch on it before you save anything. An
absent attribute means `after`, which is the schema's default, so the key is never empty.

`change()` returns `null` when the notice carries no change block — which includes every notice if
you did not announce `urn:ietf:params:xml:ns:changePoll-1.0` at login. This library mirrors the
server's greeting into `<svcs>`, so a registry that offers it is announced for you;
`Namespaces.CHANGEPOLL` is the constant if you pin your own service list with `extUris`.

Unlike the relocation rule below, announcing **nothing** does not get you `changeData`: a registry
sends it only to a client that named the namespace, because a client that has never seen it may
refuse the whole frame. The `<msg>` sentence is unchanged either way, so opting in never removes
anything you already read.

### A payload from an extension you did not announce (RFC 9038)

A notice is written into your queue before the registry knows which session will collect it, so it
can hold an element from an extension namespace. If your login listed `<svcExtension>` URIs and that
namespace was not among them, the registry moves the element out of `<resData>` and into an
`<extValue>` inside `<result>` instead of dropping it.

The frame still parses, and **you can still ack it**, so the queue keeps draining. The data is still
there:

```java
for (Map<String, Object> ext : notice.extValues()) {
    ext.get("element");     // e.g. "infData" — which element the registry relocated
    ext.get("namespace");   // the namespace you would announce to receive it as resData
    ext.get("values");      // its children by local name, as a Map<String, String>
    ext.get("reason");      // the registry's explanation
}
```

Announce the extensions you parse if you want the typed form; a login that sends no `<svcExtension>`
at all is read as "no restriction" and receives every payload as `<resData>`. See
[Session](session.md) for how the login services are chosen.

---

## A complete poller

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.EppException;

import java.util.Map;

public final class Poller {

    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .caFile("/path/to/registry-ca.pem")
                .build();

        try (Client client = new Client(config)) {
            client.connect();
            client.login();

            int count = client.poll().drain(notice -> {
                String id = notice.messageId();

                // Idempotent by messageId: a redelivery after a dropped ack must not double-handle.
                if (alreadySeen(id)) {
                    return;
                }

                Map<String, String> transfer = notice.transfer();
                Map<String, Object> pending = notice.pendingActionData();

                if (transfer != null && "pending".equals(transfer.get("status"))) {
                    // Answer it before actBy, or the registry decides.
                    String name = notice.objectName();
                    client.domain().transfer(customerAuthorisedTheMove(name) ? "approve" : "reject", name);
                } else if (pending != null) {
                    recordOutcome((String) pending.get("svTRID"),
                            Boolean.TRUE.equals(pending.get("success")),
                            (String) pending.get("object"));
                } else if (notice.balance() != null) {
                    alertBilling(notice.currentBalance());
                } else {
                    store(id, notice.queueMessage(), notice.queueDate(), notice.raw());
                }

                markSeen(id);
                // Returning normally is the ack. Throwing keeps the notice queued.
            }, 200);

            System.out.println("drained " + count);
            client.logout();
        } catch (EppException e) {
            System.err.println("EPP error: " + e.getMessage());
        }
    }

    // Your own persistence, whatever it is. These six are the only thing the loop needs from you.
    private static boolean alreadySeen(String messageId) { return false; }
    private static void markSeen(String messageId) { }
    private static void store(Object... parts) { }
    private static void recordOutcome(String svTRID, boolean success, String object) { }
    private static void alertBilling(String balance) { }
    private static boolean customerAuthorisedTheMove(String name) { return false; }
}
```

Storing `raw()` alongside the parsed fields is cheap and worth it: a notice is the only copy of an
event, and once acked the registry has none.

Note where the `client` comes from inside the lambda: it is the try-with-resources variable, which
Java treats as effectively final, so the callback can use it to answer a transfer on the same
session it is draining. Nothing stops you sending commands from inside the handler, and a transfer
you have to answer is exactly the case for it.

---

## Result codes on this page

| Code | Meaning | Exception |
|---|---|---|
| `1301` | a message is waiting (`req`), or messages remain (`ack`) | — |
| `1300` | the queue is empty | — |
| `2303` | `ack` for a message id that does not exist, or was already acknowledged | `ObjectDoesNotExistException` |
| `2400` | the registry could not complete it; may be transient | `CommandException` (`isRetryable()`) |
| `2500`–`2502` | the session ended; reconnect and log in again | `SessionException` |

`1300` and `1301` are both success codes, so `isSuccess()` is true for either — which is why
"empty" is decided by the code `1300` and never by the absence of a payload. `ResultCode` names them
`SUCCESS_NO_MESSAGES` and `SUCCESS_ACK_TO_DEQUEUE`; the full taxonomy is in [Errors](errors.md).

---

See also: [Domains](domains.md) · [Contacts](contacts.md) · [Balance & prices](balance.md) ·
[Responses](responses.md) · [Errors](errors.md)

[← Manual index](README.md)
