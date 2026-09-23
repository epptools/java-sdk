# Хости

Об'єкти хостів (серверів імен) відповідають **RFC 5732**. Там, де реєстр делегує домени за
посиланням, сервер імен має існувати як об'єкт хоста, перш ніж [домен](domains.md) зможе вказати на
нього через `<domain:hostObj>`. Там, де реєстр натомість приймає вбудовані glue-адреси, вам,
можливо, не доведеться створювати об'єкт хоста взагалі — див.
[дві моделі серверів імен](domains.md#сервери-імен-дві-моделі).

Кожна команда для хостів доступна через `client.host()` і повертає [`Response`](responses.md).
Усе тут припускає підключений клієнт із виконаним входом — див. [Сесія](session.md).

## Методи

| Метод | Команда EPP |
|---|---|
| `check(List<String> names): Response` | `<check>` |
| `info(String name): Response` | `<info>` |
| `create(String name, List<String> addresses): Response` | `<create>` |
| `update(String name, Map<String, Object> options): Response` | `<update>` |
| `updateBuilder(String name): HostUpdateBuilder` | будує `<update>` |
| `delete(String name, boolean force): Response` | `<delete>`, за потреби з розширенням примусового відкріплення |

Для хоста немає ні `renew`, ні `transfer`: RFC 5732 не визначає жодного з них. Хост іде слідом за
доменом, під яким живе, і виставляти рахунок за нього нема за що.

## Підпорядковані та зовнішні хости

Ця відмінність вирішує, чи може хост узагалі нести адреси, і саме вона є джерелом більшості відмов
під час першого запуску:

| | живе під | glue-адреси |
|---|---|---|
| **підпорядкований** | доменом у зоні, яку обслуговує цей реєстр (`ns1.example.com.ua` під `example.com.ua`) | **обов'язкові** — без жодної create дає `2003` |
| **зовнішній** | доменом деінде (`ns1.acme.example`) | **відхиляються** — його адреси живуть у його власному реєстрі, тож надіслати адресу означає `2306` |

Клієнт, який завжди надсилає адресу, має пропускати її для зовнішніх хостів. Адреси мають бути
публічними інтернет-адресами, і реєстри обмежують, скільки їх може нести один хост — запитайте
свій про цей ліміт; понад нього кадр відхиляється.

---

## check

```java
public Response check(List<String> names)
```

**У каналі передачі:** `<command><check><host:check><host:name>…` — RFC 5732 §3.1.1.

```java
Response r = client.host().check(Arrays.asList("ns1.example.com.ua", "ns2.example.com.ua"));

r.availability();                       // {ns1.example.com.ua=false, ns2.example.com.ua=true}
r.isAvailable("ns2.example.com.ua");    // true | false | null
r.unavailableReason("ns1.example.com.ua");
```

`avail => false` означає, що об'єкт хоста в реєстрі вже існує, — а це часто саме те, що вам
потрібно, бо на хост, який ви збиралися створити, можна просто послатися. Об'єкти хостів — це
простір імен на весь реєстр: сервер імен, створений іншим реєстратором, видно й вам, і посилаєтеся
ви на нього за іменем.

**Коди відповіді:** `1000` на будь-який коректно сформований check; `2005` називає синтаксично
недійсне ім'я хоста. **Порожній список** теж відхиляється, із
`ValidationException`: кадр, який зібрався би, не має жодного нащадка, а схема вимагає хоч одного —
тож цикл за рядком запиту або кошиком, який виявився порожнім, падає тут, поіменно, а не
витрачає звернення до сервера.

---

## info

```java
public Response info(String name)
```

**У каналі передачі:** `<command><info><host:info><host:name>` — RFC 5732 §3.1.2. Аргумента
`authInfo` тут немає: об'єкт хоста не має власного коду трансферу.

```java
Response h = client.host().info("ns1.example.com.ua");

h.objectName();       // "ns1.example.com.ua"
h.roid();             // the registry's own identifier for the object
h.statuses();         // ["ok"], ["linked"], ["clientUpdateProhibited"], …
h.sponsor();          // clID
h.createdBy();        // crID
h.createdDate();      // crDate
h.updatedBy();        // upID, or null
h.updatedDate();      // upDate

for (Map<String, String> addr : h.hostAddresses()) {
    System.out.println(addr.get("version") + " " + addr.get("ip"));   // "v4 203.0.113.10"
}
```

`hostAddresses()` повертає `[{ip=203.0.113.10, version=v4}, …]`. **Порожній список — це нормальна
відповідь для зовнішнього хоста**, а не відсутня: glue-адреси несе лише хост усередині зони, яку
обслуговує реєстр.

Статус `linked` означає, що принаймні один домен використовує цей хост як сервер імен. Саме він
стоїть між вами і [видаленням](#delete).

**Коди відповіді:** `1000`; `2303` (такого хоста немає).

---

## create

```java
public Response create(String name, List<String> addresses)
```

**У каналі передачі:** `<command><create><host:create>` з одним `<host:addr ip="v4|v6">` на кожну
адресу — RFC 5732 §3.2.1.

Версія IP визначається із самого літерала, тож ви передаєте плаский список, а `v4` і `v6` отримують
правильні позначки:

```java
// A subordinate host: the glue addresses are required.
Response r = client.host().create("ns1.example.com.ua", Arrays.asList("203.0.113.10", "2001:db8::10"));

System.out.println(r.objectName() + " created " + r.createdDate());

// An external host: no addresses at all.
client.host().create("ns1.acme.example");
```

Повне перше делегування — створіть хости, а потім наведіть на них домен:

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

Map<String, Object> add = new LinkedHashMap<String, Object>();
add.put("ns", Arrays.asList("ns1.example.com.ua", "ns2.example.com.ua"));
client.domain().update("example.com.ua", Collections.<String, Object>singletonMap("add", add));

System.out.println(String.join(", ", client.domain().info("example.com.ua").nameservers()));
```

**Коди відповіді:** `1000`; `2001` (адрес більше, ніж дозволяє обмеження на хост); `2003`
(підпорядкований хост без адреси); `2005` (некоректна адреса або ім'я); `2302` (хост уже існує);
`2306` (адреса на зовнішньому хості).

---

## update

```java
public Response update(String name, Map<String, Object> options)
```

**У каналі передачі:** `<command><update><host:update>` — RFC 5732 §3.2.5. Як і кожне оновлення в
EPP, це **дельта**: те, чого ви не згадали, ніхто не чіпає.

| ключ | значення | канал передачі |
|---|---|---|
| `addAddresses` | `List<String>` | `<host:addr>` усередині `<host:add>` |
| `remAddresses` | `List<String>` | `<host:addr>` усередині `<host:rem>` |
| `addStatuses` | `List<String>` | `<host:status s="…">` усередині `<host:add>` |
| `remStatuses` | `List<String>` | `<host:status s="…">` усередині `<host:rem>` |

```java
// Renumbering a nameserver: add the new address and remove the old one in one command, so the
// host is never left without a glue address.
Map<String, Object> renumber = new LinkedHashMap<String, Object>();
renumber.put("addAddresses", Arrays.asList("203.0.113.20"));
renumber.put("remAddresses", Arrays.asList("203.0.113.10"));
client.host().update("ns1.example.com.ua", renumber);

List<String> ips = new ArrayList<String>();
for (Map<String, String> addr : client.host().info("ns1.example.com.ua").hostAddresses()) {
    ips.add(addr.get("ip"));
}
System.out.println(String.join(", ", ips));
```

Адреса, яку ви видаляєте, має збігатися з тим, що тримає реєстр. Блок того боку, який ви не
використовуєте, не надсилається взагалі, тож один лише `addAddresses` дає `<host:add>`
і більше нічого.

Статуси, які ви можете встановлювати, — це родина `client*`: `clientUpdateProhibited` та
`clientDeleteProhibited`. `linked`, `ok` і статуси `server*` належать реєстру.

Тут діють ті самі правила щодо адрес, що й у create: зовнішній хост не може отримати адреси
(`2306`), а підпорядкований не може лишитися без жодної (`2003`).

**Коди відповіді:** `1000`; `2001` (адрес більше, ніж дозволяє обмеження на хост); `2003`; `2303`;
`2304` (статус забороняє); `2306`.

### Побудова update крок за кроком

```java
public HostUpdateBuilder updateBuilder(String name)
```

`addAddress` / `addAddresses`, `remAddress` / `remAddresses`, `addStatus`, `remStatus`, потім
`send()`. Кожен крок описано в [Білдери](builders.md).

---

## Перейменування не існує
**Ця бібліотека відмовляється перейменовувати об’єкт хоста**, і причина тут у політиці, а не в
протоколі. RFC 5732 §3.2.5 саме визначає перейменування — через `<host:chg><host:name>`, — але
реєстри, проти яких це будувалося, читають лише блоки `add` та `rem` у `host:update` і
відкидають `chg` без жодного слова. Проти такого реєстру кадр, який несе перейменування
поряд зі зміною адрес, застосовує адреси, відкидає перейменування і все одно відповідає `1000` —
лишаючи вас у переконанні, що сервер імен переїхав, хотя він не переїхав. **Запитайте в свого
реєстру, чи реалізує він `host:chg`.** Там, де реалізує, три команди нижче все одно безпечні,
і саме їх пропонує ця бібліотека.

Тому `update()` відхиляє опцію `newName` прямо:

```java
Map<String, Object> rename = new LinkedHashMap<String, Object>();
rename.put("newName", "ns9.example.com.ua");
client.host().update("ns1.example.com.ua", rename);
// ValidationException: host rename is not supported by this registry (host:chg is ignored) -
// create the new host, re-point the domains with domain:update, then delete the old one
```

Ця послідовність і є перейменуванням, і в ній три кроки:

```java
// 1. Create the new host with the same addresses.
Response old = client.host().info("ns1.example.com.ua");
List<String> ips = new ArrayList<String>();
for (Map<String, String> addr : old.hostAddresses()) {
    ips.add(addr.get("ip"));
}
client.host().create("ns9.example.com.ua", ips);

// 2. Re-point every domain that uses the old one. The registry keeps no list of those — it
//    comes from your own records of what you delegated where.
for (String domain : yourDomainsUsingIt) {
    Map<String, Object> add = new LinkedHashMap<String, Object>();
    add.put("ns", Arrays.asList("ns9.example.com.ua"));
    Map<String, Object> rem = new LinkedHashMap<String, Object>();
    rem.put("ns", Arrays.asList("ns1.example.com.ua"));
    Map<String, Object> repoint = new LinkedHashMap<String, Object>();
    repoint.put("add", add);
    repoint.put("rem", rem);
    client.domain().update(domain, repoint);
}

// 3. Only once nothing references it any more — otherwise this is a 2305.
client.host().delete("ns1.example.com.ua");
```

Додавайте перед тим, як видаляти, однією командою на домен, щоб домен ніколи навіть на мить не
лишався без делегування.

---

## delete

```java
public Response delete(String name, boolean force)
```

**У каналі передачі:** `<command><delete><host:delete>` — RFC 5732 §3.2.2. Із `force` у
`<extension>` їде блок `<registry:delete><registry:deleteNS confirm="yes"/>`.

Хост, який усе ще є сервером імен для якогось домену, видалити неможливо: реєстр відповідає
**`2305`**. Статус `linked` — це попередження наперед.

```java
Response h = client.host().info("ns1.example.com.ua");

if (h.statuses().contains("linked")) {
    // Detach it from the domains first, or use the forced delete below.
    return;
}

client.host().delete("ns1.example.com.ua");
```

### Примусове видалення

```java
client.host().delete("ns1.example.com.ua", true);
```

Це прибирає хост із набору серверів імен **кожного** домену, який на нього посилався, а потім
видаляє його. Обов'язковий для реєстру `confirm="yes"` надсилається за вас — і саме тому цей
прапорець є окремим аргументом, а не типовим значенням.

Перш ніж ним користуватися, зрозумійте його ціну: домен, у якого лишилося менше серверів імен, ніж
вимагає зона, стає `inactive` і перестає резолвитися. Це правильний інструмент для сервера імен,
який ви виводите з експлуатації, і неправильний — для наведення ладу. Де можете, спершу
переспрямуйте домени й скористайтеся звичайним видаленням.

**Коди відповіді:** `1000`; `2303` (такого хоста немає); `2305` (усе ще використовується як сервер
імен — звичайне видалення); `2400` (примусове відкріплення не вдалося завершити).

---

## Коди відповіді на цій сторінці

| Код | Значення | Виняток |
|---|---|---|
| `1000` | виконано | — |
| `2001` | кадр некоректно сформований — напр. адрес більше, ніж дозволяє обмеження на хост | `CommandException` |
| `2003` | підпорядкований хост без glue-адреси | `CommandException` |
| `2005` | некоректна адреса або ім'я хоста | `CommandException` |
| `2302` | хост уже існує | `ObjectExistsException` |
| `2303` | такого хоста немає | `ObjectDoesNotExistException` |
| `2304` / `2305` | статус забороняє / усе ще використовується як сервер імен | `ObjectStatusException` |
| `2306` | політика — напр. адреса на зовнішньому хості | `PolicyException` |
| `2400` | реєстр не зміг це завершити; може бути тимчасовим | `CommandException` (`isRetryable()`) |

Опція `newName` ніколи не доходить до реєстру: це `ValidationException`, кинутий ще до того, як буде
побудовано кадр. `ResultCode` має іменовану константу для кожного коду вище; повна таксономія — у
[Помилки](errors.md).

---

Див. також: [Домени](domains.md) · [Контакти](contacts.md) · [Poll](poll.md) ·
[Відповіді](responses.md) · [Білдери](builders.md)

[← Зміст посібника](README.md)
