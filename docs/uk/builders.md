# Білдери

Команди, що приймають карту опцій, можна також зібрати по одному іменованому кроку за раз.

```java
Response response = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .adminContact("C-0001")
        .techContact("C-0002")
        .nameservers("ns1.acme.example", "ns2.acme.example")
        .authInfo("D0main-Pw")
        .maxFee("100.00", "UAH")
        .send();
```

**Та сама команда, той самий кадр, той самий результат.** Білдер не будує власного XML: `send()`
передає свої опції звичайному методу, тож білдер і рівнозначна карта дають ідентичний кадр, і кожна
перевірка, що діє для одного, діє й для другого.

Змінюється те, де спливає помилка. Карта опцій приймає будь-який ключ, тож `"yeras"` ловиться
лише тому, що бібліотеку навчили всьому переліку ключів і вона відмовляє в тому, чого в переліку
немає. У білдера немає ключа, в якому можна помилитися: `.yeras(1)` — це метод, якого не існує, і
компілятор скаже вам про це раніше, ніж програма запуститься.

Усе тут припускає під'єднаного клієнта, який увійшов у сесію — див. [Сесія](session.md).

## П'ять білдерів

| Клас | Звідки береться | Надсилає |
|---|---|---|
| `DomainCreateBuilder` | `client.domain().createBuilder(String name)` | `domain:create` |
| `DomainUpdateBuilder` | `client.domain().updateBuilder(String name)` | `domain:update` |
| `ContactCreateBuilder` | `client.contact().createBuilder(String contactId, String email)` | `contact:create` |
| `ContactUpdateBuilder` | `client.contact().updateBuilder(String contactId)` | `contact:update` |
| `HostUpdateBuilder` | `client.host().updateBuilder(String name)` | `host:update` |

Вони живуть у `com.epptools.sdk.builder`. Ви ніколи не створюєте їх напряму — це робить обробник, тож
білдер уже знає, через якого клієнта надсилати. Кожен крок повертає той самий білдер, тож виклики
ланцюжаться.

Білдера для `check`, `info`, `renew`, `transfer`, `delete` чи `restore` навмисно немає. Ці команди
приймають позиційні аргументи, які мова перевіряє й без того; білдер додав би церемонію, не прибравши
жодного класу помилок.

---

## Чотири правила, що діють для кожного білдера

### 1. Кожен списковий крок накопичує

Передати кілька одразу, викликати крок ще раз або і те, і те — це одне й те саме:

```java
client.domain().createBuilder("example.com.ua")
        .techContact("C-0002", "C-0003");

client.domain().createBuilder("example.com.ua")
        .techContact("C-0002").techContact("C-0003");   // те саме
```

Саме це робить білдер таким, що читається так, як поводиться — усередині циклу чи за умовою:

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");

for (String host : nameservers) {
    builder.nameserver(host);        // every call adds one
}
if (needsDnssec) {
    builder.dsRecord(12345, 13, 2, digest);
}

builder.send();
```

Одиничні кроки натомість **замінюють**: `.years(1).years(2)` лишає `2`, так само як подвійне
присвоєння змінній. Таблиці нижче кажуть, що з них що.

Кроки, які приймають імена, ідентифікатори та адреси — сервери імен, контакти, статуси доменів,
контактів і хостів, glue-адреси — відкидають порожнє чи складене з пробілів значення, а не надсилають
його як порожній елемент, тож цикл по списку з порожнім рядком усередині не породжує
`<domain:hostObj/>`. Решту значення вони передають без змін, лише обрізаючи пробіли з країв.

### 2. Нічого не надсилається до `send()`

До того білдер — звичайне значення. Тримайте його, передавайте в іншу функцію, збирайте в одному
місці, а відправляйте в іншому.

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");
// …nothing has reached the registry yet…
Response response = builder.send();     // now it has
```

`send()` повертає [`Response`](responses.md), рівно так само, як і прямий виклик.

### 3. `toOptions()` віддає точно те, що приймає прямий виклик, — копією

```java
public Map<String, Object> toOptions()
```

Доступний у кожному білдері.

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .techContact("C-0002");

builder.toOptions();
// {years=1, registrant=C-0001, contacts={tech=[C-0002]}}

// So here is the same command, by the other road:
client.domain().create("example.com.ua", builder.toOptions());
```

Важать дві властивості:

- **Це рівно та карта, яку приймає прямий метод.** Саме це робить білдер придатним для черги:
  серіалізуйте `toOptions()`, покладіть у чергу, а воркер нехай викличе з нею `create()`.
- **Це глибока копія.** Віддача живої карти дозволила б їй змінюватися під викликачем щоразу, коли
  додається ще один крок, — і те, що ви записали в журнал, могло б не збігтися з тим, що надіслали.
  Ви отримуєте значення, яке вже завершене.

Виклик нічого не надсилає й не витрачає білдер.

### 4. Білдер надсилає один раз

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");

builder.send();
builder.send();
// ValidationException: DomainCreateBuilder has already been sent. A builder carries one command;
//                      build another rather than re-sending this one.
```

Другий `send()` на створенні — це друга реєстрація і друге списання, і це ніколи не те, що мав на
увазі викликач: повтор після збою — не те саме, що перепрогравання об'єкта, який уже пішов. Зберіть
новий білдер, вони нічого не коштують. Якщо перший `send()` завалився так, що підсумок лишився
невідомим, прочитайте
[Помилки](errors.md#коли-операція-завалилася-а-ви-не-знаєте-чи-вона-сталася), перш ніж робити
взагалі щось.

---

## DomainCreateBuilder

```java
public DomainCreateBuilder createBuilder(String name)
```

Надсилає `domain:create` (RFC 5731 §3.2.1). Кожна опція [`domain().create()`](domains.md#create) має
тут свій крок.

| Крок | Встановлює | Накопичує? |
|---|---|---|
| `years(int years)` | `years` — `<domain:period unit="y">`. Пропустіть його, і реєстр застосує власний типовий строк | замінює |
| `registrant(String handle)` | `registrant` — власник домену | замінює |
| `contact(String role, String... handles)` | `contacts[role]` — один `<domain:contact type="…">` на ідентифікатор | накопичує |
| `adminContact(String... handles)` | `contacts["admin"]` | накопичує |
| `techContact(String... handles)` | `contacts["tech"]` | накопичує |
| `billingContact(String... handles)` | `contacts["billing"]` | накопичує |
| `nameserver(String host)` | `nameservers` — одне посилання на об'єкт хоста (`<domain:hostObj>`) | накопичує |
| `nameservers(String... hosts)` | `nameservers` — те саме, кілька за раз | накопичує |
| `nameserverWithGlue(String host, String... addresses)` | `nameservers` як карта з `name` і `addresses` — вбудовані glue-адреси (`<domain:hostAttr>`) | накопичує |
| `authInfo(String password)` | `authInfo` — секрет трансферу | замінює |
| `license(String number)` | `license` — номер торговельної марки або ліцензії, якщо ваш реєстр його вимагає | замінює |
| `maxFee(String amount, String currency)` | `fee` — найбільше, що ви згодні заплатити (RFC 8748) | замінює |
| `dsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS["dsData"]` | накопичує |
| `dsRecordWithKey(int keyTag, int alg, int digestType, String digest, int flags, int protocol, int keyAlg, String pubKey)` | `secDNS["dsData"]` разом із DNSKEY, з якого його обчислено | накопичує |
| `keyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS["keyData"]` | накопичує — **альтернатива** для `dsRecord`, ніколи не поряд із ним |
| `maxSigLife(int seconds)` | `secDNS["maxSigLife"]` | замінює |
| `send(): Response` | викликає `domain().create(name, options)` | завершальний |

`maxFee()` має і коротшу форму з однією сумою, без валюти.

### Реєстрація

```java
try {
    Response r = client.domain().createBuilder("example.com.ua")
            .years(1)
            .registrant("C-0001")
            .adminContact("C-0001")
            .techContact("C-0002", "C-0003")     // one role, two handles
            .nameservers("ns1.acme.example", "ns2.acme.example")
            .authInfo("D0main-Pw")
            .maxFee("100.00", "UAH")
            .send();

    System.out.println(r.objectName() + " created " + r.createdDate());
    System.out.println("expires: " + (r.expiryDate() != null ? r.expiryDate() : "-"));
    System.out.println("charged: " + (r.feeAmount() != null ? r.feeAmount() : "-")
            + " " + (r.feeCurrency() != null ? r.feeCurrency() : ""));

    if (r.isPending()) {
        // 1001 — the registry queued it. Not registered yet; the verdict arrives through poll.
        markPending(r.svTRID());
    }
} catch (EppException e) {
    System.out.println("EPP error: " + e.getMessage());
}
```

`contact()` називає роль одним аргументом — це все, що роблять за вас `adminContact()`,
`techContact()` і `billingContact()`, і це зручно, коли роль приходить із змінної:

```java
client.domain().createBuilder("example.com.ua")
        .contact("admin", "C-0009")
        .contact("billing", "C-0010");
```

Роль — це `admin`, `billing` або `tech`, **і більше ніщо**. RFC 5731 робить
`domain:contactAttrType` закритим переліком, тож зона не отримує четвертої ролі від того, що її
розпізнає реєстр: `type="reseller"` — це кадр, який схема відхиляє, і він забирає з собою всю
команду — усі контакти в ній і саму реєстрацію. Роль поза цими трьома кидає
`ValidationException`, яке називає прийнятний набір, і те саме робить порожня роль, яка дала би
`<domain:contact type="">` і код `2005`, який не називає нічого корисного. Якщо ваш реєстр веде облік
реселера, він робить це через власне розширення, а не через цей елемент.

### Дві моделі делегування

```java
// Посилання на об'єкти хостів: спершу створіть об'єкти хостів (див. hosts.md).
client.domain().createBuilder("example.com.ua")
        .nameserver("ns1.acme.example").nameserver("ns2.acme.example");

// Вбудовані glue-адреси: адреси подорожують разом з іменем. IPv4 та IPv6 розрізняються за літералом.
client.domain().createBuilder("example.com.ua")
        .nameserverWithGlue("ns1.example.com.ua", "203.0.113.1", "2001:db8::1")
        .nameserverWithGlue("ns2.example.com.ua", "203.0.113.2");
```

RFC 5731 робить `<domain:ns>` вибором між цими двома, тож одна команда використовує одну модель або
другу. Змішування відхиляється ще під час побудови кадру — `ValidationException`, що називає
проблему, а не голий `2001` від реєстру, який не називає жодного поля. Запитайте у свого реєстру, яку
модель він приймає.

`nameserverWithGlue()` з порожнім іменем кидає одразу: сервер імен без імені — не те, що варто
дізнаватися з відповіді.

### DNSSEC під час create

```java
client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .dsRecord(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6")
        .dsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .maxSigLife(1209600)
        .send();
```

`dsRecordWithKey()` надсилає поруч із записом DS той DNSKEY, з якого обчислено дайджест. Реєстр, який
це приймає, може звірити дайджест із ключем за вас, спіймавши помилку в дайджесті ще до того, як вона
дійде до зони; той, що даних ключа не приймає, відмовить у команді, а не проігнорує зайвий елемент, —
тож спроба коштує не більше ніж `2306`.

```java
client.domain().createBuilder("example.com.ua")
        .dsRecordWithKey(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6",
                257, 3, 13, "AwEAAb…");
```

Вісім аргументів — це спершу запис DS: keyTag, алгоритм, тип дайджесту, дайджест, — а потім DNSKEY:
flags, protocol, власний алгоритм ключа, публічний ключ. Два номери алгоритму зазвичай однакові; вони
є окремими аргументами, бо протокол тримає їх окремо, і про розбіжність вам скаже реєстр.

`keyRecord()` підписує голим публічним ключем замість запису DS — там, де реєстр такі приймає.
Порожній дайджест або порожній публічний ключ кидають `ValidationException`.

`maxSigLife()` має сенс лише поруч із записом DS або записом ключа.

---

## DomainUpdateBuilder

```java
public DomainUpdateBuilder updateBuilder(String name)
```

Надсилає `domain:update` (RFC 5731 §3.2.5).

**Оновлення в EPP — це дельта, а не заміна.** Те, чого ви не згадали, лишається точно як було, а *те,
у який блок потрапляє зміна, і є вся семантика команди*:

| Блок | Означає |
|---|---|
| `add` | лишити те, що є, і додати оце |
| `rem` | забрати оце, решту лишити |
| `chg` | замінити це одиничне поле |

Надіслати сервер імен в `add`, коли ви мали на увазі `rem`, — це не збій: це делегування домену на
сервер, який ви намагалися прибрати, і реєстр відповість `1000`. Саме тому кожен крок називає свій
блок. Прочитали префікс методу — прочитали семантику.

| Крок | Блок | Встановлює |
|---|---|---|
| `addNameserver(String host)` | `add` | `add["ns"]` — делегувати ще на один, накопичує |
| `addNameservers(String... hosts)` | `add` | `add["ns"]` — кілька за раз, накопичує |
| `remNameserver(String host)` | `rem` | `rem["ns"]` — припинити делегування на один, накопичує |
| `remNameservers(String... hosts)` | `rem` | `rem["ns"]`, накопичує |
| `addContact(String role, String... handles)` | `add` | `add["contacts"][role]`, накопичує |
| `remContact(String role, String... handles)` | `rem` | `rem["contacts"][role]`, накопичує |
| `addStatus(String... statuses)` | `add` | `add["statuses"]`, накопичує |
| `remStatus(String... statuses)` | `rem` | `rem["statuses"]`, накопичує |
| `changeRegistrant(String handle)` | `chg` | `chg["registrant"]`, замінює |
| `changeAuthInfo(String password)` | `chg` | `chg["authInfo"]` — замінити секрет трансферу, замінює |
| `clearAuthInfo()` | `chg` | `chg["clearAuthInfo"]` у `true` — **прибрати** секрет трансферу |
| `restore()` | — | `restore` у `true` — запит на відновлення RGP (RFC 3915) |
| `license(String number)` | — | `license` — номер торговельної марки або ліцензії |
| `maxFee(String amount, String currency)` | — | `fee` — обмеження, коли зміна тарифікується |
| `addDsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS.add` | `secDNS["add"]["dsData"]`, накопичує |
| `remDsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS.rem` | `secDNS["rem"]["dsData"]`, накопичує |
| `addKeyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS.add` | `secDNS["add"]["keyData"]`, накопичує — **альтернатива** для `addDsRecord` у цьому блоці |
| `remKeyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS.rem` | `secDNS["rem"]["keyData"]`, накопичує — **альтернатива** для `remDsRecord` у цьому блоці |
| `removeAllDnssec()` | `secDNS.rem` | `secDNS["remAll"]` у `true` — зняти підпис із домену цілком |
| `maxSigLife(int seconds)` | `secDNS.chg` | `secDNS["maxSigLife"]`, замінює |
| `send(): Response` | — | викликає `domain().update(name, options)` |

### Зміна делегування

```java
Response r = client.domain().updateBuilder("example.com.ua")
        .addNameserver("ns3.acme.example")
        .remNameserver("ns2.acme.example")
        .addStatus("clientTransferProhibited")
        .remStatus("clientHold")
        .changeRegistrant("C-0009")
        .send();

System.out.println(r.code() + " " + r.message());   // 1000, or 1001 when the registry queues it

// An update answers with a result, not the object. Re-read the new state if you store it:
Response after = client.domain().info("example.com.ua");
System.out.println(String.join(", ", after.nameservers()));
```

Статуси, які ви можете ставити, — це родина `client*`. Ті, що `server*`, належать реєстру, і спроба
їх торкнутися повертається кодом `2304`.

`changeRegistrant()` — це зміна власника, яку багато реєстрів вважають окремою процедурою з власним
паперовим оформленням: відмова там зазвичай є політикою, а не некоректною командою.

### Відкликання витеклого коду трансферу

```java
// The code went somewhere it should not have:
client.domain().updateBuilder("example.com.ua").clearAuthInfo().send();

// Later, when the customer needs a code again:
client.domain().updateBuilder("example.com.ua").changeAuthInfo("Fresh-D0main-Pw").send();
```

`clearAuthInfo()` надсилає `<domain:authInfo><domain:null/></domain:authInfo>`, що **прибирає**
секрет. Це не те саме, що поставити порожній: порожній пароль — це все ще значення, яке власник може
пред'явити, тож домен лишився б рівно настільки ж рухомим, як був. Ці два взаємно виключні — схема не
має способу виразити обидва — тож прохання про обидва кидає `ValidationException` ще до того, як щось
буде надіслано.

### DNSSEC під час update

```java
// Rolling a key with no window in which the domain is unsigned:
client.domain().updateBuilder("example.com.ua")
        .remDsRecord(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6")
        .addDsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .send();

// Зняти підпис узагалі:
client.domain().updateBuilder("example.com.ua").removeAllDnssec().send();

// Замінити весь набір ключів однією операцією:
client.domain().updateBuilder("example.com.ua")
        .removeAllDnssec()
        .addDsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .send();
```

Запис, названий у `remDsRecord()`, має збігатися з тим, що тримає реєстр, у **кожному** полі, а не
лише за міткою ключа.

`removeAllDnssec()` і `remDsRecord()`/`remKeyRecord()` взаємно виключні: протокол не вміє виразити
«прибрати все і ще прибрати оце», і кадр, що несе обидва, відхиляється. Білдер відмовляє в такому
поєднанні сам, у якому порядку його не пиши, і повідомленням називає, які саме два кроки конфліктують.

### Відновлення через update-білдер

```java
client.domain().updateBuilder("example.com.ua")
        .restore()
        .maxFee("1000.00", "UAH")       // your cap, not a published price
        .send();
```

Ідентично [`domain().restore("example.com.ua", "1000.00")`](domains.md#restore). Не надсилайте разом
із відновленням власної зміни — застосуйте її потім, окремою командою, — але й кадр не є без
блоків: RFC 3915 §4.2.5 вимагає порожнього `<domain:add>`, `<domain:rem>` або `<domain:chg>` в
оновленні, яке несе це розширення, і бібліотека виводить `<domain:chg/>` за вас. Див.
[restore](domains.md#restore).

---

## ContactCreateBuilder

```java
public ContactCreateBuilder createBuilder(String contactId, String email)
```

Надсилає `contact:create` (RFC 5733 §3.2.1).

**Ідентифікатор і пошта — аргументи фабричного методу, а не кроки**, бо реєстр вимагає обох. Білдер,
який дозволяє забути обов'язкове поле, переносить помилку з вашого компілятора на провід.

Передайте `com.epptools.sdk.command.Contact.AUTO_ID` як ідентифікатор, щоб реєстр
[сам згенерував хендл](contacts.md#дозволити-реєстру-обрати-ідентифікатор), і прочитайте його назад
через `objectName()`.

| Крок | Встановлює | Накопичує? |
|---|---|---|
| `internationalAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` | `postalInfos` із `type` у `"int"` | накопичує |
| `localizedAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` | `postalInfos` із `type` у `"loc"` | накопичує |
| `voice(String number)` | `voice` — форма EPP `+CC.NNNNNNNNN`, за потреби `x` і додатковий номер | замінює |
| `fax(String number)` | `fax` — та сама форма | замінює |
| `authInfo(String password)` | `authInfo` — секрет трансферу контакту | замінює |
| `publish(String... fields)` | `disclose` із `flag` у `true` — згода публікувати оці | замінює |
| `withhold(String... fields)` | `disclose` із `flag` у `false` — приховати оці | замінює |
| `send(): Response` | викликає `contact().create(contactId, options)` | завершальний |

```java
Response r = client.contact().createBuilder("C-0001", "contact@example.com")
        .internationalAddress(
                "Ivan Petrenko",                    // name
                "Kyiv",                             // city
                "UA",                               // countryCode
                Arrays.asList("1 Khreschatyk St"),  // street
                "ACME LLC",                         // org
                null,                               // stateProvince
                "01001")                            // postalCode
        .localizedAddress(
                "Іван Петренко",
                "Київ",
                "UA",
                Arrays.asList("вул. Хрещатик 1"),
                "ТОВ «АКМЕ»",
                null,
                "01001")
        .voice("+380.441234567")
        .authInfo("C0nt@ct-Pw")
        .withhold("voice", "email")
        .send();

System.out.println(r.objectName());      // "C-0001" — the ID
```

Java не має іменованих аргументів, тож довга форма цих двох кроків — це сім позиційних значень у
фіксованому порядку: `name`, `city`, `countryCode`, `street`, `org`, `stateProvince`, `postalCode`.
Те, чого ви не задаєте, передавайте як `null`; коротша форма з трьох аргументів робить те саме, що
`null` у всіх чотирьох останніх. Коментар на кожному рядку, як у прикладі вище, вартий свого місця:
`internationalAddress("ACME", "Kyiv", "UA", null, "ACME LLC", null, "01001")` — це ряд значень, сенс
яких доводиться відлічувати. Там, де в грі більше одного з них, локальна змінна на аргумент читається
краще за ряд літералів:

```java
List<String> street = Arrays.asList("1 Khreschatyk St");
String org = "ACME LLC";
String stateProvince = null;
String postalCode = "01001";

client.contact().createBuilder("C-0001", "contact@example.com")
        .internationalAddress("Ivan Petrenko", "Kyiv", "UA", street, org, stateProvince, postalCode);
```

Потрібна щонайменше одна поштова форма. Давайте `internationalAddress()`, якщо у вас немає причини
вчинити інакше: це та форма, що переживає друк, пересилання поштою і читання системою, яка не знає
кирилиці, а кирилиця всередині блоку `int` відхиляється кодом `2005`. Локалізована форма є
додатковою, а не альтернативною — надсилайте обидві, коли маєте обидві, і нічого не буде втрачено.

### publish і withhold

Розкриття даних за RFC 5733. Назви полів — `name`, `org`, `addr`, `voice`, `fax` і `email`; будь-що
інше кидає `ValidationException`, називаючи ці шість.

```java
client.contact().createBuilder("C-0001", "contact@example.com")
        .withhold("voice", "email");     // ці приховано; до всього іншого діє протилежне

client.contact().createBuilder("C-0002", "contact@example.com")
        .publish("name", "org");         // ці можна публікувати; усе інше приховано
```

**Це два способи сказати одне й те саме, і другий виклик замінює перший.** Оберіть той, що збігається
з тим, як ви думаєте про це налаштування, і не викликайте обидва: прапорець і є весь сенс списку, тож
блок, зібраний з двох половин, скаже те, чого не мав на увазі жоден із викликів.

`name`, `org` і `addr` існують по одному на кожну поштову форму, тож згадка будь-якого з них покриває
**обидві** форми. Приховати лише форму ASCII, лишивши локальну публічною, було б налаштуванням
приватності, яке читається як застосоване і таким не є.

---

## ContactUpdateBuilder

```java
public ContactUpdateBuilder updateBuilder(String contactId)
```

Надсилає `contact:update` (RFC 5733 §3.2.5). Те, чого ви не згадали, лишається недоторканим.

| Крок | Блок | Встановлює |
|---|---|---|
| `changeInternationalAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` | `chg` | `chg["postalInfos"]` із `type` у `"int"` — лише ті аргументи, які ви передали |
| `changeLocalizedAddress(…ті самі параметри…)` | `chg` | `chg["postalInfos"]` із `type` у `"loc"` |
| `changeVoice(String number)` | `chg` | `chg["voice"]` |
| `changeFax(String number)` | `chg` | `chg["fax"]` |
| `changeEmail(String email)` | `chg` | `chg["email"]` |
| `changeAuthInfo(String password)` | `chg` | `chg["authInfo"]` — замінити секрет трансферу |
| `publish(String... fields)` | `chg` | `chg["disclose"]` із `flag` у `true` |
| `withhold(String... fields)` | `chg` | `chg["disclose"]` із `flag` у `false` |
| `addStatus(String... statuses)` | `add` | `addStatuses`, накопичує |
| `remStatus(String... statuses)` | `rem` | `remStatuses`, накопичує |
| `send(): Response` | — | викликає `contact().update(contactId, options)` |

```java
client.contact().updateBuilder("C-0001")
        .changeEmail("new-contact@example.com")
        .changeVoice("+380.441234500")
        .addStatus("clientUpdateProhibited")
        .send();
```

### Адресу ЗАМІЩУЮТЬ цілком, а не зливають по полях

Блок, який ви передаєте, **заміщає** той, що тримає реєстр. Їх не зливають поле за полем, тож усе,
чого ви не подали, зникає:

| Що ви пишете | Що відбувається |
|---|---|
| передати значення | поле встановлюється в нього |
| передати `""` | поле **очищується** — так прибирають `org`, `stateProvince` чи `postalCode` |
| передати `null` | поле не надсилається — і реєстр видаляє те, що тримав |

RFC 5733 можна прочитати як «не подавайте — і реєстр збереже своє значення», бо кожна складова
`chgPostalInfoType` необов’язкова, але це читання небезпечне. Проти реєстру, який заміщає блок — і
відповідає при цьому **1000**, — повний блок, надісланий без `org`, повертається вже без
організації. Блок, у якому був самий лише `org`, лишив би контакт узагалі без поштової
адреси: без імені, вулиці, міста, індексу та країни. Ця бібліотека відхиляє другий варіант до
відправки — саме тому це відмова, яку можна прочитати, а не 1000, якого прочитати не можна.

Саме тому `name`, `city` і `countryCode` обов'язкові в кожній зміні адреси, і білдер без них
відмовляє. Вони тримають кадр валідним, але не здатні повернути поле, якого ви не подали.
**Спершу прочитайте блок і поверніть його разом зі своєю зміною:**

```java
Map<String, Object> current = client.contact().info("C-0001").postalInfo().get("int");

@SuppressWarnings("unchecked")
List<String> street = (List<String>) current.get("street");

// Перенесіть контакт до Львова і зітріть організацію, залишивши все інше як було.
client.contact().updateBuilder("C-0001")
        .changeInternationalAddress((String) current.get("name"), "Lviv", "UA",
                street, "", (String) current.get("sp"), (String) current.get("pc"))
        .send();
```

Форма, якої ви не згадали — локальна чи міжнародна, — лишається недоторканою: вони адресуються
окремо.

### Тут немає clearAuthInfo()

RFC 5731 дає доменові форму, що допускає порожнє значення, — `<domain:authInfo><domain:null/>`; RFC
5733 не визначає рівноцінної для контакту. Тож секрет трансферу контакту можна **замінити, але не
прибрати**. Не тягніться до порожнього пароля як до заміни: порожнє значення — це все ще значення,
яке власник може пред'явити. Ставте натомість свіжий секрет через `changeAuthInfo()`.

---

## HostUpdateBuilder

```java
public HostUpdateBuilder updateBuilder(String name)
```

Надсилає `host:update` (RFC 5732 §3.2.5).

| Крок | Блок | Встановлює |
|---|---|---|
| `addAddress(String ip)` | `add` | `addAddresses` — одна glue-адреса, накопичує |
| `addAddresses(String... ips)` | `add` | `addAddresses` — кілька, накопичує |
| `remAddress(String ip)` | `rem` | `remAddresses`, накопичує |
| `remAddresses(String... ips)` | `rem` | `remAddresses`, накопичує |
| `addStatus(String... statuses)` | `add` | `addStatuses`, накопичує |
| `remStatus(String... statuses)` | `rem` | `remStatuses`, накопичує |
| `send(): Response` | — | викликає `host().update(name, options)` |

```java
client.host().updateBuilder("ns1.example.com.ua")
        .addAddresses("192.0.2.10", "2001:db8::10")
        .remAddress("192.0.2.9")
        .send();
```

IPv4 і IPv6 розрізняються за самим записом, тож `v4` і `v6` підписуються правильно без того, щоб ви
казали, де що.

**Кроку перейменування немає.** Не тому, що його немає в протоколі — RFC 5732 визначає
`host:chg`, — а тому, що реєстри, проти яких це будувалося, відкидають його, тож
перейменування тут звітувало би про успіх, якого не сталося. Див.
[Хости](hosts.md#перейменування-не-існує), де описані три команди, які роблять цю роботу
натомість, і запитайте в свого реєстру, чи реалізує він `host:chg` узагалі.

Додавання і прибирання тієї самої адреси в одній команді — це суперечність, яку реєстр розв'язує так,
як сам вирішить. Надсилайте одне або друге.

---

## Чого білдер не змінює

Білдер — це фасад над картою опцій, тож усе, чому підпорядкована карта, лишається чинним:

- **Та сама валідація.** `send()` викликає звичайний метод, який перевіряє свої опції рівно так, як
  зробив би це для написаної руками карти. Білдер не здатен породити невідомий ключ, але здатен
  породити поєднання, від якого команда відмовиться.
- **Ті самі коди відповіді.** `2302`, `2104`, `1001` означають те, що означають; див.
  [Помилки](errors.md).
- **Та сама поведінка `throwOnFailure`.** З вимкненим киданням `send()` повертає відмову як
  `Response`, а не піднімає виняток.
- **Те саме поводження з секретами.** `authInfo()` встановлює живий секрет доступу. У власних журналах
  бібліотеки він маскується — але `toOptions()` це вже ваша карта, і якщо ви пишете її в журнал чи
  кладете в чергу, вона несе пароль відкритим текстом. Маскуйте його самі, перш ніж він дійде до
  журналу.

## Які кроки кидають ще до відправки

Усе це — `ValidationException`, і в кожному випадку жодного кадру не було збудовано:

| Крок | Кидає, коли |
|---|---|
| будь-який `contact(…)` / `addContact(…)` / `remContact(…)` | роль порожня або складається з пробілів; або, на `send()`, вона не є однією з admin, billing, tech |
| `nameserverWithGlue(…)` | ім'я сервера імен порожнє |
| `dsRecord(…)`, `dsRecordWithKey(…)`, `addDsRecord(…)`, `remDsRecord(…)` | дайджест порожній або складається з пробілів |
| `keyRecord(…)`, `addKeyRecord(…)`, `remKeyRecord(…)`, `dsRecordWithKey(…)` | публічний ключ порожній або складається з пробілів |
| `removeAllDnssec()` після `remDsRecord()`/`remKeyRecord()`, або будь-який із них після нього | ці два взаємно виключні |
| `dsRecord(…)`/`addDsRecord(…)`/`remDsRecord(…)` разом із `keyRecord(…)`/`addKeyRecord(…)`/`remKeyRecord(…)` в одному блоці | RFC 5910 робить `dsData` і `keyData` вибором; вкладіть ключ у запис через `dsRecordWithKey(…)` |
| `maxSigLife(…)` | тривалість менша за 1 секунду — `secDNS-1.1.xsd` задає minInclusive 1 |
| `maxFee(…)` | сума не є звичайним десятковим числом на кшталт `100.00` |
| `publish(…)` / `withhold(…)` | поле не є одним із name, org, addr, voice, fax, email |
| `send()` | білдер уже було надіслано |

Некоректна згода на ціну перевіряється тут, а не на проводі, бо інакше вона тягне голий `2001`, який
не називає жодного поля, — і приходить уже після того, як команду спробували виконати.

---

## Коли карта є кращим інструментом

Білдери й карти — це одне й те саме, тож користуйтеся тим, що пасує:

- Збірка з конфігураційного файла, рядка бази даних чи корисного навантаження з черги, яке вже є
  картою: передавайте її прямо в `create()`/`update()`. Перетворювати її на ланцюжок викликів
  лише для того, щоб білдер перетворив її назад на карту, не додає нічого.
- Написання команди прямо в коді, особливо оновлення: беріть білдер. `.remStatus("clientHold")` каже,
  у який блок потрапляє зміна, у місці, де помилитися неможливо.

`toOptions()` — місток між цими двома, і він працює в обидва боки: збирайте плинним API, зберігайте
карту, програвайте її потім прямим викликом.

---

Див. також: [Домени](domains.md) · [Контакти](contacts.md) · [Хости](hosts.md) ·
[Баланс і ціни](balance.md) · [Відповіді](responses.md) · [Помилки](errors.md)

[← Зміст посібника](README.md)
