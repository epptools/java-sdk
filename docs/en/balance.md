# Balance and prices

Two mechanisms live on this page. They are independent of each other and they have one thing in
common: both deal in money, and money in this library is always an exact decimal **String**.

- **The balance query** — what your account holds and what it can still spend. It uses the registry's
  own balance extension, whose namespace the client reads from the `<greeting>`. No RFC defines this
  command, so a registry may not offer one at all: where it does not, `balance()` throws
  `ConfigException` listing what the server did advertise, rather than sending a frame the server
  would ignore. See [Commands](commands.md#your-registrys-own-extensions).
- **The fee extension (RFC 8748)** — standard EPP. Ask what an operation would cost, and cap what a
  transform is allowed to charge you.

Everything here assumes a connected, logged-in client — see [Session](session.md).

**Money comes back as an exact decimal string, never a `double`.** `0.1 + 0.2` is not `0.3` in binary
floating point, and a balance summed that way drifts by a hundredth here and a hundredth there until
your ledger and the registry's disagree. Use `BigDecimal`, or integer minor units. The same rule
holds for every amount on this page: a quoted price, a cap, a charged fee, a credit limit.

Amounts in the examples are illustrative. They are not the registry's tariff.

## The surface

| Call | Sends | Answers |
|---|---|---|
| `client.balance()` → `Response` | `<info><balance:info>` | credit limit, balance, available credit |
| `domain().check(List<String> names, Map<String,Object> fee, String currency)` | `domain:check` + `<fee:check>` | availability **and** prices |
| `domain().create(name, options)` with a `fee` key | `domain:create` + `<fee:create>` | the create, capped |
| `domain().renew(name, curExpDate, years, fee)` | `domain:renew` + `<fee:renew>` | the renewal, capped |
| `domain().transfer("request", name, authInfo, years, fee)` | `domain:transfer` + `<fee:transfer>` | the transfer, capped |
| `domain().restore(name, fee)` | `domain:update` + `<fee:update>` | the restore, capped |

| Accessor | Reads |
|---|---|
| `balance()` → `Map<String,String>` | the whole balance block, or `null` |
| `threshold()` → `String` | the figure whose crossing queued a low-balance notice, or `null` on a plain report |
| `creditLimit()` · `currentBalance()` · `availableCredit()` → `String` | one figure each |
| `fees()` → `Map<String,Object>` | every quote in a check reply, keyed by name |
| `feeFor(String name, String operation, int years)` · `feeFor(name, operation)` → `String` | one quote |
| `feeClass(String name)` · `isPremium(String name)` | which price list a name sits on |
| `chargedFee()` → `Map<String,String>` · `feeAmount()` · `feeCurrency()` | what a transform actually charged |

---

## balance

```java
Response balance();
```

**On the wire:** `<command><info><balance:info/></info>` in the registry's balance namespace
(for example `http://registry.example/epp/balance-1.0`). It is a read: nothing is charged and nothing
changes.

It hangs off the client itself rather than off a command handler, because it is about the account
rather than about an object.

```java
Response b = client.balance();

b.creditLimit();       // "5000.00"  — how far below zero the account may go
b.currentBalance();    // "1240.50"  — what is in it now
b.availableCredit();   // "6240.50"  — what you can still spend: balance plus the limit
```

All three are decimal strings in your account currency. The whole block in one call:

```java
Map<String, String> figures = client.balance().balance();
// {creditLimit=5000.00, balance=1240.50, availableCredit=6240.50}
```

`currentBalance()` exists because `balance()` is the block and `balance` is one figure inside it.
Reading `balance().get("balance")` is the same value; the named accessor is there so a line about
money does not read like a typo.

**`balance()` returns `null` when the reply carries no balance block.** That is what to test, rather
than assuming the figures are there:

```java
Response b = client.balance();

if (b.balance() == null) {
    // Not a balance answer. With throwOnFailure(false) a refusal arrives here as an ordinary
    // Response instead of an exception, and it carries no figures.
    throw new IllegalStateException(b.message() != null ? b.message() : "no balance in the reply");
}
```

### Checking before a batch

The reason to call it is to decide something before spending. Compare with `BigDecimal`, never by
parsing to a `double`:

```java
String available = client.balance().availableCredit();
String needed = "2400.00";                     // 24 registrations at an illustrative 100.00

if (new BigDecimal(available).compareTo(new BigDecimal(needed)) < 0) {
    // Stop here rather than at name 13, half-way through a batch.
    alertBilling("available " + available + ", need " + needed);
    return;
}
```

`new BigDecimal(String)` is the constructor to use. `BigDecimal.valueOf(double)` and
`new BigDecimal(double)` both start from a binary value that is already not the decimal the registry
sent, which is the drift this page is about.

A batch that runs out mid-way is not a disaster — the registry refuses each remaining billable
command with `2104` and charges nothing — but it leaves you reconciling a half-finished order. See
[Errors](errors.md#insufficientfundsexception-2104) for why `2104` means stop the batch rather than
skip the name.

### The low-balance notice

The registry can also push the figures at you. A low-balance [poll notice](poll.md#a-low-balance-notice)
carries the same block, so the same accessors read it:

```java
client.poll().drain(notice -> {
    if (notice.balance() != null) {
        alertBilling(notice.currentBalance());   // a decimal string — never parse it to a double
    }
});
```

**Result codes:** `1000`, with the figures in the frame. A refusal — the service not offered to this
session, an account that may not read it — arrives as a `CommandException` like any other; if you
see one, check that the balance URI was announced at login. By default the login advertises exactly
the services the greeting offered, so this is usually a matter of `extUris` on the config naming a
list of its own — see [Session](session.md).

---

## Prices: the fee extension (RFC 8748)

One extension, two entirely separate uses. Keep them apart in your head and the rest follows:

| Use | Where | What it does |
|---|---|---|
| **Ask** | `domain().check()` | quotes a price. Changes nothing, costs nothing |
| **Cap** | `create`, `renew`, `transfer`, `restore` | states the most you consent to pay. A higher real price refuses the command |

The cap is **not** a price you set. The registry charges its own tariff. What the cap buys you is
that the tariff cannot exceed what you agreed to without the command failing instead of billing you.

Both are optional. A command with no fee block proceeds normally and the registry's own price is
charged.

---

### Asking a price on check

```java
Response check(List<String> names, Map<String, Object> fee, String currency);
```

`fee` maps an operation to a period. The operations are `create`, `renew`, `transfer`, `restore`,
`update` and `delete`.

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);
fee.put("renew", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, null);

r.isAvailable("example.com.ua");                  // Boolean.TRUE — the name is free
r.feeFor("example.com.ua", "create", 1);          // "100.00"
r.feeFor("example.com.ua", "renew", 1);           // "90.00"
r.fees().get("_currency");                        // "UAH"
```

The map is a `Map<String, Object>` rather than a `Map<String, Integer>` because a value is either one
period or a list of them; see the next section. One round trip answers both questions — is it free,
and what would it cost. The fee block applies to every name in the command, and the reply carries one
entry per name:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);

Response r = client.domain().check(
        Arrays.asList("one.com.ua", "two.com.ua", "three.com.ua"), fee, null);

for (Map.Entry<String, Boolean> entry : r.availability().entrySet()) {
    if (Boolean.TRUE.equals(entry.getValue())) {
        String quote = r.feeFor(entry.getKey(), "create", 1);
        System.out.printf("%-16s %s %s%n", entry.getKey(),
                quote != null ? quote : "-", r.fees().get("_currency"));
    }
}
```

An availability answer is a snapshot and so is a price. Between the check and the create the name can
be taken and the tariff can move — which is exactly what the [cap](#capping-what-you-agree-to-pay) is
for.

---

### Several periods in one command

A **List** of years asks the same operation at each period, so a whole price table costs one round
trip instead of five:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 3, 5, 10));

Response table = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

table.feeFor("example.com.ua", "create", 1);    // "100.00"
table.feeFor("example.com.ua", "create", 5);    // "480.00"
table.feeFor("example.com.ua", "create", 10);   // "950.00"
```

Single periods and lists mix freely, and several operations can travel together:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));
fee.put("renew", Arrays.asList(1, 2));
fee.put("restore", 1);

client.domain().check(Arrays.asList("example.com.ua"), fee, null);
```

**A frame carries at most 20 fee entries.** An entry is one *(operation, period)* pair, so the
example above is six: three creates, two renews, one restore. The number of names does not enter into
it — twenty entries is twenty entries whether you ask about one name or fifty.

```java
List<Object> everyYear = new ArrayList<Object>();
for (int years = 1; years <= 30; years++) {
    everyYear.add(years);
}
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", everyYear);

client.domain().check(Arrays.asList("example.com.ua"), fee, null);
// ValidationException: a fee query carries at most 20 entries; this one has 30
```

That is refused here, before a frame is built, rather than at the registry — where an over-long query
comes back as a `2306` naming nothing in particular. Split it across two calls.

A period below one is sent as one, so `0` asks for a one-year price rather than for nothing.

---

### Naming a currency

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");
```

The currency travels as `<fee:currency>` and is upper-cased for you. Pass `null` — which is what the
one-argument `check(names)` does — and the registry quotes in its own.

**A currency the registry does not price in comes back as unavailable with a reason, not as a
converted guess.** That distinction is the point: a converted figure would look like a quote you could
cap against, and it would be wrong.

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "JPY");

Object entry = r.fees().get("example.com.ua");
if (entry instanceof Map) {
    Map<?, ?> quoted = (Map<?, ?>) entry;
    if (Boolean.FALSE.equals(quoted.get("avail"))) {
        System.out.println(quoted.get("reason"));     // e.g. "Currency not supported"
    }
}
```

**A currency with no operations at all is refused**, with a `ValidationException` saying that a
currency on its own prices nothing. RFC 8748 makes `fee:checkType` require at least one
`<fee:command>`, so the frame it would build is one the registry cannot answer — and refusing beats
quietly dropping the currency, because you asked what a name costs in that currency and silence would
look like an answer. Name the operations you want quoted:
`check(names, Collections.singletonMap("create", 1), "UAH")`.

---

### transfer and restore are one-year operations

**However many years you ask for, `transfer` and `restore` are priced as a single year**, and the
reply echoes the period that would actually be charged. So quote them at one year and read them back
at one year:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("transfer", 1);
fee.put("restore", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, null);

r.feeFor("example.com.ua", "transfer", 1);   // "120.00"
r.feeFor("example.com.ua", "restore", 1);    // "1000.00"
r.feeFor("example.com.ua", "restore", 3);    // null — nothing was quoted at three years
```

Asking for a restore at three years is not an error; the answer comes back describing one year,
because that is what the operation is. Reading it back at three gives `null`, and a `null` treated as
"free" is how a restore gets booked at nothing. Read the `periods` list if you want to see the period
the registry actually priced:

```java
Map<?, ?> entry = (Map<?, ?>) r.fees().get("example.com.ua");

for (Object quote : (List<?>) entry.get("periods")) {
    // {op=restore, years=1, fee=1000.00} — years is what was PRICED
    System.out.println(quote);
}
```

The same holds for a transfer that carries a mandatory renewal: the renewal is a separate line in the
catalogue, so quote `transfer` and `renew` together if you want the total.

---

### Reading the answer

```java
Map<String, Object> fees();
String feeFor(String name, String operation, int years);
String feeClass(String name);
boolean isPremium(String name);
```

`fees()` is the whole reply, keyed by name, with the currency alongside:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

r.fees();
// {
//   _currency      = UAH,
//   example.com.ua = {
//       avail    = true,                 // could the registry PRICE it — see below
//       reason   = null,                 // why not, when avail is false
//       class    = premium,              // present only when the registry sent a class
//       commands = {create={years=1, fee=100.00}},
//       periods  = [{op=create, years=1, fee=100.00},
//                   {op=create, years=2, fee=200.00},
//                   {op=create, years=5, fee=480.00}]
//   }
// }
```

Three things to know about that shape:

- **`commands` holds one entry per operation** — the first period you asked for. When you asked for
  several periods, read `feeFor()` or `periods`. A loop over `commands` after asking for 1, 2 and 5
  years quietly reports the one-year price for all three.
- **`avail` here is about pricing, not about the name.** `false` means the registry could not quote
  it — a zone it does not serve, a currency it does not price in — and `reason` says which. Whether
  the *name* is free is `isAvailable()`, which is a different question with a different answer.
- **A quote can be `null` inside a period** with a `reason` beside it: the registry priced the name
  but not that operation. `null` is "no quote", never "free".

The map is a `Map<String, Object>` because the entries are heterogeneous — a currency string beside
one nested block per name — so reading into it means a cast. `feeFor()`, `feeClass()` and
`isPremium()` exist so that the common questions need none.

```java
String quote = r.feeFor("example.com.ua", "create", 1);
if (quote == null) {
    throw new IllegalStateException("no create quote for example.com.ua — do not assume a price");
}
```

`feeClass()` and `isPremium()` say which price list a name sits on:

```java
r.feeClass("example.com.ua");    // "premium" | "standard" | null
r.isPremium("example.com.ua");   // true when a class is present and is not "standard"
```

Both have a no-argument overload; with none they answer for the first name in the reply that carries
a class. **Take the price from `fees()`, not from the class.** The class says which list applies, not
what it costs, and a `false` from `isPremium()` is only "the answer declared no special class" — not
a promise of the standard price.

A different thing with a similar name: `prices()` and `priceChannel()` on a `domain:info` are the
registry's own price hints for a domain you already hold, not RFC 8748 quotes. They are in
[Responses](responses.md#domain).

---

## Capping what you agree to pay

The same extension, pointed the other way. On a transform you state the most you consent to pay, and
the registry refuses the command rather than charging more.

| Command | How the cap is passed | Wire |
|---|---|---|
| `create` | the `fee` option | `<fee:create>` |
| `renew` | the fourth argument | `<fee:renew>` |
| `transfer` | the fifth argument (on `request`) | `<fee:transfer>` |
| `restore` | the second argument | `<fee:update>` — a restore *is* an update |
| `update` | the `fee` option | `<fee:update>` |

Two shapes, everywhere: a bare amount, or an amount with the currency it is in. The argument is
typed `Object` so that both fit one parameter — a `String` for the first, a `Map` for the second.

```java
Object bare = "100.00";                        // the amount, in the registry's own currency

Map<String, Object> withCurrency = new LinkedHashMap<String, Object>();
withCurrency.put("amount", "100.00");
withCurrency.put("currency", "UAH");           // …and the currency it is in
```

```java
Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "90.00");
cap.put("currency", "UAH");

// Create
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");
options.put("fee", "100.00");
client.domain().create("example.com.ua", options);

// Renew
client.domain().renew("example.com.ua", "2027-04-01", 1, cap);

// Transfer in
client.domain().transfer("request", "example.com.ua", "the-code", 1, "120.00");

// Restore
client.domain().restore("example.com.ua", "1000.00");
```

The [builders](builders.md) spell it `maxFee()`, which is what it is:

```java
client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .maxFee("100.00", "UAH")
        .send();
```

`maxFee()` also checks the amount is a plain decimal — `100`, `100.5`, `100.00` — and throws a
`ValidationException` before anything is sent if it is not. The direct option is checked the same way
when the frame is built, so `"100,00"` or `"$100"` fails readably in both, rather than as a bare
`2004`/`2005` naming no field after the command has been attempted.

### What a refusal at 2004 means

**`2004` on a command that carried a cap means the real price is higher than the cap. Nothing was
done and nothing was charged.**

```java
try {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("years", 1);
    options.put("registrant", "C-0001");
    options.put("fee", "100.00");
    client.domain().create("rare.com.ua", options);
} catch (CommandException e) {
    if (e.eppCode() == ResultCode.PARAMETER_VALUE_RANGE_ERROR) {
        // The domain is NOT registered and you were NOT charged. Re-quote and decide again —
        // do not widen the cap in a loop until it passes, which is how a premium name gets
        // bought at a price nobody approved.
        Map<String, Object> fee = new LinkedHashMap<String, Object>();
        fee.put("create", 1);
        String quote = client.domain().check(Arrays.asList("rare.com.ua"), fee, null)
                .feeFor("rare.com.ua", "create", 1);
        askAHumanAbout("rare.com.ua", quote);
    }
}
```

That is the whole value of the cap: a tariff change, a premium name you did not know was premium, or
a stale price in your own cache becomes a refusal you can look at instead of an invoice you find
later. `2004` is also the generic "value out of range" code, so it can mean a period the zone does not
offer — the `reasons()` on the exception say which, and the cap is the first thing to check when the
command carried one.

Widening the cap automatically defeats it. If a create fails at `2004`, re-quote with `check()` and
either accept the new price deliberately or leave the name alone.

---

## Reading what a transform actually charged

```java
Map<String, String> chargedFee();
String feeAmount();
String feeCurrency();
```

A successful transform that carried a fee agreement echoes what it charged:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");
options.put("fee", "100.00");

Response r = client.domain().create("example.com.ua", options);

r.chargedFee();     // {currency=UAH, fee=100.00}
r.feeAmount();      // "100.00"
r.feeCurrency();    // "UAH"
```

**Record `feeAmount()` against the order, not the figure you quoted from a `check`.** The quote was a
statement about a moment; this is what the registry billed. They agree almost always, and the whole
point of storing the second one is the case where they do not.

`null` means the reply carried no fee block — an ordinary answer for a command sent without a cap
against a registry that does not echo prices unasked. It never means "free".

The echo is read from whichever transform block the reply carries (`creData`, `renData`, `trnData`,
`updData`, `delData`), so the same three accessors work after a create, a renew, a transfer, a
restore and a delete.

---

## A price-checked registration, end to end

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.ResultCode;
import com.epptools.sdk.exception.CommandException;
import com.epptools.sdk.exception.EppException;
import com.epptools.sdk.exception.InsufficientFundsException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RegisterAtAKnownPrice {

    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .caFile("/path/to/registry-ca.pem")
                .build();

        try (Client client = new Client(config)) {
            client.connect();
            client.login();

            String name = "example.com.ua";

            // 1. Is there money to do this at all?
            String available = client.balance().availableCredit();

            // 2. Ask availability and the price in one round trip.
            Map<String, Object> fee = new LinkedHashMap<String, Object>();
            fee.put("create", Arrays.asList(1, 2));
            Response check = client.domain().check(Arrays.asList(name), fee, "UAH");

            if (!Boolean.TRUE.equals(check.isAvailable(name))) {
                String reason = check.unavailableReason(name);
                System.out.println(name + " is not available: "
                        + (reason != null ? reason : "no reason given"));
                client.logout();
                return;
            }

            String quote = check.feeFor(name, "create", 1);
            if (quote == null) {
                throw new IllegalStateException("no create quote — refusing to register at an unknown price");
            }
            if (check.isPremium(name)) {
                System.out.println("premium name, class " + check.feeClass(name));
            }
            if (new BigDecimal(available).compareTo(new BigDecimal(quote)) < 0) {
                System.out.println("available " + available + " is short of " + quote);
                client.logout();
                return;
            }

            // 3. Register, capped at the price we were quoted a moment ago.
            Map<String, Object> contacts = new LinkedHashMap<String, Object>();
            contacts.put("admin", "C-0001");
            contacts.put("tech", "C-0001");

            Map<String, Object> options = new LinkedHashMap<String, Object>();
            options.put("years", 1);
            options.put("registrant", "C-0001");
            options.put("contacts", contacts);
            options.put("authInfo", "D0main-Pw");
            options.put("fee", quote);

            Response r = client.domain().create(name, options);

            // 4. Store what was actually charged, and the registry's own dates and ids.
            System.out.println("registered " + r.objectName() + " until " + r.expiryDate());
            System.out.println("charged    " + r.feeAmount() + " " + r.feeCurrency());
            System.out.println("svTRID     " + r.svTRID());

            if (r.isPending()) {
                // 1001: queued. It is not registered yet; the verdict arrives as a poll notice.
                markPending(r.svTRID());
            }

            client.logout();
        } catch (InsufficientFundsException e) {
            alertBilling(e.getMessage());        // stop; every later billable command fails the same way
        } catch (CommandException e) {
            if (e.eppCode() == ResultCode.PARAMETER_VALUE_RANGE_ERROR) {
                System.out.println("the price moved above the cap — nothing was registered or charged");
            } else {
                System.out.println("EPP " + e.eppCode() + ": " + e.getMessage());
            }
        } catch (EppException e) {
            System.out.println("EPP error: " + e.getMessage());
        }
    }

    private static void markPending(String svTRID) { }
    private static void alertBilling(String message) { }
}
```

Four habits in that program worth keeping: quote and cap with the **same** figure; store what was
charged rather than what was quoted; test `isPending()` before recording anything as done; and treat
`2104` as a reason to stop rather than to move on to the next name.

Note the order of the `catch` clauses. `InsufficientFundsException` is a subclass of
`CommandException`, so it has to come first — Java refuses to compile the other order, which is one
place where the compiler enforces something this manual would otherwise only be able to advise.

---

## Result codes on this page

| Code | Meaning | Exception |
|---|---|---|
| `1000` | done — the figures, or the quotes, are in the frame | — |
| `1001` | the transform was accepted and completes offline; the fee follows it | — |
| `2004` | the real price is above the cap you agreed, or a period is out of range. **Nothing charged** | `CommandException` |
| `2005` | a fee amount the registry cannot read as a number | `CommandException` |
| `2103` | the fee extension is not offered for this zone | `CommandException` |
| `2104` | insufficient funds; nothing was done | `InsufficientFundsException` |
| `2306` | the registry's policy refuses the query or the agreement | `PolicyException` |

A fee query with more than 20 entries, and a fee amount that is not a plain decimal, are both
refused by this library with a `ValidationException` before anything is sent.

---

See also: [Domains](domains.md) · [Poll](poll.md) · [Responses](responses.md) ·
[Builders](builders.md) · [Errors](errors.md)

[← Manual index](README.md)
