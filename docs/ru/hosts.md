# Хосты

Объекты хостов (серверов имён) следуют **RFC 5732**. Там, где реестр делегирует домены по ссылке,
сервер имён обязан существовать как объект хоста прежде, чем [домен](domains.md) сможет указать на
него через `<domain:hostObj>`. Там, где реестр принимает встроенные glue-адреса, объект хоста вам
может не понадобиться вовсе — см.
[две модели серверов имён](domains.md#серверы-имён-две-модели).

Каждая хостовая команда вызывается через `client.host()` и возвращает [`Response`](responses.md).
Всё здесь предполагает подключённый клиент с выполненным входом — см. [Сессия](session.md).

## Методы

| Метод | Команда EPP |
|---|---|
| `check(List<String> names): Response` | `<check>` |
| `info(String name): Response` | `<info>` |
| `create(String name, List<String> addresses): Response` | `<create>` |
| `update(String name, Map<String, Object> options): Response` | `<update>` |
| `updateBuilder(String name): HostUpdateBuilder` | строит `<update>` |
| `delete(String name, boolean force): Response` | `<delete>`, при необходимости с расширением принудительного отсоединения |

Для хоста нет ни `renew`, ни `transfer`: RFC 5732 не определяет ни того, ни другого. Хост следует за
доменом, под которым живёт, и платить за него не за что.

## Подчинённые и внешние хосты

Это различие решает, может ли хост вообще нести адреса, и оно же — источник большинства отказов на
первом запуске:

| | живёт под | glue-адреса |
|---|---|---|
| **подчинённый** | доменом в зоне, которую обслуживает этот реестр (`ns1.example.com.ua` под `example.com.ua`) | **обязательны** — без них создание даёт `2003` |
| **внешний** | доменом в другом месте (`ns1.acme.example`) | **отклоняются** — его адреса живут в его собственном реестре, поэтому отправка адреса даёт `2306` |

Клиент, который всегда отправляет адрес, обязан опускать его для внешних хостов. Адреса должны быть
публичными адресами Интернета, и реестры ограничивают, сколько их может нести один хост —
спросите свой про этот предел; сверх него кадр отклоняется.

---

## check

```java
public Response check(List<String> names);
```

**На проводе:** `<command><check><host:check><host:name>…` — RFC 5732 §3.1.1.

```java
Response r = client.host().check(Arrays.asList("ns1.example.com.ua", "ns2.example.com.ua"));

r.availability();                        // {ns1.example.com.ua=false, ns2.example.com.ua=true}
r.isAvailable("ns2.example.com.ua");     // TRUE | FALSE | null
r.unavailableReason("ns1.example.com.ua");
```

`avail => false` означает, что объект хоста в реестре уже есть, — а это часто ровно то, что вам
нужно: хост, который вы собирались создать, можно просто использовать по ссылке. Объекты хостов
образуют общее для всего реестра пространство имён: сервер имён, созданный другим регистратором,
виден вам, и ссылаются на него по имени.

**Коды ответа:** `1000` на любую корректно сформированную проверку; `2005` называет синтаксически
некорректное имя хоста. **Пустой список** тоже отклоняется, с
`ValidationException`: у кадра, который собрался бы, нет ни одного дочернего элемента, а схема
требует хотя бы одного — так что цикл по строке запроса или по корзине, которая оказалась
пустой, падает здесь, поимённо, а не тратит обращение к серверу.

---

## info

```java
public Response info(String name);
```

**На проводе:** `<command><info><host:info><host:name>` — RFC 5732 §3.1.2. Аргумента `authInfo`
здесь нет: у объекта хоста нет собственного кода авторизации трансфера.

```java
Response h = client.host().info("ns1.example.com.ua");

h.objectName();       // "ns1.example.com.ua"
h.roid();             // the registry's own object id
h.statuses();         // ["ok"], ["linked"], ["clientUpdateProhibited"], …
h.sponsor();          // clID
h.createdBy();        // crID           h.createdDate();   // crDate
h.updatedBy();        // upID or null   h.updatedDate();   // upDate

for (Map<String, String> addr : h.hostAddresses()) {
    System.out.println(addr.get("version") + " " + addr.get("ip"));   // "v4 203.0.113.10"
}
```

`hostAddresses()` возвращает `[{ip=203.0.113.10, version=v4}, …]`. **Пустой список —
нормальный ответ для внешнего хоста**, а не пропажа: glue-адреса несёт только хост внутри зоны,
которую обслуживает реестр.

Статус `linked` означает, что как минимум один домен использует этот хост как сервер имён. Именно
он стоит между вами и [удалением](#delete).

**Коды ответа:** `1000`; `2303` (такого хоста нет).

---

## create

```java
public Response create(String name, List<String> addresses);
public Response create(String name);
```

**На проводе:** `<command><create><host:create>` с одним `<host:addr ip="v4|v6">` на каждый адрес —
RFC 5732 §3.2.1.

Версия IP определяется по самому литералу, поэтому вы передаёте плоский список, а `v4` и `v6`
проставляются правильно:

```java
// A subordinate host: the glue addresses are required.
Response r = client.host().create("ns1.example.com.ua",
        Arrays.asList("203.0.113.10", "2001:db8::10"));

System.out.println(r.objectName() + " created " + r.createdDate());

// An external host: no addresses at all.
client.host().create("ns1.acme.example");
```

Первое делегирование целиком — создать хосты, затем указать на них домен:

```java
Map<String, String> wanted = new LinkedHashMap<String, String>();
wanted.put("ns1.example.com.ua", "203.0.113.10");
wanted.put("ns2.example.com.ua", "203.0.113.11");

for (Map.Entry<String, String> entry : wanted.entrySet()) {
    String ns = entry.getKey();
    if (Boolean.TRUE.equals(client.host().check(Arrays.asList(ns)).isAvailable(ns))) {
        client.host().create(ns, Arrays.asList(entry.getValue()));
    }
}

client.domain().update("example.com.ua", Collections.<String, Object>singletonMap(
        "add", Collections.singletonMap("ns",
                Arrays.asList("ns1.example.com.ua", "ns2.example.com.ua"))));

System.out.println(String.join(", ", client.domain().info("example.com.ua").nameservers()));
```

**Коды ответа:** `1000`; `2001` (адресов больше, чем разрешено на хост); `2003` (подчинённый хост
без адреса); `2005` (испорченный адрес или имя); `2302` (хост уже существует); `2306` (адрес у
внешнего хоста).

---

## update

```java
public Response update(String name, Map<String, Object> options);
```

**На проводе:** `<command><update><host:update>` — RFC 5732 §3.2.5. Как и всякое обновление в EPP,
это **дельта**: то, о чём вы не упомянули, остаётся нетронутым.

| ключ | значение | на проводе |
|---|---|---|
| `addAddresses` | `List<String>` | `<host:addr>` внутри `<host:add>` |
| `remAddresses` | `List<String>` | `<host:addr>` внутри `<host:rem>` |
| `addStatuses` | `List<String>` | `<host:status s="…">` внутри `<host:add>` |
| `remStatuses` | `List<String>` | `<host:status s="…">` внутри `<host:rem>` |

```java
// Change a nameserver's address: add the new one and remove the old one in one command, so the
// host is never left without glue.
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("addAddresses", Arrays.asList("203.0.113.20"));
options.put("remAddresses", Arrays.asList("203.0.113.10"));

client.host().update("ns1.example.com.ua", options);

List<String> ips = new ArrayList<String>();
for (Map<String, String> addr : client.host().info("ns1.example.com.ua").hostAddresses()) {
    ips.add(addr.get("ip"));
}
System.out.println(String.join(", ", ips));
```

Адрес, который вы удаляете, должен совпадать с тем, что хранит реестр. Блок для стороны, которой вы
не пользуетесь, не отправляется вовсе, поэтому карта с одним лишь `addAddresses` даёт
`<host:add>` и ничего больше.

Устанавливать вы можете статусы семейства `client*` — `clientUpdateProhibited` и
`clientDeleteProhibited`. `linked`, `ok` и статусы `server*` принадлежат реестру.

Здесь действуют те же правила для адресов, что и при создании: внешний хост не может получить
адреса (`2306`), а подчинённый не может остаться без единого (`2003`).

**Коды ответа:** `1000`; `2001` (адресов больше, чем разрешено на хост); `2003`; `2303`; `2304`
(запрещает статус); `2306`.

### Обновление по шагам

```java
public HostUpdateBuilder updateBuilder(String name);
```

`addAddress` / `addAddresses`, `remAddress` / `remAddresses`, `addStatus`, `remStatus`, затем
`send()`. Каждый шаг описан в [Билдеры](builders.md).

---

## Переименования не существует

**Эта библиотека отказывается переименовывать объект хоста**, и причина здесь в политике, а не в
протоколе. RFC 5732 §3.2.5 как раз определяет переименование — через `<host:chg><host:name>`, — но
реестры, против которых это строилось, читают только блоки `add` и `rem` команды
`host:update` и отбрасывают `chg` без всяких слов. Против такого реестра кадр, несущий
переименование вместе со сменой адреса, применяет адреса, отбрасывает переименование и всё
равно отвечает `1000` — оставляя вас в уверенности, что сервер имён переехал, хотя он
остался на месте. **Спросите у своего реестра, реализует ли он `host:chg`.** Там, где реализует,
три команды ниже всё равно безопасны, и именно их предлагает эта библиотека.

Поэтому `update()` отвергает опцию `newName` сразу:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("newName", "ns9.example.com.ua");

client.host().update("ns1.example.com.ua", options);
// ValidationException: host rename is not supported by this registry (host:chg is ignored) -
// create the new host, re-point the domains with domain:update, then delete the old one
```

Переименование — это вот эта последовательность, и в ней три шага:

```java
// 1. Create the new host with the same addresses.
Response old = client.host().info("ns1.example.com.ua");
List<String> addresses = new ArrayList<String>();
for (Map<String, String> addr : old.hostAddresses()) {
    addresses.add(addr.get("ip"));
}
client.host().create("ns9.example.com.ua", addresses);

// 2. Repoint every domain that uses the old one. The registry keeps no list of them - it comes
//    from your own records of what you delegated where.
for (String domain : yourDomainsUsingIt) {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("add", Collections.singletonMap("ns", Arrays.asList("ns9.example.com.ua")));
    options.put("rem", Collections.singletonMap("ns", Arrays.asList("ns1.example.com.ua")));
    client.domain().update(domain, options);
}

// 3. Only once nothing references it any more - otherwise you get 2305.
client.host().delete("ns1.example.com.ua");
```

Добавляйте раньше, чем убираете, и одной командой на домен, чтобы домен ни на мгновение не остался
неделегированным.

---

## delete

```java
public Response delete(String name, boolean force);
public Response delete(String name);
```

**На проводе:** `<command><delete><host:delete>` — RFC 5732 §3.2.2. С `force` в `<extension>` едет
блок `<registry:delete><registry:deleteNS confirm="yes"/>`.

Хост, который всё ещё служит сервером имён хотя бы одному домену, удалить нельзя: реестр отвечает
**`2305`**. Статус `linked` — предупреждение об этом заранее.

```java
Response h = client.host().info("ns1.example.com.ua");

if (h.statuses().contains("linked")) {
    // Detach it from the domains that use it first, or use the forced delete below.
    return;
}

client.host().delete("ns1.example.com.ua");
```

### Принудительное удаление

```java
client.host().delete("ns1.example.com.ua", true);
```

Хост убирается из набора серверов имён **каждого** домена, который на него ссылался, а затем
удаляется. Обязательный для реестра `confirm="yes"` отправляется за вас — ради этого флаг и сделан
отдельным аргументом, а не значением по умолчанию.

Прежде чем этим пользоваться, поймите цену: домен, у которого осталось меньше серверов имён, чем
требует зона, уходит в `inactive` и перестаёт резолвиться. Это правильный инструмент для сервера
имён, который вы выводите из эксплуатации, и неправильный — для наведения порядка. Там, где это
возможно, сначала переставьте домены и пользуйтесь обычным удалением.

**Коды ответа:** `1000`; `2303` (такого хоста нет); `2305` (всё ещё используется как сервер имён —
при обычном удалении); `2400` (принудительное отсоединение не удалось завершить).

---

## Коды ответа на этой странице

| Код | Значение | Исключение |
|---|---|---|
| `1000` | выполнено | — |
| `2001` | кадр сформирован неверно — например, адресов больше, чем разрешено на хост | `CommandException` |
| `2003` | подчинённый хост без glue-адреса | `CommandException` |
| `2005` | испорченный адрес или имя хоста | `CommandException` |
| `2302` | хост уже существует | `ObjectExistsException` |
| `2303` | такого хоста нет | `ObjectDoesNotExistException` |
| `2304` / `2305` | запрещает статус / всё ещё используется как сервер имён | `ObjectStatusException` |
| `2306` | политика — например, адрес у внешнего хоста | `PolicyException` |
| `2400` | реестр не смог завершить операцию; может быть временным | `CommandException` (`isRetryable()`) |

Опция `newName` до реестра не доходит никогда: это `ValidationException`, поднятый ещё до того, как
построен кадр. У `ResultCode` есть именованная константа для каждого кода выше; полная таксономия —
в [Ошибки](errors.md).

---

См. также: [Домены](domains.md) · [Контакты](contacts.md) · [Poll](poll.md) ·
[Ответы](responses.md) · [Билдеры](builders.md)

[← Оглавление руководства](README.md)
