# Помилки

Кожен збій, який піднімає ця бібліотека, успадковує `com.epptools.sdk.exception.EppException`, тож
один `catch` покриває все. Понад те, **клас існує там, де відрізняється правильний наступний крок, — і
більше ніде.** Ця систематика описує не те, що пішло не так, а те, що з цим робити.

```java
try (Client client = new Client(config)) {
    client.connect();
    client.login();

    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("years", 1);
    options.put("registrant", "C-0001");
    client.domain().create("example.com.ua", options);

    client.logout();
} catch (EppException e) {
    System.out.println("EPP error: " + e.getMessage());
}
```

`EppException` успадковує `RuntimeException`, тож перехоплення «всього підряд» вище по стеку теж його
побачить, а жоден підпис методу не несе `throws`.

## Ієрархія

```
EppException
├── ValidationException     значення в ЦЬОМУ виклику непридатне; нічого не надіслано
├── ConfigException         клієнт налаштований неправильно; кожен виклик падає, доки це не виправлено
├── ConnectionException     транспорт: TLS, сокет, тайм-аут, кадрування. Сервер так і не відповів
└── CommandException        реєстр відмовив, із кодом відповіді
    ├── InsufficientFundsException   2104
    ├── AuthenticationException      2200
    ├── AuthorizationException       2201, 2202
    ├── ObjectExistsException        2302
    ├── ObjectDoesNotExistException  2303
    ├── ObjectStatusException        2304, 2305
    ├── PolicyException              2306, 2308
    └── SessionException             2500, 2501, 2502
```

| Ловити | Коли піднімається | Коди | Що робити далі |
|---|---|---|---|
| `ValidationException` | значення в цьому виклику неможливо використати; **нічого не надіслано** | — | виправити аргументи й викликати знову |
| `ConfigException` | сам клієнт налаштований неправильно | — | виправити розгортання; доти падає кожен виклик |
| `ConnectionException` | рукостискання TLS, під'єднання, тайм-аут читання/запису, кадрування, некоректний XML | — | з'єднання закрито. Перепід'єднайтеся або виправте набір сертифікатів |
| `InsufficientFundsException` | рахунок не може заплатити | `2104` | **зупиніть пакет**, поповніть, продовжте |
| `AuthenticationException` | облікові дані відхилено | `2200` | виправте clID/пароль. Не повторюйте з тими самими |
| `AuthorizationException` | об'єкт не ваш або `authInfo` хибний | `2201`, `2202` | перевірте власника або код трансферу |
| `ObjectExistsException` | ім'я чи ідентифікатор зайнято | `2302` | оберіть інше або з'ясуйте, хто його тримає |
| `ObjectDoesNotExistException` | немає такого імені, ідентифікатора чи хоста | `2303` | застарілий ідентифікатор або друкарська помилка |
| `ObjectStatusException` | заважає статус або зв'язок | `2304`, `2305` | прочитайте об'єкт, приберіть перешкоду, повторіть |
| `PolicyException` | власні правила реєстру відмовляють цьому значенню | `2306`, `2308` | змініть запит; повтор не допоможе |
| `SessionException` | сервер завершує сесію | `2500`–`2502` | перепід'єднайтеся, увійдіть знову, потім повторіть |
| `CommandException` | будь-який інший код ≥ 2000 | усі решта | розгалужуйтеся за `e.eppCode()` |

Ловіть від конкретного до загального: Java бере перший відповідний `catch`, а підклас, написаний
нижче за свого предка, узагалі не компілюється — компілятор скаже, що цей виняток уже перехоплено.

---

## Поганий аргумент — це не погана конфігурація

`ValidationException` і `ConfigException` обидва означають «нічого не надіслано». Вони є окремими
класами, бо потребують протилежних реакцій, а спільний клас лишив би сервіс у здогадках.

| | `ValidationException` | `ConfigException` |
|---|---|---|
| Що не так | аргументи **одного виклику** | **клієнт**, для кожного виклику |
| Хто це спричинив | той, хто зробив цей запит | той, хто розгорнув сервіс |
| Хто має про це почути | той, хто викликав, — відповідайте йому | ваші оператори — сповіщайте їх |
| Наступний виклик спрацює? | так, з іншими аргументами | ні, доки не зміниться розгортання |

Помилитися тут означає повідомити клієнтові про власну помилку конфігурації оператора як про його
провину — «ваш запит недійсний» за відсутній пароль у файлі середовища.

**`ValidationException` — аргументи цього виклику:**

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("secdns", new LinkedHashMap<String, Object>());
client.domain().create("example.com.ua", options);
// ValidationException: domain:create does not accept 'secdns' (did you mean 'secDNS'?).
// Accepted: authInfo, contacts, fee, license, nameServers, nameservers, registrant, secDNS, years.
```

Перевірка ключів сягає й **усередину** вкладених карт, тож `emial` у блоку `disclose`, `key_tag`
у записі `dsData`, `curency` у згоді на ціну, `nmae` у glue-записі та `postalCode` у `postalInfo`
відхиляються, і в кожному разі називається найближче прийнятне написання. Найбільше це важливо там,
де кадр, збудований із помилки, є **валідним за схемою**: DS-запис із нулів і порожнім
дайджестом приймається, отримує 1000 і публікується як підписувач, який не відповідає жодному ключу.

Інші того самого ⒑атунку: суміш двох моделей серверів імен в одній команді, `dsData` разом із
`keyData` в одному блоці secDNS, `maxSigLife` менший за одну секунду, сума ціни, що не є простим
десятковим числом, порожня роль контакту або роль поза admin/billing/tech, трансферний `op` поза
п’ятьма іменами з RFC 5730, `hosts` у `info` поза all/del/sub/none, поштовий `type` або форма
розкриваного поля поза int/loc, порожня пошта під час створення контакту, `check()` зовсім без
імен, ціновий запит, який називає валюту і жодної операції, `newName` в оновленні хоста (реєстр,
який відкидає `host:chg`), `authInfo` і `clearAuthInfo` в одному `chg`, `removeAllDnssec()` разом із
прибиранням названого запису, поштова зміна без імені, міста чи коду країни, ціновий запит більш
ніж на двадцять позицій і другий `send()` на білдері.

Спільна риса — та, яку називає ім'я класу: **нічого не надіслано**. Кадру не збудовано, гроші не
рухалися, у реєстрі нічого не змінилося.

**`ConfigException` — клієнт, для кожного виклику:**

```java
Config.builder("", "EXAMPLE", "your-secret").build();
// ConfigException: Config: host is required

client.login();
// ConfigException: login requires a non-empty clID and password (clID set, password EMPTY)
//                  - check your config
```

Правила довжини пароля також приходять сюди, і приходять у два моменти. Межі, чинні для будь-якого
сервера, перевіряються ще до відкриття сокета; чи придатний пароль, довший за 16 символів, залежить
від того, чи оголошує сервер RFC 8807, — тож це перевіряється, щойно прочитано привітання:

```
ConfigException: Config.password must be 6-128 characters long (got 4)
ConfigException: Config.password is 40 characters, but this server does not advertise
                 urn:ietf:params:xml:ns:epp:loginSec-1.0 - the EPP <pw> schema type allows
                 at most 16, so the server would answer a bare 2001
```

І те, і те є фактами розгортання, а не фактами запиту. Усе про це — на сторінці [Сесія](session.md).

---

## ConnectionException

Транспорт завалився, і **сервер так і не відповів** — або зв'язок обірвався посеред обміну. Коду
відповіді читати нема, бо жодного результату не прийшло.

| Повідомлення | Що сталося |
|---|---|
| `Cannot connect to epp.registry.example:700 - …` | сокет не відкрився. Хвіст — це справжня причина, яку повідомив JDK, а не узагальнена |
| `TLS handshake with epp.registry.example:700 failed - PKIX path building failed…` | `caFile` не заданий або вказує на хибний набір |
| `Read timed out` | минув `readTimeout`, поки команда була в дорозі |
| `Write failed (connection closed?)` | інша сторона зникла до того, як кадр було дописано |
| `Connection is no longer usable: …` | попередній збій уже зробив цей потік недовірливим |
| `Connection closed while reading` | інша сторона зникла посеред кадру |
| `Invalid EPP frame length: …` | префікс довжини RFC 5734 виявився нісенітницею |
| `Server returned malformed XML` | кадр не розібрався |
| `Not connected - call connect() first` | сокета немає; сесію не відкривали або її закрив попередній збій |
| `Response does not belong to this command (sent clTRID …, received …)` | потік розсинхронізувався |
| `First frame from … is not an EPP <greeting>` | на під'єднання відповіло щось інше, ніж привітання |

**Більшість із них закривають з'єднання, і це навмисно.** Часткове читання лишає потік байтів на
невідомому зсуві, тож *наступна* команда прочитала б відповідь *цієї* — зсув на одиницю крізь
тарифіковані операції, де продовження `example2.com.ua` повертає `1000` із датою завершення `example1.com.ua`, і
реєстратор записує продовженим не той домен. Закриття — це те, що робить збій остаточним, а не
мовчазним: `isConnected()` стає false, і кожен наступний виклик відмовляє, замість читати з середини
кадру.

Перевірка луни `clTRID` — та сама думка на рівень вище. Сервер повторює ідентифікатор транзакції, який
ви надіслали (RFC 5730 §2.5), цей клієнт породжує унікальний на кожну команду, і розбіжність означає,
що відповідь належить якійсь іншій команді. Див. [Команди](commands.md#перевірка-відлуння).

**`ConnectionException` під час операції — це єдиний збій, підсумку якого ви не знаєте.** Йому
присвячено весь [останній розділ](#коли-операція-завалилася-а-ви-не-знаєте-чи-вона-сталася)
цієї сторінки.

Провал перевірки сертифіката — найчастіша проблема першого запуску, і виправлення ніколи не полягає в
`verifyPeer(false)`; діагностика — на сторінці [Сесія](session.md#коли-рукостискання-завалюється).

---

## CommandException

Реєстр відповів, і відповіддю була відмова: код відповіді 2000 або більше.

```java
public int eppCode()
public Response response()
public boolean isRetryable()
public String subject()
public List<String> reasons()
```

| Член | Повертає |
|---|---|
| `e.eppCode()` | код відповіді як `int` |
| `e.response()` | увесь [`Response`](responses.md) — усе, що несла відповідь, або `null` |
| `e.isRetryable()` | чи могла б та сама команда, надіслана ще раз, спрацювати |
| `e.subject()` | об'єкт, до якого був закид у реєстру, коли той його назвав |
| `e.reasons()` | додатковий діагностичний текст, як `List<String>` |
| `CommandException.forCode(int code, String message, Response response)` | будує найконкретніший підклас для коду |

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");

try {
    client.domain().create("example.com.ua", options);
} catch (CommandException e) {
    e.eppCode();                       // 2302
    e.getMessage();                    // "EPP 2302: Object exists ('example.com.ua')"
    e.subject();                       // "example.com.ua"
    e.reasons();                       // [Domain is registered by another registrar]
    e.response().svTRID();             // the registry's transaction id — store it
    boolean exists = e.eppCode() == ResultCode.OBJECT_EXISTS;   // true
}
```

**`subject()` виправдовує себе на команді, що несе кілька об'єктів.** «EPP 2302: Object exists» після
перевірки п'яти імен лишає вам самим з'ясовувати, котре з п'яти, — а відповідь лежить непрочитаною в
`<extValue>`. Бібліотека читає перше непорожнє значення ще й у текст винятку, і саме тому повідомлення
вище закінчується іменем.

`forCode()` — єдине місце, де код стає класом, тож зіставлення не може розійтися між командами. Він
публічний, бо корисний і вам: з `throwOnFailure(false)` ви можете перетворити `Response`, який
вирішили відкинути, на той самий виняток, який підняла б бібліотека.

`e.response()` дорівнює `null` лише тоді, коли жодної відповіді не було розібрано. В усіх інших
випадках це повна відповідь — `errorReasons()`, `extValues()`, `svTRID()` і сирий кадр усі там, для
журналу.

### InsufficientFundsException (2104)

```java
for (String name : namesToRegister) {
    try {
        client.domain().createBuilder(name).years(1).registrant("C-0001").send();
    } catch (InsufficientFundsException e) {
        // Not this name's problem — the account's. Carrying on gives the same failure for every
        // remaining name, one round trip each.
        alertBilling(e.getMessage());
        break;
    }
}
```

Із запитом усе гаразд, і **кожна наступна тарифікована команда падає так само**, доки баланс не
поповнять. Це єдина відмова, що каже щось про чергу, а не про позицію в ній, — і саме тому вона має
власний клас: пакет, який ставиться до неї як до будь-якої іншої помилки, перемелює всю решту роботи,
породжуючи однакові збої, і операторові, який читає журнал, доводиться самому здогадуватися, що
справжньою подією була лише перша.

Нічого не зареєстровано і нічого не списано. Поповніть, потім продовжіть із місця зупинки. Про
перевірку перед початком — на сторінці [Баланс](balance.md).

### AuthenticationException (2200) і AuthorizationException (2201 / 2202)

Вони звучать схоже й означають протилежне.

- **`AuthenticationException` — це сесія, якій не вдалося увійти.** `2200`, і тільки `2200`. Сервер
  може відмовити у вході й з інших причин: рахунок на межі кількості сесій (`2502`), сервер
  закривається (`2501`), оголошений вами сервіс не пропонується (`2307`), це з'єднання вже увійшло
  (`2002`), версія протоколу (`2100`) — і кожна приходить власним класом із власним виправленням.
  Називати їх усі помилкою автентифікації означає відправити вас міняти пароль, який ніколи не був
  проблемою.
- **`AuthorizationException` — це один об'єкт, до якого не дотягнутися**, тоді як сесія цілком
  справна. Або він належить іншому реєстратору (`2201`), або наданий вами `authInfo` не збігається
  (`2202`). Ніколи не повторюйте з тими самими даними.

Збій входу піднімається **хай що каже `throwOnFailure`**, бо вхід, який не вдався, — це не та сесія,
у якій можна працювати далі.

### Класи життєвого циклу об'єкта

| Клас | Коди | Ситуація | Виправлення |
|---|---|---|---|
| `ObjectExistsException` | `2302` | ім'я чи ідентифікатор хтось тримає — ви або інший реєстратор | оберіть інше. Повтор не зробить його вільним |
| `ObjectDoesNotExistException` | `2303` | імені, ідентифікатора чи хоста в реєстрі немає | застарілий ідентифікатор або друкарська помилка |
| `ObjectStatusException` | `2304`, `2305` | `clientHold`, трансфер в очікуванні, сервер імен, який ще використовує домен | прочитайте об'єкт, приберіть перешкоду, повторіть — **той самий запит тоді спрацює** |
| `PolicyException` | `2306`, `2308` | команда коректна й надсилати її можна; реєстр не прийме саме це значення | змініть запит |

`ObjectStatusException` — єдиний із чотирьох, де той самий кадр варто надіслати ще раз, і лише після
того, як щось змінилося:

```java
try {
    client.domain().delete("example.com.ua");
} catch (ObjectStatusException e) {
    Response info = client.domain().info("example.com.ua");
    System.out.println(String.join(", ", info.statuses()));           // e.g. clientDeleteProhibited
    System.out.println(String.join(", ", info.subordinateHosts()));   // or hosts still living under it
}
```

Змагання з іншим реєстратором за ім'я, що вивільняється, — єдиний випадок, коли
`ObjectExistsException` є очікуваним, а не винятковим.

### SessionException (2500 / 2501 / 2502)

Сервер завершує сесію: забагато одночасних сесій, тайм-аут бездіяльності або зупинка. З'єднання
зникло або зникає.

**Сама команда може бути цілком доброю**, тож це один із небагатьох збоїв, де повтор доречний — на
*новому* з'єднанні, після паузи:

```java
Response r;
try {
    r = client.domain().info("example.com.ua");
} catch (SessionException e) {
    client.disconnect();
    client.connect();
    client.login();
    r = client.domain().info("example.com.ua");   // читання: повторювати безпечно
}
System.out.println(r.expiryDate());
```

**Читання** повторюйте вільно. Перш ніж повторювати операцію, прочитайте
[правило невідомого підсумку](#коли-операція-завалилася-а-ви-не-знаєте-чи-вона-сталася): `2502`,
що прийшов до відповіді, і `2502`, що прийшов замість неї, звідси виглядають однаково.

---

## Що можна повторювати і чому більшість — ні

```java
public boolean isRetryable()
```

**True рівно для чотирьох кодів** і false для всього іншого:

| Код | Константа | Чому повтор може допомогти |
|---|---|---|
| `2400` | `COMMAND_FAILED` | власний тимчасовий збій реєстру. Із запитом усе гаразд |
| `2500` | `COMMAND_FAILED_SERVER_CLOSING` | завершилася сесія, а не команда. Спершу перепід'єднайтеся |
| `2501` | `AUTHENTICATION_SERVER_CLOSING` | так само |
| `2502` | `SESSION_LIMIT_EXCEEDED_SERVER_CLOSING` | забагато сесій саме в цю мить. Перепід'єднайтеся після паузи |

Правило за цим переліком: **придатне до повтору означає, що збій був про момент, а не про запит.**

Для всього іншого воно навмисно false, зокрема для відмов, які кортить повторити. Повтор `2302` не
зробить ім'я вільним, `2104` не заплатить за нього, `2306` не змінить політику реєстру, а `2303` не
викличе об'єкт із небуття. Цикл, що вважає кожен збій тимчасовим, перетворює одну відмову на бан за
частотою — на що реєстр відповідає `2502` або брандмауером.

```java
try {
    client.domain().info("example.com.ua");
} catch (CommandException e) {
    if (!e.isRetryable()) {
        throw e;                          // a retry cannot change the answer
    }
    retryLater.add("example.com.ua");     // and pause before trying
}
```

Робіть паузи між спробами і обмежуйте їх кількість. Три спроби зі зростаючою паузою — це інтеграція;
необмежений цикл — це аварія вашого власного виготовлення.

**`isRetryable()` говорить про команду, а не про ваш облік.** Для `create`, `renew` чи `transfer`
«чи могло б це спрацювати, якби надіслати ще раз» — не те саме питання, що «чи безпечно надсилати ще
раз»; див. нижче.

---

## Вимкнення кидання винятків

Типово кожен код ≥ 2000 піднімається винятком. Саме це робить прямолінійну інтеграцію правильною за
замовчуванням: не можна забути перевірити код, якого ви ніколи не бачите.

```java
client.throwOnFailure(false);

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");
Response r = client.domain().create("example.com.ua", options);
if (r.code() == ResultCode.OBJECT_EXISTS) {
    taken.add("example.com.ua");
} else if (!r.isSuccess()) {
    throw new IllegalStateException(r.message() != null ? r.message() : "create failed");
}
```

Чого це **не** вимикає:

- `ConnectionException` — сервер так і не відповів, тож коду читати нема.
- `ValidationException` і `ConfigException` — нічого взагалі не надсилалося.
- `AuthenticationException` та інші відмови у вході з `login()`.
- `CommandException`, який піднімає `poll().drain()`, коли відповідь не є ні сповіщенням, ні
  порожньою чергою. Читати відмову як спорожнену чергу означало б доповісти про успіх, тоді як не
  прочитано нічого.

Повний розбір — на сторінці [Команди](commands.md#перемикач-throwonfailure).

---

## Коди відповіді

`com.epptools.sdk.ResultCode` має іменовану константу для кожного коду. Розгалужуйтеся за ними, а не
за голими числами: `e.eppCode() == ResultCode.OBJECT_EXISTS` каже, що воно означає.

### Успіх — 1xxx

| Код | Константа | Значення |
|---|---|---|
| `1000` | `SUCCESS` | зроблено |
| `1001` | `SUCCESS_PENDING` | прийнято; реєстр завершує поза сесією і звітує через [чергу](poll.md) |
| `1300` | `SUCCESS_NO_MESSAGES` | черга: порожньо |
| `1301` | `SUCCESS_ACK_TO_DEQUEUE` | черга: на вас чекає повідомлення |
| `1500` | `SUCCESS_END_SESSION` | відповідь на `logout` |

**`1001` — той, на якому люди спотикаються.** Це код успіху, тож `isSuccess()` дорівнює true і нічого
не піднімається, — а об'єкт при цьому *ще не* створено, не продовжено і не передано. Перевіряйте
`isPending()`, перш ніж записувати щось як зроблене, і зіставляйте вирок за `svTRID`, коли прийде
сповіщення з черги.

### Протокол і синтаксис — 2000–2099

| Код | Константа | Значення | Виняток |
|---|---|---|---|
| `2000` | `UNKNOWN_COMMAND` | сервер не розпізнає команду | `CommandException` |
| `2001` | `COMMAND_SYNTAX_ERROR` | кадр не пройшов перевірку за схемою, зазвичай нічого не називаючи | `CommandException` |
| `2002` | `COMMAND_USE_ERROR` | команда недійсна в цьому стані — напр. вхід уже виконано | `CommandException` |
| `2003` | `REQUIRED_PARAMETER_MISSING` | бракує чогось обов'язкового | `CommandException` |
| `2004` | `PARAMETER_VALUE_RANGE_ERROR` | значення поза діапазоном — **зокрема обмеження ціни, нижче за справжню** | `CommandException` |
| `2005` | `PARAMETER_VALUE_SYNTAX_ERROR` | значення синтаксично некоректне | `CommandException` |

### Нереалізоване й тарифікація — 2100–2199

| Код | Константа | Значення | Виняток |
|---|---|---|---|
| `2100` | `UNIMPLEMENTED_PROTOCOL_VERSION` | `<version>` у вході має бути 1.0 | `CommandException` |
| `2101` | `UNIMPLEMENTED_COMMAND` | цей сервер не реалізує команду | `CommandException` |
| `2102` | `UNIMPLEMENTED_OPTION` | напр. мова сесії, якої він не пропонує | `CommandException` |
| `2103` | `UNIMPLEMENTED_EXTENSION` | розширення тут недоступне — напр. DNSSEC у зоні, яка його забороняє | `CommandException` |
| `2104` | `BILLING_FAILURE` | недостатньо коштів. Нічого не зроблено | `InsufficientFundsException` |
| `2105` | `NOT_ELIGIBLE_FOR_RENEWAL` | зокрема `curExpDate`, який не збігається | `CommandException` |
| `2106` | `NOT_ELIGIBLE_FOR_TRANSFER` | об'єкт неможливо перемістити | `CommandException` |

`2105` на продовженні означає, що дата завершення не така, як ви думали. Перечитайте її через
`info()`; це ніколи не привід надіслати той самий кадр ще раз.

### Безпека — 2200–2299

| Код | Константа | Значення | Виняток |
|---|---|---|---|
| `2200` | `AUTHENTICATION_ERROR` | сам вхід не вдався | `AuthenticationException` |
| `2201` | `AUTHORIZATION_ERROR` | об'єкт не ваш, щоб діяти з ним | `AuthorizationException` |
| `2202` | `INVALID_AUTHORIZATION` | `authInfo` хибний | `AuthorizationException` |

### Життєвий цикл об'єкта — 2300–2399

| Код | Константа | Значення | Виняток |
|---|---|---|---|
| `2300` | `OBJECT_PENDING_TRANSFER` | трансфер уже в очікуванні | `CommandException` |
| `2301` | `OBJECT_NOT_PENDING_TRANSFER` | нема чого підтверджувати, відхиляти, скасовувати чи запитувати | `CommandException` |
| `2302` | `OBJECT_EXISTS` | уже зареєстровано | `ObjectExistsException` |
| `2303` | `OBJECT_DOES_NOT_EXIST` | такого об'єкта немає | `ObjectDoesNotExistException` |
| `2304` | `OBJECT_STATUS_PROHIBITS_OPERATION` | заважає статус | `ObjectStatusException` |
| `2305` | `OBJECT_ASSOCIATION_PROHIBITS_OPERATION` | заважає зв'язок — прив'язаний контакт, підпорядкований хост | `ObjectStatusException` |
| `2306` | `PARAMETER_VALUE_POLICY_ERROR` | політика реєстру відмовляє цьому значенню | `PolicyException` |
| `2307` | `UNIMPLEMENTED_OBJECT_SERVICE` | сервіс об'єкта або зона тут не обслуговуються | `CommandException` |
| `2308` | `DATA_MANAGEMENT_POLICY_VIOLATION` | зміна порушила б політику реєстру щодо даних | `PolicyException` |

### Сервер — 2400+

| Код | Константа | Значення | Виняток |
|---|---|---|---|
| `2400` | `COMMAND_FAILED` | реєстр не зміг це завершити. **Придатне до повтору** | `CommandException` |
| `2500` | `COMMAND_FAILED_SERVER_CLOSING` | не вдалося, і сесія завершується. **Придатне до повтору після перепід'єднання** | `SessionException` |
| `2501` | `AUTHENTICATION_SERVER_CLOSING` | так само | `SessionException` |
| `2502` | `SESSION_LIMIT_EXCEEDED_SERVER_CLOSING` | забагато одночасних сесій | `SessionException` |

---

## Коли операція завалилася, а ви не знаєте, чи вона сталася
Тайм-аут читання, обірване з'єднання або `2500` посеред `create`, `renew`, `transfer` чи `restore`
лишають по-справжньому **невідомий підсумок**: реєстр міг виконати команду й списати кошти ще до того,
як відповідь загубилася. Ця бібліотека не здатна відрізнити одне від іншого, і ви за винятком теж.
Команда або сталася, або ні, а кадр, який сказав би про це, так і не прийшов.

**Не повторюйте просто так. Сліпий повтор — це те, як домен реєструють і оплачують двічі.**

Натомість запитайте в реєстру, як воно є:

| Команда, що завалилася | Як з'ясувати, що сталося |
|---|---|
| `domain().create()` | `domain().info(name)`. Він або існує, або тягне `2303` |
| `contact().create()` | `contact().info(contactId)` |
| `contact().createAuto()` | **знайти за ідентифікатором неможливо** — хендл існував лише у відповіді, яку ви втратили. Звірте зі своїми записами, перш ніж викликати знову; кожен виклик карбує свіжий хендл, тож повтор — це другий контакт |
| `domain().renew()` | порівняйте `info().expiryDate()` з тим, чого очікували. Продовження або зсунуло її, або ні |
| `domain().transfer("request", …)` | `domain().transfer("query", name)` — `2300`, поки трансфер очікує, `2301`, коли жодного немає |
| `domain().restore()` | `info().rgpStatus()`. Відновлений домен уже вийшов із `redemptionPeriod` |
| `domain().update()` / `delete()` | `info()` і порівняння з тим, що ви надіслали |

Звіряльник, написаний один раз:

```java
String name = "example.com.ua";
String quote = "100.00";

Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");
options.put("fee", quote);

try {
    Response r = client.domain().create(name, options);
    recordCreated(name, r.svTRID(), r.expiryDate(), r.feeAmount());
} catch (ConnectionException e) {
    // The reply was lost. The command may have worked, and the money may have moved.
    markUnknown(name, e.getMessage());

    client.disconnect();
    client.connect();
    client.login();

    try {
        Response info = client.domain().info(name);
        if ("EXAMPLE".equals(info.sponsor())) {
            // It happened. Record it from the registry's own answer, not from your assumption.
            recordCreated(name, info.svTRID(), info.expiryDate(), null);
        } else {
            // Registered, but not by us. Not ours to retry.
            escalate(name, "held by " + info.sponsor());
        }
    } catch (ObjectDoesNotExistException missing) {
        // 2303 — it really did not happen. NOW a retry is safe.
        readyToRetry(name);
    }
}
```

Три речі роблять це правильним, а не просто обачним:

- **Перепід'єднайтеся перед звіркою.** З'єднання, що загубило відповідь, було закрите збоєм, а потоку
  на невідомому зсуві не можна довіряти відповідь на питання, яке ви збираєтеся поставити.
- **`2303` — єдиний доказ того, що події не було.** Будь-що інше — тайм-аут на `info`, `2201`, ім'я,
  яке тримає хтось інший — це все ще невідомість, а невідомість — це не «ні».
- **Гроші є частиною невідомості.** За створення, яке спрацювало, кошти списано, бачили ви відповідь
  чи ні. Звіряйте гроші з відповіді реєстру або зі свого виписки, а не з того, у що вірив ваш власний
  код.

Випадок `1001` — не цей випадок: відкладена дія має *відомий* підсумок — прийнято й завершується поза
сесією. Збережіть `svTRID` і зіставте вирок, коли прийде
[сповіщення з черги](poll.md#результат-відкладеної-дії). Чого не має статися ніколи — це
трактування `1001` як збою й повторного надсилання: це та сама подвійна реєстрація іншою дорогою.

**Збій, підсумку якого ви не можете встановити, заслуговує на увагу оператора, а не на автоматичну
другу спробу.** Поставте його в чергу до людини, збережіть надісланий `clTRID` і зупиніться.

---

## Як повідомити про проблему

Вкладайте **svTRID** з відповіді і **clTRID**, який надіслав ваш клієнт. Разом вони однозначно
визначають транзакцію в журналах реєстру, і саме це робить звернення таким, на яке можна відповісти
без зустрічного запиту.

```java
try {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("years", 1);
    options.put("registrant", "C-0001");
    client.domain().create("example.com.ua", options);
} catch (CommandException e) {
    Map<String, Object> fields = new LinkedHashMap<String, Object>();
    fields.put("code", e.eppCode());
    fields.put("subject", e.subject());
    fields.put("reasons", e.reasons());
    fields.put("svTRID", e.response() != null ? e.response().svTRID() : null);
    fields.put("clTRID", e.response() != null ? e.response().clTRID() : null);
    fields.put("version", Version.VERSION);
    logError("EPP refused", fields);
}
```

Надсилайте й самі кадри, якщо можете, але **спершу приберіть `<pw>`, `<newPW>` та `<authInfo>`**: це
живі секрети доступу, і бібліотека маскує їх у власних журналах з тієї самої причини. `raw()` на
відповіді — це незамаскований кадр.

Питання про бібліотеку, про кадр, який відхилив реєстр, або про помилку: **https://github.com/epptools/java-sdk/issues**.
Питання щодо рахунку й тарифікації — до вашого менеджера в реєстрі.

---

Див. також: [Команди](commands.md) · [Домени](domains.md) · [Баланс і ціни](balance.md) ·
[Черга](poll.md) · [Відповіді](responses.md)

[← Зміст посібника](README.md)
