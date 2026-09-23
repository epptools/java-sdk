# Баланс і ціни

На цій сторінці живуть два механізми. Вони незалежні один від одного і мають одне спільне: обидва
мають справу з грошима, а гроші в цій бібліотеці — це завжди точний десятковий **рядок**.

- **Запит балансу** — скільки тримає ваш обліковий запис і скільки він ще може витратити. Він іде у
  власному розширенні балансу реєстру, і його простір імен клієнт читає з `<greeting>`. Ця команда не
  описана в жодному RFC, тож реєстр може її й не пропонувати: там, де не пропонує, `balance()` кидає
  `ConfigException` з переліком того, що сервер оголосив, а не надсилає кадр, який той проігнорує.
  Див. [Команди](commands.md#власні-розширення-вашого-реєстру).
- **Розширення fee (RFC 8748)** — стандартний EPP. Запитайте, скільки коштувала б операція, і
  обмежте суму, яку команда має право з вас списати.

Усе тут припускає підключений клієнт із виконаним входом — див. [Сесія](session.md).

**Гроші повертаються точним десятковим рядком, ніколи не `double`.** У двійковій рухомій комі
`0.1 + 0.2` — це не `0.3`, і баланс, підсумований у такий спосіб, дрейфує на сотку тут і на сотку
там, доки ваша бухгалтерія й бухгалтерія реєстру не розійдуться. Використовуйте `BigDecimal` або цілі
числа в найменших одиницях валюти. Те саме правило діє для кожної суми на цій сторінці: для
котированої ціни, для межі, для стягнутої комісії, для кредитної лінії.

Суми в прикладах наведено для ілюстрації. Це не тариф реєстру.

## Поверхня API

| Виклик | Надсилає | Відповідає |
|---|---|---|
| `client.balance(): Response` | `<info><balance:info>` | кредитна лінія, баланс, доступні кошти |
| `domain().check(List<String> names, Map<String, Object> fee, String currency): Response` | `domain:check` + `<fee:check>` | доступність **і** ціни |
| `domain().create(name, options)` з ключем `fee` | `domain:create` + `<fee:create>` | create з погодженою межею |
| `domain().renew(name, curExpDate, years, fee)` | `domain:renew` + `<fee:renew>` | продовження з погодженою межею |
| `domain().transfer("request", name, authInfo, years, fee)` | `domain:transfer` + `<fee:transfer>` | трансфер з погодженою межею |
| `domain().restore(name, fee)` | `domain:update` + `<fee:update>` | відновлення з погодженою межею |

| Аксесор | Що читає |
|---|---|
| `balance(): Map<String, String>` | увесь блок балансу, або `null` |
| `threshold(): String` | число, перетин якого поставив у чергу сповіщення про низький баланс, або `null` на звичайному звіті |
| `creditLimit(): String` · `currentBalance(): String` · `availableCredit(): String` | по одному числу кожен |
| `fees(): Map<String, Object>` | кожне котирування у відповіді на check, за іменем |
| `feeFor(String name, String operation, int years): String` | одне котирування |
| `feeClass(String name): String` · `isPremium(String name): boolean` | до якого прайс-листа належить ім'я |
| `chargedFee(): Map<String, String>` · `feeAmount(): String` · `feeCurrency(): String` | що команда справді стягнула |

---

## balance

```java
public Response balance()
```

**У каналі передачі:** `<command><info><balance:info/></info>` у просторі імен balance реєстру
(`http://registry.example/epp/balance-1.0`). Це читання: нічого не списується і нічого не змінюється.

Він висить на самому клієнті, а не на обробнику команд об'єкта, бо стосується облікового запису, а
не об'єкта.

```java
Response b = client.balance();

b.creditLimit();       // "5000.00"  — how far below zero the account may go
b.currentBalance();    // "1240.50"  — what is on it now
b.availableCredit();   // "6240.50"  — what you can still spend: the balance plus the line
```

Усі три — десяткові рядки у валюті вашого облікового запису. Увесь блок одним викликом:

```java
b.balance();
// {creditLimit=5000.00, balance=1240.50, availableCredit=6240.50}
```

`currentBalance()` існує тому, що `balance()` — це блок, а `balance` — одне число всередині нього.
`b.balance().get("balance")` дає те саме значення; іменований аксесор потрібен, щоб рядок про гроші не
читався як описка.

**`balance()` повертає `null`, коли у відповіді немає блоку балансу.** Перевіряйте саме це, а не
припускайте, що числа на місці:

```java
Response b = client.balance();

if (b.balance() == null) {
    // This is not a balance answer. With throwOnFailure(false) a refusal arrives here as an
    // ordinary Response instead of an exception, and it carries no numbers at all.
    throw new IllegalStateException(b.message() != null ? b.message() : "no balance in the reply");
}
```

### Перевірка перед пакетом операцій

Викликати його варто для того, щоб щось вирішити до витрат. Порівнюйте через `BigDecimal`, а не
оператором `<` над `double`:

```java
String available = client.balance().availableCredit();
String needed = "2400.00";                  // 24 registrations at 100.00, for illustration

if (new BigDecimal(available).compareTo(new BigDecimal(needed)) < 0) {
    // Stop here, not at the 13th name, in the middle of the batch.
    alertBilling("available " + available + ", need " + needed);
    return;
}
```

Пакет, у якого кошти вичерпалися посеред роботи, — не катастрофа: реєстр відхиляє кожну наступну
платну команду з `2104` і не стягує нічого, — але вам лишається звіряти напівзроблене замовлення.
Чому `2104` означає «зупинити пакет», а не «пропустити ім'я», див. у
[Помилки](errors.md#insufficientfundsexception-2104).

### Сповіщення про низький баланс

Реєстр може й сам надіслати вам ці числа. [poll-сповіщення](poll.md#сповіщення-про-низький-баланс) про
низький баланс несе той самий блок, тож читають його ті самі аксесори:

```java
client.poll().drain(notice -> {
    if (notice.balance() != null) {
        alertBilling(notice.currentBalance());   // a decimal string — never coerce it
    }
});
```

**Коди відповіді:** `1000`, із числами в кадрі. Відмова — служба не пропонується цій сесії,
обліковому запису не дозволено її читати — надходить як `CommandException`, як і будь-яка інша;
якщо ви її бачите, перевірте, чи URI балансу було оголошено під час входу. Типово вхід оголошує
рівно ті служби, які запропонувало привітання, тож зазвичай річ у тому, що `Config.extUris`
називає власний список, — див. [Сесія](session.md).

---

## Ціни: розширення fee (RFC 8748)

Одне розширення, два цілком окремі застосування. Тримайте їх у голові окремо — і решта складеться
сама:

| Застосування | Де | Що робить |
|---|---|---|
| **Запитати** | `domain().check()` | котирує ціну. Нічого не змінює, нічого не коштує |
| **Обмежити** | `create`, `renew`, `transfer`, `restore` | вказує максимум, на який ви погоджуєтеся. Вища реальна ціна веде до відмови в команді |

Межа — це **не** ціна, яку встановлюєте ви. Реєстр стягує власний тариф. Межа дає вам те, що тариф
не може перевищити погоджене вами без того, щоб команда зазнала невдачі замість того, щоб виставити
вам рахунок.

Обидва застосування необов'язкові. Команда без блоку fee виконується як звичайно, і стягується
власна ціна реєстру.

---

### Запит ціни під час check

```java
public Response check(List<String> names, Map<String, Object> fee, String currency)
```

`fee` — це карта `operation` → років. Операції: `create`, `renew`, `transfer`, `restore`, `update` та
`delete`.

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);
fee.put("renew", 1);
Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, null);

r.isAvailable("example.com.ua");                  // true — the name is free
r.feeFor("example.com.ua", "create", 1);          // "100.00"
r.feeFor("example.com.ua", "renew", 1);           // "90.00"
r.fees().get("_currency");                        // "UAH"
```

Один обмін із сервером відповідає на обидва питання — чи вільне ім'я і скільки воно коштувало б.
Блок комісій застосовується до кожного імені в команді, а у відповіді на кожне ім'я припадає один
запис:

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

Відповідь про доступність — це знімок, і ціна теж. Між check і create ім'я можуть зайняти, а тариф —
змінитися; саме для цього й потрібна [межа](#обмеження-суми-на-яку-ви-погоджуєтеся).

---

### Кілька періодів в одній команді

**Список** років запитує ту саму операцію на кожному з періодів, тож ціла таблиця цін коштує одного
обміну замість п'яти:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 3, 5, 10));
Response table = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

table.feeFor("example.com.ua", "create", 1);    // "100.00"
table.feeFor("example.com.ua", "create", 5);    // "480.00"
table.feeFor("example.com.ua", "create", 10);   // "950.00"
```

Окремі числа і списки вільно поєднуються, і кілька операцій можуть їхати разом:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));
fee.put("renew", Arrays.asList(1, 2));
fee.put("restore", 1);
client.domain().check(Arrays.asList("example.com.ua"), fee, null);
```

**Кадр несе щонайбільше 20 записів комісій.** Запис — це одна пара *(операція, період)*, тож у
прикладі вище їх шість: три create, два renew, один restore. Кількість імен тут ні до чого —
двадцять записів лишаються двадцятьма, питаєте ви про одне ім'я чи про п'ятдесят.

```java
List<Object> everyYear = new ArrayList<Object>();
for (int y = 1; y <= 30; y++) {
    everyYear.add(y);
}
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", everyYear);
client.domain().check(Arrays.asList("example.com.ua"), fee, null);
// ValidationException: a fee query carries at most 20 entries; this one has 30
```

Це відхиляється тут, ще до побудови кадру, а не в реєстрі, де надто довгий запит повертається
`2306`, який не називає нічого конкретного. Розділіть його на два виклики.

Період, менший за один, надсилається як один, тож `0` питає ціну на рік, а не ціну ні за що.

---

### Вибір валюти

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);
Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");
```

Валюта їде як `<fee:currency>` і переводиться у верхній регістр за вас. Передайте `null` — і реєстр
котируватиме у своїй.

**Валюта, у якій реєстр не котирує, повертається як недоступна, з причиною, а не як перерахований
здогад.** У цій різниці й суть: перерахована сума виглядала б як котирування, від якого можна
відштовхнутися, задаючи межу, — і була б хибною.

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);
Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "JPY");

Map<String, Object> entry = (Map<String, Object>) r.fees().get("example.com.ua");
if (entry != null && Boolean.FALSE.equals(entry.get("avail"))) {
    System.out.println(entry.get("reason"));     // e.g. "Currency not supported"
}
```

**Валюта без жодної операції відхиляється** із `ValidationException`, яке каже, що валюта сама собою
нічого не оцінює. RFC 8748 вимагає від `fee:checkType` щонайменше одного `<fee:command>`, тож кадр,
який зібрався би, реєстр відповісти не зможе. А відмова ліпша за тихе відкидання валюти: ви
запитали, скільки коштує ім’я в цій валюті, і тиша виглядала би як відповідь. Називайте операції,
котирування яких вам потрібні: `check(names, Collections.singletonMap("create", 1), "UAH")`.

---

### transfer і restore — операції на один рік

**Скільки б років ви не питали, `transfer` і `restore` котируються як один рік**, а відповідь
повторює той період, який справді буде стягнуто. Тож питайте їх на один рік і зчитуйте на один рік:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("transfer", 1);
fee.put("restore", 1);
Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, null);

r.feeFor("example.com.ua", "transfer", 1);   // "120.00"
r.feeFor("example.com.ua", "restore", 1);    // "1000.00"
r.feeFor("example.com.ua", "restore", 3);    // null — nothing was quoted for three years
```

Запит `restore` на 3 роки не є помилкою; відповідь повертається з описом одного року, бо саме такою
є ця операція. Зчитування на трьох роках дає `null`, а `null`, сприйнятий як «безплатно», — це те,
як відновлення проводять у книгах за нуль. Прочитайте список `periods`, якщо хочете побачити період,
який реєстр справді оцінив:

```java
Map<?, ?> entry = (Map<?, ?>) r.fees().get("example.com.ua");

for (Object quote : (List<?>) entry.get("periods")) {
    // {op=restore, years=1, fee=1000.00} — years — це те, що було ОЦІНЕНО
    System.out.println(quote);
}
```

Те саме стосується трансферу, який несе обов'язкове продовження: продовження — окремий рядок
каталогу, тож питайте `transfer` і `renew` разом, якщо хочете суму.

---

### Читання відповіді

```java
public Map<String, Object> fees()
public String feeFor(String name, String operation, int years)
public String feeClass(String name)
public boolean isPremium(String name)
```

`fees()` — це вся відповідь, за ключем-іменем, і валюта поруч:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));
Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

r.fees();
// {
//   _currency=UAH,
//   example.com.ua={
//       avail=true,                 // whether the registry could PRICE it — see below
//       reason=null,                // why not, when avail is false
//       class=premium,              // present only when the registry sent a class
//       commands={create={years=1, fee=100.00}},
//       periods=[
//           {op=create, years=1, fee=100.00},
//           {op=create, years=2, fee=200.00},
//           {op=create, years=5, fee=480.00}
//       ]
//   }
// }
```

Три речі, які варто знати про цю форму:

- **`commands` містить один запис на операцію** — той перший період, який ви запитали. Коли ви
  питали кілька періодів, читайте `feeFor()` або `periods`. Цикл по `commands` після запиту
  `[1, 2, 5]` тихо повідомить річну ціну для всіх трьох.
- **`avail` тут про оцінювання, а не про ім'я.** `false` означає, що реєстр не зміг його котирувати
  — зона, якої він не обслуговує, валюта, у якій він не котирує, — а `reason` каже, що саме. Чи
  вільне *ім'я* — це `isAvailable()`, інше питання з іншою відповіддю.
- **Котирування може бути `null` усередині періоду**, а поруч стоятиме `reason`: реєстр оцінив ім'я,
  але не цю операцію. `null` — це «немає котирування», ніколи не «безплатно».

```java
String quote = r.feeFor("example.com.ua", "create", 1);
if (quote == null) {
    throw new IllegalStateException("no create quote for example.com.ua — do not assume a price");
}
```

`feeClass()` та `isPremium()` кажуть, до якого прайс-листа належить ім'я:

```java
r.feeClass("example.com.ua");    // "premium" | "standard" | null
r.isPremium("example.com.ua");   // true when a class is present and is not "standard"
```

Обидва мають форму без імені; тоді вони відповідають про перше ім'я у відповіді, яке несе клас.
**Беріть ціну з `fees()`, а не з класу.** Клас каже, який прайс-лист застосовано, а не скільки це
коштує, і `false` від `isPremium()` означає лише «у відповіді не оголошено особливого класу», а не
обіцянку стандартної ціни.

Інша річ зі схожою назвою: `prices()` та `priceChannel()` у `domain:info` — це власні цінові
підказки реєстру для домену, який ви вже тримаєте, а не котирування з RFC 8748. Вони описані в
[Відповіді](responses.md#домен).

---

## Обмеження суми, на яку ви погоджуєтеся
Те саме розширення, спрямоване в інший бік. У команді, що змінює дані, ви вказуєте максимум, який
згодні заплатити, і реєстр радше відхилить команду, ніж спише більше.

| Команда | Як передається межа | Канал передачі |
|---|---|---|
| `create` | ключем `fee` | `<fee:create>` |
| `renew` | четвертим аргументом | `<fee:renew>` |
| `transfer` | п'ятим аргументом (у `request`) | `<fee:transfer>` |
| `restore` | другим аргументом | `<fee:update>` — відновлення *і є* оновленням |
| `update` | ключем `fee` | `<fee:update>` |

Дві форми, скрізь однакові:

```java
Object flat = "100.00";                          // an amount, in the registry's own currency

Map<String, Object> withCurrency = new LinkedHashMap<String, Object>();
withCurrency.put("amount", "100.00");            // …and the currency it is stated in
withCurrency.put("currency", "UAH");
```

```java
// Create
Map<String, Object> create = new LinkedHashMap<String, Object>();
create.put("years", 1);
create.put("registrant", "C-0001");
create.put("fee", "100.00");
client.domain().create("example.com.ua", create);

// Renew
Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "90.00");
cap.put("currency", "UAH");
client.domain().renew("example.com.ua", "2027-04-01", 1, cap);

// An incoming transfer
client.domain().transfer("request", "example.com.ua", "the-code", 1, "120.00");

// Restore
client.domain().restore("example.com.ua", "1000.00");
```

[Білдери](builders.md) називають це `maxFee()` — саме тим, чим воно є:

```java
client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .maxFee("100.00", "UAH")
        .send();
```

`maxFee()` ще й перевіряє, що сума — звичайне десяткове число (`100`, `100.5`, `100.00`), і кидає
`ValidationException` ще до надсилання, якщо це не так. Переданий напряму ключ віддає ваш рядок
реєстру як є, і некоректний повертається голим `2004`/`2005`, який не називає жодного поля, — уже
після спроби виконати команду.

### Що означає відмова з 2004

**`2004` на команді, яка несла межу, означає, що реальна ціна вища за цю межу. Нічого не зроблено і
нічого не стягнуто.**

```java
Map<String, Object> create = new LinkedHashMap<String, Object>();
create.put("years", 1);
create.put("registrant", "C-0001");
create.put("fee", "100.00");

try {
    client.domain().create("rare.com.ua", create);
} catch (CommandException e) {
    if (e.eppCode() == ResultCode.PARAMETER_VALUE_RANGE_ERROR) {
        // The domain was NOT registered, and you were NOT charged. Re-quote and decide again —
        // do not widen the cap in a loop until it goes through: that is how a premium name is
        // bought at a price nobody agreed to.
        Map<String, Object> fee = new LinkedHashMap<String, Object>();
        fee.put("create", 1);
        String quote = client.domain().check(Arrays.asList("rare.com.ua"), fee, null)
                .feeFor("rare.com.ua", "create", 1);
        askAHumanAbout("rare.com.ua", quote);
    }
}
```

У цьому й уся цінність межі: зміна тарифу, преміальне ім'я, про преміальність якого ви не знали, чи
застаріла ціна у вашому власному кеші перетворюються на відмову, яку можна побачити, а не на
рахунок, який ви знайдете згодом. `2004` — це ще й загальний код «значення поза діапазоном», тож він
може означати період, якого зона не пропонує; що саме — скаже `reasons()` у винятку, а межа — це
перше, що варто перевірити, коли команда її несла.

Автоматичне розширення межі зводить її нанівець. Якщо create завершився `2004`, перезапитайте ціну
через `check()` і або свідомо погодьтеся на нову ціну, або лишіть ім'я в спокої.

---

## Читання того, що команда справді стягнула

```java
public Map<String, String> chargedFee()
public String feeAmount()
public String feeCurrency()
```

Успішна команда, яка несла погодження щодо комісії, повторює у відповіді те, що стягнула:

```java
Map<String, Object> create = new LinkedHashMap<String, Object>();
create.put("years", 1);
create.put("registrant", "C-0001");
create.put("fee", "100.00");
Response r = client.domain().create("example.com.ua", create);

r.chargedFee();     // {currency=UAH, fee=100.00}
r.feeAmount();      // "100.00"
r.feeCurrency();    // "UAH"
```

**Записуйте до замовлення `feeAmount()`, а не те число, яке ви отримали в котируванні через
`check`.** Котирування було твердженням про мить; а це — те, що реєстр виставив. Майже завжди вони
збігаються, і весь сенс зберігати друге — саме той випадок, коли ні.

`null` означає, що у відповіді не було блоку комісії — звичайна відповідь для команди, надісланої
без межі, до реєстру, який не повторює цін, коли його про них не питали. Це ніколи не означає
«безплатно».

Це значення читається з того блоку, який несе відповідь (`creData`, `renData`, `trnData`, `updData`,
`delData`), тож ті самі три аксесори працюють після create, renew, transfer, restore і delete.

---

## Реєстрація з перевіреною ціною, від початку до кінця

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

public class PricedRegistration {
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

            // 2. Ask availability and price in one round trip.
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

            // 3. Register with a cap at the price you were just quoted.
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
            System.out.println("registered " + r.objectName() + " until "
                    + (r.expiryDate() != null ? r.expiryDate() : "-"));
            System.out.println("charged    " + (r.feeAmount() != null ? r.feeAmount() : "-")
                    + " " + (r.feeCurrency() != null ? r.feeCurrency() : ""));
            System.out.println("svTRID     " + r.svTRID());

            if (r.isPending()) {
                // 1001: queued. The domain is not registered yet; the verdict arrives as a poll notice.
                markPending(r.svTRID());
            }

            client.logout();
        } catch (InsufficientFundsException e) {
            // Stop; every later billable command fails the same way.
            alertBilling(e.getMessage());
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

    // Your own order book and your own billing alerts; these stand in for them.
    private static void markPending(String svTRID) { }
    private static void alertBilling(String message) { }
}
```

Чотири звички з цієї програми, які варто зберегти: котируйте і задавайте межу **тим самим** числом;
зберігайте те, що стягнуто, а не те, що котирувалося; перевіряйте `isPending()`, перш ніж записати
щось як виконане; і сприймайте `2104` як привід зупинитися, а не перейти до наступного імені.

---

## Коди відповіді на цій сторінці

| Код | Значення | Виняток |
|---|---|---|
| `1000` | виконано — числа або котирування в кадрі | — |
| `1001` | команду прийнято, вона завершується офлайн; комісія йде слідом за нею | — |
| `2004` | реальна ціна вища за погоджену вами межу, або період поза діапазоном. **Нічого не стягнуто** | `CommandException` |
| `2005` | сума комісії, яку реєстр не може прочитати як число | `CommandException` |
| `2103` | розширення fee для цієї зони не пропонується | `CommandException` |
| `2104` | недостатньо коштів; нічого не зроблено | `InsufficientFundsException` |
| `2306` | політика реєстру відхиляє запит або погодження | `PolicyException` |

Запит комісій більш ніж на 20 записів і сума в `maxFee()`, яка не є звичайним десятковим числом, —
обидва відхиляються цією бібліотекою з `ValidationException` ще до того, як щось буде надіслано.

---

Див. також: [Домени](domains.md) · [Poll](poll.md) · [Відповіді](responses.md) ·
[Білдери](builders.md) · [Помилки](errors.md)

[← Зміст посібника](README.md)
