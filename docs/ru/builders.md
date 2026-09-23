# Билдеры

Команды, принимающие карту опций, можно собрать и по одному именованному шагу за раз.

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

**Та же команда, тот же кадр, тот же результат.** Билдер не строит собственного XML: `send()`
передаёт свои опции обычному методу, поэтому билдер и равнозначная карта дают одинаковый кадр, и
каждая проверка, применимая к одному, применима и к другому.

Меняется то, где всплывает ошибка. Карта опций принимает любой ключ, поэтому `"yeras"`
отлавливается лишь потому, что библиотеке известен весь список ключей и она отвергает всё, чего в нём
нет. В билдере ошибаться не в чем: `.yeras(1)` — несуществующий метод, и ваш редактор скажет об этом
прямо при наборе.

Всё здесь предполагает подключённый клиент с выполненным входом — см. [Сессию](session.md).

## Пять билдеров

| Класс | Откуда берётся | Что отправляет |
|---|---|---|
| `DomainCreateBuilder` | `client.domain().createBuilder(String name)` | `domain:create` |
| `DomainUpdateBuilder` | `client.domain().updateBuilder(String name)` | `domain:update` |
| `ContactCreateBuilder` | `client.contact().createBuilder(String id, String email)` | `contact:create` |
| `ContactUpdateBuilder` | `client.contact().updateBuilder(String id)` | `contact:update` |
| `HostUpdateBuilder` | `client.host().updateBuilder(String name)` | `host:update` |

Они живут в `com.epptools.sdk.builder`. Напрямую вы их не создаёте — это делает обработчик, поэтому
билдер уже знает, через какой клиент отправлять.

Для `check`, `info`, `renew`, `transfer`, `delete` и `restore` билдера намеренно нет. Они принимают
позиционные аргументы, которые язык и так проверяет; билдер добавил бы церемоний, не убрав ни одного
класса ошибок.

---

## Четыре правила, действующие для каждого билдера

### 1. Каждый списочный шаг накапливает

Передать несколько сразу, вызвать шаг ещё раз или и то и другое — одно и то же:

```java
client.domain().createBuilder("example.com.ua")
        .techContact("C-0002", "C-0003");

client.domain().createBuilder("example.com.ua")
        .techContact("C-0002").techContact("C-0003");   // то же самое
```

Именно поэтому билдер читается так же, как ведёт себя в цикле или за условием:

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");

for (String host : nameservers) {
    builder.nameserver(host);        // each call adds one
}
if (needsDnssec) {
    builder.dsRecord(12345, 13, 2, digest);
}

builder.send();
```

Одиночные шаги вместо этого **заменяют**: `.years(1).years(2)` оставит `2`, как двойное
присваивание переменной. Какой шаг к чему относится, сказано в таблицах ниже.

Пустые значения и значения из одних пробелов списочные шаги отбрасывают, а не отправляют пустыми
элементами, поэтому цикл по списку с пропуском внутри не выдаст `<domain:hostObj/>`.

### 2. Ничего не отправляется до `send()`

До этого билдер — обычное значение. Храните его, передавайте в другую функцию, собирайте в одном
месте, а отправляйте в другом.

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");
// …nothing has reached the registry…
Response response = builder.send();     // and now it has
```

`send()` возвращает [`Response`](responses.md) — ровно так же, как и прямой вызов.

### 3. `toOptions()` отдаёт ровно то, что принимает прямой вызов, — копией

```java
public Map<String, Object> toOptions();
```

Есть у каждого билдера.

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .techContact("C-0002");

builder.toOptions();
// {years=1, registrant=C-0001, contacts={tech=[C-0002]}}

// So this is the same command by another road:
client.domain().create("example.com.ua", builder.toOptions());
```

Важны два свойства:

- **Это ровно та карта, которую принимает прямой метод.** Благодаря этому билдер можно ставить в
  очередь: сериализуйте `toOptions()`, положите в очередь, и пусть рабочий процесс вызовет с ним
  `create()`.
- **Это глубокая копия.** Отдавать живую карту значило бы позволить ей меняться под вызывающим при
  каждом новом шаге, и тогда записанное в журнал и отправленное могли бы разойтись. Вы получаете
  законченное значение.

Вызов ничего не отправляет и не расходует билдер.

### 4. Билдер отправляет один раз

```java
DomainCreateBuilder builder = client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001");

builder.send();
builder.send();
// ValidationException: DomainCreateBuilder has already been sent. A builder carries one
//                      command; build another rather than re-sending this one.
```

Второй `send()` на создании — это вторая регистрация и второе списание, и вызывающий никогда не имел
этого в виду: повтор после сбоя — не то же самое, что переигрывание объекта, который уже ушёл.
Соберите новый билдер, они ничего не стоят. Если первый `send()` упал так, что результат остался
неизвестным, прочитайте
[Ошибки](errors.md#когда-трансформирующая-команда-упала-и-вы-не-знаете-выполнилась-ли-она), прежде чем
делать хоть что-нибудь.

---

## DomainCreateBuilder

```java
public DomainCreateBuilder createBuilder(String name);
```

Отправляет `domain:create` (RFC 5731 §3.2.1). У каждой опции
[`domain().create()`](domains.md#create) здесь есть свой шаг.

| Шаг | Что задаёт | Накапливает? |
|---|---|---|
| `years(int years)` | `years` — `<domain:period unit="y">`. Не задавайте его — и реестр применит свой срок по умолчанию | заменяет |
| `registrant(String handle)` | `registrant` — держатель домена | заменяет |
| `contact(String role, String... handles)` | `contacts[role]` — по одному `<domain:contact type="…">` на идентификатор | накапливает |
| `adminContact(String... handles)` | `contacts["admin"]` | накапливает |
| `techContact(String... handles)` | `contacts["tech"]` | накапливает |
| `billingContact(String... handles)` | `contacts["billing"]` | накапливает |
| `nameserver(String host)` | `nameservers` — одна ссылка на объект хоста (`<domain:hostObj>`) | накапливает |
| `nameservers(String... hosts)` | `nameservers` — то же самое, по нескольку за раз | накапливает |
| `nameserverWithGlue(String host, String... addresses)` | `nameservers` в виде `{"name": …, "addresses": [...]}` — встроенные glue-адреса (`<domain:hostAttr>`) | накапливает |
| `authInfo(String password)` | `authInfo` — код трансфера | заменяет |
| `license(String number)` | `license` — номер товарного знака или лицензии, если ваш реестр его требует | заменяет |
| `maxFee(String amount, String currency)` | `fee` — максимум, который вы согласны заплатить (RFC 8748); есть и форма с одной суммой | заменяет |
| `dsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS["dsData"]` | накапливает |
| `dsRecordWithKey(int keyTag, int alg, int digestType, String digest, int flags, int protocol, int keyAlg, String pubKey)` | `secDNS["dsData"]` вместе с DNSKEY, из которого он вычислен | накапливает |
| `keyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS["keyData"]` | накапливает — **альтернатива** `dsRecord`, никогда не рядом с ним |
| `maxSigLife(int seconds)` | `secDNS["maxSigLife"]` | заменяет |
| `send(): Response` | вызывает `domain().create(name, options)` | терминальный |

### Регистрация

```java
import com.epptools.sdk.exception.EppException;

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
        // 1001 - the registry queued it. Not registered yet; the verdict arrives via poll.
        orders.markPending(r.svTRID());
    }
} catch (EppException e) {
    System.out.println("EPP error: " + e.getMessage());
}
```

`contact()` называет роль одним аргументом — это всё, что делают за вас `adminContact()`,
`techContact()` и `billingContact()`, и это удобно, когда роль приходит из переменной:

```java
client.domain().createBuilder("example.com.ua")
        .contact("admin", "C-0009")
        .contact("billing", "C-0010");
```

Роль — это `admin`, `billing` или `tech`, **и больше ничего**. RFC 5731 делает
`domain:contactAttrType` закрытым перечислением, так что зона не получает четвёртой роли от того,
что её распознаёт реестр: `type="reseller"` — кадр, который схема отклоняет, и он забирает с
собой всю команду — все контакты в ней и саму регистрацию. Роль помимо этих трёх вызывает
`ValidationException`, называющее допустимый набор, и то же делает пустая роль, которая дала бы
`<domain:contact type="">` и код `2005`, не называющий ничего полезного. Если ваш реестр ведёт учёт
реселлера, он делает это через своё расширение, а не через этот элемент.

### Две модели делегирования

```java
// Ссылки на объекты хостов: сначала создайте сами объекты хостов (см. hosts.md).
client.domain().createBuilder("example.com.ua")
        .nameserver("ns1.acme.example").nameserver("ns2.acme.example");

// Встроенные glue-адреса: адреса едут вместе с именем. IPv4 и IPv6 различаются по литералу.
client.domain().createBuilder("example.com.ua")
        .nameserverWithGlue("ns1.example.com.ua", "203.0.113.1", "2001:db8::1")
        .nameserverWithGlue("ns2.example.com.ua", "203.0.113.2");
```

RFC 5731 делает `<domain:ns>` выбором между двумя моделями, поэтому одна команда использует либо
одну, либо другую. Смешение отклоняется при сборке кадра — `ValidationException` называет проблему,
вместо голого `2001` от реестра, который не называет ни одного поля. Спросите у своего реестра,
какую модель он принимает.

`nameserverWithGlue()` с пустым именем выбрасывает исключение сразу: сервер имён без имени — не то,
о чём стоит узнавать из ответа.

### DNSSEC при создании

```java
client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .dsRecord(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6")
        .dsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .maxSigLife(1209600)
        .send();
```

`dsRecordWithKey()` отправляет вместе с DS-записью тот DNSKEY, из которого вычислен дайджест.
Реестр, который это принимает, может сверить дайджест с ключом за вас и поймать опечатку в дайджесте
до того, как она дойдёт до зоны; реестр, который данные ключа не принимает, отклоняет команду, а не
игнорирует лишний элемент, так что попытка не стоит ничего, кроме `2306`.

```java
client.domain().createBuilder("example.com.ua")
        .dsRecordWithKey(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6",
                257, 3, 13, "AwEAAb…");
```

`keyRecord()` подписывает голым открытым ключом вместо DS-записи — там, где реестр такие принимает.
Пустой дайджест или пустой открытый ключ вызывает `ValidationException`.

`maxSigLife()` имеет смысл только рядом с DS-записью или записью ключа.

---

## DomainUpdateBuilder

```java
public DomainUpdateBuilder updateBuilder(String name);
```

Отправляет `domain:update` (RFC 5731 §3.2.5).

**Обновление EPP — это дельта, а не замена.** То, чего вы не упомянули, остаётся ровно таким, каким
было, а *то, в какой блок попадает изменение, и есть вся семантика команды*:

| Блок | Что означает |
|---|---|
| `add` | оставить то, что есть, и добавить это |
| `rem` | убрать это, остальное оставить |
| `chg` | заменить это одиночное поле |

Отправить сервер имён в `add`, имея в виду `rem`, — это не сбой: домен делегируется тому серверу,
который вы пытались убрать, а реестр отвечает `1000`. Поэтому каждый шаг называет свой блок.
Прочитали префикс метода — прочитали семантику.

| Шаг | Блок | Что задаёт |
|---|---|---|
| `addNameserver(String host)` | `add` | `add["ns"]` — делегировать ещё одному, накапливает |
| `addNameservers(String... hosts)` | `add` | `add["ns"]` — по нескольку за раз, накапливает |
| `remNameserver(String host)` | `rem` | `rem["ns"]` — перестать делегировать одному, накапливает |
| `remNameservers(String... hosts)` | `rem` | `rem["ns"]`, накапливает |
| `addContact(String role, String... handles)` | `add` | `add["contacts"][role]`, накапливает |
| `remContact(String role, String... handles)` | `rem` | `rem["contacts"][role]`, накапливает |
| `addStatus(String... statuses)` | `add` | `add["statuses"]`, накапливает |
| `remStatus(String... statuses)` | `rem` | `rem["statuses"]`, накапливает |
| `changeRegistrant(String handle)` | `chg` | `chg["registrant"]`, заменяет |
| `changeAuthInfo(String password)` | `chg` | `chg["authInfo"]` — заменить код трансфера, заменяет |
| `clearAuthInfo()` | `chg` | `chg["clearAuthInfo"] = true` — **удалить** код трансфера |
| `restore()` | — | `restore = true` — запрос восстановления RGP (RFC 3915) |
| `license(String number)` | — | `license` — номер товарного знака или лицензии |
| `maxFee(String amount, String currency)` | — | `fee` — ограничение суммы, когда изменение тарифицируется |
| `addDsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS.add` | `secDNS["add"]["dsData"]`, накапливает |
| `remDsRecord(int keyTag, int alg, int digestType, String digest)` | `secDNS.rem` | `secDNS["rem"]["dsData"]`, накапливает |
| `addKeyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS.add` | `secDNS["add"]["keyData"]`, накапливает — **альтернатива** `addDsRecord` в этом блоке |
| `remKeyRecord(int flags, int protocol, int alg, String pubKey)` | `secDNS.rem` | `secDNS["rem"]["keyData"]`, накапливает — **альтернатива** `remDsRecord` в этом блоке |
| `removeAllDnssec()` | `secDNS.rem` | `secDNS["remAll"] = true` — полностью снять подпись с домена |
| `maxSigLife(int seconds)` | `secDNS.chg` | `secDNS["maxSigLife"]`, заменяет |
| `send(): Response` | — | вызывает `domain().update(name, options)` |

### Смена делегирования

```java
Response r = client.domain().updateBuilder("example.com.ua")
        .addNameserver("ns3.acme.example")
        .remNameserver("ns2.acme.example")
        .addStatus("clientTransferProhibited")
        .remStatus("clientHold")
        .changeRegistrant("C-0009")
        .send();

System.out.println(r.code() + " " + r.message());   // 1000, or 1001 if the registry queues it

// An update answers with a result, not an object. Re-read the new state if you store it:
Response after = client.domain().info("example.com.ua");
System.out.println(String.join(", ", after.nameservers()));
```

Устанавливать вы можете статусы семейства `client*`. Статусы `server*` принадлежат реестру, и попытка
тронуть их возвращается кодом `2304`.

`changeRegistrant()` — это смена держателя, которую многие реестры считают отдельной процедурой со
своим документооборотом; отказ здесь обычно означает политику, а не неправильно составленную
команду.

### Отзыв утёкшего кода трансфера

```java
// The code went somewhere it should not have:
client.domain().updateBuilder("example.com.ua").clearAuthInfo().send();

// Later, when the customer needs a code again:
client.domain().updateBuilder("example.com.ua").changeAuthInfo("Fresh-D0main-Pw").send();
```

`clearAuthInfo()` отправляет `<domain:authInfo><domain:null/></domain:authInfo>`, что **удаляет**
код. Это не то же самое, что задать пустой: пустой пароль — всё ещё значение, которое держатель может
предъявить, так что домен остался бы ровно настолько же переносимым, насколько был. Эти два варианта
взаимоисключающие — схема не умеет выразить оба сразу, — поэтому запрос обоих вызывает
`ValidationException` до того, как что-либо будет отправлено.

### DNSSEC при обновлении

```java
// Roll a key with no window in which the domain is unsigned:
client.domain().updateBuilder("example.com.ua")
        .remDsRecord(12345, 13, 2, "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6")
        .addDsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .send();

// Снять подпись совсем:
client.domain().updateBuilder("example.com.ua").removeAllDnssec().send();

// Заменить весь набор ключей одной операцией:
client.domain().updateBuilder("example.com.ua")
        .removeAllDnssec()
        .addDsRecord(54321, 13, 2, "A1B2C3D4E5F60718293A4B5C6D7E8F90112233445566778899AABBCCDDEEFF00")
        .send();
```

Запись, названная в `remDsRecord()`, должна совпадать с тем, что хранит реестр, по **каждому** полю,
а не только по key tag.

`removeAllDnssec()` и `remDsRecord()`/`remKeyRecord()` взаимоисключающие: протокол не умеет выразить
«удалить всё и заодно удалить вот это», и кадр, несущий оба указания, отклоняется. Билдер отвергает
такое сочетание сам, в каком бы порядке вы его ни написали, сообщением о том, какие именно два шага
конфликтуют.

### Восстановление через билдер обновления

```java
client.domain().updateBuilder("example.com.ua")
        .restore()
        .maxFee("1000.00", "UAH")       // your cap, not a published price
        .send();
```

Идентично [`domain().restore("example.com.ua", "1000.00")`](domains.md#restore). Не отправляйте
вместе с восстановлением своего изменения — примените его потом, второй командой, — но и кадр
не без блоков: RFC 3915 §4.2.5 требует пустого `<domain:add>`, `<domain:rem>` или `<domain:chg>`
в обновлении, несущем это расширение, и библиотека выводит `<domain:chg/>` за вас. См.
[restore](domains.md#restore).

---

## ContactCreateBuilder

```java
public ContactCreateBuilder createBuilder(String contactId, String email);
```

Отправляет `contact:create` (RFC 5733 §3.2.1).

**Идентификатор и адрес электронной почты — аргументы конструктора, а не шаги**, потому что реестр
требует и то и другое. Билдер, позволяющий забыть обязательное поле, переносит ошибку из вашего
редактора на провод.

Передайте в качестве идентификатора `com.epptools.sdk.command.Contact.AUTO_ID`, чтобы
[идентификатор выдал реестр](contacts.md#позволить-реестру-выбрать-идентификатор), и прочитайте его
через `objectName()`.

| Шаг | Что задаёт | Накапливает? |
|---|---|---|
| `internationalAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` | `postalInfos` с `type = "int"` | накапливает |
| `localizedAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` | `postalInfos` с `type = "loc"` | накапливает |
| `voice(String number)` | `voice` — форма EPP `+CC.NNNNNNNNN`, при необходимости с `x` и добавочным номером | заменяет |
| `fax(String number)` | `fax` — та же форма | заменяет |
| `authInfo(String password)` | `authInfo` — код трансфера контакта | заменяет |
| `publish(String... fields)` | `disclose` с `flag = true` — согласие публиковать перечисленное | заменяет |
| `withhold(String... fields)` | `disclose` с `flag = false` — скрывать перечисленное | заменяет |
| `send(): Response` | вызывает `contact().create(id, options)` | терминальный |

```java
Response r = client.contact().createBuilder("C-0001", "contact@example.com")
        .internationalAddress("Ivan Petrenko", "Kyiv", "UA",
                Arrays.asList("1 Khreschatyk St"), "ACME LLC", null, "01001")
        .localizedAddress("Іван Петренко", "Київ", "UA",
                Arrays.asList("вул. Хрещатик 1"), "ТОВ «АКМЕ»", null, "01001")
        .voice("+380.441234567")
        .authInfo("C0nt@ct-Pw")
        .withhold("voice", "email")
        .send();

System.out.println(r.objectName());      // "C-0001" - the id
```

Порядок аргументов у этих двух шагов один и тот же — имя, город, код страны, улица, организация,
область, индекс, — и это единственное место в билдерах, где аргументов столько, что их стоит сверять
с сигнатурой. Для самого частого случая есть короткая форма из трёх обязательных значений,
`internationalAddress("ACME LLC", "Kyiv", "UA")`; в полной форме `null` означает «это поле не
отправлять». Там, где в игре сразу несколько таких аргументов, локальная переменная на каждый
читается лучше, чем ряд литералов:

```java
List<String> street = Arrays.asList("1 Khreschatyk St");
String org = "ACME LLC";
String stateProvince = null;
String postalCode = "01001";

client.contact().createBuilder("C-0001", "contact@example.com")
        .internationalAddress("Ivan Petrenko", "Kyiv", "UA", street, org, stateProvince, postalCode);
```

Нужна хотя бы одна форма адреса. Указывайте `internationalAddress()`, если нет причин поступить
иначе: это та форма, которая переживает печать, пересылку по почте и чтение системой, не знающей
кириллицы, а кириллица внутри блока `int` отклоняется кодом `2005`. Локализованная форма —
дополнение, а не альтернатива: присылайте обе, если у вас есть обе, ничего не отбрасывается.

### publish и withhold

Раскрытие по RFC 5733. Имена полей — `name`, `org`, `addr`, `voice`, `fax` и `email`; всё остальное
вызывает `ValidationException` с перечислением этих шести.

```java
client.contact().createBuilder("C-0001", "contact@example.com")
        .withhold("voice", "email");     // эти скрыты, со всеми остальными — наоборот

client.contact().createBuilder("C-0002", "contact@example.com")
        .publish("name", "org");         // эти можно публиковать, все остальные скрыты
```

**Это два способа сказать одно и то же, и второй вызов заменяет первый.** Выберите тот, который
совпадает с тем, как вы думаете об этом предпочтении, и не вызывайте оба: флаг — это весь смысл
списка, поэтому блок, собранный из двух половин, скажет то, чего не имел в виду ни один из вызовов.

`name`, `org` и `addr` существуют по одному на каждую почтовую форму, поэтому указание любого из них
покрывает **обе** формы. Скрыть только ASCII-форму, оставив локальную публичной, — это настройка
приватности, которая читается как применённая, но таковой не является.

---

## ContactUpdateBuilder

```java
public ContactUpdateBuilder updateBuilder(String contactId);
```

Отправляет `contact:update` (RFC 5733 §3.2.5). То, чего вы не упомянули, остаётся нетронутым.

| Шаг | Блок | Что задаёт |
|---|---|---|
| `changeInternationalAddress(String name, String city, String countryCode, List<String> street, String org, String stateProvince, String postalCode)` | `chg` | `chg["postalInfos"]` с `type = "int"` — только те аргументы, которые не `null` |
| `changeLocalizedAddress(…те же параметры…)` | `chg` | `chg["postalInfos"]` с `type = "loc"` |
| `changeVoice(String number)` | `chg` | `chg["voice"]` |
| `changeFax(String number)` | `chg` | `chg["fax"]` |
| `changeEmail(String email)` | `chg` | `chg["email"]` |
| `changeAuthInfo(String password)` | `chg` | `chg["authInfo"]` — заменить код трансфера |
| `publish(String... fields)` | `chg` | `chg["disclose"]` с `flag = true` |
| `withhold(String... fields)` | `chg` | `chg["disclose"]` с `flag = false` |
| `addStatus(String... statuses)` | `add` | `addStatuses`, накапливает |
| `remStatus(String... statuses)` | `rem` | `remStatuses`, накапливает |
| `send(): Response` | — | вызывает `contact().update(id, options)` |

```java
client.contact().updateBuilder("C-0001")
        .changeEmail("new-contact@example.com")
        .changeVoice("+380.441234500")
        .addStatus("clientUpdateProhibited")
        .send();
```

### Адрес ЗАМЕЩАЕТСЯ целиком, а не сливается по полям

Блок, который вы передаёте, **замещает** тот, что хранит реестр. Их не сливают поле за полем,
поэтому всё, чего вы не передали, исчезает:

| Что вы пишете | Что происходит |
|---|---|
| передаёте значение | поле принимает это значение |
| передаёте `""` | поле **очищается** — так убирают `org`, `stateProvince` или `postalCode` |
| передаёте `null` | поле не отправляется — и реестр удаляет то, что хранил |

RFC 5733 можно прочитать как «не передавайте — и реестр сохранит своё значение», ведь каждая
составляющая `chgPostalInfoType` необязательна, но это чтение небезопасно. Против реестра, который
замещает блок, — и отвечает при этом **1000**, — полный блок, отправленный без `org`,
возвращается уже без организации. Блок, в котором был один лишь `org`, оставил бы контакт
вообще без почтового адреса: без имени, улицы, города, индекса и страны. Эта библиотека
отклоняет второй вариант до отправки — именно поэтому это отказ, который можно прочитать, а не 1000,
который прочитать нельзя.

Именно поэтому `name`, `city` и `countryCode` обязательны в любом изменении адреса, и билдер без них
отказывает. Они удерживают кадр валидным, но вернуть поле, которого вы не передали, не могут.
**Сначала прочитайте блок и верните его вместе со своим изменением:**

```java
Map<String, Object> current = client.contact().info("C-0001").postalInfo().get("int");

@SuppressWarnings("unchecked")
List<String> street = (List<String>) current.get("street");

// Перенести контакт во Львов и стереть организацию, оставив всё остальное как было.
client.contact().updateBuilder("C-0001")
        .changeInternationalAddress((String) current.get("name"), "Lviv", "UA",
                street, "", (String) current.get("sp"), (String) current.get("pc"))
        .send();
```

Форма, которую вы не упомянули, — локальная или международная — остаётся нетронутой: они
адресуются раздельно.

### Здесь нет clearAuthInfo()

RFC 5731 даёт домену обнуляемую форму `<domain:authInfo><domain:null/>`; для контакта RFC 5733
ничего равнозначного не определяет. Поэтому код трансфера контакта можно **заменить, но не
удалить**. Не хватайтесь взамен за пустой пароль: пустое значение — всё ещё значение, которое
держатель может предъявить. Вместо этого задайте новый код через `changeAuthInfo()`.

---

## HostUpdateBuilder

```java
public HostUpdateBuilder updateBuilder(String name);
```

Отправляет `host:update` (RFC 5732 §3.2.5).

| Шаг | Блок | Что задаёт |
|---|---|---|
| `addAddress(String ip)` | `add` | `addAddresses` — один glue-адрес, накапливает |
| `addAddresses(String... ips)` | `add` | `addAddresses` — несколько, накапливает |
| `remAddress(String ip)` | `rem` | `remAddresses`, накапливает |
| `remAddresses(String... ips)` | `rem` | `remAddresses`, накапливает |
| `addStatus(String... statuses)` | `add` | `addStatuses`, накапливает |
| `remStatus(String... statuses)` | `rem` | `remStatuses`, накапливает |
| `send(): Response` | — | вызывает `host().update(name, options)` |

```java
client.host().updateBuilder("ns1.example.com.ua")
        .addAddresses("192.0.2.10", "2001:db8::10")
        .remAddress("192.0.2.9")
        .send();
```

IPv4 и IPv6 различаются по самому литералу, поэтому `v4` и `v6` проставляются верно без ваших
указаний, где что.

**Шага переименования нет.** Не потому, что его нет в протоколе — RFC 5732 определяет
`host:chg`, — а потому, что реестры, против которых это строилось, его отбрасывают, так что
переименование здесь сообщало бы об успехе, которого не было. Три команды, которые делают
эту работу вместо него, описаны в [Хостах](hosts.md#переименования-не-существует); спросите у
своего реестра, реализует ли он `host:chg` вообще.

Добавить и удалить один и тот же адрес в одной команде — противоречие, которое реестр разрешает так,
как сочтёт нужным. Отправляйте что-то одно.

---

## Что билдер не меняет

Билдер — это фасад над картой опций, поэтому всё, чему подчиняется карта, действует и здесь:

- **Та же проверка.** `send()` вызывает обычный метод, и тот проверяет свои опции ровно так же, как
  сделал бы это для написанной руками карты. Билдер не может выдать неизвестный ключ, но может
  выдать сочетание, которое команда отвергнет.
- **Те же коды ответа.** `2302`, `2104`, `1001` означают то, что означают; см. [Ошибки](errors.md).
- **То же поведение `throwOnFailure`.** С выключенными исключениями `send()` возвращает отказ как
  `Response`, а не выбрасывает его.
- **То же обращение с секретами.** `authInfo()` задаёт действующие учётные данные. В собственном
  журнале библиотеки они маскируются, но `toOptions()` — это ваша карта, и если вы пишете её в
  журнал или кладёте в очередь, пароль едет в ней открытым текстом. Маскируйте его сами, прежде чем
  он попадёт в журнал.

## Какие шаги выбрасывают исключение до отправки

Все они — `ValidationException`, и ни в одном из случаев кадр не был собран:

| Шаг | Когда выбрасывает |
|---|---|
| любой `contact(…)` / `addContact(…)` / `remContact(…)` | роль пуста или состоит из пробелов; либо, на `send()`, она не входит в число admin, billing, tech |
| `nameserverWithGlue(…)` | имя сервера имён пусто |
| `dsRecord(…)`, `remDsRecord(…)`, `dsRecordWithKey(…)` | дайджест пуст |
| `keyRecord(…)`, `addKeyRecord(…)`, `remKeyRecord(…)`, `dsRecordWithKey(…)` | открытый ключ пуст |
| `removeAllDnssec()` после `remDsRecord()`/`remKeyRecord()` или любой из них после него | эти два взаимоисключающие |
| `dsRecord(…)`/`addDsRecord(…)`/`remDsRecord(…)` вместе с `keyRecord(…)`/`addKeyRecord(…)`/`remKeyRecord(…)` в одном блоке | RFC 5910 делает `dsData` и `keyData` выбором; вложите ключ в запись через `dsRecordWithKey(…)` |
| `maxSigLife(…)` | длительность меньше 1 секунды — `secDNS-1.1.xsd` задаёт minInclusive 1 |
| `maxFee(…)` | сумма не является простым десятичным числом вида `100.00` |
| `publish(…)` / `withhold(…)` | поле не входит в число name, org, addr, voice, fax, email |
| `send()` | билдер уже был отправлен |

Неправильно оформленное согласование тарифа проверяется здесь, а не на проводе, потому что иначе оно
получит голый `2001`, который не называет ни одного поля, — и придёт уже после того, как команда была
предпринята.

---

## Когда карта — более подходящий инструмент

Билдеры и карты — это одно и то же, поэтому пользуйтесь тем, что подходит:

- Собираете из файла конфигурации, строки базы данных или полезной нагрузки очереди, которая и так
  является картой: передавайте её прямо в `create()`/`update()`. Превращать её в цепочку вызовов
  лишь затем, чтобы билдер превратил её обратно в карту, — пустая работа.
- Пишете команду прямо в коде, особенно update: берите билдер. `.remStatus("clientHold")` называет
  блок, в который попадёт изменение, в том месте, где ошибиться нельзя.

`toOptions()` — мост между этими двумя путями, и он работает в обе стороны: соберите текучим API,
сохраните карту, позже проиграйте её прямым вызовом.

---

См. также: [Домены](domains.md) · [Контакты](contacts.md) · [Хосты](hosts.md) ·
[Баланс и цены](balance.md) · [Ответы](responses.md) · [Ошибки](errors.md)

[← К содержанию руководства](README.md)
