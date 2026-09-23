# Домены

Объекты доменов следуют **RFC 5731**: DNSSEC — по **RFC 5910**, восстановление из периода выкупа —
по **RFC 3915**, цены — по **RFC 8748**, а номер лицензии — по собственному расширению реестра,
если оно у него есть.
Каждая доменная команда вызывается через `client.domain()`, и каждая из них возвращает
[`Response`](responses.md).

Всё на этой странице предполагает подключённый клиент с выполненным входом — как его получить,
см. [Сессия](session.md), а что такое команда и ответ вообще — [Команды](commands.md).

Две привычки, которые пригодятся на всей странице:

- **Даты возвращаются собственной строкой реестра** (`2027-04-01T09:15:00Z`), а не объектом
  `Instant`. Реестр сам решает, на какой календарный день попадёт продление; пересчёт через
  локальный часовой пояс — это и есть тот способ, которым клиент начинает показывать (и продлевать
  по) дату на сутки раньше.
- **Деньги возвращаются точной десятичной строкой**, а не числом с плавающей точкой. Баланс,
  просуммированный в двоичной плавающей арифметике, уплывает. Берите `BigDecimal` или целые числа в
  минорных единицах.

## Методы

| Метод | Команда EPP |
|---|---|
| `check(List<String> names, Map<String, Object> fee, String currency): Response` | `<check>` + опциональный `<fee:check>` |
| `info(String name, String authInfo, String hosts): Response` | `<info>` |
| `create(String name, Map<String, Object> options): Response` | `<create>` |
| `createBuilder(String name): DomainCreateBuilder` | строит `<create>` |
| `update(String name, Map<String, Object> options): Response` | `<update>` |
| `updateBuilder(String name): DomainUpdateBuilder` | строит `<update>` |
| `delete(String name): Response` | `<delete>` |
| `renew(String name, String curExpDate, int years, Object fee): Response` | `<renew>` |
| `transfer(String op, String name, String authInfo, Integer years, Object fee): Response` | `<transfer op="…">` |
| `restore(String name, Object fee): Response` | `<update>` + `<rgp:restore op="request"/>` |

`create()` и `update()` принимают карту опций. **Ключ опции, которого эта библиотека не знает,
отклоняется с `ValidationException` ещё до того, как будет построен кадр**, и в отказе называется
ближайший известный ключ. Это важнее, чем выглядит: молча проигнорированный `"secdns"`
зарегистрирует домен неподписанным, а реестр всё равно ответит `1000` — с его точки зрения вы ни о
чём не просили.

---

## check

```java
public Response check(List<String> names, Map<String, Object> fee, String currency);
public Response check(List<String> names);
```

**На проводе:** `<command><check><domain:check><domain:name>…` — RFC 5731 §3.1.1. Каждое имя в
`names` становится одним `<domain:name>`. Если задан `fee` или `currency`, вместе с командой в
`<extension>` едет блок `<fee:check>` (RFC 8748); всё про цены — в
[Баланс и цены](balance.md).

Доступность передаётся в полезной нагрузке, а не в коде ответа: проверка, ответившая «занято», —
это **успешная** команда.

```java
Response r = client.domain().check(Arrays.asList("example.com.ua", "taken.com.ua"));

r.availability();                        // {example.com.ua=true, taken.com.ua=false}
r.isAvailable("example.com.ua");         // TRUE | FALSE | null
r.unavailableReason("taken.com.ua");     // "In use", or null when the name is free
```

`isAvailable()` возвращает `null`, когда в ответе про это имя не сказано ничего. Пользуйтесь им, а
не ручной индексацией `availability()`: там `null` приходит и на «занято», и на «вы опечатались в
ключе» — два ответа, которые не должны выглядеть одинаково в той строке кода, что регистрирует имя.

```java
for (Map.Entry<String, Boolean> entry : client.domain().check(candidates).availability().entrySet()) {
    if (Boolean.TRUE.equals(entry.getValue())) {
        register.add(entry.getKey());
    }
}
```

Ответ о доступности — снимок, а не бронь. Между проверкой и созданием имя может занять кто-то
другой, и вы узнаете об этом из `2302` на создании — вот это и есть окончательный ответ.

**Коды ответа:** `1000` на любую корректно сформированную проверку. `2005` называет синтаксически
некорректное доменное имя, `2307` — зону, которую этот реестр не обслуживает, `2306` — ценовой
блок, который отклоняет политика реестра. Запрос цен более чем на 20 позиций эта библиотека
отклоняет с `ValidationException` ещё до отправки — и так же валюту, названную вообще
без операций: валюта сама по себе ничего не оценивает. **Пустой список** тоже отклоняется, с
`ValidationException`: у кадра, который собрался бы, нет ни одного дочернего элемента, а схема
требует хотя бы одного — так что цикл по строке запроса или по корзине, которая оказалась
пустой, падает здесь, поимённо, а не тратит обращение к серверу.

---

## info

```java
public Response info(String name, String authInfo, String hosts);
public Response info(String name);
```

**На проводе:** `<command><info><domain:info><domain:name hosts="all">` — RFC 5731 §3.1.2. Передайте
`authInfo` — и он уйдёт как `<domain:authInfo><domain:pw>`; именно так полную запись читает
регистратор, который **не** является владельцем домена.

`hosts` выбирает, какие хосты перечислит ответ; это атрибут `hosts` из RFC 5731:

| значение | что перечисляет ответ |
|---|---|
| `all` (по умолчанию) | делегированные серверы имён и подчинённые хосты |
| `del` | только делегированные серверы имён |
| `sub` | только подчинённые хосты |
| `none` | ни те, ни другие |

Эти четыре — и больше ничего: `hostsType` из RFC 5731 — закрытое перечисление, так что `hosts`
помимо них — это `ValidationException`, называющее набор, а не кадр, который реестр отклоняет, не
называя атрибута.

```java
Response info = client.domain().info("example.com.ua");

info.objectName();          // "example.com.ua"
info.roid();                // the registry's own object id
info.statuses();            // ["ok"], or ["clientHold", "clientTransferProhibited", …]
info.expiryDate();          // "2027-04-01T09:15:00Z" - the registry's own string
info.createdDate();         // crDate          info.createdBy();   // crID
info.updatedDate();         // upDate or null  info.updatedBy();   // upID or null
info.sponsor();             // clID - the account the domain belongs to now
info.registrarOfRecord();   // the id the registry's WHOIS/RDAP publishes, when it differs
info.transferDate();        // when the domain last changed hands, or null

info.registrant();          // the registrant contact id
info.contacts();            // {admin=[acme-01], tech=[acme-01, acme-02]}
info.adminContacts();       // that role alone - techContacts() / billingContacts() exist too
info.contactsFor("tech");   // any role, matched case-insensitively; [] when the role is empty
info.allContacts();         // every id, the registrant included, de-duplicated

info.nameservers();         // names - whether the registry answered hostObj or hostAttr
info.nameserverAddresses(); // inline glue by nameserver name, when the registry sent it
info.subordinateHosts();    // hosts living UNDER this domain

info.authInfo();            // the transfer authorisation code - see the warning below
info.license();             // a trademark or licence number, or null
info.rgpStatus();           // ["redemptionPeriod"] and the like, or []
info.isSigned();            // whether the domain carries any DNSSEC data at all
info.dsRecords();           // [{keyTag=…, alg=…, digestType=…, digest=…}, …]
info.keyRecords();          // [{flags=…, protocol=…, alg=…, pubKey=…}, …]
info.prices();              // {renewal={value=180.00, currency=UAH}, …}
info.priceChannel();        // which catalogue row these prices came from, or null
```

`authInfo()` — это тот код, по которому **любой** регистратор может увести у вас домен. Он
возвращается только регистратору-владельцу. Не пишите его в журналы, не вставляйте в тикет
поддержки и меняйте его, как только он побывал у клиента — см.
[Отзыв утёкшего кода трансфера](#отзыв-утёкшего-кода-трансфера).

Два аксессора стоит читать вместе. `nameservers()` даёт имена при любой из двух моделей EPP —
за списком идите туда; `nameserverAddresses()` заполняется только там, где реестр отвечает
встроенными glue-адресами, поэтому пустой результат **не** означает, что домен не делегирован. Там,
где реестр отвечает ссылками на объекты хостов, адреса добираются одним
[`host().info()`](hosts.md#info) на каждое имя.

`subordinateHosts()` — то, что проверяют перед удалением: реестр отказывается удалять домен, пока
под ним живут хосты.

**Коды ответа:** `1000`; `2202` (неверный `authInfo` у не-владельца); `2303` (домена нет).

---

## create

```java
public Response create(String name, Map<String, Object> options);
```

**На проводе:** `<command><create><domain:create>` — RFC 5731 §3.2.1, плюс `<secDNS:create>`
(RFC 5910), `<registry:create><registry:license>` и `<fee:create>` (RFC 8748) в `<extension>`, если вы их
запросили. **Плата за создание списывается при успехе.**

### Все опции

| ключ | значение | на проводе |
|---|---|---|
| `years` | `int` | `<domain:period unit="y">` — не указывайте, чтобы взять срок по умолчанию, принятый в реестре |
| `registrant` | идентификатор | `<domain:registrant>` |
| `contacts` | карта `роль => идентификатор` или `роль => List<String>` | по одному `<domain:contact type="…">` на идентификатор |
| `nameservers` (пишется также `nameServers`) | `List<String>` либо список карт `{"name": …, "addresses": [...]}` | `<domain:ns>` с `<domain:hostObj>` или `<domain:hostAttr>` внутри |
| `authInfo` | строка | `<domain:authInfo><domain:pw>` |
| `license` | строка | `<registry:license>` внутри `<registry:create>` |
| `secDNS` | карта с `maxSigLife` и **либо** `dsData`, **либо** `keyData` — не обоими сразу | `<secDNS:create>` |
| `fee` | `"100.00"` или карта `{"amount": "100.00", "currency": "UAH"}` | `<fee:create>` — предел, на который вы соглашаетесь |

### Первая регистрация

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

            // Прочитайте ответ. На create приходят имя и даты, которые назначил реестр.
            System.out.println(r.objectName() + " created " + r.createdDate());
            System.out.println("expires: " + or(r.expiryDate(), "-"));
            System.out.println("charged: " + or(r.feeAmount(), "-") + " " + or(r.feeCurrency(), ""));

            if (r.isPending()) {
                // 1001: реестр поставил регистрацию в очередь. Домен ещё НЕ зарегистрирован, а
                // результат придёт позже poll-уведомлением — см. poll.md.
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

### Контакты: один идентификатор на роль или несколько

Роль принимает одну строку или список, и каждый идентификатор становится собственным
`<domain:contact type="…">` — так это разрешает RFC 5731. Сколько идентификаторов может держать
роль, решает политика реестра.

```java
Map<String, Object> contacts = new LinkedHashMap<String, Object>();
contacts.put("admin", "acme-01");
contacts.put("tech", Arrays.asList("acme-01", "acme-02"));
contacts.put("billing", "acme-03");
```

Регистрант — **не** одна из этих ролей: это отдельный элемент со своим смыслом, и задаётся он
ключом `registrant`.

### Серверы имён: две модели

Сервер имён — это либо **имя**, ссылка на [объект хоста](hosts.md), который уже существует в
реестре, либо имя **со встроенными glue-адресами**. Какую модель принимает ваш реестр, спросите у
него.

```java
// Ссылки на объекты хостов (<domain:hostObj>): сначала создайте сами хосты.
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList("ns1.acme.example", "ns2.acme.example"));
```

```java
// Встроенные glue-адреса (<domain:hostAttr>): адреса едут вместе с именем.
Map<String, Object> ns1 = new LinkedHashMap<String, Object>();
ns1.put("name", "ns1.example.com.ua");
ns1.put("addresses", Arrays.asList("203.0.113.1", "2001:db8::1"));
Map<String, Object> ns2 = new LinkedHashMap<String, Object>();
ns2.put("name", "ns2.example.com.ua");
ns2.put("addresses", Arrays.asList("203.0.113.2"));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList(ns1, ns2));
```

Версия IP определяется по самому литералу, поэтому `v4` и `v6` проставляются правильно и без
подсказок с вашей стороны.

В RFC 5731 `<domain:ns>` — это *выбор* между двумя формами, поэтому одна команда пользуется одной
моделью или другой. Смесь здесь даёт `ValidationException`, а не голый `2001` от реестра, в котором
не названо ни одного поля:

```java
Map<String, Object> glue = new LinkedHashMap<String, Object>();
glue.put("name", "ns2.acme.example");
glue.put("addresses", Arrays.asList("203.0.113.2"));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("nameservers", Arrays.asList("ns1.acme.example", glue));
client.domain().create("example.com.ua", options);
// ValidationException: nameservers must be all names or all name-with-glue, not a mixture
```

Регистрация вообще без `nameservers` законна: домен остаётся неделегированным, и реестр сообщает о
нём `inactive` — это состояние, а не ошибка.

### authInfo

`<domain:authInfo>` при создании обязателен, поэтому элемент уходит всегда. Укажете опцию
`authInfo` — в нём поедет ваше значение; не укажете — уйдёт пустой `<domain:pw/>`, и выбор
останется за политикой зоны: многие зоны тогда генерируют код за вас, а вы читаете его обратно
через `info()`. Код, который вы задаёте сами, должен удовлетворять политике стойкости зоны, иначе
создание отклоняется с `2306`.

### secDNS при создании (RFC 5910)

```java
Map<String, Object> dsData = new LinkedHashMap<String, Object>();
dsData.put("keyTag", 12345);
dsData.put("alg", 13);
dsData.put("digestType", 2);
dsData.put("digest", "49FD46E6C4B45C55D4AC69E1F3B2A0D7C8E5904B1A2C3D4E5F60718293A4B5C6");
// Optional: the DNSKEY the digest was computed from. A registry that accepts it can verify the
// digest for you; one that does not answers 2306 rather than ignoring it.
// Map<String, Object> key = new LinkedHashMap<String, Object>();
// key.put("flags", 257); key.put("protocol", 3); key.put("alg", 13); key.put("pubKey", "AwEAA…");
// dsData.put("keyData", key);

Map<String, Object> secDns = new LinkedHashMap<String, Object>();
secDns.put("maxSigLife", 1209600);
secDns.put("dsData", Arrays.asList(dsData));
// Or bare public keys instead of DS records, where the registry accepts those:
// secDns.put("keyData", Arrays.asList(key));

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "acme-01");
options.put("secDNS", secDns);

client.domain().create("example.com.ua", options);
```

Карта `secDNS` принимает `dsData`, `keyData` и `maxSigLife` — и ничего больше, а `dsData` и
`keyData` — это **альтернативы**: RFC 5910 §2 и §4 говорят, что их «MUST NOT be mixed», а
`secDNS-1.1.xsd` делает их XSD-выбором, так что блок, несущий и то и другое, отклоняется целиком —
и забирает с собой всю смену DNSSEC. Карта, в которой есть и то и другое, отклоняется здесь, с
`ValidationException`. Чтобы отправить DS-запись вместе с DNSKEY, из которой она вычислена,
вложите ключ **внутрь** записи (ключ `keyData` в записи `dsData`) — это единственное вложение,
которое разрешает RFC 5910. Карта `secDNS`, в которой нет ни `dsData`, ни `keyData`, не отправляет
блок DNSSEC вовсе, потому что `<secDNS:create/>` без дочерних элементов не проходит схемную проверку реестра.

### лицензия (там, где реестр её требует)

Некоторые реестры не регистрируют отдельные имена без номера товарного знака или лицензии — чаще
всего это короткие и дорогие имена непосредственно под доменом верхнего уровня. Там, где это так,
передавайте его в `license`:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "acme-01");
options.put("license", "TM-2026-000123");   // goes out as <registry:license> in <registry:create>

client.domain().create("example.com.ua", options);
```

Он едет в **собственном** расширении реестра, и его пространство имён клиент читает из `<greeting>` —
см. [Команды](commands.md#собственные-расширения-вашего-реестра). Реестру, который такого расширения
не объявляет, вместо кадра, который тот бы проигнорировал, будет брошено `ConfigException`.

Каким именно именам он нужен — это политика реестра, а не протокола, поэтому спрашивайте у своего.
О том, что вы не угадали, скажут два отказа: имя, которому лицензия нужна, но её не передали, обычно
отклоняется с `2003` (отсутствует обязательный параметр), а лицензия, отправленная туда, где её не
ждут, — с `2306` (недопустимое значение параметра по политике).

### fee: предел того, что вы согласны заплатить

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();

options.put("fee", "100.00");     // "I agree to pay at most 100.00"

// Или тот же лимит с явной валютой. Обе формы живут под одним ключом, поэтому второй
// put ЗАМЕНЯЕТ первый - задавайте одну из них, а не обе, как написано здесь.
Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "100.00");
cap.put("currency", "UAH");
options.put("fee", cap);          // …in that currency
```

Это **предел, а не цена, которую назначаете вы**. Если реальная цена выше — сменился тариф, имя
оказалось премиум-именем, у вас на стороне устарел кеш, — реестр отклоняет команду с `2004` и не
списывает ничего, вместо того чтобы молча выставить вам больше. Без этого ключа команда выполняется,
и списывается собственная цена реестра. Тема целиком — в [Баланс и цены](balance.md).

**Коды ответа:** `1000`; `1001`, когда реестр ставит регистрацию в очередь; `2003` / `2004` / `2005`
/ `2306` (валидация и политика, включая предел `fee` ниже реальной цены); `2104` (недостаточно
средств — [остановите пакет](errors.md)); `2302` (уже зарегистрирован); `2103` (DNSSEC в этой зоне
не предлагается); `2307` (зона не обслуживается).

### Создание по шагам

```java
public DomainCreateBuilder createBuilder(String name);
```

Та же команда, тот же кадр, тот же результат — билдер вызывает `create()`. Меняется одно: опечатка
превращается в несуществующий метод, о котором вам скажет редактор. Каждый шаг описан в
[Билдеры](builders.md).

---

## update

```java
public Response update(String name, Map<String, Object> options);
```

**На проводе:** `<command><update><domain:update>` — RFC 5731 §3.2.5, а в `<extension>`, по
необходимости, `<secDNS:update>`, `<rgp:update>`, `<registry:update>` и `<fee:update>`.

**Обновление в EPP — это дельта, а не замена.** То, о чём вы не упомянули, остаётся ровно таким,
каким было. Блок, в который попадает изменение, *и есть* смысл команды:

| блок | значение |
|---|---|
| `add` | оставить то, что есть, и добавить это |
| `rem` | убрать это, остальное оставить |
| `chg` | заменить это одиночное значение |

| ключ | значение |
|---|---|
| `add` / `rem` | `{"ns": [...], "contacts": {"роль": идентификатор\|список}, "statuses": [...]}` |
| `chg` | `{"registrant": идентификатор, "authInfo": строка, "clearAuthInfo": true}` |
| `secDNS` | `{"add": {...}, "rem": {...}, "remAll": true, "maxSigLife": int}` |
| `restore` | `true` — см. [restore](#restore) |
| `license` | строка — заменяет номер товарного знака или лицензии |
| `fee` | предел, на который вы соглашаетесь, когда изменение платное |

```java
Map<String, Object> add = new LinkedHashMap<String, Object>();
add.put("ns", Arrays.asList("ns3.acme.example"));
add.put("contacts", Collections.singletonMap("tech", "acme-02"));
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

System.out.println(r.code() + " " + r.message());   // 1000, or 1001 when the registry queued it

// An update answers with a result, not an object. Read the new state separately when you need
// to store it:
Response after = client.domain().info("example.com.ua");
System.out.println(String.join(", ", after.nameservers()));
System.out.println(String.join(", ", after.statuses()));
```

Устанавливать вы можете статусы семейства `client*` — `clientHold`, `clientUpdateProhibited`,
`clientTransferProhibited`, `clientDeleteProhibited`, `clientRenewProhibited`. Статусы `server*`
принадлежат реестру, и попытка тронуть их возвращается кодом `2304`. `ok` и `inactive` вычисляются
и не принадлежат никому.

Пустой блок `add` или `rem` не отправляется вовсе. Обновление, в котором нет ни `add`, ни `rem`, ни
`chg` — и нет изменений в расширениях, — это пустая команда, и реестр отклоняет её с `2003`.

### secDNS при обновлении (RFC 5910)

Здесь обновление — тоже дельта, и форма у него другая, чем у блока создания:

```java
// Смена ключа: убрать старую запись DS и добавить новую одной командой, чтобы не возникло окна,
// в котором домен не подписан.
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
// Снять подпись с домена целиком:
Map<String, Object> unsign = new LinkedHashMap<String, Object>();
unsign.put("remAll", Boolean.TRUE);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", unsign);
client.domain().update("example.com.ua", options);
```

```java
// Заменить весь набор ключей одной операцией: remAll плюс записи, которые нужно вернуть.
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
// Изменить только срок жизни подписи:
Map<String, Object> lifetime = new LinkedHashMap<String, Object>();
lifetime.put("maxSigLife", 1209600);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("secDNS", lifetime);
client.domain().update("example.com.ua", options);
```

Запись, названная в `rem`, должна совпадать с тем, что хранит реестр, по каждому полю, а не только
по keyTag. `remAll` и `rem` — альтернативы: если присутствуют оба, уходит `remAll`;
[билдер обновления](builders.md) вообще отказывается принимать такое сочетание, вместо того чтобы
выбирать за вас.

Карта `secDNS`, в которой нет ни `add`, ни `rem`, ни `remAll`, ни `maxSigLife`, не отправляет блок
DNSSEC вовсе: `<secDNS:update/>` без дочерних элементов реестр встречает кодом `2003` на то, что
выглядит как пустая операция, — и задуманное вами изменение DNSSEC пропадёт, а команда при этом
будет считаться неудачной.

### Отзыв утёкшего кода трансфера

```java
// Код ушёл туда, куда не должен был. Уберите его совсем:
Map<String, Object> clear = new LinkedHashMap<String, Object>();
clear.put("clearAuthInfo", Boolean.TRUE);
Map<String, Object> revoke = new LinkedHashMap<String, Object>();
revoke.put("chg", clear);
client.domain().update("example.com.ua", revoke);

// Позже, когда клиенту снова понадобится код:
Map<String, Object> fresh = new LinkedHashMap<String, Object>();
fresh.put("authInfo", "Fresh-D0main-Pw");
Map<String, Object> reissue = new LinkedHashMap<String, Object>();
reissue.put("chg", fresh);
client.domain().update("example.com.ua", reissue);
```

`clearAuthInfo` отправляет `<domain:authInfo><domain:null/></domain:authInfo>`, что **удаляет** код.
Присвоить `authInfo` значение `""` — не то же самое и не решение: пустой пароль остаётся значением,
которое держатель может предъявить, так что домен остаётся ровно настолько же уводимым, как и был.

Одно исключает другое — схема не умеет выразить оба сразу, — поэтому запрос обоих в одном `chg`
поднимает `ValidationException` до того, как что-либо будет отправлено.

**Коды ответа:** `1000`; `1001`, когда команда поставлена в очередь; `2003` / `2004` / `2005` /
`2306`; `2303` (домена нет); `2304` (запрещает статус); `2305` (запрещает связь); `2103` (DNSSEC
здесь не предлагается).

### Обновление по шагам

```java
public DomainUpdateBuilder updateBuilder(String name);
```

Билдер обновления называет блок, в который попадает каждое изменение, — `addNameserver`,
`remStatus`, `changeRegistrant`, `clearAuthInfo` — по той же причине, по которой это делает карта.
См. [Билдеры](builders.md).

---

## delete

```java
public Response delete(String name);
```

**На проводе:** `<command><delete><domain:delete>` — RFC 5731 §3.2.2.

```java
Response before = client.domain().info("example.com.ua");
if (!before.subordinateHosts().isEmpty()) {
    // The registry refuses the delete while hosts live under this domain (2305).
    throw new IllegalStateException("remove " + String.join(", ", before.subordinateHosts()) + " first");
}

Response r = client.domain().delete("example.com.ua");
System.out.println(r.code() + " " + r.message());
```

Что именно делает удаление, зависит от того, где домен находится в своём жизненном цикле: внутри
окна add-grace он убирается немедленно, иначе — переходит в `redemptionPeriod`, откуда его можно
[восстановить](#restore), пока окно не закроется и имя не будет вычищено. Что произошло, покажет
`rgpStatus()` в следующем `info()`.

**Коды ответа:** `1000`; `1001`, когда команда поставлена в очередь; `2303`; `2304` (например,
`clientDeleteProhibited`); `2305` (под доменом ещё есть подчинённые хосты).

---

## renew

```java
public Response renew(String name, String curExpDate, int years, Object fee);
public Response renew(String name, String curExpDate, int years);
public Response renew(String name, String curExpDate);
```

**На проводе:** `<command><renew><domain:renew>` с `<domain:name>`, `<domain:curExpDate>` и
`<domain:period unit="y">` — RFC 5731 §3.2.3. **Плата за продление списывается при успехе.**

`curExpDate` обязан совпадать с **текущей** датой истечения срока домена. Это не формальность:
именно он не даёт дублю или повтору продления добавить второй год. Читайте дату из реестра, а не из
собственного кеша.

**Передавайте `expiryDate()` как есть.** Это два разных XML-типа — `<domain:exDate>` есть отметка
времени, а `<domain:curExpDate>` — дата, — и дневную часть библиотека берёт сама:

```java
Response info = client.domain().info("example.com.ua");
// info.expiryDate() is "2027-04-01T09:15:00.0Z"; "2027-04-01" is what goes on the wire.

Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "90.00");
cap.put("currency", "UAH");

Response r = client.domain().renew("example.com.ua", info.expiryDate(), 1, cap);

System.out.println("new expiry: " + r.expiryDate());   // the registry's own string - store as is
System.out.println("charged:    " + (r.feeAmount() != null ? r.feeAmount() : "-")
        + " " + (r.feeCurrency() != null ? r.feeCurrency() : ""));
```

Дата берётся **так, как её написал сервер**, без разбора и без перевода часовых поясов. Это сделано
намеренно: отметки времени в EPP — в UTC, и дата истечения у реестра тоже в UTC, поэтому клиент,
который переформатирует её через местный пояс, для каждого домена, истекающего около полуночи,
попадает на сутки в ту или другую сторону — и затем продлевает по дате, которой у реестра нет. Если
нужно местное время, переводите его там, где показываете, а не перед отправкой обратно.

Несовпадение `curExpDate` возвращается как `2105`, и верить надо именно этому ответу: срок истечения
у домена не тот, который вы предполагали, — перечитайте его, прежде чем делать что-либо ещё. `2105`
никогда не повод отправить тот же кадр повторно.

**Коды ответа:** `1000`; `2105` (несовпадение `curExpDate` либо домен не подлежит
продлению); `2104` (недостаточно средств); `2303`; `2304`; `2306`. Период вне границы 1–99 из
RFC 5731 до реестра никогда не доходит: эта библиотека отклоняет его с `ValidationException`,
называющим значение, так что `2004`, который дала бы схема, вы здесь не увидите. Реестр всё
равно может ответить `2004` или `2306` на период, который он принимает в принципе, но не
предлагает в этой зоне.

---

## transfer

```java
public Response transfer(String op, String name, String authInfo, Integer years, Object fee);
public Response transfer(String op, String name, String authInfo);
public Response transfer(String op, String name);
```

**На проводе:** `<command><transfer op="…"><domain:transfer>` — RFC 5731 §3.2.4 (и §3.1.3 для
`query`). `op` — одно из `request`, `query`, `approve`, `reject`, `cancel`, и **больше ничего**:
RFC 5730 закрывает этот набор, так что шестое имя — это `ValidationException`, называющее эти пять, а
не кадр, который реестр отклоняет, пока окно трансфера продолжает идти.

| `op` | кто отправляет | что делает |
|---|---|---|
| `request` | принимающий регистратор | запрашивает домен, передавая текущий `authInfo` |
| `query` | любая из сторон | сообщает, на какой стадии запрос, ничего не меняя |
| `approve` | текущий владелец | принимает ожидающий запрос |
| `reject` | текущий владелец | отклоняет ожидающий запрос |
| `cancel` | запрашивающий регистратор | отзывает собственный запрос |

`years` уходит как `<domain:period unit="y">` **только если вы передали число**, поэтому `null`
опускает элемент целиком. Что из двух нужно зоне — политика реестра: зоны, где в трансфер включено
обязательное продление на год, принимают `1` (или значение по умолчанию при опущенном элементе), а
зоны, где трансфер бесплатен и ничего не меняет, требуют не отправлять элемент вовсе. Что из этого
относится к зоне, из которой вы переносите домен, спросите в реестре.

### Запрос входящего трансфера

```java
Response r = client.domain().transfer("request", "example.com.ua",
        "the-code-from-the-losing-registrar", 1, null);

r.code();               // 1001 - accepted and pending, not done
r.transferStatus();     // "pending"

Map<String, String> t = r.transfer();      // the whole trnData block
// {
//   status=pending,
//   requestedBy=EXAMPLE,                  // reID
//   requestedAt=2026-04-01T09:15:00Z,     // reDate
//   actingClient=DELTA,                   // acID - who has to answer
//   actBy=2026-04-06T09:15:00Z,           // acDate - the deadline
//   expiryDate=2028-04-01T09:15:00Z       // the expiry this transfer would set
// }
```

`transferStatus()` сам по себе говорит, что трансфер в ожидании, но не говорит, чей он и сколько
времени у кого-либо осталось на действие. `transfer()` даёт `actBy`, и значение имеет именно эта
дата: **молчание завершает трансфер.** По истечении срока решает реестр, и в этих зонах он
подтверждает. Владелец, который подшил poll-уведомление вместо того, чтобы ответить на него, теряет
домен.

### Ответ на трансфер со стороны теряющего регистратора

Запрос приходит к вам [poll-уведомлением](poll.md) с полезной нагрузкой `trnData`. Ответьте на
него:

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

### Проверка и отзыв

```java
client.domain().transfer("query", "example.com.ua");    // 2300 pending, 2301 nothing there
client.domain().transfer("cancel", "example.com.ua");   // withdraw your own request
```

Пока у домена стоит `pendingTransfer`, никакая другая операция с ним не принимается, включая
автоматические.

**Коды ответа:** `1000` / `1001`; `2201` (объект не ваш, действовать с ним нельзя); `2202` (неверный
`authInfo`); `2300` (трансфер уже в ожидании); `2301` (в ожидании нет ничего, что можно принять,
отклонить, отозвать или запросить); `2304`; `2306`; `2106` (не подлежит трансферу).

---

## restore

```java
public Response restore(String name, Object fee);
public Response restore(String name);
```

**На проводе:** `<update>`, который несёт `<domain:chg/>` и больше ничего, плюс
`<rgp:update><rgp:restore op="request"/>` в расширении — RFC 3915. Это ровно то же самое, что
`update(name, Collections.singletonMap("restore", true))`, и одно взаимозаменяемо с другим.
**Плата за восстановление списывается при успехе**, и обычно это самая дорогая операция в каталоге.

Восстановление **не меняет ничего другого**, но обновление, на котором оно едет, всё равно
обязано нести блок. RFC 3915 §4.2.5: «at least one empty `<domain:add>`, `<domain:rem>`, or
`<domain:chg>` element MUST be present if this extension is specified within an `<update>` command»,
и собственный пример RFC несёт `<domain:chg/>`. Библиотека выводит этот пустой элемент за вас.
Поэтому не отправляйте вместе с восстановлением своего изменения — примените его потом, второй
командой, — но и не ждите, что кадр окажется обновлением совсем без блоков: обновление,
несущее только имя, — пустая операция, на которую часть реестров отвечает 2003, а
восстановление, которого не произошло, — это домен, который покидает редемпционный
период тем, что его удаляют.

```java
Response info = client.domain().info("example.com.ua");

if (info.rgpStatus().contains("redemptionPeriod")) {
    Response r = client.domain().restore("example.com.ua", "1000.00");   // your cap, nobody's tariff

    System.out.println(r.code());                 // 1000 restored, 1001 queued
    System.out.println("charged: " + (r.feeAmount() != null ? r.feeAmount() : "-"));

    Response after = client.domain().info("example.com.ua");
    System.out.println("rgp:     "
            + (after.rgpStatus().isEmpty() ? "-" : String.join(", ", after.rgpStatus())));
    System.out.println("expires: " + after.expiryDate());
}
```

Читайте `rgpStatus()`, а не `statuses()`: состояния выкупа приходят в `<extension>` как
`<rgp:infData>`, поэтому клиент, читающий только `<domain:status>`, увидит домен за считанные дни до
удаления с обычным `ok`.

Восстановление возможно только внутри окна восстановления. После него имя высвобождается, и
восстанавливать уже нечего.

**Коды ответа:** `1000`; `1001`, когда восстановление завершается асинхронно; `2104` (недостаточно
средств); `2303`; `2304` (домен не в том состоянии, из которого его восстанавливают); `2306`.

---

## Когда трансформирующая команда упала и вы не знаете, выполнилась ли она

Тайм-аут чтения или оборванное соединение посреди `create`, `renew` или `transfer` оставляют
по-настоящему неизвестный исход: реестр мог выполнить команду и списать деньги ещё до того, как
ответ потерялся. Ни эта библиотека, ни исключение отличить одно от другого не могут.

**Не повторяйте команду просто так.** Слепой повтор — это то, как домен регистрируется, и
оплачивается, дважды. Вместо этого спросите у реестра, как обстоит дело: после создания — `info()`,
после продления — сравнение `expiryDate()` с тем, что вы ожидали. Повторяйте, только если объект
действительно в том состоянии, с которого вы начали. Правило целиком, вместе с таксономией
исключений, — в [Ошибки](errors.md).

---

## Коды ответа на этой странице

| Код | Значение | Исключение |
|---|---|---|
| `1000` | выполнено | — |
| `1001` | принято, завершается офлайн; итог приходит через [poll](poll.md) | — |
| `2003` | отсутствует обязательный параметр | `CommandException` |
| `2004` | значение вне диапазона — в том числе предел `fee` ниже реальной цены | `CommandException` |
| `2005` | значение синтаксически некорректно | `CommandException` |
| `2103` | расширение не поддерживается для этой зоны | `CommandException` |
| `2104` | недостаточно средств; ничего не зарегистрировано и не списано | `InsufficientFundsException` |
| `2105` | несовпадение `curExpDate` либо домен не подлежит продлению | `CommandException` |
| `2106` | не подлежит трансферу | `CommandException` |
| `2201` | объект не ваш, действовать с ним нельзя | `AuthorizationException` |
| `2202` | неверный `authInfo` | `AuthorizationException` |
| `2300` / `2301` | трансфер уже в ожидании / трансфера в ожидании нет | `CommandException` |
| `2302` | уже зарегистрирован | `ObjectExistsException` |
| `2303` | домена нет | `ObjectDoesNotExistException` |
| `2304` / `2305` | операцию запрещает статус или связь | `ObjectStatusException` |
| `2306` / `2308` | политика реестра отклоняет это значение | `PolicyException` |
| `2307` | зона не обслуживается | `CommandException` |

У `ResultCode` есть именованная константа для каждого из них. Полная таксономия, правила повторов и
альтернатива `throwOnFailure(false)` — в [Ошибки](errors.md).

---

См. также: [Контакты](contacts.md) · [Хосты](hosts.md) · [Poll](poll.md) ·
[Баланс и цены](balance.md) · [Ответы](responses.md) · [Билдеры](builders.md)

[← Оглавление руководства](README.md)
