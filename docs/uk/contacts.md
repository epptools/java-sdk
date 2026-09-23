# Контакти

Об'єкти контактів відповідають **RFC 5733**. Контакт — це людина або організація з адресою, номером
телефону та e-mail; [домен](domains.md) посилається на контакти за ідентифікатором — для реєстранта
і для кожної ролі, якої вимагає зона. Тому контакти йдуть першими: створіть їх, збережіть їхні
ідентифікатори, а потім реєструйте на них домени.

Кожна контактна команда доступна через `client.contact()` і повертає
[`Response`](responses.md). Усе тут припускає підключений клієнт із виконаним входом — див.
[Сесія](session.md).

## Методи

| Метод | Команда EPP |
|---|---|
| `check(List<String> ids): Response` | `<check>` |
| `info(String contactId, String authInfo): Response` | `<info>` |
| `create(String contactId, Map<String, Object> options): Response` | `<create>` |
| `createAuto(Map<String, Object> options): Response` | `<create>` із зарезервованим ідентифікатором |
| `createBuilder(String contactId, String email): ContactCreateBuilder` | будує `<create>` |
| `update(String contactId, Map<String, Object> options): Response` | `<update>` |
| `updateBuilder(String contactId): ContactUpdateBuilder` | будує `<update>` |
| `delete(String contactId): Response` | `<delete>` |
| `transfer(String op, String contactId, String authInfo): Response` | `<transfer op="…">` |

Одна константа: `Contact.AUTO_ID` — зарезервований ідентифікатор, яким ви просите реєстр
[згенерувати ідентифікатор](#дозволити-реєстру-обрати-ідентифікатор).

`create()` та `update()` приймають карту опцій, і **ключ опції, якого ця бібліотека не розуміє,
відхиляється з `ValidationException` ще до того, як буде побудовано хоч один кадр**, із назвою
найближчого відомого їй ключа. Мовчки проігнорований ключ надіслав би команду, на яку реєстр
відповідає `1000`, — без тієї частини, яку ви просили.

---

## check

```java
public Response check(List<String> ids)
```

**У каналі передачі:** `<command><check><contact:check><contact:id>…` — RFC 5733 §3.1.1. Це питання
про те, чи вільний **ідентифікатор**, а не про те, чи існує людина.

```java
Response r = client.contact().check(Arrays.asList("acme-01", "acme-02"));

r.availability();               // {acme-01=false, acme-02=true}
r.isAvailable("acme-02");       // true | false | null ("the answer said nothing")
r.unavailableReason("acme-01"); // "In use", or null when the id is free
```

Ідентифікатори контактів — це спільний простір імен на весь реєстр, тож власна схема з префіксом,
який контролюєте ви, варта більшого, ніж цикл «check, потім create». Якщо схеми у вас немає,
[дозвольте реєстру згенерувати ідентифікатор](#дозволити-реєстру-обрати-ідентифікатор) — і питання
колізій зникне взагалі.

**Коди відповіді:** `1000` на будь-який коректно сформований check; `2005` називає синтаксично
недійсний ідентифікатор. **Порожній список** теж відхиляється, із
`ValidationException`: кадр, який зібрався би, не має жодного нащадка, а схема вимагає хоч одного —
тож цикл за рядком запиту або кошиком, який виявився порожнім, падає тут, поіменно, а не
витрачає звернення до сервера.

---

## info

```java
public Response info(String contactId, String authInfo)
```

**У каналі передачі:** `<command><info><contact:info><contact:id>` — RFC 5733 §3.1.2. Передайте
`authInfo` — і він піде як `<contact:authInfo><contact:pw>`; саме так реєстратор, який не є
власником контакту, читає повний запис.

```java
Response c = client.contact().info("acme-01");

c.objectName();     // "acme-01" — the ID, not the person's name
c.roid();           // the registry's own identifier for the object
c.statuses();       // ["linked"], ["ok"], ["clientUpdateProhibited"], …
c.sponsor();        // clID
c.createdBy();      // crID
c.createdDate();    // crDate
c.updatedBy();      // upID, or null
c.updatedDate();    // upDate
c.authInfo();       // the transfer secret — never log it

c.email();          // "contact@example.com"
c.voice();          // "+380.441234567" — EPP's +CC.NNNN form
c.fax();            // the same, or null

c.postalInfo();     // {int={…}, loc={…}}
c.disclose();       // {flag=false, elements=[email, voice]} or null
```

`objectName()` дає ідентифікатор. Щоб прочитати ім'я людини, треба зайти в поштовий блок:

```java
Map<String, Map<String, Object>> postal = client.contact().info("acme-01").postalInfo();

postal.get("int").get("name");      // "ACME LLC"
postal.get("int").get("street");    // [1 Khreschatyk St] — a List, up to 3 lines
postal.get("int").get("city");      // "Kyiv"
postal.get("int").get("cc");        // "UA"

// Форма місцевим письмом, коли контакт її несе.
Object localName = postal.containsKey("loc") ? postal.get("loc").get("name") : null;
```

Кожен запис містить `name`, `org`, `street` (список), `city`, `sp`, `pc`, `cc`, а відсутні частини —
як `""`. Контакт може нести форму `int`, форму `loc` або обидві — читайте обачно.

`disclose()` повертає `null`, коли контакт не висловлює жодних побажань і діє сама лише політика
реєстру. Коли він є, значення списку визначає `flag`: `true` каже, що перелічені елементи можна
публікувати, `false` — що їх треба приховати, а все, чого в переліку **немає**, отримує протилежне.
Без прапорця список не має сенсу, тож ніколи не читайте одне без іншого.

**Коди відповіді:** `1000`; `2202` (неправильний `authInfo` як у не-власника); `2303` (такого
ідентифікатора немає).

---

## create

```java
public Response create(String contactId, Map<String, Object> options)
```

**У каналі передачі:** `<command><create><contact:create>` — RFC 5733 §3.2.1.

### Кожна опція

| ключ | значення | канал передачі |
|---|---|---|
| `type` | `"int"` (типово) або `"loc"` | атрибут `type` єдиного плаского блоку |
| `name` | рядок | `<contact:name>` |
| `org` | рядок | `<contact:org>` — надсилається, лише коли непорожній |
| `street` | `List<String>`, до 3 рядків | один `<contact:street>` на рядок |
| `city` | рядок | `<contact:city>` |
| `sp` | рядок | `<contact:sp>` — надсилається, лише коли непорожній |
| `pc` | рядок | `<contact:pc>` — надсилається, лише коли непорожній |
| `cc` | 2-літерний код країни | `<contact:cc>` |
| `postalInfos` | список блоків, кожен за формою пласких ключів вище | один `<contact:postalInfo>` на запис |
| `voice` | `+CC.NNNN` | `<contact:voice>` — надсилається, лише коли непорожній |
| `fax` | `+CC.NNNN` | `<contact:fax>` — надсилається, лише коли непорожній |
| `email` | рядок | `<contact:email>` — **обов'язковий** |
| `authInfo` | рядок | `<contact:authInfo><contact:pw>` |
| `disclose` | див. [блок disclose](#блок-disclose) | `<contact:disclose flag="0\|1">` |

`email` вимагає RFC 5733, і порожнє значення викликає тут `ValidationException`, а не їде в реєстр
порожнім елементом, щоб повернутися непрозорим `2005`.

`<contact:authInfo>` виходить завжди. Задайте опцію `authInfo` — і в ньому поїде ваше значення;
пропустіть її — і замість нього піде порожній `<contact:pw/>`, що передає вибір власній політиці
реєстру.

### Дві поштові форми

Пласкі ключі будують **один** блок. Передайте натомість `postalInfos`, щоб надіслати обидві форми
однією командою:

```java
// int: ASCII / Latin only. This is the form the registry may show any party, so at least one
// of them is needed. Cyrillic here is refused with 2005.
Map<String, Object> international = new LinkedHashMap<String, Object>();
international.put("type", "int");
international.put("name", "Ivan Petrenko");
international.put("org", "ACME LLC");
international.put("street", Arrays.asList("1 Khreschatyk St"));
international.put("city", "Kyiv");
international.put("pc", "01001");
international.put("cc", "UA");

// loc: the local script, as the registrant actually wrote it.
Map<String, Object> localized = new LinkedHashMap<String, Object>();
localized.put("type", "loc");
localized.put("name", "Іван Петренко");
localized.put("org", "ТОВ «АКМЕ»");
localized.put("street", Arrays.asList("вул. Хрещатик 1"));
localized.put("city", "Київ");
localized.put("pc", "01001");
localized.put("cc", "UA");

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("postalInfos", Arrays.asList(international, localized));
options.put("voice", "+380.441234567");
options.put("email", "contact@example.com");
options.put("authInfo", "C0nt@ct-Pw");

Response r = client.contact().create("acme-01", options);

System.out.println(r.objectName() + " created " + r.createdDate());   // "acme-01"
```

Надсилайте обидві форми щоразу, коли маєте обидві. Нічого не відкидається, і `info()` повертає все,
що ви надіслали.

Коротка форма — для контакту, у якого лише ASCII:

```java
Map<String, Object> simple = new LinkedHashMap<String, Object>();
simple.put("name", "ACME LLC");
simple.put("city", "Kyiv");
simple.put("cc", "UA");
simple.put("email", "contact@example.com");
client.contact().create("acme-02", simple);
```

`type` типово дорівнює `int`, і це те, що потрібно для адреси латиницею. Ставте `"type"` у `"loc"`,
коли єдиний блок, який ви надсилаєте, написано локальним письмом.

### Блок disclose
Побажання щодо приватності з RFC 5733. `name`, `org` та `addr` задаються для кожної форми окремо,
тож беруть список форм, до яких застосовуються — `int`, `loc` або обидві, і більше нічого, — а
`voice`, `fax` та `email` — прості прапорці. Ім’я поля поза цими шістьма або форма поза цими
двома — це `ValidationException`, яке називає прийнятний набір: помилка тут — це вказівка щодо
приватності, яка читається як застосована і не є нею, бо кадр виходить як
`<contact:disclose flag="0"/>` без нащадків, а реєстр відповідає 1000.

```java
Map<String, Object> disclose = new LinkedHashMap<String, Object>();
disclose.put("flag", false);                        // false: hide what is listed. true: consent to publish.
disclose.put("addr", Arrays.asList("int"));         // the international address block
disclose.put("name", Arrays.asList("int", "loc"));
disclose.put("voice", true);
disclose.put("email", true);
options.put("disclose", disclose);
```

Прапорець і є всім значенням списку. `flag=false` разом із `email=true` **приховує** адресу
e-mail; `flag=true` разом із `email=true` дає згоду на її публікацію. Елементи, яких ви не
перелічили, отримують протилежне до прапорця, тож блок із прапорцем і без нічого теж дещо каже.

**Коди відповіді:** `1000`; `2003` (немає поштового блоку або немає e-mail); `2005` (поганий
синтаксис — некоректний e-mail або кирилиця в блоці `int`); `2302` (ідентифікатор зайнято); `2306`
(політика, напр. `authInfo`, слабший за правило надійності зони).

---

## Дозволити реєстру обрати ідентифікатор
```java
public Response createAuto(Map<String, Object> options)
public static final String AUTO_ID = "autonic"
```

`createAuto()` надсилає замість ідентифікатора зарезервоване значення `autonic`, і реєстр генерує
ідентифікатор за вас. Відповідь — **єдине** місце, де згенерований ідентифікатор з'являється, тож
збережіть те, що дає `objectName()`:

```java
Map<String, Object> details = new LinkedHashMap<String, Object>();
details.put("name", "ACME LLC");
details.put("city", "Kyiv");
details.put("cc", "UA");
details.put("email", "contact@example.com");

// e.g. "c-9f4b2ad10e" — store it before doing anything else
String handle = client.contact().createAuto(details).objectName();

Map<String, Object> registration = new LinkedHashMap<String, Object>();
registration.put("years", 1);
registration.put("registrant", handle);
client.domain().create("example.com.ua", registration);
```

Користуйтеся ним, коли у вас немає власної схеми найменування або коли інакше довелося б крутити
повтори навколо `2302`, бо ідентифікатор першим зайняв хтось інший. **Кожен виклик генерує новий
ідентифікатор**, тож повторення — це другий контакт, а не колізія; а отже, повтор після незрозумілої
невдачі створює дублікат. Якщо виклик `createAuto()` завершився невідомим результатом, звірте свої
записи, перш ніж викликати його ще раз.

`Contact.AUTO_ID` — це константа за ним, і передача її в `create()` чи `createBuilder()` робить те
саме:

```java
Map<String, Object> details = new LinkedHashMap<String, Object>();
details.put("name", "ACME LLC");
details.put("city", "Kyiv");
details.put("cc", "UA");
details.put("email", "contact@example.com");
client.contact().create(Contact.AUTO_ID, details);
```

Зарезервоване значення ніколи не зберігається як ідентифікатор, тож лишається доступним для всіх.

---

## update

```java
public Response update(String contactId, Map<String, Object> options)
```

**У каналі передачі:** `<command><update><contact:update>` — RFC 5733 §3.2.5.

| ключ | значення |
|---|---|
| `chg` | карта з ключами `postalInfo`, `postalInfos`, `voice`, `fax`, `email`, `authInfo`, `disclose` |
| `addStatuses` | `List<String>` — клієнтські статуси, які треба встановити |
| `remStatuses` | `List<String>` — клієнтські статуси, які треба зняти |

```java
Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("email", "new-contact@example.com");
chg.put("voice", "+380.441234500");

Map<String, Object> update = new LinkedHashMap<String, Object>();
update.put("chg", chg);
update.put("addStatuses", Arrays.asList("clientUpdateProhibited"));
client.contact().update("acme-01", update);
```

Усі статуси з `addStatuses` потрапляють в один блок `<contact:add>`, а всі з `remStatuses` — в один
`<contact:rem>`: саме це дозволяє схема RFC 5733 — по одному блоку кожного виду, до семи статусів
у ньому.

### Правило оновлення: поштовий блок ЗАМІЩУЄТЬСЯ, а не зливається

Надішліть поштовий блок у `chg` — і реєстр **замістить** ним той, що тримає. Їх не зливають поле за
полем, тож усе, чого ви не подали, зникає.

RFC 5733 можна прочитати й інакше: у `chgPostalInfoType` кожна зі складових — name, org, addr —
необов'язкова, і це схоже на «не подавайте її, і реєстр збереже те, що має». Це читання небезпечне:
проти реєстру, який заміщає блок, перші дві з цих команд відповідають **1000**, а третя ніколи не
покидає цю бібліотеку:

| що ніс `chg` | що лишилося в контакті |
|---|---|
| повний блок із `org` у `""` | організацію прибрано, адреса недоторкана |
| повний блок без ключа `org` | організацію **також прибрано** |
| самий лише `org` у `""` | відхиляється тут, із `ValidationException` — на дроті це лишило би **поштового блоку взагалі немає**: ані імені, ані вулиці, міста, індексу чи країни |
Тобто змінити одне поле адреси неможливо, і збій мовчазний: команда успішна, а даних немає. `name`,
`city` і `cc` обов'язкові в кожній поштовій зміні, і ця бібліотека без них відмовляє, — але ця
перевірка лише тримає кадр валідним і не здатна повернути `org`, `sp` чи `pc`, якого ви не подали.

**Прочитайте блок, застосуйте свою зміну і надішліть його цілим:**

```java
Map<String, Object> current = client.contact().info("acme-01").postalInfo().get("int");

// Change the city and clear the org, leaving everything else exactly as it was.
Map<String, Object> block = new LinkedHashMap<String, Object>(current);
block.put("type", "int");
block.put("city", "Lviv");
block.put("org", "");

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("postalInfo", block);
client.contact().update("acme-01", Collections.<String, Object>singletonMap("chg", chg));
```

Чого заміщення не досягає — то це другої поштової форми: `int` і `loc` адресуються окремо, тож
заміна однієї лишає другу точно такою, як була.

Усередині блоку, який ви надсилаєте, порожній рядок так само очищає необов'язкове поле:

| що ви пишете | що відбувається |
|---|---|
| ключ містить значення | поле встановлюється в це значення |
| ключ містить `""` | поле **очищується** — так прибирають `org`, `sp` чи `pc` |
| ключа немає | поле не надсилається — і реєстр видаляє те, що тримав |

Змінюйте обидві форми однією командою через `postalInfos`:

```java
Map<String, Object> international = new LinkedHashMap<String, Object>();
international.put("type", "int");
international.put("name", "ACME LLC");
international.put("city", "Lviv");
international.put("cc", "UA");

Map<String, Object> localized = new LinkedHashMap<String, Object>();
localized.put("type", "loc");
localized.put("name", "ТОВ «АКМЕ»");
localized.put("city", "Львів");
localized.put("cc", "UA");

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("postalInfos", Arrays.asList(international, localized));
client.contact().update("acme-01", Collections.<String, Object>singletonMap("chg", chg));
```

Форму, якої ви не згадали, ніхто не чіпає.

### Зміна коду трансферу

```java
Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("authInfo", "Fresh-C0nt@ct-Pw");
client.contact().update("acme-01", Collections.<String, Object>singletonMap("chg", chg));
```

Код трансферу контакту можна **замінити, але не видалити**: RFC 5731 дає домену форму з `null`, а
RFC 5733 нічого рівнозначного для контакту не визначає. Не хапайтеся за порожній пароль як за
заміну — порожнє значення лишається значенням, яке його власник може пред'явити. Задайте натомість
новий код.

### Зміна розкриття

```java
Map<String, Object> disclose = new LinkedHashMap<String, Object>();
disclose.put("flag", false);
disclose.put("email", true);
disclose.put("voice", true);

Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("disclose", disclose);
client.contact().update("acme-01", Collections.<String, Object>singletonMap("chg", chg));
```

Блок надсилається цілком, тож щоразу вказуйте повне побажання, а не різницю з попереднім.

**Коди відповіді:** `1000`; `2303` (такого ідентифікатора немає); `2304` (статус забороняє); `2306`
(політика); `2308` (зміна прибрала б те, чого реєстр вимагає).

### Побудова update крок за кроком

```java
public ContactCreateBuilder createBuilder(String contactId, String email)
public ContactUpdateBuilder updateBuilder(String contactId)
```

Ідентифікатор та e-mail є аргументами `createBuilder()`, а не кроками, бо реєстр вимагає обох, а
крок — це те, що можна забути. У білдері оновлення те саме правило наявності виглядає як «передайте
аргумент, щоб змінити, передайте `""`, щоб очистити, не викликайте крок, щоб не чіпати». Кожен крок
описано в [Білдери](builders.md).

---

## delete

```java
public Response delete(String contactId)
```

**У каналі передачі:** `<command><delete><contact:delete>` — RFC 5733 §3.2.2.

Контакт, на який усе ще посилається домен, видалити неможливо — реєстр відповідає **`2305`**. Статус
`linked` у `statuses()` — це те, як реєстр каже про це заздалегідь:

```java
Response c = client.contact().info("acme-01");

if (c.statuses().contains("linked")) {
    // Still in use. Re-point the domains that reference it first — allContacts() on a
    // domain:info says which handles a domain holds.
    return;
}

client.contact().delete("acme-01");
```

**Коди відповіді:** `1000`; `2303`; `2305` (усе ще пов'язаний з доменом).

---

## transfer

```java
public Response transfer(String op, String contactId, String authInfo)
```

**У каналі передачі:** `<command><transfer op="…"><contact:transfer>` — RFC 5733 §3.2.4 (і §3.1.3
для `query`). `op` — це одне з `request`, `query`, `approve`, `reject`, `cancel`, із тими самими
значеннями, що й у [трансфері домену](domains.md#transfer): `request` і `cancel` належать
реєстратору, що приймає, а `approve` та `reject` — поточному реєстратору-власнику.

```java
Response r = client.contact().transfer("request", "acme-01", "the-code");

r.code();             // 1000, or 1001 when the sponsoring registrar has to answer
r.transferStatus();   // "pending"
r.transfer();         // {status, requestedBy, requestedAt, actingClient, actBy, expiryDate}
```

Запит надходить до реєстратора-власника як [poll-сповіщення](poll.md) з `trnData`. Як власник:

```java
client.contact().transfer("approve", "acme-01");
// or
client.contact().transfer("reject", "acme-01");
```

`query` повідомляє, на якій стадії запит: `2300`, поки він очікує, і `2301`, коли не очікує нічого.

**Коди відповіді:** `1000` / `1001`; `2201` (не ваш об'єкт, щоб діяти з ним); `2202` (неправильний
`authInfo`); `2300` (уже очікує); `2301` (нічого не очікує); `2303`; `2304`.

---

## Коди відповіді на цій сторінці

| Код | Значення | Виняток |
|---|---|---|
| `1000` | виконано | — |
| `1001` | прийнято, завершується офлайн; результат надходить через [poll](poll.md) | — |
| `2003` | бракує обов'язкового параметра (поштовий блок, e-mail) | `CommandException` |
| `2005` | значення синтаксично недійсне (e-mail, кирилиця в блоці `int`) | `CommandException` |
| `2201` | не ваш об'єкт, щоб діяти з ним | `AuthorizationException` |
| `2202` | неправильний `authInfo` | `AuthorizationException` |
| `2300` / `2301` | уже очікує трансферу / не очікує трансферу | `CommandException` |
| `2302` | ідентифікатор зайнято | `ObjectExistsException` |
| `2303` | такого ідентифікатора немає | `ObjectDoesNotExistException` |
| `2304` / `2305` | статус забороняє / усе ще пов'язаний з доменом | `ObjectStatusException` |
| `2306` / `2308` | політика реєстру відхиляє це значення | `PolicyException` |

`ResultCode` має іменовану константу для кожного з них; повна таксономія — у
[Помилки](errors.md).

---

Див. також: [Домени](domains.md) · [Хости](hosts.md) · [Poll](poll.md) ·
[Баланс і ціни](balance.md) · [Відповіді](responses.md) · [Білдери](builders.md)

[← Зміст посібника](README.md)
