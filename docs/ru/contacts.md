# Контакты

Объекты контактов следуют **RFC 5733**. Контакт — это человек или организация с адресом, телефоном
и электронной почтой; [домен](domains.md) ссылается на контакты по идентификатору — на регистранта
и на каждую роль, которую требует зона. Поэтому контакты идут первыми: создайте их, сохраните их
идентификаторы и уже потом регистрируйте на них домены.

Каждая контактная команда вызывается через `client.contact()` и возвращает
[`Response`](responses.md). Всё здесь предполагает подключённый клиент с выполненным входом — см.
[Сессия](session.md).

## Методы

| Метод | Команда EPP |
|---|---|
| `check(List<String> ids): Response` | `<check>` |
| `info(String id, String authInfo): Response` | `<info>` |
| `create(String id, Map<String, Object> options): Response` | `<create>` |
| `createAuto(Map<String, Object> options): Response` | `<create>` с зарезервированным идентификатором |
| `createBuilder(String id, String email): ContactCreateBuilder` | строит `<create>` |
| `update(String id, Map<String, Object> options): Response` | `<update>` |
| `updateBuilder(String id): ContactUpdateBuilder` | строит `<update>` |
| `delete(String id): Response` | `<delete>` |
| `transfer(String op, String id, String authInfo): Response` | `<transfer op="…">` |

Одна константа: `Contact.AUTO_ID` — зарезервированное значение, которым вы просите реестр
[сгенерировать идентификатор](#позволить-реестру-выбрать-идентификатор).

`create()` и `update()` принимают карту опций, и **ключ опции, которого эта библиотека не знает,
отклоняется с `ValidationException` ещё до того, как будет построен кадр**, с указанием ближайшего
известного ключа. Молча проигнорированный ключ отправил бы команду, на которую реестр ответит
`1000`, — но без той части, о которой вы просили.

---

## check

```java
public Response check(List<String> ids);
```

**На проводе:** `<command><check><contact:check><contact:id>…` — RFC 5733 §3.1.1. Здесь спрашивают,
свободен ли **идентификатор**, а не существует ли человек.

```java
Response r = client.contact().check(Arrays.asList("acme-01", "acme-02"));

r.availability();                // {acme-01=false, acme-02=true}
r.isAvailable("acme-02");        // TRUE | FALSE | null ("the answer said nothing about it")
r.unavailableReason("acme-01");  // "In use", or null when the id is free
```

Идентификаторы контактов — общее пространство имён на весь реестр, поэтому собственная схема с
префиксом, которым распоряжаетесь вы, стоит больше, чем цикл «проверил — создал». Если схемы нет,
[позвольте реестру выбрать идентификатор](#позволить-реестру-выбрать-идентификатор) и снимите
вопрос коллизий целиком.

**Коды ответа:** `1000` на любую корректно сформированную проверку; `2005` называет синтаксически
некорректный идентификатор. **Пустой список** тоже отклоняется, с
`ValidationException`: у кадра, который собрался бы, нет ни одного дочернего элемента, а схема
требует хотя бы одного — так что цикл по строке запроса или по корзине, которая оказалась
пустой, падает здесь, поимённо, а не тратит обращение к серверу.

---

## info

```java
public Response info(String id, String authInfo);
public Response info(String id);
```

**На проводе:** `<command><info><contact:info><contact:id>` — RFC 5733 §3.1.2. Передайте `authInfo`
— и он уйдёт как `<contact:authInfo><contact:pw>`; именно так полную запись читает регистратор,
который не является владельцем контакта.

```java
Response c = client.contact().info("acme-01");

c.objectName();     // "acme-01" - the ID, not the person's name
c.roid();           // the registry's own object id
c.statuses();       // ["linked"], ["ok"], ["clientUpdateProhibited"], …
c.sponsor();        // clID
c.createdBy();      // crID           c.createdDate();   // crDate
c.updatedBy();      // upID or null   c.updatedDate();   // upDate
c.authInfo();       // the transfer authorisation code - never log it

c.email();          // "contact@example.com"
c.voice();          // "+380.441234567" - the EPP +CC.NNNN form
c.fax();            // the same form, or null

c.postalInfo();     // {int={…}, loc={…}}
c.disclose();       // {flag=false, elements=[email, voice]}, or null
```

`objectName()` даёт идентификатор. Имя человека лежит в почтовом блоке:

```java
Map<String, Map<String, Object>> postal = client.contact().info("acme-01").postalInfo();

postal.get("int").get("name");      // "ACME LLC"
postal.get("int").get("street");    // [1 Khreschatyk St] — список, до 3 строк
postal.get("int").get("city");      // "Kyiv"
postal.get("int").get("cc");        // "UA"

// Форма в местном письме, если контакт её несёт.
Object localName = postal.containsKey("loc") ? postal.get("loc").get("name") : null;
```

Каждая запись содержит `name`, `org`, `street` (список), `city`, `sp`, `pc`, `cc`; отсутствующие
части приходят как `""`. Контакт может нести форму `int`, форму `loc` или обе — обращайтесь к
ключам осмотрительно.

`disclose()` возвращает `null`, когда контакт не выражает никаких предпочтений и действует одна
лишь политика реестра. Когда блок есть, смысл списку задаёт `flag`: `true` говорит, что
перечисленные элементы можно публиковать, `false` — что их следует скрыть, а всё, что в списке
**не** названо, получает противоположное. Список без флага не значит ничего, поэтому никогда не
читайте одно без другого.

**Коды ответа:** `1000`; `2202` (неверный `authInfo` у не-владельца); `2303` (такого идентификатора
нет).

---

## create

```java
public Response create(String id, Map<String, Object> options);
```

**На проводе:** `<command><create><contact:create>` — RFC 5733 §3.2.1.

### Все опции

| ключ | значение | на проводе |
|---|---|---|
| `type` | `"int"` (по умолчанию) или `"loc"` | атрибут `type` единственного плоского блока |
| `name` | строка | `<contact:name>` |
| `org` | строка | `<contact:org>` — отправляется, только если не пусто |
| `street` | `List<String>`, до 3 строк | по одному `<contact:street>` на строку |
| `city` | строка | `<contact:city>` |
| `sp` | строка | `<contact:sp>` — отправляется, только если не пусто |
| `pc` | строка | `<contact:pc>` — отправляется, только если не пусто |
| `cc` | 2-буквенный код страны | `<contact:cc>` |
| `postalInfos` | список блоков, каждый — той же формы, что плоские ключи выше | по одному `<contact:postalInfo>` на запись |
| `voice` | `+CC.NNNN` | `<contact:voice>` — отправляется, только если не пусто |
| `fax` | `+CC.NNNN` | `<contact:fax>` — отправляется, только если не пусто |
| `email` | строка | `<contact:email>` — **обязателен** |
| `authInfo` | строка | `<contact:authInfo><contact:pw>` |
| `disclose` | см. [блок disclose](#блок-disclose) | `<contact:disclose flag="0\|1">` |

`email` обязателен по RFC 5733, и пустое значение поднимает здесь `ValidationException` — вместо
того чтобы уехать в реестр пустым элементом и вернуться невнятным `2005`.

`<contact:authInfo>` отправляется всегда. Укажете опцию `authInfo` — в нём поедет ваше значение; не
укажете — уйдёт пустой `<contact:pw/>`, и выбор останется за политикой реестра.

### Две почтовые формы

Плоские ключи строят **один** блок. Чтобы отправить обе формы одной командой, передайте
`postalInfos`:

```java
Map<String, Object> international = new LinkedHashMap<String, Object>();
// int: ASCII / Latin only. The registry may show this form to any party, so at least one of
// these is needed. Cyrillic here is refused with 2005.
international.put("type", "int");
international.put("name", "Ivan Petrenko");
international.put("org", "ACME LLC");
international.put("street", Arrays.asList("1 Khreschatyk St"));
international.put("city", "Kyiv");
international.put("pc", "01001");
international.put("cc", "UA");

Map<String, Object> localized = new LinkedHashMap<String, Object>();
// loc: the local script, exactly as the registrant wrote it.
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

Присылайте обе формы, когда у вас есть обе. Здесь ничего не отбрасывается, и `info()` возвращает
всё, что вы прислали.

Короткая форма, для контакта с ASCII-адресом:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("name", "ACME LLC");
options.put("city", "Kyiv");
options.put("cc", "UA");
options.put("email", "contact@example.com");

client.contact().create("acme-02", options);
```

`type` по умолчанию равен `int` — то, что нужно для адреса латиницей. Ставьте `"type", "loc"`,
когда единственный блок, который вы отправляете, записан местной письменностью.

### Блок disclose

Предпочтения приватности из RFC 5733. `name`, `org` и `addr` задаются для каждой формы отдельно,
поэтому принимают список форм, к которым относятся — `int`, `loc` или обе, и больше ничего, — а
`voice`, `fax` и `email` — простые флаги. Имя поля помимо этих шести или форма помимо этих
двух — это `ValidationException`, называющее допустимый набор: описка здесь — это указание о
приватности, которое читается как применённое и таковым не является, ведь кадр уходит как
`<contact:disclose flag="0"/>` без дочерних элементов, а реестр отвечает 1000.

```java
Map<String, Object> disclose = new LinkedHashMap<String, Object>();
disclose.put("flag", false);                       // false: hide what is listed. true: consent to publish.
disclose.put("addr", Arrays.asList("int"));        // the international address block
disclose.put("name", Arrays.asList("int", "loc"));
disclose.put("voice", true);
disclose.put("email", true);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("disclose", disclose);
```

Весь смысл списку задаёт флаг. `flag=false` вместе с `email=true` **скрывает** адрес электронной
почты; `flag=true` вместе с `email=true` — это согласие на его публикацию. Элементы, которых в
списке нет, получают значение, противоположное флагу, поэтому блок с флагом и без единого элемента
тоже кое-что говорит.

**Коды ответа:** `1000`; `2003` (нет почтового блока или нет адреса электронной почты); `2005`
(неверный синтаксис — испорченный адрес электронной почты или кириллица в блоке `int`); `2302`
(идентификатор занят); `2306` (политика, например `authInfo` слабее правила стойкости зоны).

---

## Позволить реестру выбрать идентификатор

```java
public Response createAuto(Map<String, Object> options);
public static final String AUTO_ID = "autonic";
```

`createAuto()` отправляет вместо идентификатора зарезервированное значение `autonic`, и реестр
генерирует идентификатор за вас. Ответ — **единственное** место, где сгенерированное значение
появляется, поэтому сохраните то, что даёт `objectName()`:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("name", "ACME LLC");
options.put("city", "Kyiv");
options.put("cc", "UA");
options.put("email", "contact@example.com");

String handle = client.contact().createAuto(options).objectName();
// e.g. "c-9f4b2ad10e" - store it BEFORE anything else

Map<String, Object> domain = new LinkedHashMap<String, Object>();
domain.put("years", 1);
domain.put("registrant", handle);
client.domain().create("example.com.ua", domain);
```

Берите этот путь, когда у вас нет собственной схемы имён или когда иначе пришлось бы крутить
повторы вокруг `2302`, потому что идентификатор занял кто-то раньше вас. **Каждый вызов генерирует
новый идентификатор**, поэтому повтор — это второй контакт, а не коллизия; отсюда же следует, что
повтор после неясного сбоя создаёт дубликат. Если вызов `createAuto()` закончился неизвестным
исходом, сверьтесь с реестром, прежде чем звать его снова.

`Contact.AUTO_ID` — константа, стоящая за этим, и передача её в `create()` или `createBuilder()`
делает то же самое:

```java
import com.epptools.sdk.command.Contact;

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("name", "ACME LLC");
options.put("city", "Kyiv");
options.put("cc", "UA");
options.put("email", "contact@example.com");

client.contact().create(Contact.AUTO_ID, options);
```

Зарезервированное значение никогда не сохраняется как идентификатор, поэтому остаётся доступным
всем.

---

## update

```java
public Response update(String id, Map<String, Object> options);
```

**На проводе:** `<command><update><contact:update>` — RFC 5733 §3.2.5.

| ключ | значение |
|---|---|
| `chg` | `{"postalInfo": {...}, "postalInfos": [{...}], "voice": …, "fax": …, "email": …, "authInfo": …, "disclose": {...}}` |
| `addStatuses` | `List<String>` — клиентские статусы, которые нужно установить |
| `remStatuses` | `List<String>` — клиентские статусы, которые нужно снять |

```java
Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("email", "new-contact@example.com");
chg.put("voice", "+380.441234500");

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("chg", chg);
options.put("addStatuses", Arrays.asList("clientUpdateProhibited"));

client.contact().update("acme-01", options);
```

Все статусы из `addStatuses` уходят в один блок `<contact:add>`, а все из `remStatuses` — в один
`<contact:rem>`: именно это позволяет схема RFC 5733 — по одному блоку каждого вида, до семи
статусов в каждом.

### Правило обновления: почтовый блок ЗАМЕЩАЕТСЯ, а не сливается

Отправьте почтовый блок в `chg` — и реестр **заместит** им тот, что хранит. Их не сливают поле за
полем, поэтому всё, чего вы не передали, исчезает.

RFC 5733 можно прочитать и иначе: в `chgPostalInfoType` каждая из составляющих — name, org, addr —
необязательна, и это выглядит как «не передавайте её, и реестр сохранит то, что хранит». Это чтение
небезопасно: против реестра, который замещает блок, первые две из этих команд отвечают **1000**, а
третья никогда не покидает эту библиотеку:

| что нёс `chg` | что осталось у контакта |
|---|---|
| полный блок с `"org"` равным `""` | организация убрана, адрес нетронут |
| полный блок без ключа `org` | организация **тоже убрана** |
| один лишь `org` со значением `""` | отклоняется здесь, с `ValidationException` — на проводе это оставило бы **почтового блока нет вообще**: ни имени, ни улицы, города, индекса и страны |
То есть изменить одно поле адреса невозможно, и сбой молчаливый: команда успешна, а данных нет.
`name`, `city` и `cc` обязательны в любом почтовом изменении, и эта библиотека без них отказывает, —
но эта проверка лишь удерживает кадр валидным и вернуть `org`, `sp` или `pc`, которого вы не
передали, не может.

**Прочитайте блок, примените своё изменение и отправьте его целиком:**

```java
Map<String, Object> current =
        new LinkedHashMap<String, Object>(client.contact().info("acme-01").postalInfo().get("int"));

// Change the city and clear the org, leaving everything else exactly as it was.
current.put("type", "int");
current.put("city", "Lviv");
current.put("org", "");

client.contact().update("acme-01", Collections.<String, Object>singletonMap(
        "chg", Collections.singletonMap("postalInfo", current)));
```

Чего замещение не достаёт — так это второй почтовой формы: `int` и `loc` адресуются раздельно,
поэтому замена одной оставляет другую ровно такой, какой она была.

Внутри блока, который вы отправляете, пустая строка по-прежнему очищает необязательное поле:

| что вы написали | что происходит |
|---|---|
| ключ со значением | поле получает это значение |
| ключ со значением `""` | поле **очищается** — так убирают `org`, `sp` или `pc` |
| ключа нет | поле не отправляется — и реестр удаляет то, что хранил |

Обе формы меняются одной командой через `postalInfos`:

```java
Map<String, Object> intBlock = new LinkedHashMap<String, Object>();
intBlock.put("type", "int");
intBlock.put("name", "Ivan Petrenko");
intBlock.put("city", "Lviv");
intBlock.put("cc", "UA");

Map<String, Object> locBlock = new LinkedHashMap<String, Object>();
locBlock.put("type", "loc");
locBlock.put("name", "Іван Петренко");
locBlock.put("city", "Львів");
locBlock.put("cc", "UA");

client.contact().update("acme-01", Collections.<String, Object>singletonMap(
        "chg", Collections.singletonMap("postalInfos", Arrays.asList(intBlock, locBlock))));
```

Форма, которую вы не упомянули, остаётся нетронутой.

### Смена кода авторизации трансфера

```java
Map<String, Object> chg = new LinkedHashMap<String, Object>();
chg.put("authInfo", "Fresh-C0nt@ct-Pw");
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("chg", chg);

client.contact().update("acme-01", options);
```

Код авторизации трансфера у контакта можно **заменить, но нельзя удалить**: RFC 5731 даёт домену
форму с явным «пусто», а RFC 5733 ничего подобного для контакта не определяет. И не тянитесь к
пустому паролю как к замене: пустое значение — это всё ещё значение, которое держатель может
предъявить. Задайте новый код.

### Смена предпочтений раскрытия

```java
Map<String, Object> disclose = new LinkedHashMap<String, Object>();
disclose.put("flag", false);
disclose.put("email", true);
disclose.put("voice", true);

client.contact().update("acme-01", Collections.<String, Object>singletonMap(
        "chg", Collections.singletonMap("disclose", disclose)));
```

Блок отправляется целиком, поэтому каждый раз указывайте полное предпочтение, а не разницу с
предыдущим.

**Коды ответа:** `1000`; `2303` (такого идентификатора нет); `2304` (запрещает статус); `2306`
(политика); `2308` (изменение убрало бы то, что реестр требует).

### Обновление по шагам

```java
public ContactCreateBuilder createBuilder(String contactId, String email);
public ContactUpdateBuilder updateBuilder(String contactId);
```

Идентификатор и адрес электронной почты — аргументы `createBuilder()`, а не шаги: реестр требует
оба, а шаг можно забыть. В билдере обновления то же правило наличия выглядит так: «передайте
аргумент, чтобы изменить значение, передайте `""`, чтобы очистить его, опустите, чтобы оставить как
есть». Каждый шаг описан в [Билдеры](builders.md).

---

## delete

```java
public Response delete(String id);
```

**На проводе:** `<command><delete><contact:delete>` — RFC 5733 §3.2.2.

Контакт, на который всё ещё ссылается домен, удалить нельзя; реестр отвечает **`2305`**. Статус
`linked` в `statuses()` — это способ реестра сказать об этом заранее:

```java
Response c = client.contact().info("acme-01");

if (c.statuses().contains("linked")) {
    // Still in use. Repoint the domains that reference it first - allContacts() on a
    // domain:info says which ids a domain holds.
    return;
}

client.contact().delete("acme-01");
```

**Коды ответа:** `1000`; `2303`; `2305` (всё ещё связан с доменом).

---

## transfer

```java
public Response transfer(String op, String id, String authInfo);
public Response transfer(String op, String id);
```

**На проводе:** `<command><transfer op="…"><contact:transfer>` — RFC 5733 §3.2.4 (и §3.1.3 для
`query`). `op` — одно из `request`, `query`, `approve`, `reject`, `cancel`, с теми же значениями,
что и при [трансфере домена](domains.md#transfer): `request` и `cancel` принадлежат принимающему
регистратору, `approve` и `reject` — текущему владельцу.

```java
Response r = client.contact().transfer("request", "acme-01", "the-code");

r.code();             // 1000, or 1001 when the sponsor has to answer
r.transferStatus();   // "pending"
r.transfer();         // {status, requestedBy, requestedAt, actingClient, actBy, expiryDate}
```

Запрос приходит к владельцу [poll-уведомлением](poll.md) с полезной нагрузкой `trnData`. Как
владелец:

```java
client.contact().transfer("approve", "acme-01");
// or
client.contact().transfer("reject", "acme-01");
```

`query` сообщает, на какой стадии запрос: `2300`, пока он в ожидании, `2301`, когда в ожидании
ничего нет.

**Коды ответа:** `1000` / `1001`; `2201` (объект не ваш, действовать с ним нельзя); `2202` (неверный
`authInfo`); `2300` (уже в ожидании); `2301` (в ожидании ничего нет); `2303`; `2304`.

---

## Коды ответа на этой странице

| Код | Значение | Исключение |
|---|---|---|
| `1000` | выполнено | — |
| `1001` | принято, завершается офлайн; итог приходит через [poll](poll.md) | — |
| `2003` | отсутствует обязательный параметр (почтовый блок, адрес электронной почты) | `CommandException` |
| `2005` | значение синтаксически некорректно (адрес электронной почты, кириллица в блоке `int`) | `CommandException` |
| `2201` | объект не ваш, действовать с ним нельзя | `AuthorizationException` |
| `2202` | неверный `authInfo` | `AuthorizationException` |
| `2300` / `2301` | трансфер уже в ожидании / трансфера в ожидании нет | `CommandException` |
| `2302` | идентификатор занят | `ObjectExistsException` |
| `2303` | такого идентификатора нет | `ObjectDoesNotExistException` |
| `2304` / `2305` | запрещает статус / всё ещё связан с доменом | `ObjectStatusException` |
| `2306` / `2308` | политика реестра отклоняет это значение | `PolicyException` |

У `ResultCode` есть именованная константа для каждого из них; полная таксономия — в
[Ошибки](errors.md).

---

См. также: [Домены](domains.md) · [Хосты](hosts.md) · [Poll](poll.md) ·
[Баланс и цены](balance.md) · [Ответы](responses.md) · [Билдеры](builders.md)

[← Оглавление руководства](README.md)
