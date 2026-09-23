# Команды

Всё, что идёт после входа, — это команда и её ответ. Эта страница — весь набор команд целиком: как
добраться до команды, что она отдаёт, как помечаются транзакции, как отключить исключения и как
отправить кадр, который библиотека не моделирует.

Подробности по каждому объекту — на своей странице: [Домены](domains.md),
[Контакты](contacts.md), [Хосты](hosts.md), [Poll](poll.md), [Баланс](balance.md).

## Как добраться до команды

На клиенте висят четыре обработчика плюс запрос баланса, который является отдельным методом:

```java
client.domain();    // com.epptools.sdk.command.Domain  - RFC 5731
client.contact();   // com.epptools.sdk.command.Contact - RFC 5733
client.host();      // com.epptools.sdk.command.Host    - RFC 5732
client.poll();      // com.epptools.sdk.command.Poll    - RFC 5730 section 2.9.2.3
client.balance();   // the registry's own balance extension - a Response, not a handler
```

Каждый обработчик создаётся один раз на клиент и возвращается снова при каждом вызове, поэтому
`client.domain().check(…)` внутри цикла ничего дополнительно не стоит.

## Весь набор целиком

| Метод | Что отправляет | Где описан |
|---|---|---|
| `domain().check(List<String> names, Map<String, Object> fee, String currency): Response` | `domain:check`, при необходимости с `fee:check` | [Домены](domains.md), [Баланс](balance.md) |
| `domain().info(String name, String authInfo, String hosts): Response` | `domain:info` | [Домены](domains.md) |
| `domain().create(String name, Map<String, Object> options): Response` | `domain:create` | [Домены](domains.md) |
| `domain().update(String name, Map<String, Object> options): Response` | `domain:update` | [Домены](domains.md) |
| `domain().renew(String name, String curExpDate, int years, Object fee): Response` | `domain:renew` | [Домены](domains.md) |
| `domain().restore(String name, Object fee): Response` | `domain:update` с `rgp:restore` (RFC 3915) | [Домены](domains.md) |
| `domain().delete(String name): Response` | `domain:delete` | [Домены](domains.md) |
| `domain().transfer(String op, String name, String authInfo, Integer years, Object fee): Response` | `domain:transfer` | [Домены](domains.md) |
| `domain().createBuilder(String name): DomainCreateBuilder` | ничего до `send()` | [Билдеры](builders.md) |
| `domain().updateBuilder(String name): DomainUpdateBuilder` | ничего до `send()` | [Билдеры](builders.md) |
| `contact().check(List<String> ids): Response` | `contact:check` | [Контакты](contacts.md) |
| `contact().info(String id, String authInfo): Response` | `contact:info` | [Контакты](contacts.md) |
| `contact().create(String id, Map<String, Object> options): Response` | `contact:create` | [Контакты](contacts.md) |
| `contact().createAuto(Map<String, Object> options): Response` | `contact:create` с зарезервированным идентификатором `Contact.AUTO_ID` | [Контакты](contacts.md) |
| `contact().update(String id, Map<String, Object> options): Response` | `contact:update` | [Контакты](contacts.md) |
| `contact().delete(String id): Response` | `contact:delete` | [Контакты](contacts.md) |
| `contact().transfer(String op, String id, String authInfo): Response` | `contact:transfer` | [Контакты](contacts.md) |
| `contact().createBuilder(String id, String email): ContactCreateBuilder` | ничего до `send()` | [Билдеры](builders.md) |
| `contact().updateBuilder(String id): ContactUpdateBuilder` | ничего до `send()` | [Билдеры](builders.md) |
| `host().check(List<String> names): Response` | `host:check` | [Хосты](hosts.md) |
| `host().info(String name): Response` | `host:info` | [Хосты](hosts.md) |
| `host().create(String name, List<String> addresses): Response` | `host:create` | [Хосты](hosts.md) |
| `host().update(String name, Map<String, Object> options): Response` | `host:update` | [Хосты](hosts.md) |
| `host().delete(String name, boolean force): Response` | `host:delete`, при необходимости с расширением реестра для принудительного удаления | [Хосты](hosts.md) |
| `host().updateBuilder(String name): HostUpdateBuilder` | ничего до `send()` | [Билдеры](builders.md) |
| `poll().request(): Response` | `<poll op="req">` | [Poll](poll.md) |
| `poll().ack(String messageId): Response` | `<poll op="ack">` | [Poll](poll.md) |
| `poll().drain(Consumer<Response> handler, int limit): int` | request/ack в цикле | [Poll](poll.md) |
| `balance(): Response` | `balance:info` | [Баланс](balance.md) |
| `request(Frame frame): Response` · `request(String xml): Response` | то, что вы собрали | [ниже](#произвольные-кадры) |
| `frame(): Frame` | ничего; возвращает кадр с проставленным идентификатором | [ниже](#произвольные-кадры) |

То, что в таблице стоит последними аргументами, почти всюду необязательно, и оформлено это
перегрузками: `check(names)`, `info(name)`, `info(name, authInfo)` у контакта, `renew(name,
curExpDate)`, `renew(name, curExpDate, years)`, `transfer(op, name)`, `transfer(op, name, authInfo)`,
`restore(name)`, `create(name)` и `delete(name)` у хоста, `drain(handler)`. Перегрузка подставляет
ровно то же значение, что стояло бы в полной форме.

Методы сессии — `connect()`, `hello()`, `login()`, `logout()`, `disconnect()` — описаны в
[Сессии](session.md).

## Что возвращает команда

**Каждая команда возвращает [`Response`](responses.md).** Не `null`, не карту, не bool. Объект
оборачивает разобранный ответ, и все аксессоры читают из него.

```java
Response response = client.domain().check(Arrays.asList("example.com.ua"));

response.code();        // int:     1000
response.isSuccess();   // boolean: true for any 1xxx
response.message();     // String:  the server's <msg>, in your session's language
response.svTRID();      // String:  the registry's transaction id - store it
```

Три исхода, под которые нужно писать код:

| Код | Значение | Что делать |
|---|---|---|
| `1000` | выполнено | продолжайте |
| `1001` | принято, завершается офлайн | **не отправляйте повторно.** Объект несёт статус `pending*`, а результат приходит позже как poll-уведомление |
| `2xxx` | отказано; ничего не изменено | прочитайте код — по умолчанию он уже выброшен как исключение |

`1001` — именно тот, на котором спотыкаются. Это код успеха, поэтому `isSuccess()` возвращает `true`
и никакого исключения нет. Проверяйте его явно:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");

Response response = client.domain().create("example.com.ua", options);

if (response.isPending()) {
    // Store svTRID() beside the order. The verdict arrives in the poll queue as
    // pendingActionData(), and its svTRID from paTRID matches the verdict to this command.
    orders.markPending(response.svTRID());
}
```

Вторая половина этого обмена — в [Poll](poll.md).

Ещё два кода означают успех и не должны читаться как неудача: **1300** (poll: очередь пуста) и
**1500** (ответ на `logout`).

## Клиентские идентификаторы транзакций
Каждая команда несёт `clTRID`, который выбираете вы, а каждый ответ несёт `svTRID`, который назначает
реестр.

| Идентификатор | Кто его задаёт | Для чего он |
|---|---|---|
| `clTRID` | этот клиент | сопоставить ответ с запросом, который его вызвал |
| `svTRID` | реестр | собственная запись реестра об этой операции |

Библиотека проставляет уникальный `clTRID` на каждом собираемом ею кадре. Форма такая:

```
JAVA-SDK-20260816103000-24191-0007
    │            │          │    └── счётчик, монотонный в пределах этого экземпляра клиента
    │            │          └──────── идентификатор процесса ОС, чтобы два рабочих процесса не столкнулись
    │            └─────────────────── отметка времени UTC, YYYYMMDDHHMMSS: когда
    └──────────────────────────────── Config.clTRIDPrefix
```

Задайте в `clTRIDPrefix` что-то, по чему узнаётся ваша система. Это метка для сопоставления
человеком, а не секрет, и именно её вам зачитает поддержка реестра.

**Сохраняйте `svTRID` рядом с объектом, которого касалась команда.** Это единственное значение, по
которому поддержка может найти операцию; `clTRID` не значит ничего ни для кого, кроме вас.
Записывайте оба, на каждой команде, включая удавшиеся: именно с ними вы сравниваете, когда более
поздняя не удаётся.

### Проверка отражённого идентификатора
Сервер отражает ваш `clTRID` обратно (RFC 5730 §2.5). Поскольку этот клиент создаёт уникальный
идентификатор на каждую команду, их сравнение превращает любую рассинхронизацию потока из тихой
подмены в громкий сбой: без него ответ, принадлежащий предыдущей команде, неотличим от ответа на
текущую, а для продления или регистрации это значит записать выполненным не тот домен.

Несовпадение выбрасывает `ConnectionException` **и закрывает соединение**: как только смещения
разошлись, подозрителен и каждый следующий кадр в этом потоке. Сравнение допускает сервер, который
нормализует значение, поскольку схемный тип идентификатора транзакции — это 3–64 символа: законно
усечённое или дополненное отражение принимается, неверное — нет.

## Переключатель `throwOnFailure`
По умолчанию любой код ответа от 2000 и выше выбрасывается как `CommandException` (или как
подходящий коду подкласс). Именно это делает линейную интеграцию правильной по умолчанию: нельзя
забыть проверить код, которого вы никогда не видите.

```java
client.throwOnFailure(false);   // and (true) to switch it back on
```

С выключенным переключателем отказ возвращается обычным `Response`, и вы сами ветвитесь по `code()`:

```java
import com.epptools.sdk.ResultCode;

client.throwOnFailure(false);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");

Response response = client.domain().create("example.com.ua", options);
if (response.code() == ResultCode.OBJECT_EXISTS) {
    taken.add("example.com.ua");
} else if (!response.isSuccess()) {
    throw new IllegalStateException(response.message() != null ? response.message() : "create failed");
}
```

Переключатель возвращает клиент, поэтому его можно вызывать по цепочке, и он действует на все
последующие команды этого клиента, пока вы не вернёте его обратно.

Что он **не** отключает:

- `ConnectionException` — сервер не ответил вовсе, читать нечего.
- `ValidationException` и `ConfigException` — не было отправлено вообще ничего.
- `AuthenticationException` из `login()`. Неудавшийся вход — это не та сессия, в которой можно
  продолжать, поэтому исключение выбрасывается, что бы ни говорил переключатель.
- Отказ, который выбрасывает `poll().drain()`, когда ответ на poll — это ни уведомление, ни пустая
  очередь. С выключенными исключениями такой ответ доходит до цикла, и цикл выбрасывает его явно, а
  не читает отказ как опустошённую очередь.

В `com.epptools.sdk.ResultCode` есть именованная константа для каждого кода — см. таблицу в
[Ошибках](errors.md#коды-ответа).

## Ключи опций проверяются до сборки кадра

Команды, принимающие карту опций, отклоняют непонятный им ключ и называют ближайший, который
понимают:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("secdns", secDns);

client.domain().create("example.com.ua", options);
// ValidationException: domain:create does not accept 'secdns' (did you mean 'secDNS'?).
// Accepted: authInfo, contacts, fee, license, nameServers, nameservers, registrant, secDNS, years.
```

Это осознанный размен. Иначе карта опций принимает что угодно: ключ с опечаткой, в неверном
регистре или оставшийся от старой интеграции просто никогда не читается. Команда всё равно уходит,
реестр всё равно отвечает 1000, а того, что вы просили, нет: `"secdns"` вместо `"secDNS"`
регистрирует домен **неподписанным**, `"nameservers"` с опечаткой — **без делегирования**. В ответе
об этом ничего не сказано, потому что с точки зрения реестра вы и не просили.

Там, где оба написания разумны, принимаются оба: `nameservers` и `nameServers` в создании домена —
одна и та же опция. Вложенные блоки тоже проверяются: у блоков `add`, `rem`, `chg` и `secDNS` в
update свой список у каждого.

[Билдеры](builders.md) убирают этот класс ошибок целиком: шаг с опечаткой — это несуществующий
метод, и ваш редактор скажет об этом прямо при наборе.

## Произвольные кадры
Всё, что не покрывает высокоуровневый API, можно собрать через `com.epptools.sdk.Frame` и отправить через
`Client.request()`.

```java
import com.epptools.sdk.Frame;
import com.epptools.sdk.Namespaces;
import org.w3c.dom.Element;

Frame frame = client.frame();                    // <command> with a clTRID already stamped
Element check = frame.ns(frame.verb("check"), Namespaces.DOMAIN, "domain:check");
frame.ns(check, Namespaces.DOMAIN, "domain:name", "example.com.ua");

Response response = client.request(frame);
response.availability();
```

`Client.frame()` — та точка входа, которая вам нужна: она возвращает `Frame` со сгенерированным
`clTRID`, поэтому [проверка отражённого идентификатора](#проверка-отражённого-идентификатора) продолжает защищать обмен.
`Frame.command(clTRID)` собирает кадр с вашим собственным идентификатором, и тогда за уникальность
отвечаете вы.

### API `Frame`

| Метод | Что делает |
|---|---|
| `Frame.command(String clTRID): Frame` | Начать кадр `<command>` с этим идентификатором транзакции. |
| `verb(String name): Element` | Добавить глагол команды — `check`, `info`, `create`, `update`, `renew`, `transfer`, `delete`, `poll`, `login`, `logout` — и вернуть его, чтобы навесить содержимое. |
| `extension(): Element` | Элемент `<extension>`: создаётся при первом вызове и возвращается снова при последующих, поэтому несколько расширений делят один блок. |
| `epp(Element parent, String name, String text, Map<String, ?> attrs): Element` | Добавить элемент в базовом пространстве имён `epp-1.0`, без префикса. |
| `ns(Element parent, String nsUri, String qname, String text, Map<String, ?> attrs): Element` | Добавить элемент с пространством имён, несущий свой префикс, например `domain:name`. |
| `root(): Element` | Корневой элемент `<epp>` — для всего, чего два помощника добавления выразить не могут. |
| `toXml(): String` | Сериализовать. `clTRID` пишется последним потомком `<command>` — в том порядке, который закрепляет RFC 5730. |

У `epp()` и `ns()` есть короткие формы без текста и без атрибутов. Текст, переданный в них,
добавляется как текстовый узел, а не склеивается строками, поэтому `&` и `<` в значении экранируются
за вас. `toXml()` безопасно вызывать несколько раз — записать кадр в журнал, затем отправить, — и
результат несёт ровно один `clTRID`, потому что `<command>` с двумя получает голый 2001.

### `request(Frame frame): Response` и `request(String xml): Response`

Отправляет кадр и возвращает разобранный ответ. Принимает `Frame` или сырой XML:

```java
Response fromFrame = client.request(client.frame());
Response fromXml = client.request("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
        + "<epp xmlns=\"urn:ietf:params:xml:ns:epp-1.0\"><hello/></epp>");
```

Произвольный кадр получает всё то же, что и обычная команда: кадрирование по длине, запись в журнал
с замаскированными секретами, проверку отражённого `clTRID` и поведение `throwOnFailure`.

### Расширение, которое библиотека не моделирует

Шаблон того, как везти расширение вместе со стандартной командой; здесь — выдуманное пространство
имён на `domain:info`:

```java
String thing = "urn:example:params:xml:ns:thing-1.0";

Frame frame = client.frame();
Element info = frame.ns(frame.verb("info"), Namespaces.DOMAIN, "domain:info");
Map<String, Object> attrs = new LinkedHashMap<String, Object>();
attrs.put("hosts", "all");
frame.ns(info, Namespaces.DOMAIN, "domain:name", "example.com.ua", attrs);

Element block = frame.ns(frame.extension(), thing, "thing:info");
frame.ns(block, thing, "thing:detail", "full");

Response response = client.request(frame);
for (String detail : response.values("detail")) {
    System.out.println(detail);
}
```

Два момента, которые важно не упустить. Объявите URI расширения при входе — через `extUris` в
конфигурации или позволив приветствию подставить его, — иначе у сервера нет причин возвращать данные
этого расширения. И читайте ответ через `value()` и `values()` либо прямо по `dom()`, если нужно
дерево: именованные аксессоры знают только те расширения, которые моделирует библиотека; см.
[Ответы](responses.md#прямой-доступ).

### Константы пространств имён

`com.epptools.sdk.Namespaces` содержит те самые строки, которые идут на провод:

| Константа | URI | Чем определено |
|---|---|---|
| `EPP` | `urn:ietf:params:xml:ns:epp-1.0` | RFC 5730 |
| `DOMAIN` | `urn:ietf:params:xml:ns:domain-1.0` | RFC 5731 |
| `HOST` | `urn:ietf:params:xml:ns:host-1.0` | RFC 5732 |
| `CONTACT` | `urn:ietf:params:xml:ns:contact-1.0` | RFC 5733 |
| `SECDNS` | `urn:ietf:params:xml:ns:secDNS-1.1` | RFC 5910 (DNSSEC) |
| `RGP` | `urn:ietf:params:xml:ns:rgp-1.0` | RFC 3915 (восстановление) |
| `FEE` | `urn:ietf:params:xml:ns:epp:fee-1.0` | RFC 8748 (цены) |
| `LOGINSEC` | `urn:ietf:params:xml:ns:epp:loginSec-1.0` | RFC 8807 (безопасность входа) |
| `CHANGEPOLL` | `urn:ietf:params:xml:ns:changePoll-1.0` | RFC 8590 (изменения, которых вы не делали) |
| `XSI` | `http://www.w3.org/2001/XMLSchema-instance` | XML Schema |

Ещё три записи — значения, а не пространства имён: `LOGINSEC_SENTINEL` — это зарезервированная
строка `[LOGIN-SECURITY]`, которую RFC 8807 помещает в `<pw>`, когда настоящий пароль едет в
расширении, а `DEFAULT_OBJ_URIS` и `DEFAULT_EXT_URIS` — перечни сервисов, применяемые только если
приветствие пришло вообще без меню сервисов.

### Собственные расширения вашего реестра

Каждый URI выше определён каким-нибудь RFC и одинаков у любого реестра в мире. С СОБСТВЕННЫМИ
расширениями реестра — лицензией на торговую марку, ценой, балансом учётной записи — это уже не так,
и константы для них здесь не будет: нет значения, которое было бы верным более чем для одного
реестра.

Они **определяются из `<greeting>`**. Любой сервер перечисляет то, что поддерживает, ещё до того как
вы что-то отправите, поэтому сразу после `connect()` клиент уже знает:

```java
client.connect();

client.registryExtUri();      // e.g. "http://registry.example/epp/registry-1.0", or null
client.registryBalanceUri();  // e.g. "http://registry.example/epp/balance-1.0", or null
```

`null` означает, что этот сервер такого расширения не объявляет, — это факт о сервере, а не ошибка.
Команды, которым расширение необходимо, об этом говорят, а не догадываются: `domain:create` с
`license`, `host:delete` с `force` и `balance()` бросают `ConfigException`, называя, что именно
понадобилось, и перечисляя, что сервер предложил. В этом отказе и есть смысл. Расширение, отправленное
в пространстве имён, которого сервер не знает, **игнорируется, а не отвергается**, — то есть догадка
вернулась бы как `1000 OK` с молча не установленной лицензией.

`requireRegistryExtUri(String what)` — тот же поиск с уже встроенным отказом, для вашей собственной
команды, которая не может выполниться без этого пространства имён.

Определение сопоставляет последний сегмент объявленного URI — `.../registry-1.0`, `urn:…:balance`, —
и это соглашение, которому реестры следуют, а не правило, которое кто-то принуждает соблюдать. Если
реестр называет свои расширения иначе, задайте их сами — тогда приветствие не спрашивается:

```java
Config config = Config.builder("epp.registry.example", "EXAMPLE", "...")
        .registryExtUri("urn:example:params:xml:ns:myreg-1.0")
        .registryBalanceUri("urn:example:params:xml:ns:myreg-balance-1.0")
        .build();
```

## По одной команде за раз

Отправьте команду, прочитайте ответ, затем отправляйте следующую. Не конвейеризируйте команды в
одном соединении, рассчитывая, что ответы выстроятся по порядку. Если нужна пропускная способность,
открывайте больше сессий, а не накладывайте команды в одной, — и помните про лимит одновременных
сессий, который приходит как 2502.

---

[← К содержанию руководства](README.md)
