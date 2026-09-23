# Домени

Об'єкти доменів відповідають **RFC 5731**, DNSSEC — **RFC 5910**, відновлення з періоду викупу —
**RFC 3915**, ціни — **RFC 8748**, плюс власне розширення реєстру, якщо воно в нього є. Кожна
доменна команда доступна через `client.domain()`, і кожна з них повертає
[`Response`](responses.md).

Усе на цій сторінці припускає підключений клієнт із виконаним входом — див. [Сесія](session.md),
щоб його отримати, і [Команди](commands.md), щоб зрозуміти, чим команда й відповідь є загалом.

Дві звички, які варто пронести через усю сторінку:

- **Дати повертаються власним рядком реєстру** (`2027-04-01T09:15:00Z`), ніколи не `Instant`. Саме
  реєстр вирішує, на який календарний день припадає продовження; переформатування через локальний
  часовий пояс — це те, через що клієнт починає показувати попередній день і від нього ж
  продовжувати.
- **Гроші повертаються точним десятковим рядком**, ніколи не `double`. Баланс, підсумований у
  двійковій рухомій комі, дрейфує. Використовуйте `BigDecimal` або цілі числа в найменших одиницях
  валюти.

## Методи

| Метод | Команда EPP |
|---|---|
| `check(List<String> names, Map<String, Object> fee, String currency): Response` | `<check>` + необов'язковий `<fee:check>` |
| `info(String name, String authInfo, String hosts): Response` | `<info>` |
| `create(String name, Map<String, Object> options): Response` | `<create>` |
| `createBuilder(String name): DomainCreateBuilder` | будує `<create>` |
| `update(String name, Map<String, Object> options): Response` | `<update>` |
| `updateBuilder(String name): DomainUpdateBuilder` | будує `<update>` |
| `delete(String name): Response` | `<delete>` |
| `renew(String name, String curExpDate, int years, Object fee): Response` | `<renew>` |
| `transfer(String op, String name, String authInfo, Integer years, Object fee): Response` | `<transfer op="…">` |
| `restore(String name, Object fee): Response` | `<update>` + `<rgp:restore op="request"/>` |

`create()` та `update()` приймають карту опцій. **Ключ опції, якого ця бібліотека не розуміє,
відхиляється з `ValidationException` ще до того, як буде побудовано хоч один кадр**, із назвою
найближчого відомого їй ключа. Це важливіше, ніж здається: мовчки проігнорований `"secdns"`
зареєструє домен непідписаним, а реєстр усе одно відповість `1000` — бо, з його погляду, ви цього
й не просили.

---

## check

```java
public Response check(List<String> names, Map<String, Object> fee, String currency)
```

**У каналі передачі:** `<command><check><domain:check><domain:name>…` — RFC 5731 §3.1.1. Кожне ім'я
з `names` стає одним `<domain:name>`. Коли задано `fee` або `currency`, у `<extension>` їде блок
`<fee:check>` (RFC 8748); увесь набір можливостей комісій описано в [Баланс і ціни](balance.md).
Коротша форма `check(names)` не надсилає розширення взагалі.

Доступність передається в корисному навантаженні, а не в коді відповіді: check, який відповідає
«зайнято», — це **успішна** команда.

```java
Response r = client.domain().check(Arrays.asList("example.com.ua", "taken.com.ua"));

r.availability();                        // {example.com.ua=true, taken.com.ua=false}
r.isAvailable("example.com.ua");         // true | false | null
r.unavailableReason("taken.com.ua");     // "In use", or null when the name is free
```

`isAvailable()` повертає `null`, коли відповідь про це ім'я нічого не сказала. Віддавайте перевагу
йому перед ручним читанням `availability()`: там `null` означає і «зайнято», і «ви помилилися
в ключі» — дві відповіді, які не мають права виглядати однаково в рядку, що реєструє ім'я.

```java
for (Map.Entry<String, Boolean> entry : client.domain().check(candidates).availability().entrySet()) {
    if (Boolean.TRUE.equals(entry.getValue())) {
        register.add(entry.getKey());
    }
}
```

Відповідь про доступність — це знімок, а не бронювання. Між check і create ім'я може зайняти хтось
інший, і ви дізнаєтеся про це з `2302` на create — саме він і є остаточною відповіддю.

**Коди відповіді:** `1000` на будь-який коректно сформований check. `2005` називає синтаксично
недійсне доменне ім'я, `2307` — зону, яку цей реєстр не обслуговує, `2306` — доданий блок комісії,
який відхиляє політика реєстру. Запит комісій більш ніж на 20 записів ця бібліотека відхиляє з
`ValidationException` ще до надсилання — і так само валюту, названу зовсім без операцій:
валюта сама собою нічого не оцінює. **Порожній список** теж відхиляється, із
`ValidationException`: кадр, який зібрався би, не має жодного нащадка, а схема вимагає хоч одного —
тож цикл за рядком запиту або кошиком, який виявився порожнім, падає тут, поіменно, а не
витрачає звернення до сервера.

---

## info

```java
public Response info(String name, String authInfo, String hosts)
```

**У каналі передачі:** `<command><info><domain:info><domain:name hosts="all">` — RFC 5731 §3.1.2.
Передайте `authInfo` — і він піде як `<domain:authInfo><domain:pw>`; саме так реєстратор, який
**не** є власником домену, читає повний запис.

`hosts` обирає, які хости перелічить відповідь, і є атрибутом `hosts` із RFC 5731:

| значення | що перелічує відповідь |
|---|---|
| `all` (типово) | делеговані сервери імен і підпорядковані хости |
| `del` | лише делеговані сервери імен |
| `sub` | лише підпорядковані хости |
| `none` | нічого з цього |

Ці чотири — і більше нічого: `hostsType` із RFC 5731 є закритим переліком, тож `hosts` поза ними —
це `ValidationException`, яке називає набір, а не кадр, який реєстр відхиляє, не називаючи атрибута.

```java
Response info = client.domain().info("example.com.ua");

info.objectName();          // "example.com.ua"
info.roid();                // the registry's own identifier for the object
info.statuses();            // ["ok"] or ["clientHold", "clientTransferProhibited", …]
info.expiryDate();          // "2027-04-01T09:15:00Z" — the registry's own string
info.createdDate();         // crDate
info.createdBy();           // crID
info.updatedDate();         // upDate, or null
info.updatedBy();           // upID, or null
info.sponsor();             // clID — the account the domain belongs to now
info.registrarOfRecord();   // the handle the registry's WHOIS/RDAP publishes, when it differs
info.transferDate();        // when the domain last changed hands, or null

info.registrant();          // the registrant's contact handle
info.contacts();            // {admin=[acme-01], tech=[acme-01, acme-02]}
info.adminContacts();       // just that role — also techContacts() / billingContacts()
info.contactsFor("tech");   // any role, case-insensitively; empty when nobody holds it
info.allContacts();         // every handle, the registrant included, de-duplicated

info.nameservers();         // names, whether the registry answered hostObj or hostAttr
info.nameserverAddresses(); // inlined glue by nameserver name, when the registry sends it
info.subordinateHosts();    // hosts living UNDER this domain

info.authInfo();            // the transfer secret — see the warning below
info.license();             // a trademark or licence number, or null
info.rgpStatus();           // ["redemptionPeriod"] and so on, or empty
info.isSigned();            // whether the domain carries any DNSSEC data
info.dsRecords();           // [{keyTag=…, alg=…, digestType=…, digest=…}, …]
info.keyRecords();          // [{flags=…, protocol=…, alg=…, pubKey=…}, …]
info.prices();              // {renewal={value=180.00, currency=UAH}, …}
info.priceChannel();        // which catalogue row those prices came from, or null
```

`authInfo()` — це секрет, який дозволяє **будь-якому** реєстратору забрати у вас домен. Він
повертається лише реєстратору-власнику. Ніколи не пишіть його в журнал, ніколи не вставляйте у
звернення до підтримки і змінюйте його, щойно він побував у клієнта — див.
[Відкликання витеклого коду трансферу](#відкликання-витеклого-коду-трансферу).

Два аксесори варто читати разом. `nameservers()` дає імена за будь-якої з двох моделей EPP, тож
беріть його для списку; `nameserverAddresses()` заповнюється лише там, де реєстр відповідає
вбудованими glue-адресами, тож порожній результат **не** означає, що домен без делегування. Там, де
реєстр відповідає посиланнями на об'єкти хостів, адреси ви отримуєте одним
[`host().info()`](hosts.md#info) на кожне ім'я.

`subordinateHosts()` — це те, що варто перевірити перед видаленням: реєстр відмовляється видаляти
домен, поки під ним живуть хости.

**Коди відповіді:** `1000`; `2202` (неправильний `authInfo` як у не-власника); `2303` (такого
домену немає).

---

## create

```java
public Response create(String name, Map<String, Object> options)
```

**У каналі передачі:** `<command><create><domain:create>` — RFC 5731 §3.2.1, плюс `<secDNS:create>`
(RFC 5910), `<registry:create><registry:license>` та `<fee:create>` (RFC 8748) у `<extension>`, коли ви їх
просите. **Комісія за create стягується в разі успіху.**

### Кожна опція

| ключ | значення | канал передачі |
|---|---|---|
| `years` | `int` | `<domain:period unit="y">` — пропустіть, щоб узяти типовий термін реєстру |
| `registrant` | ідентифікатор | `<domain:registrant>` |
| `contacts` | карта `role` → ідентифікатор або `role` → список ідентифікаторів | один `<domain:contact type="…">` на кожен ідентифікатор |
| `nameservers` (пишеться також `nameServers`) | `List<String>` або список карт із ключами `name` і `addresses` | `<domain:ns>`, що містить `<domain:hostObj>` або `<domain:hostAttr>` |
| `authInfo` | рядок | `<domain:authInfo><domain:pw>` |
| `license` | рядок | `<registry:license>` усередині `<registry:create>` |
| `secDNS` | карта з `maxSigLife` і **або** `dsData`, **або** `keyData` — не обома разом | `<secDNS:create>` |
| `fee` | `"100.00"` або карта з `amount` і `currency` | `<fee:create>` — межа, на яку ви погоджуєтеся |

### Перша реєстрація

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.EppException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RegisterOne {

    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .caFile("/path/to/registry-ca.pem")
                .build();

        try (Client client = new Client(config)) {
            client.connect();
            client.login();

            Map<String, Object> contacts = new LinkedHashMap<String, Object>();
            contacts.put("admin", "acme-01");
            contacts.put("tech", Arrays.asList("acme-01", "acme-02"));

            Map<String, Object> options = new LinkedHashMap<String, Object>();
            options.put("years", 1);
            options.put("registrant", "acme-01");
            options.put("contacts", contacts);
            options.put("nameservers", Arrays.asList("ns1.acme.example", "ns2.acme.example"));
            options.put("authInfo", "D0main-Pw");

            Response r = client.domain().create("example.com.ua", options);

            // Прочитайте відповідь. Create відповідає іменем і датами, які призначив реєстр.
            System.out.println(r.objectName() + " created " + r.createdDate());
            System.out.println("expires: " + or(r.expiryDate(), "-"));
            System.out.println("charged: " + or(r.feeAmount(), "-") + " " + or(r.feeCurrency(), ""));

            if (r.isPending()) {
                // 1001: реєстр поставив реєстрацію в чергу. Домен ЩЕ не зареєстрований, а результат
                // надійде пізніше як poll-сповіщення — див. poll.md.
                System.out.println("queued for offline processing (svTRID " + r.svTRID() + ")");
            }

            client.logout();
        } catch (EppException e) {
            System.err.println("EPP error: " + e.getMessage());
        }
    }

    private static String or(String value, String fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }
}
```

### Контакти: один ідентифікатор на роль або кілька

Роль приймає одне значення або список, і кожен ідентифікатор стає власним
`<domain:contact type="…">` — саме це дозволяє RFC 5731. Скільки їх може тримати одна роль — це
політика реєстру.

```java
Map<String, Object> contacts = new LinkedHashMap<String, Object>();
contacts.put("admin", "acme-01");
contacts.put("tech", Arrays.asList("acme-01", "acme-02"));
contacts.put("billing", "acme-03");
options.put("contacts", contacts);
```

Реєстрант до них **не** належить — це окремий елемент із власним значенням, і задається він ключем
`registrant`.

### Сервери імен: дві моделі
Сервер імен — це або **ім'я**, тобто посилання на [об'єкт хоста](hosts.md), який уже існує в
реєстрі, або ім'я **з вбудованими glue-адресами**. Запитайте свій реєстр, яку модель він приймає.

```java
// Посилання на об'єкти хостів (<domain:hostObj>): спершу створіть хости.
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList("ns1.acme.example", "ns2.acme.example"));
```

```java
// Вбудовані glue-адреси (<domain:hostAttr>): адреси подорожують разом з іменем.
Map<String, Object> ns1 = new LinkedHashMap<String, Object>();
ns1.put("name", "ns1.example.com.ua");
ns1.put("addresses", Arrays.asList("203.0.113.1", "2001:db8::1"));
Map<String, Object> ns2 = new LinkedHashMap<String, Object>();
ns2.put("name", "ns2.example.com.ua");
ns2.put("addresses", Arrays.asList("203.0.113.2"));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList(ns1, ns2));
```

Версія IP визначається із самого літерала, тож `v4` і `v6` отримують правильні позначки без того,
щоб ви вказували, де яка.

RFC 5731 робить `<domain:ns>` *вибором* між двома моделями, тож одна команда використовує або одну,
або другу. Суміш тут дає `ValidationException`, а не голий `2001` від реєстру, який не називає
жодного поля:

```java
Map<String, Object> glue = new LinkedHashMap<String, Object>();
glue.put("name", "ns2.acme.example");
glue.put("addresses", Arrays.asList("203.0.113.2"));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList("ns1.acme.example", glue));
client.domain().create("example.com.ua", options);
// ValidationException: nameservers must be all names or all name-with-glue, not a mixture
```

Реєстрація взагалі без `nameservers` цілком законна: домен лишається без делегування, і реєстр
повідомляє про нього `inactive` — а це стан, а не помилка.

### authInfo

`<domain:authInfo>` у create обов'язковий, тож елемент виходить завжди. Задайте опцію `authInfo` —
і в ньому поїде ваше значення; пропустіть її — і замість нього піде порожній `<domain:pw/>`, що
передає вибір власній політиці реєстру для цієї зони: багато зон тоді самі генерують код для вас, і
ви зчитуєте його через `info()`. Наданий вами код має задовольняти політику надійності зони, інакше
create відхиляється з `2306`.

### secDNS під час create (RFC 5910)

```java
Map<String, Object> ds = new LinkedHashMap<String, Object>();
ds.put("keyTag", 12345);
ds.put("alg", 13);
ds.put("digestType", 2);
ds.put("digest", "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6");
// Необов'язково: той DNSKEY, з якого обчислено дайджест, як вкладена карта keyData. Реєстр, який
// її приймає, може перевірити дайджест за вас; той, який не приймає, відповідає 2306, а не ігнорує.
//   Map<String, Object> key = new LinkedHashMap<String, Object>();
//   key.put("flags", 257); key.put("protocol", 3); key.put("alg", 13); key.put("pubKey", "AwEAA…");
//   ds.put("keyData", key);

Map<String, Object> secDns = new LinkedHashMap<String, Object>();
secDns.put("maxSigLife", 1209600);
secDns.put("dsData", Arrays.asList(ds));
// Або голі відкриті ключі замість DS-записів, там, де реєстр приймає їх:
//   secDns.put("keyData", Arrays.asList(key));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "acme-01");
options.put("secDNS", secDns);

client.domain().create("example.com.ua", options);
```

Карта `secDNS` приймає `dsData`, `keyData` і `maxSigLife` — і більше нічого, а `dsData` і
`keyData` — це **альтернативи**: RFC 5910 §2 і §4 кажуть, що їх «MUST NOT be mixed», а
`secDNS-1.1.xsd` робить їх XSD-вибором, тож блок, який несе обидві, відхиляється цілком — і
забирає з собою всю зміну DNSSEC. Карта, у якій є й те, й те, відхиляється тут, із
`ValidationException`. Щоб надіслати DS-запис разом із DNSKEY, з якого його обчислено,
вкладіть ключ **усередину** запису (ключ `keyData` у записі `dsData`) — це єдине вкладення,
яке дозволяє RFC 5910. Карта `secDNS`, у якій немає ні `dsData`, ні `keyData`, не надсилає блоку
DNSSEC узагалі, бо `<secDNS:create/>` без нащадків не проходить перевірку схеми в реєстрі.

### ліцензія (там, де реєстр її вимагає)

Деякі реєстри не реєструють окремі імена без номера торговельної марки або ліцензії — найчастіше це
короткі й дорогі імена безпосередньо під доменом верхнього рівня. Там, де це так, передавайте його
в `license`:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "acme-01");
options.put("license", "TM-2026-000123");   // їде як <registry:license> всередині <registry:create>

client.domain().create("example.com.ua", options);
```

Він їде у **власному** розширенні реєстру, і його простір імен клієнт читає з `<greeting>` — див.
[Команди](commands.md#власні-розширення-вашого-реєстру). Реєстру, який такого розширення не оголошує,
замість кадру, який той би проігнорував, буде кинуто `ConfigException`.

Яким саме іменам вона потрібна — це політика реєстру, а не протоколу, тож питайте у свого. Про те,
що ви не вгадали, скажуть дві відмови: ім'я, якому ліцензія потрібна, але її не передали, зазвичай
відхиляється з `2003` (бракує обов'язкового параметра), а ліцензія, надіслана туди, де на неї не
чекають, — з `2306` (неприпустиме за політикою значення параметра).

### fee: обмеження суми, на яку ви погоджуєтеся

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();

options.put("fee", "100.00");                       // «погоджуюся заплатити до 100.00»

// Або той самий ліміт із явною валютою. Обидві форми живуть під одним ключем, тож другий
// put ЗАМІНЮЄ перший - задавайте одну з них, а не обидві, як тут написано.
Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "100.00");
cap.put("currency", "UAH");
options.put("fee", cap);                            // …у цій валюті
```

Це **межа, а не ціна, яку встановлюєте ви**. Якщо реальна ціна вища — змінився тариф, ім'я виявилося
преміальним, у вас застарів кеш — реєстр відхиляє команду з `2004` і не стягує нічого, замість того
щоб мовчки списати більше. Без цього ключа команда виконується, і стягується власна ціна реєстру.
Повністю тему розкрито в [Баланс і ціни](balance.md).

**Коди відповіді:** `1000`; `1001`, коли реєстр ставить реєстрацію в чергу; `2003` / `2004` / `2005`
/ `2306` (валідація та політика, включно з межею `fee`, нижчою за реальну ціну); `2104` (недостатньо
коштів — [зупиніть пакет](errors.md)); `2302` (уже зареєстровано); `2103` (DNSSEC у цій зоні не
пропонується); `2307` (зона не обслуговується).

### Побудова create крок за кроком

```java
public DomainCreateBuilder createBuilder(String name)
```

Та сама команда, той самий кадр, той самий результат — білдер викликає `create()`. Змінюється те, що
описка стає неіснуючим методом, про який вам скаже компілятор. Кожен крок описано в
[Білдери](builders.md).

---

## update

```java
public Response update(String name, Map<String, Object> options)
```

**У каналі передачі:** `<command><update><domain:update>` — RFC 5731 §3.2.5, із `<secDNS:update>`,
`<rgp:update>`, `<registry:update>` та `<fee:update>` у `<extension>` за потреби.

**Оновлення в EPP — це дельта, а не заміна.** Те, чого ви не згадали, залишається точно таким, як
було. Блок, у який потрапляє зміна, *і є* семантикою команди:

| блок | значення |
|---|---|
| `add` | лишити те, що є, і додати оце |
| `rem` | забрати оце, решту лишити |
| `chg` | замінити це однозначне поле |

| ключ | значення |
|---|---|
| `add` / `rem` | карта з ключами `ns`, `contacts` і `statuses` |
| `chg` | карта з ключами `registrant`, `authInfo`, `clearAuthInfo` |
| `secDNS` | карта з ключами `add`, `rem`, `remAll`, `maxSigLife` |
| `restore` | `true` — див. [restore](#restore) |
| `license` | рядок — замінює номер торговельної марки або ліцензії |
| `fee` | межа, на яку ви погоджуєтеся, коли зміна платна |

```java
Map<String, Object> addContacts = new LinkedHashMap<String, Object>();
addContacts.put("tech", "acme-02");

Map<String, Object> add = new LinkedHashMap<String, Object>();
add.put("ns", Arrays.asList("ns3.acme.example"));
add.put("contacts", addContacts);
add.put("statuses", Arrays.asList("clientTransferProhibited"));

Map<String, Object> rem = new LinkedHashMap<String, Object>();
rem.put("ns", Arrays.asList("ns2.acme.example"));
rem.put("statuses", Arrays.asList("clientHold"));

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("registrant", "acme-09");
chg.put("authInfo", "New-D0main-Pw");

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("add", add);
options.put("rem", rem);
options.put("chg", chg);

Response r = client.domain().update("example.com.ua", options);

System.out.println(r.code() + " " + r.message());   // 1000, або 1001, якщо реєстр поставив у чергу

// Update відповідає результатом, а не об'єктом. Перечитайте новий стан, коли вам
// потрібно його зберегти:
Response after = client.domain().info("example.com.ua");
System.out.println(String.join(", ", after.nameservers()));
System.out.println(String.join(", ", after.statuses()));
```

Статуси, які ви можете встановлювати, — це родина `client*`: `clientHold`,
`clientUpdateProhibited`, `clientTransferProhibited`, `clientDeleteProhibited`,
`clientRenewProhibited`. Статуси `server*` належать реєстру, і спроба їх зачепити повертається з
`2304`. `ok` та `inactive` обчислюються і не належать нікому.

Порожній блок `add` чи `rem` не надсилається взагалі. Оновлення, яке не несе ні `add`, ні `rem`, ні
`chg` — і жодної зміни в розширенні, — це порожня команда, і реєстр відхиляє її з `2003`.

### secDNS під час update (RFC 5910)

Оновлення й тут є дельтою, і форма в нього інша, ніж у блоці create:

```java
// Заміна ключа: заберіть старий DS і додайте новий у тій самій команді, щоб не було вікна,
// у якому домен лишається без підпису.
Map<String, Object> oldDs = new LinkedHashMap<String, Object>();
oldDs.put("keyTag", 12345);
oldDs.put("alg", 13);
oldDs.put("digestType", 2);
oldDs.put("digest", "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6");

Map<String, Object> newDs = new LinkedHashMap<String, Object>();
newDs.put("keyTag", 54321);
newDs.put("alg", 13);
newDs.put("digestType", 2);
newDs.put("digest", "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00");

Map<String, Object> remBlock = new LinkedHashMap<String, Object>();
remBlock.put("dsData", Arrays.asList(oldDs));
Map<String, Object> addBlock = new LinkedHashMap<String, Object>();
addBlock.put("dsData", Arrays.asList(newDs));

Map<String, Object> secDns = new LinkedHashMap<String, Object>();
secDns.put("rem", remBlock);
secDns.put("add", addBlock);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", secDns);
client.domain().update("example.com.ua", options);
```

```java
// Зняти підпис із домена повністю:
Map<String, Object> unsign = new LinkedHashMap<String, Object>();
unsign.put("remAll", Boolean.TRUE);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", unsign);
client.domain().update("example.com.ua", options);
```

```java
// Замінити весь набір ключів однією операцією: remAll плюс записи, які треба покласти назад.
Map<String, Object> newDs = new LinkedHashMap<String, Object>();
newDs.put("keyTag", 54321);
newDs.put("alg", 13);
newDs.put("digestType", 2);
newDs.put("digest", "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00");
Map<String, Object> addBlock = new LinkedHashMap<String, Object>();
addBlock.put("dsData", Arrays.asList(newDs));

Map<String, Object> replace = new LinkedHashMap<String, Object>();
replace.put("remAll", Boolean.TRUE);
replace.put("add", addBlock);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", replace);
client.domain().update("example.com.ua", options);
```

```java
// Змінити лише час життя підпису:
Map<String, Object> lifetime = new LinkedHashMap<String, Object>();
lifetime.put("maxSigLife", 1209600);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", lifetime);
client.domain().update("example.com.ua", options);
```

Запис, названий у `rem`, має збігатися з тим, що тримає реєстр, у кожному полі, а не лише за
keyTag. `remAll` і `rem` — альтернативи: коли є обидва, виходить `remAll`;
[білдер оновлення](builders.md) відмовляє в такому поєднанні прямо, замість того щоб вибирати за
вас.

Карта `secDNS`, у якій немає ні `add`, ні `rem`, ні `remAll`, ні `maxSigLife`, не надсилає блоку
DNSSEC узагалі: `<secDNS:update/>` без нащадків — це `2003` у реєстрі за те, що читається як команда
без дії, і задумана вами зміна DNSSEC загубилася б, а команда все одно повідомила б про невдачу.

### Відкликання витеклого коду трансферу
```java
// Код потрапив туди, куди не мав. Приберіть його зовсім:
Map<String, Object> clear = new LinkedHashMap<String, Object>();
clear.put("clearAuthInfo", Boolean.TRUE);
Map<String, Object> revoke = new LinkedHashMap<String, Object>();
revoke.put("chg", clear);
client.domain().update("example.com.ua", revoke);

// Пізніше, коли клієнтові знову потрібен код:
Map<String, Object> fresh = new LinkedHashMap<String, Object>();
fresh.put("authInfo", "Fresh-D0main-Pw");
Map<String, Object> reissue = new LinkedHashMap<String, Object>();
reissue.put("chg", fresh);
client.domain().update("example.com.ua", reissue);
```

`clearAuthInfo` надсилає `<domain:authInfo><domain:null/></domain:authInfo>`, що **видаляє** секрет.
Встановити `authInfo` в `""` — це не те саме і не розв'язання: порожній пароль лишається значенням,
яке той, хто його має, може пред'явити, тож домен лишається рівно таким самим рухомим, як був.

Ці два ключі взаємовиключні — схема не має способу виразити обидва, — тож прохання про обидва в
одному `chg` викликає `ValidationException` ще до того, як щось буде надіслано.

**Коди відповіді:** `1000`; `1001`, коли поставлено в чергу; `2003` / `2004` / `2005` / `2306`;
`2303` (такого домену немає); `2304` (статус забороняє); `2305` (зв'язок забороняє); `2103` (DNSSEC
тут не пропонується).

### Побудова update крок за кроком

```java
public DomainUpdateBuilder updateBuilder(String name)
```

Білдер оновлення називає блок, у який потрапляє кожна зміна, — `addNameserver`, `remStatus`,
`changeRegistrant`, `clearAuthInfo` — з тієї самої причини, що й карта. Див.
[Білдери](builders.md).

---

## delete

```java
public Response delete(String name)
```

**У каналі передачі:** `<command><delete><domain:delete>` — RFC 5731 §3.2.2.

```java
Response before = client.domain().info("example.com.ua");
if (!before.subordinateHosts().isEmpty()) {
    // The registry refuses the delete while hosts live under this domain (2305).
    throw new IllegalStateException("remove " + String.join(", ", before.subordinateHosts()) + " first");
}

Response r = client.domain().delete("example.com.ua");
System.out.println(r.code() + " " + r.message());
```

Що саме зробить delete, залежить від того, де домен перебуває у своєму життєвому циклі: у межах
вікна add-grace він видаляється негайно, інакше — переходить у `redemptionPeriod`, звідки його можна
[відновити](#restore), доки вікно не закриється і ім'я не буде очищено. Прочитайте `rgpStatus()` у
наступному `info()`, щоб побачити, що саме сталося.

**Коди відповіді:** `1000`; `1001`, коли поставлено в чергу; `2303`; `2304` (напр.
`clientDeleteProhibited`); `2305` (під доменом усе ще існують хости).

---

## renew

```java
public Response renew(String name, String curExpDate, int years, Object fee)
```

**У каналі передачі:** `<command><renew><domain:renew>` з `<domain:name>`, `<domain:curExpDate>` та
`<domain:period unit="y">` — RFC 5731 §3.2.3. **Комісія за renew стягується в разі успіху.**

`curExpDate` має дорівнювати **поточній** даті завершення терміну реєстрації домену. Це не
формальність: саме вона не дає дубльованому чи повтореному renew додати ще один рік. Читайте її з
реєстру, а не зі свого кешу.

**Передавайте `expiryDate()` як є.** Це два різні XML-типи — `<domain:exDate>` є відміткою часу, а
`<domain:curExpDate>` — датою, — і денну частину бібліотека бере сама:

```java
Response info = client.domain().info("example.com.ua");
// info.expiryDate() is "2027-04-01T09:15:00.0Z"; what goes on the wire is "2027-04-01".

Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "90.00");
cap.put("currency", "UAH");
Response r = client.domain().renew("example.com.ua", info.expiryDate(), 1, cap);

System.out.println("new expiry: " + r.expiryDate());   // the registry's own string — store it as is
System.out.println("charged:    " + (r.feeAmount() != null ? r.feeAmount() : "-")
        + " " + (r.feeCurrency() != null ? r.feeCurrency() : ""));
```

Дата береться **такою, якою її написав сервер**, без розбору і без переведення часових поясів. Це
зроблено навмисно: відмітки часу в EPP — в UTC, і дата завершення в реєстру теж у UTC, тож клієнт,
який переформатовує її через місцевий пояс, для кожного домену, що завершується близько опівночі,
влучає на добу в той чи інший бік — і далі продовжує за датою, якої в реєстру немає. Якщо потрібен
місцевий час, переводьте його там, де показуєте, а не перед надсиланням назад.

Розбіжність у `curExpDate` повертається як `2105`, і саме цій відповіді треба вірити: вона означає,
що термін реєстрації домену не такий, як ви думали, тож перечитайте його, перш ніж робити будь-що
інше. `2105` ніколи не є підставою повторити той самий кадр.

**Коди відповіді:** `1000`; `2105` (розбіжність `curExpDate` або домен не підлягає
продовженню); `2104` (недостатньо коштів); `2303`; `2304`; `2306`. Період поза межею 1–99 із
RFC 5731 до реєстру ніколи не доходить: ця бібліотека відхиляє його через `ValidationException`,
яке називає значення, тож `2004`, який дала би схема, ви тут не побачите. Реєстр все одно
може відповісти `2004` або `2306` на період, який він приймає в принципі, але не пропонує в цій зоні.

---

## transfer

```java
public Response transfer(String op, String name, String authInfo, Integer years, Object fee)
```

**У каналі передачі:** `<command><transfer op="…"><domain:transfer>` — RFC 5731 §3.2.4 (і §3.1.3 для
`query`). `op` — це одне з `request`, `query`, `approve`, `reject`, `cancel`, і **більше ніщо**:
RFC 5730 закриває цей набір, тож шоста назва — це `ValidationException`, яке називає ці п’ять, а не
кадр, який реєстр відхиляє, поки вікно трансферу продовжує йти.

| `op` | хто надсилає | що робить |
|---|---|---|
| `request` | реєстратор, що приймає | запитує домен, із поточним `authInfo` |
| `query` | будь-яка зі сторін | повідомляє, на якій стадії запит, нічого не змінюючи |
| `approve` | поточний реєстратор-власник | приймає запит, що очікує |
| `reject` | поточний реєстратор-власник | відмовляє в запиті, що очікує |
| `cancel` | реєстратор, що запитує | відкликає власний запит |

`years` виходить як `<domain:period unit="y">` **лише тоді, коли ви передаєте число**, тож `null`
пропускає елемент цілком. Що з двох потрібне зоні — це політика реєстру: зони, які вкладають у
трансфер обов'язкове продовження на один рік, беруть `1` (або пропущене типове значення), а зони, де
трансфер безплатний і нічого не змінює, хочуть, щоб елемента не було взагалі. Запитайте свій реєстр,
що діє в зоні, з якої ви переносите домен.

### Запит трансферу до себе

```java
Response r = client.domain().transfer(
        "request", "example.com.ua", "the-code-from-the-losing-registrar", 1, null);

r.code();               // 1001 — accepted and pending, not done
r.transferStatus();     // "pending"

Map<String, String> t = r.transfer();      // the whole trnData block
// {
//   status=pending,
//   requestedBy=EXAMPLE,                  // reID
//   requestedAt=2026-04-01T09:15:00Z,     // reDate
//   actingClient=DELTA,                   // acID — who has to answer
//   actBy=2026-04-06T09:15:00Z,           // acDate — the deadline
//   expiryDate=2028-04-01T09:15:00Z       // the expiry that will apply
// }
```

Сам по собі `transferStatus()` каже, що трансфер очікує, але не каже, чий він і скільки часу
лишилося на дію. `transfer()` дає `actBy`, і саме ця дата має значення: **мовчання завершує
трансфер.** Після строку рішення ухвалює реєстр, а в цих зонах він трансфер підтверджує.
Реєстратор-власник, який підшиває poll-сповіщення замість того, щоб на нього відповісти, втрачає
домен.

### Відповідь на трансфер як реєстратор, що втрачає домен

Запит надходить до вас як [poll-сповіщення](poll.md) з `trnData`. Відповідайте на нього:

```java
client.poll().drain(notice -> {
    Map<String, String> t = notice.transfer();
    if (t == null || !"pending".equals(t.get("status"))) {
        return;
    }
    String name = notice.objectName();

    if (customerAuthorisedTheMove(name)) {
        client.domain().transfer("approve", name);
    } else {
        client.domain().transfer("reject", name);
    }
});
```

### Перевірка й відкликання

```java
client.domain().transfer("query", "example.com.ua");    // 2300 pending, 2301 nothing pending
client.domain().transfer("cancel", "example.com.ua");   // withdraw your own request
```

Поки домен у `pendingTransfer`, жодна інша операція з ним не приймається, включно з автоматичними.

**Коди відповіді:** `1000` / `1001`; `2201` (не ваш об'єкт, щоб діяти з ним); `2202` (неправильний
`authInfo`); `2300` (уже очікує); `2301` (немає нічого, що очікує, щоб схвалити, відхилити,
скасувати чи запитати); `2304`; `2306`; `2106` (не підлягає трансферу).

---

## restore

```java
public Response restore(String name, Object fee)
```

**У каналі передачі:** `<update>`, який несе `<domain:chg/>` і більше нічого, плюс
`<rgp:update><rgp:restore op="request"/>` у розширенні — RFC 3915. Це рівно те саме, що
`update(name, singletonMap("restore", true))`, і вони взаємозамінні. **Комісія за відновлення
стягується в разі успіху**, і це зазвичай найдорожча операція в каталозі.

Відновлення **не змінює нічого іншого**, але оновлення, на якому воно їде, все одно мусить
нести блок. RFC 3915 §4.2.5: «at least one empty `<domain:add>`, `<domain:rem>`, or `<domain:chg>`
element MUST be present if this extension is specified within an `<update>` command», і власний
приклад RFC несе `<domain:chg/>`. Бібліотека виводить цей порожній елемент за вас. Тому не
надсилайте разом із відновленням власної зміни — застосуйте її потім, другою командою, — але й не
чекайте, що кадр буде оновленням зовсім без блоків: оновлення, яке несе лише ім’я, — це
порожня дія, за яку частина реєстрів відповідає 2003, а відновлення, якого не сталося, — це
домен, який виходить із редемпційного періоду тим, що його видаляють.

```java
Response info = client.domain().info("example.com.ua");

if (info.rgpStatus().contains("redemptionPeriod")) {
    Response r = client.domain().restore("example.com.ua", "1000.00");   // your cap, not a published price

    System.out.println(r.code());                        // 1000 restored, or 1001 queued
    System.out.println("charged: " + (r.feeAmount() != null ? r.feeAmount() : "-"));

    Response after = client.domain().info("example.com.ua");
    System.out.println("rgp:     "
            + (after.rgpStatus().isEmpty() ? "-" : String.join(", ", after.rgpStatus())));
    System.out.println("expires: " + after.expiryDate());
}
```

Читайте `rgpStatus()`, а не `statuses()`: стани викупу надходять у `<extension>` як
`<rgp:infData>`, тож клієнт, який читає лише `<domain:status>`, побачить домен за кілька днів до
видалення зі звичайним `ok`.

Відновлення можливе лише в межах вікна викупу. Після нього ім'я вивільняється, і відновлювати вже
нічого.

**Коди відповіді:** `1000`; `1001`, коли відновлення завершується асинхронно; `2104` (недостатньо
коштів); `2303`; `2304` (домен не в тому стані, з якого його можна відновити); `2306`.

---

## Коли команда, що змінює дані, зазнала невдачі, а ви не знаєте, чи вона відбулася

Тайм-аут читання або розірване з'єднання посеред `create`, `renew` чи `transfer` лишає справді
невідомий результат: реєстр міг виконати команду і списати з вас гроші ще до того, як відповідь
загубилася. Ні ця бібліотека, ні виняток різниці не бачать.

**Не повторюйте просто так.** Сліпий повтор — це те, як домен реєструють — і оплачують — двічі.
Замість цього запитайте в реєстру, як є насправді: `info()` для create, а для renew — `expiryDate()`,
звірений із тим, чого ви очікували. Повторюйте, лише якщо об'єкт справді в тому стані, з якого ви
починали. Повне правило разом із таксономією винятків — у [Помилки](errors.md).

---

## Коди відповіді на цій сторінці

| Код | Значення | Виняток |
|---|---|---|
| `1000` | виконано | — |
| `1001` | прийнято, завершується офлайн; результат надходить через [poll](poll.md) | — |
| `2003` | бракує обов'язкового параметра | `CommandException` |
| `2004` | значення поза діапазоном — включно з межею `fee`, нижчою за реальну ціну | `CommandException` |
| `2005` | значення синтаксично недійсне | `CommandException` |
| `2103` | розширення не підтримується для цієї зони | `CommandException` |
| `2104` | недостатньо коштів; нічого не зареєстровано і нічого не стягнуто | `InsufficientFundsException` |
| `2105` | розбіжність `curExpDate` або домен не підлягає продовженню | `CommandException` |
| `2106` | не підлягає трансферу | `CommandException` |
| `2201` | не ваш об'єкт, щоб діяти з ним | `AuthorizationException` |
| `2202` | неправильний `authInfo` | `AuthorizationException` |
| `2300` / `2301` | уже очікує трансферу / не очікує трансферу | `CommandException` |
| `2302` | уже зареєстровано | `ObjectExistsException` |
| `2303` | такого домену немає | `ObjectDoesNotExistException` |
| `2304` / `2305` | статус або зв'язок забороняє це | `ObjectStatusException` |
| `2306` / `2308` | політика реєстру відхиляє це значення | `PolicyException` |
| `2307` | зона не обслуговується | `CommandException` |

`ResultCode` має іменовану константу для кожного з них. Повна таксономія, правила повторів та
альтернатива `throwOnFailure(false)` — у [Помилки](errors.md).

---

Див. також: [Контакти](contacts.md) · [Хости](hosts.md) · [Poll](poll.md) ·
[Баланс і ціни](balance.md) · [Відповіді](responses.md) · [Білдери](builders.md)

[← Зміст посібника](README.md)
