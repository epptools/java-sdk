# Відповіді

Кожна команда повертає `com.epptools.sdk.Response`. Він огортає розібрану відповідь і відповідає на
питання про неї іменованими аксесорами, тож вам ніколи не доводиться читати карту за рядком, який
довелося вгадати.

Ця сторінка перелічує **кожен** аксесор, згрупований за тим, на що він відповідає, з його сигнатурою
і з тим, що він повертає, коли відповідь нічого про це не несе.

Два правила діють на весь клас:

- **Дати повертаються рядком самого реєстру** — `2027-04-01T09:15:00Z` або зі зсувом — ніколи не
  `Instant`. Реєстр вирішує, на який календарний день припадає продовження, і переформатування через
  локальну часову зону — це те, як клієнт починає показувати, а потім і продовжувати, попередній день.
- **Гроші повертаються точним десятковим рядком**, ніколи не числом з рухомою комою. `0.1 + 0.2` у
  двійковій арифметиці не дорівнює `0.3`, і баланс, підсумований так, поволі відходить від істини.
  Використовуйте `BigDecimal` або цілі в копійках.

Аксесор, запитаний про те, чого у відповіді немає, повертає `null`, порожній список, `false` або `0`
— як дозволяє його тип. Це законна відповідь — «реєстр про це нічого не сказав» — а не помилка.

## Результат і підсумок

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `code(): int` | Код відповіді EPP: 1000, 1001, 2303 … | `0` для привітання чи будь-якого кадру без `<result>` |
| `isSuccess(): boolean` | `true` для будь-якого коду 1xxx | `false` |
| `isPending(): boolean` | `true` лише для 1001 — прийнято, завершується поза сесією | `false` |
| `isGreeting(): boolean` | `true`, коли кадр є `<greeting>`, а не `<response>` | `false` |
| `message(): String` | `<msg>` результату, мовою сесії | `null` |
| `messageLang(): String` | Мова цього повідомлення: `en`, `uk`, `ua`, `ru` | `null` |
| `errorReasons(): List<String>` | Текст `<extValue><reason>` з невдалої команди | порожній список |
| `extValues(): List<Map<String, Object>>` | Блоки `<extValue>` повністю — див. нижче | порожній список |
| `clTRID(): String` | Клієнтський ідентифікатор транзакції, який сервер повторив | `null` |
| `svTRID(): String` | Ідентифікатор транзакції реєстру. Зберігайте його поруч з об'єктом | `null` |

`isSuccess()`, що дорівнює true, — це не те саме, що виконана робота: 1001 теж код успіху й означає,
що реєстр прийняв команду і завершить її поза сесією. Перевіряйте `isPending()`, перш ніж записувати
щось як завершене.

### `extValues(): List<Map<String, Object>>`

Там, де `errorReasons()` дає текст, цей аксесор дає **який саме елемент** не влаштував сервер — а це
та частина, з якою можна щось зробити. Перевірка п'яти доменів, що впала на одному, несе тут це одне
ім'я.

```java
for (Map<String, Object> ext : response.extValues()) {
    ext.get("element");    // "name" — the local name of the offending node, "" if unnamed
    ext.get("namespace");  // "urn:ietf:params:xml:ns:domain-1.0"
    ext.get("text");       // "bad..name" — the node's OWN character data
    ext.get("values");     // its child elements by local name, when the node is a container
    ext.get("xml");        // the node, serialized for a log
    ext.get("reason");     // the server's explanation
    ext.get("lang");       // its language
}
```

`text` — це власні символьні дані елемента, а не рекурсивний текстовий вміст, тож контейнер ніколи не
повертається як його дочірні елементи, злиті в один рядок, що читається як значення і ним не є.
`CommandException.subject()` читає перший непорожній `text` за вас — див. [Помилки](errors.md).

## Ідентичність об'єкта

Ці працюють однаково для домену, хоста й контакту.

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `objectName(): String` | Ім'я домену, ім'я хоста або **ідентифікатор** контакту | `null` |
| `roid(): String` | Власний ідентифікатор об'єкта в реєстрі | `null` |
| `sponsor(): String` | `clID` — реєстратор, якому об'єкт належить зараз | `null` |
| `registrarOfRecord(): String` | Хендл, який WHOIS і RDAP реєстру публікують як реєстратора | `null` |
| `createdBy(): String` | `crID` — реєстратор, що створив об'єкт | `null` |
| `createdDate(): String` | `crDate`, як його записав реєстр | `null` |
| `updatedBy(): String` | `upID` — реєстратор, що змінював останнім | `null`, зокрема коли змін не було жодного разу |
| `updatedDate(): String` | `upDate` | `null`, коли змін не було |
| `authInfo(): String` | Секрет трансферу `<authInfo><pw>` | `null`, коли реєстр його не віддав |
| `statuses(): List<String>` | Значення статусів з атрибута `s`: `[ok]`, `[clientHold, …]` | порожній список |

`objectName()` читає безпосередню дитину блоку об'єкта. Для контакту це принципово: пошук елемента
`<name>` по всьому документу спершу знаходить повне ім'я особи всередині поштової адреси, і подача
його назад як ідентифікатора дає 2303.

`registrarOfRecord()` і `sponsor()` для реселера — не одна й та сама сторона. `sponsor()` називає
обліковий запис, якому об'єкт належить у вашій власній ієрархії; `registrarOfRecord()` називає того,
кого публікує реєстр.

`updatedBy()` надсилається лише реєстратору-власнику. Зіставляйте його з `updatedDate()`, коли
звіряєтеся: зміна, якої ви не робили, прийшла з боку реєстру або від дії підтримки, а не з вашої
системи.

`authInfo()` — це живий секрет доступу: те, що дозволяє будь-якому реєстратору забрати домен у вас.
Ніколи не пишіть його в журнал, ніколи не вставляйте в заявку до підтримки і замінюйте після того, як
передали клієнту.

## Домен

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `expiryDate(): String` | `exDate`, точно як його записав реєстр | `null` |
| `registrant(): String` | Ідентифікатор контакту реєстранта | `null` |
| `contacts(): Map<String, List<String>>` | Контакти за ролями: `{admin=[c-1], tech=[c-1, c-2]}`. Реєстрант сюди **не** входить | порожня карта |
| `contactsFor(String role): List<String>` | Ідентифікатори однієї ролі, без урахування регістру | порожній список — законна відповідь, бо обов'язковим усюди є лише реєстрант |
| `adminContacts(): List<String>` | `contactsFor("admin")` | порожній список |
| `techContacts(): List<String>` | `contactsFor("tech")` | порожній список |
| `billingContacts(): List<String>` | `contactsFor("billing")` | порожній список |
| `allContacts(): List<String>` | Кожен ідентифікатор у будь-якій ролі, разом з реєстрантом, без дублікатів | порожній список |
| `nameservers(): List<String>` | Делегування, у нижньому регістрі, у порядку реєстру | порожній список |
| `nameserverAddresses(): Map<String, List<Map<String, String>>>` | Вбудовані glue-адреси за іменем сервера імен | порожня карта |
| `subordinateHosts(): List<String>` | Об'єкти хостів, що живуть **під** цим доменом, у нижньому регістрі | порожній список |
| `transfer(): Map<String, String>` | Трансфер повністю — див. нижче | `null` |
| `transferStatus(): String` | `trStatus`: `pending`, `serverApproved`, … | `null` |
| `transferDate(): String` | `trDate` — коли домен востаннє змінив власника | `null`, якщо цього не було |
| `license(): String` | Номер торговельної марки або ліцензії з власного розширення реєстру | `null` |
| `rgpStatus(): List<String>` | Значення статусів RGP, напр. `[redemptionPeriod]` (RFC 3915) | порожній список |
| `dsRecords(): List<Map<String, Object>>` | Записи DS для DNSSEC (RFC 5910) | порожній список, коли домен не підписаний |
| `keyRecords(): List<Map<String, Object>>` | Публічні ключі DNSSEC | порожній список |
| `isSigned(): boolean` | Чи несе домен хоч якісь дані DNSSEC | `false` |
| `prices(): Map<String, Map<String, String>>` | Підказки про ціни з цінового розширення реєстру, за операціями | порожня карта |
| `priceChannel(): String` | Ціновий канал, за яким тарифікується цей домен | `null` |

`contactsFor()` зіставляє роль без урахування регістру, бо реєстри непослідовні щодо `tech` проти
`Tech`, а пошук з урахуванням регістру мовчки повідомляє «технічного контакту немає» для домену, у
якого він є.

`allContacts()` — той, що потрібен, коли вам важливо, **що** контакт узагалі згадано, а не в якій
ролі: перед видаленням контакту або коли ви з'ясовуєте, які з ваших об'єктів контактів ще в ужитку.

`nameservers()` покриває обидві моделі делегування EPP: `<domain:hostObj>` — посилання на об'єкт
хоста, і `<domain:hostAttr>` — ім'я, вбудоване разом із glue-адресами. Клієнт, що читає лише одну з
них, бачить порожній список проти реєстру, який використовує іншу, і робить висновок, що в домену
взагалі немає серверів імен.

`nameserverAddresses()` повертає лише вбудовані glue-адреси:

```java
Response info = client.domain().info("example.com.ua");

info.nameserverAddresses();
// {ns1.example.com.ua=[{ip=192.0.2.1, version=v4}]}
```

Він наповнюється тільки там, де реєстр відповідає через `hostAttr`. Проти того, який відповідає через
`hostObj`, ви отримаєте порожню карту і братимете адреси окремим `host().info()` на кожне ім'я — тож
порожній результат тут **не** означає, що домен не делеговано. Для списку користуйтеся
`nameservers()`.

`subordinateHosts()` важливий перед видаленням: реєстр відмовляється видаляти домен, поки під ним
живуть об'єкти серверів імен. Перегляньте список, приберіть або перенаправте їх, а потім видаляйте.

`prices()` і `priceChannel()` — це власні цінові підказки реєстру на `domain:info`, відмінні від
котирувань RFC 8748 у [`fees()`](#перевірка-і-гроші):

```java
Response info = client.domain().info("example.com.ua");

info.prices();
// {renewal={value=180.00, currency=UAH}, …}
info.priceChannel();
// непрозорий ідентифікатор, що відповідає рядку опублікованого каталогу реєстру
```

Домен, зареєстрований давно, може бути на іншому каналі, ніж той, яким скористалася б нова
реєстрація в тій самій зоні — саме тому канал належить домену, а не зоні.

### `transfer(): Map<String, String>`

Уся інформація про трансфер, з відповіді на трансфер або з `trnData` poll-повідомлення:

```java
Map<String, String> t = response.transfer();
// {
//   status=pending,
//   requestedBy=ACME,                     // reID
//   requestedAt=2026-08-14T10:00:00Z,     // reDate
//   actingClient=EXAMPLE,                 // acID — who has to answer
//   actBy=2026-08-19T10:00:00Z,           // acDate — the deadline
//   expiryDate=2028-04-01T09:15:00Z       // the exDate the transfer would give
// }
```

Сам по собі `transferStatus()` каже, що трансфер очікує, але не каже, чий він і скільки у вас часу.
`actBy` — це момент, після якого реєстр вирішить за вас.

### Записи DNSSEC

```java
Response info = client.domain().info("example.com.ua");

info.dsRecords();
// [{keyTag=12345, alg=8, digestType=2, digest=ABCD…}]

info.keyRecords();
// [{flags=257, protocol=3, alg=8, pubKey=AwEAAb…}]

info.isSigned();   // true when either list is non-empty
```

## Хост

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `hostAddresses(): List<Map<String, String>>` | Glue-адреси об'єкта хоста: `[{ip=192.0.2.1, version=v4}, …]` | порожній список |

Glue-адреси несе лише хост **усередині** зони, яку він обслуговує. Для зовнішнього сервера імен
реєстр не повертає жодної, і це нормально, а не відсутня відповідь.

Список обмежений самим об'єктом хоста, тож він ніколи не змішує сюди поіменні glue-адреси вбудованого
делегування домену — то `nameserverAddresses()`. Відсутній атрибут `ip` означає `v4`, бо це власне
значення за замовчуванням у схемі хоста: реєстр, що працює лише з IPv4 і опускає атрибут, буде
показаний правильно, а не як щось інше.

## Контакт

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `postalInfo(): Map<String, Map<String, Object>>` | Адреси за формою: `int` (ASCII) і `loc` (локальне письмо) | порожня карта |
| `email(): String` | Адреса електронної пошти контакту | `null` |
| `voice(): String` | Номер телефону у формі EPP `+CC.NNNN` | `null` |
| `fax(): String` | Номер факсу, та сама форма | `null` |
| `disclose(): Map<String, Object>` | Налаштування розкриття даних | `null`, коли контакт не несе жодного і діє сама лише політика реєстру |

```java
Response c = client.contact().info("C-0001");

c.postalInfo();
// {int={name=ACME LLC, org=ACME LLC, street=[1 Main St],
//       city=Kyiv, sp=, pc=01001, cc=UA},
//  loc={…}}
```

Контакт може нести будь-яку з форм або обидві; частини, яких він не несе, дорівнюють `""`, а не
відсутні. Читайте `int`, коли вам потрібне те, що безпечно надрукувати будь-де, і `loc`, коли
хочете адресу такою, як її написав сам реєстрант.

```java
client.contact().info("C-0001").disclose();
// {flag=false, elements=[email, voice]}
```

`flag`, що дорівнює true, означає, що перелічені елементи **можна** публікувати; false — що їх
належить приховати. Елементи, яких у списку *немає*, отримують протилежне до прапорця, тож список без
нього не має сенсу. Елемент, який існує по одному на кожну поштову форму, з'являється зі своїм типом:
як `name:int` або `addr:loc`.

## Перевірка і гроші

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `availability(): Map<String, Boolean>` | Уся карта `*:check`: ім'я або ідентифікатор ⇒ `Boolean` | порожня карта |
| `isAvailable(String name): Boolean` | Одне ім'я, без урахування регістру | `null` — «у відповіді про нього нічого не сказано» |
| `unavailableReason(String name): String` | Чому ім'я недоступне: `In use`, `Reserved` | `null`, коли воно доступне або причини не надано |
| `fees(): Map<String, Object>` | Котирування RFC 8748 за іменами, плюс `_currency` | порожня карта |
| `feeFor(String name, String operation, int years): String` | Одне котирування, десятковим рядком | `null`, коли у відповіді такого котирування не було |
| `feeClass(String name): String` | Ціновий клас реєстру: `premium`, `standard` | `null` |
| `isPremium(String name): boolean` | Чи тарифікується ім'я поза стандартним переліком | `false` |
| `chargedFee(): Map<String, String>` | Що операція справді списала: `{currency=UAH, fee=100.00}` | `null` |
| `feeAmount(): String` | Сума з `chargedFee()` | `null` |
| `feeCurrency(): String` | Валюта з `chargedFee()` | `null` |
| `balance(): Map<String, String>` | Увесь блок балансу | `null`, коли це не відповідь про баланс |
| `creditLimit(): String` | Ваш кредитний ліміт | `null` |
| `currentBalance(): String` | Ваш поточний баланс | `null` |
| `availableCredit(): String` | Скільки ще можна витратити: баланс плюс кредитний ліміт, якщо він є | `null` |

Те, що `isAvailable()` повертає `null`, — і є причина його існування. Читання `availability()`
вручну дає `null` і для «зайнято», і для «ви помилилися в ключі», а ці дві відповіді не повинні
виглядати однаково в рядку перед реєстрацією.

```java
Response check = client.domain().check(Arrays.asList("example.com.ua", "taken.com.ua"));

check.availability();                       // {example.com.ua=true, taken.com.ua=false}
check.isAvailable("EXAMPLE.com.ua");        // true
check.isAvailable("never-asked.com.ua");    // null
check.unavailableReason("taken.com.ua");    // "In use"
```

### `fees()` і `feeFor()`

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));
Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

r.fees();
// {
//   _currency=UAH,
//   example.com.ua={
//       avail=true,                  // false => read "reason" (zone not served, currency, …)
//       reason=null,
//       class=premium,               // present only when the registry sent a class
//       commands={create={years=1, fee=100.00}},
//       periods=[{op=create, years=1, fee=100.00},
//                {op=create, years=2, fee=200.00},
//                {op=create, years=5, fee=480.00}]
//   }
// }

r.feeFor("example.com.ua", "create", 5);    // "480.00"
```

Запит однієї операції на кількох періодах повертає по одному котируванню на період. Карта `commands`
містить по одному запису на операцію — для першого періоду, який ви запитали, — тож **читайте
`feeFor()` або `periods`, щойно запитали більше ніж один**. `transfer` і `restore` є однорічними
операціями, скільки років не запитуй, тож читайте їх назад на одному році.

Суми тут ілюстративні, а не тариф реєстру. Усе про запит і обмеження цін — на сторінці
[Баланс](balance.md).

### `isPremium()` і `feeClass()`

```java
r.feeClass("example.com.ua");   // "premium" | "standard" | null
r.isPremium("example.com.ua");  // true when a class is present and is not "standard"
```

Обидва мають форму без імені; тоді вони відповідають про перше ім'я у відповіді, яке несе клас.
`false` від `isPremium()` не є обіцянкою стандартної ціни — воно означає, що відповідь не оголосила
жодного особливого класу. Рахуйте за `fees()`, а обмеження ціни ставте на самій операції.

### Що було списано насправді

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");
Response create = client.domain().create("example.com.ua", options);

create.chargedFee();    // {currency=UAH, fee=100.00} or null
create.feeAmount();     // "100.00"
create.feeCurrency();   // "UAH"
```

Реєстр повертає це на успішній операції, яка несла згоду на ціну. Записуйте до замовлення саме
`feeAmount()`, а не ціну, яку ви взяли з `check`: між ними тариф міг змінитися.

### Блок балансу

```java
Response b = client.balance();

b.balance();           // {creditLimit=…, balance=…, availableCredit=…}, or null
b.creditLimit();
b.currentBalance();    // named for what it is, because balance() returns the whole block
b.availableCredit();
b.threshold();         // only on a low-balance notice
```

Цифри — це десяткові рядки у валюті вашого рахунку. `balance()` відповідає `null`, коли відповідь
зовсім не є відповіддю про баланс, і читає цифри лише з балансового `infData` — ніколи з
`<fee:balance>`, який відбивається в створенні чи продовженні: це квитанція, а не звіт.
`threshold()` — саме те, що варто перевіряти на сповіщенні про низький баланс: сама його
**присутність** відрізняє попередження від звіту, а на звичайному звіті він `null`. Див.
[Баланс](balance.md).

## Черга повідомлень

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `messageId(): String` | Ідентифікатор повідомлення в черзі, який передається в `poll().ack()` | `null` — черга порожня |
| `messageCount(): int` | Скільки повідомлень лишається в черзі | `0` |
| `queueMessage(): String` | Текст **сповіщення**, з `<msgQ><msg>` | `null` |
| `queueMessageLang(): String` | Мова сповіщення: `uk`, `ru`, `en` | `null` |
| `queueDate(): String` | Коли сповіщення поставлено в чергу | `null` |
| `change(): Map<String, String>` | Що реєстр зробив із вашим об’єктом без вашого запиту (RFC 8590) | `null` |
| `pendingActionData(): Map<String, Object>` | Підсумок операції, яку реєстр обробив поза сесією | `null` для звичайного сповіщення або порожньої черги |

**`queueMessage()`, а не `message()`.** `message()` повертає банер результату команди — «Command
completed successfully; ack to dequeue» — а це стала рядкова константа. Справжній зміст сповіщення
лежить у `queueMessage()`, і читання не того аксесора віддає вам банер, тоді як зміст втрачається, а
підтвердження знищує його в реєстрі безповоротно.

Сповіщення несе власну мову, налаштовану для реєстратора, незалежну від мови сесії, про яку повідомляє
`messageLang()`.

### `pendingActionData(): Map<String, Object>`

Так відкладена команда нарешті звітує про себе (RFC 5731 §3.3, RFC 5733 §3.3). Ви надсилаєте create,
отримуєте **1001** і `svTRID`; пізніше poll-повідомлення приносить вирок.

```java
Map<String, Object> pan = notice.pendingActionData();
// {
//   object=example.com.ua,
//   success=true,                         // the paResult attribute
//   clTRID=JAVA-SDK-…-0007,               // from paTRID: the ORIGINAL command
//   svTRID=SRV-…-00042,
//   date=2026-08-14T10:05:00Z             // paDate: when the action completed
// }
```

- **`success` — єдине, що каже, чи спрацювало.** Оточення `<result code="1301">` означає «ось
  повідомлення», а не «ваша операція вдалася». Читати замість нього — класична помилка: тоді кожна
  відповідь черги виглядає успіхом. Відсутній вирок трактується як невдача, бо відсутній вирок — це
  не «так».
- **`svTRID` зіставляє його назад** із командою, за яку вам дали 1001. Не припускайте, що це остання
  за часом: черга є черга.
- **`date`** — це коли завершено дію, а не коли ви опитали чергу.

Блок шукається за локальним іменем в усіх просторах імен об'єктів, тож повертаються і `domain:panData`,
і `contact:panData`.

## Сесія і безпека

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `securityEvents(): List<Map<String, String>>` | Події безпеки входу RFC 8807 про цю сесію | порожній список — здорова сесія |
| `serviceObjUris(): List<String>` | Привітання: сервіси об'єктів, які оголошує сервер | порожній список |
| `serviceExtUris(): List<String>` | Привітання: сервіси розширень, які він оголошує | порожній список |

```java
for (Map<String, String> event : client.login().securityEvents()) {
    event.get("text");     // always present
    event.get("type");     // certificate | cipher | tlsProtocol | password | newPW | stat | custom
    event.get("level");    // "warning" | "error"
    event.get("exDate");   // the certificate's expiry, for type=certificate
}
```

Сервер повертає їх лише клієнтові, який узяв участь у розширенні. Повний розбір — на сторінці
[Сесія](session.md#безпека-входу-rfc-8807).

## Прямий доступ

Для всього, чого іменовані аксесори не моделюють: вашого власного розширення, поля, яке додасть
майбутній сервер.

| Аксесор | Повертає | Коли у відповіді цього немає |
|---|---|---|
| `value(String localName): String` | Перший елемент будь-де з таким локальним іменем, обрізаний, у будь-якому просторі імен | `null` |
| `values(String localName): List<String>` | Кожен елемент із таким локальним іменем, обрізаний | порожній список |
| `resData(): Element` | Елемент `<resData>`, для власного розбору | `null` |
| `raw(): String` | XML відповіді точно таким, яким він прийшов | порожній документ, який вам передали |
| `dom(): Document` | Розібраний документ | — |
| `Response.fromXml(String xml): Response` | Розбирає кадр у `Response`. Кидає `ConnectionException` на некоректному XML | — |

`value()` і `values()` зіставляють за **локальним** іменем, ігноруючи простір імен, і саме це робить
їх корисними проти розширення, якого ви не моделювали. Дві пастки варто назвати:

- Статус живе в **атрибуті** `s`, тож `values("status")` повертає ряд порожніх рядків. Користуйтеся
  `statuses()`.
- `ns` — це лише обгортка навколо делегування, і власного тексту вона не несе, тож
  `values("ns")` повертає ряд **порожніх** рядків — а не імена серверів, злиті разом, як того
  хотілося би. Користуйтеся `nameservers()` або `values("hostObj")`.

`dom()` повертає документ `org.w3c.dom.Document`, розібраний із увімкненою підтримкою просторів імен,
тож вузол шукається за URI простору імен, а не за префіксом: жодних префіксів прив'язувати не треба, і
`Namespaces` містить рівно ті URI, які визначені RFC і однакові в будь-якого реєстру.

Для власного розширення вашого реєстру URI невідомий, доки не прочитано привітання. І знати його
зазвичай не потрібно: усі аксесори на цій сторінці знаходять дані розширення за ЛОКАЛЬНИМ ІМЕНЕМ,
тож ліцензія чи ціна читаються однаково, у якому б просторі імен вони не надійшли:

```java
Response info = client.domain().info("example.com.ua");

info.value("license");      // власне розширення реєстру — простір імен не потрібен
info.values("price");
```

Там, де вам потрібне дерево, а не значення, `dom()` і `resData()` дають DOM, який розібрала
бібліотека, і ті пошуки з урахуванням просторів імен, які вже є в JDK:

```java
Response info = client.domain().info("example.com.ua");

NodeList names = info.dom().getElementsByTagNameNS(Namespaces.DOMAIN, "hostName");
for (int i = 0; i < names.getLength(); i++) {
    System.out.println(names.item(i).getTextContent());
}
```

`javax.xml.xpath` є в JDK, якщо вам зручніше запитувати виразом; йому потрібен власний
`NamespaceContext`, а URI для нього — це константи з `Namespaces` плюс те, що визначив
`client.registryExtUri()`.

`raw()` — це незамаскований кадр. Якщо ви його зберігаєте, маскуйте `<pw>`, `<newPW>` та `<authInfo>`
самі — бібліотека маскує їх у власних журналах саме з цієї причини.

`Response.fromXml()` — це те, чим бібліотека будує кожну відповідь, і він публічний, щоб ви могли
розібрати перехоплений кадр. Некоректний XML кидає `ConnectionException`, а не повертає
напівпобудований об'єкт.

---

[← Зміст посібника](README.md)
