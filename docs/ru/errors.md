# Ошибки

Любой сбой, который поднимает эта библиотека, наследует `com.epptools.sdk.exception.EppException`, поэтому
один `catch` покрывает всё. Дальше действует правило: **отдельный класс есть там, где отличается
правильный следующий шаг, — и больше нигде.** Эта таксономия описывает не то, что пошло не так, а
то, что с этим делать.

```java
import com.epptools.sdk.exception.EppException;

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

`EppException` наследует `RuntimeException`, поэтому общий перехватчик выше по стеку тоже его
увидит, а ни одна сигнатура метода не несёт `throws`: ни одно исключение этой библиотеки не является
проверяемым.

## Иерархия

```
EppException
├── ValidationException     a value in THIS call is unusable; nothing was sent
├── ConfigException         the client is set up wrong; every call fails until it is fixed
├── ConnectionException     transport: TLS, socket, timeout, framing. The server never answered
└── CommandException        the registry refused, with a result code
    ├── InsufficientFundsException   2104
    ├── AuthenticationException      2200
    ├── AuthorizationException       2201, 2202
    ├── ObjectExistsException        2302
    ├── ObjectDoesNotExistException  2303
    ├── ObjectStatusException        2304, 2305
    ├── PolicyException              2306, 2308
    └── SessionException             2500, 2501, 2502
```

| Что ловить | Когда поднимается | Коды | Что делать дальше |
|---|---|---|---|
| `ValidationException` | значение в этом вызове использовать нельзя; **ничего не отправлено** | — | исправьте аргументы и вызовите снова |
| `ConfigException` | неверно настроен сам клиент | — | чините развёртывание; до тех пор падает каждый вызов |
| `ConnectionException` | TLS-рукопожатие, подключение, тайм-аут чтения/записи, кадрирование, испорченный XML | — | соединение закрыто. Переподключитесь или почините набор сертификатов |
| `InsufficientFundsException` | учётной записи нечем платить | `2104` | **остановите пакет**, пополните счёт, продолжите |
| `AuthenticationException` | учётные данные отвергнуты | `2200` | исправьте clID/пароль. Не повторяйте с теми же |
| `AuthorizationException` | объект не ваш либо неверен `authInfo` | `2201`, `2202` | проверьте владельца или код трансфера |
| `ObjectExistsException` | имя или идентификатор занят | `2302` | возьмите другое или выясните, у кого он |
| `ObjectDoesNotExistException` | такого имени, идентификатора или хоста нет | `2303` | устаревший идентификатор или опечатка |
| `ObjectStatusException` | мешает статус или связь | `2304`, `2305` | прочитайте объект, снимите помеху, повторите |
| `PolicyException` | собственные правила реестра отклоняют это значение | `2306`, `2308` | измените запрос; повтор помочь не может |
| `SessionException` | сервер завершает сессию | `2500`–`2502` | переподключитесь, войдите снова, затем повторите |
| `CommandException` | любой другой код ≥ 2000 | все остальные | ветвитесь по `e.eppCode()` |

Ловите от частного к общему. Об обратном порядке скажет компилятор: `catch (CommandException)`,
написанный выше `catch (InsufficientFundsException)`, не даёт программе собраться и называет
подклассы, которые уже перехвачены, — то есть ровно те случаи, которые вы хотели обработать иначе.

---

## Плохой аргумент — это не плохая конфигурация

И `ValidationException`, и `ConfigException` означают «ничего не отправлено». Это разные классы,
потому что реакция на них нужна противоположная, а общий класс оставил бы сервис гадать.

| | `ValidationException` | `ConfigException` |
|---|---|---|
| Что не так | аргументы **одного вызова** | **клиент**, для всех вызовов сразу |
| Кто это устроил | тот, кто сделал этот запрос | тот, кто разворачивал сервис |
| Кто должен узнать | вызывающий — ответьте ему | ваши операторы — поднимите тревогу |
| Сработает ли следующий вызов? | да, с другими аргументами | нет, пока не изменится развёртывание |

Ошибка в этом месте приводит к тому, что собственный промах оператора в конфигурации сообщается
клиенту как его вина: «ваш запрос некорректен» — в ответ на пароль, забытый в файле окружения.

**`ValidationException` — аргументы этого вызова:**

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("secdns", secDns);

client.domain().create("example.com.ua", options);
// ValidationException: domain:create does not accept 'secdns' (did you mean 'secDNS'?).
// Accepted: authInfo, contacts, fee, license, nameServers, nameservers, registrant, secDNS, years.
```

Проверка ключей достаёт и **внутрь** вложенных карт, так что `emial` в блоке `disclose`, `key_tag`
в записи `dsData`, `curency` в согласовании тарифа, `nmae` в glue-записи и `postalCode` в `postalInfo`
отклоняются, и в каждом случае называется ближайшее допустимое написание. Больше всего это важно там,
где кадр, собранный из описки, **валиден по схеме**: DS-запись из нулей с пустым дайджестом
принимается, получает 1000 и публикуется как подписывающая, не соответствующая ни одному ключу.

Другие того же рода: смесь двух моделей серверов имён в одной команде; `dsData` вместе с
`keyData` в одном блоке secDNS; `maxSigLife` меньше одной секунды; сумма платы, не являющаяся
обычным десятичным числом; пустая роль контакта или роль помимо admin/billing/tech;
трансферный `op` помимо пяти имён из RFC 5730; `hosts` у `info` помимо all/del/sub/none;
почтовый `type` или форма раскрываемого поля помимо int/loc; пустой адрес электронной почты
при создании контакта; `check()` вовсе без имён; запрос цен, называющий валюту и ни одной
операции; `newName` в обновлении хоста (реестр, который отбрасывает `host:chg`); `authInfo` и
`clearAuthInfo` в одном `chg`; `removeAllDnssec()` вместе с удалением поимённой записи; почтовое
изменение без имени, города или кода страны; запрос цен более чем на двадцать позиций;
второй `send()` у билдера.

Общее у них — ровно то, что говорит имя класса: **ничего не отправлено**. Кадр не построен, деньги
не двигались, в реестре ничего не изменилось.

**`ConfigException` — клиент, для всех вызовов сразу:**

```java
Config.builder("", "EXAMPLE", "your-secret").build();
// ConfigException: Config: host is required

client.login();
// ConfigException: login requires a non-empty clID and password (clID set, password EMPTY)
//                  - check your config
```

Сюда же попадают правила длины пароля, и приходят они в два момента. Границы, действующие для
любого сервера, проверяются до открытия сокета; а годится ли пароль длиннее 16 символов, зависит от
того, объявляет ли сервер RFC 8807, поэтому это проверяется уже после того, как прочитано
приветствие:

```
ConfigException: Config.password must be 6-128 characters long (got 4)
ConfigException: Config.password is 40 characters, but this server does not advertise
                 urn:ietf:params:xml:ns:epp:loginSec-1.0 - the EPP <pw> schema type allows
                 at most 16, so the server would answer a bare 2001
```

И то и другое — факты о развёртывании, а не о запросе. Полностью — в [Сессия](session.md).

---

## ConnectionException

Транспорт отказал, и **сервер не ответил вовсе** — либо связь оборвалась посреди обмена. Читать код
ответа неоткуда, потому что ответа не пришло.

| Сообщение | Что произошло |
|---|---|
| `Cannot connect to epp.registry.example:700 - …` | не удалось открыть сокет. Настоящая причина — в подробностях |
| `TLS handshake with … failed - PKIX path building failed` | `caFile` не задан или указывает не на тот набор сертификатов |
| `Read timed out` / `Write failed (connection closed?)` | истёк `readTimeout` либо запись не ушла, пока команда была в полёте |
| `Connection closed while reading` | другая сторона ушла посреди кадра |
| `Invalid EPP frame length: …` | префикс длины из RFC 5734 оказался бессмыслицей |
| `Server returned malformed XML` | кадр не разобрался |
| `Not connected - call connect() first` | сокета нет: сессию либо не открывали, либо её закрыл более ранний сбой |
| `Response does not belong to this command (sent clTRID …, received …)` | поток рассинхронизировался |
| `First frame from … is not an EPP <greeting>` | на подключение ответило что-то, кроме приветствия |

**Большинство из них закрывает соединение, и это сделано намеренно.** Незавершённое чтение
оставляет поток байтов на неизвестном смещении, поэтому *следующая* команда прочитала бы ответ на
*эту* — сдвиг на единицу поперёк платных трансформирующих команд, при котором продление `example2.com.ua`
возвращает `1000` с датой истечения `example1.com.ua`, а регистратор записывает продлённым не тот домен.
Закрытие делает сбой окончательным, а не молчаливым: `isConnected()` становится ложным, и каждый
последующий вызов отказывает, вместо того чтобы читать из середины кадра.

Проверка эха `clTRID` — та же мысль уровнем выше. Сервер отражает отправленный вами идентификатор
транзакции (RFC 5730 §2.5), этот клиент генерирует уникальный на каждую команду, и несовпадение
означает, что ответ принадлежит другой команде. См. [Команды](commands.md#проверка-отражённого-идентификатора).

**`ConnectionException` посреди трансформирующей команды — единственный сбой, исход которого вам
неизвестен.** Ему целиком посвящён
[последний раздел](#когда-трансформирующая-команда-упала-и-вы-не-знаете-выполнилась-ли-она) этой
страницы.

Ошибка проверки сертификата — самая частая беда на первом запуске, и лечится она никогда не через
`verifyPeer(false)`; разбор — в [Сессия](session.md#когда-рукопожатие-не-удаётся).

---

## CommandException

Реестр ответил, и ответом был отказ: код ответа 2000 или выше. Класс наследует `EppException` и
несёт с собой обе вещи, по которым можно ветвиться:

```java
int eppCode();               // код ответа EPP
Response response();         // весь разобранный ответ либо null, если его не удалось перехватить
boolean isRetryable();       // может ли та же самая команда, отправленная снова, оказаться успешной
String subject();            // объект, к которому у реестра претензия, если он его назвал
List<String> reasons();      // дополнительные пояснения
```

| Член | Что возвращает |
|---|---|
| `e.eppCode()` | код ответа как `int` |
| `e.response()` | весь [`Response`](responses.md) — всё, что нёс ответ |
| `e.isRetryable(): boolean` | может ли та же самая команда, отправленная снова, оказаться успешной |
| `e.subject(): String` | объект, к которому у реестра претензия, если он его назвал |
| `e.reasons(): List<String>` | дополнительные пояснения |
| `CommandException.forCode(int code, String message, Response response): CommandException` | строит наиболее конкретный подкласс для кода |

```java
import com.epptools.sdk.ResultCode;
import com.epptools.sdk.exception.CommandException;

try {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("years", 1);
    options.put("registrant", "C-0001");
    client.domain().create("example.com.ua", options);
} catch (CommandException e) {
    e.eppCode();                       // 2302
    e.getMessage();                    // "EPP 2302: Object exists ('example.com.ua')"
    e.subject();                       // "example.com.ua"
    e.reasons();                       // [Domain is registered by another registrar]
    if (e.response() != null) {
        e.response().svTRID();         // the registry's transaction id - store it
    }
    boolean exists = e.eppCode() == ResultCode.OBJECT_EXISTS;   // true
}
```

**`subject()` окупается на команде, которая несёт несколько объектов.** «EPP 2302: Object exists»
после проверки пяти имён оставляет вас выяснять, какое из пяти, — при том что ответ лежит
непрочитанным в `<extValue>`. Библиотека к тому же переносит первое непустое значение и в текст
исключения, поэтому сообщение выше заканчивается именем.

`forCode()` — единственное место, где код превращается в класс, поэтому соответствие не может
разъехаться между командами. Он публичный, потому что полезен и вам: при `throwOnFailure(false)` вы
можете превратить `Response`, который решили отвергнуть, ровно в то исключение, которое подняла бы
библиотека.

`e.response()` равен `null` только тогда, когда ответ не был разобран. Во всех остальных случаях это
полный ответ: `errorReasons()`, `extValues()`, `svTRID()` и сырой кадр — всё на месте, для журнала.

### InsufficientFundsException (2104)

```java
import com.epptools.sdk.exception.InsufficientFundsException;

for (String name : namesToRegister) {
    try {
        client.domain().createBuilder(name).years(1).registrant("C-0001").send();
    } catch (InsufficientFundsException e) {
        // Not about this name, about the account. Carrying on means the same failure for every
        // remaining name, at the cost of one round trip each.
        alertBilling(e.getMessage());
        break;
    }
}
```

С запросом всё в порядке, а **каждая следующая платная команда упадёт точно так же**, пока счёт не
пополнят. Это единственный отказ, который говорит о всей очереди, а не об отдельной позиции в ней, —
поэтому у него свой класс: пакет, который принимает его за обычную ошибку, перемалывает остаток
работы, производя одинаковые сбои, а оператору, читающему журнал, приходится догадываться, что
настоящим событием был только первый.

Ничего не зарегистрировано и ничего не списано. Пополните счёт и продолжите с того места, где
остановились. Как проверить средства заранее — в [Баланс](balance.md).

### AuthenticationException (2200) и AuthorizationException (2201 / 2202)

Звучат похоже, означают противоположное.

- **`AuthenticationException` — это неудавшийся вход в сессию.** `2200`, и только `2200`. Сервер
  может отказать во входе и по другим причинам: у учётной записи исчерпан лимит сессий (`2502`),
  сервер закрывается (`2501`), объявленный вами сервис не предлагается (`2307`), на этом соединении
  вход уже выполнен (`2002`), не та версия протокола (`2100`) — и каждая приходит своим классом со
  своим лечением. Назвать их все ошибкой аутентификации — значит отправиться менять пароль, который
  тут вовсе ни при чём.
- **`AuthorizationException` — это недоступность одного объекта** при совершенно исправной сессии.
  Либо он принадлежит другому регистратору (`2201`), либо переданный вами `authInfo` не подходит
  (`2202`). Никогда не повторяйте с теми же данными.

Сбой входа поднимается **независимо от того, что говорит `throwOnFailure`**: сессия, вход в которую
не удался, — не та сессия, в которой можно продолжать работу.

### Классы жизненного цикла объекта

| Класс | Коды | Ситуация | Что делать |
|---|---|---|---|
| `ObjectExistsException` | `2302` | имя или идентификатор кем-то занят — вами или другим регистратором | возьмите другое. Повтор не сделает его свободным |
| `ObjectDoesNotExistException` | `2303` | имени, идентификатора или хоста в реестре нет | устаревший идентификатор или опечатка |
| `ObjectStatusException` | `2304`, `2305` | `clientHold`, ожидающий трансфер, сервер имён, который всё ещё используется доменом | прочитайте объект, снимите помеху, повторите — **тот же запрос тогда пройдёт** |
| `PolicyException` | `2306`, `2308` | команда сформирована верно и отправлять её вам можно; реестр не примет это значение | измените запрос |

`ObjectStatusException` — единственный из четырёх, при котором тот же кадр стоит отправить снова, и
то лишь после того, как что-то изменилось:

```java
import com.epptools.sdk.exception.ObjectStatusException;

try {
    client.domain().delete("example.com.ua");
} catch (ObjectStatusException e) {
    Response info = client.domain().info("example.com.ua");
    System.out.println(String.join(", ", info.statuses()));           // e.g. clientDeleteProhibited
    System.out.println(String.join(", ", info.subordinateHosts()));   // or hosts living under it
}
```

Гонка с другим регистратором за освобождающимся именем — единственный случай, когда
`ObjectExistsException` ожидаем, а не исключителен.

### SessionException (2500 / 2501 / 2502)

Сервер завершает сессию: слишком много одновременных сессий, простой или остановка сервера.
Соединение уже разорвано или вот-вот будет.

**Сама команда при этом может быть совершенно правильной**, поэтому это один из немногих сбоев, при
которых повтор уместен — на *новом* соединении и после паузы:

```java
import com.epptools.sdk.exception.SessionException;

Response r;
try {
    r = client.domain().info("example.com.ua");
} catch (SessionException e) {
    client.disconnect();
    client.connect();
    client.login();
    r = client.domain().info("example.com.ua");   // a read: safe to repeat
}
System.out.println(r.expiryDate());
```

**Чтение** повторяйте свободно. Прежде чем повторять трансформирующую команду, прочтите
[правило о неизвестном исходе](#когда-трансформирующая-команда-упала-и-вы-не-знаете-выполнилась-ли-она):
`2502`, пришедший до ответа, и `2502`, пришедший вместо него, отсюда выглядят одинаково.

---

## Что можно повторять и почему почти ничего

```java
public boolean isRetryable();
```

**Истинно ровно для четырёх кодов** и ложно для всех остальных:

| Код | Константа | Почему повтор может помочь |
|---|---|---|
| `2400` | `COMMAND_FAILED` | собственный временный сбой реестра. С запросом всё в порядке |
| `2500` | `COMMAND_FAILED_SERVER_CLOSING` | закончилась сессия, а не команда. Сначала переподключитесь |
| `2501` | `AUTHENTICATION_SERVER_CLOSING` | то же самое |
| `2502` | `SESSION_LIMIT_EXCEEDED_SERVER_CLOSING` | слишком много сессий в тот момент. Переподключитесь после паузы |

Правило, стоящее за этим списком: **повторять можно тогда, когда сбой был про момент, а не про
запрос.**

Для всего остального оно намеренно ложно — включая отказы, которые так и тянет повторить. Повтор
`2302` не освободит имя, повтор `2104` за него не заплатит, `2306` не изменит политику реестра, а
`2303` не сотворит объект из воздуха. Цикл, который считает временным любой сбой, превращает один
отказ в блокировку по частоте запросов — а на неё реестр отвечает кодом `2502` либо брандмауэром.

```java
import com.epptools.sdk.exception.CommandException;

try {
    client.domain().info("example.com.ua");
} catch (CommandException e) {
    if (!e.isRetryable()) {
        throw e;                            // a retry will not change the answer
    }
    retryLater.add("example.com.ua");        // and pause before the next attempt
}
```

Делайте паузы между попытками и ограничивайте их число. Три попытки с растущей паузой — это
интеграция; цикл без ограничения — авария, устроенная вами самими.

**`isRetryable()` говорит о команде, а не о вашем учёте.** Для `create`, `renew` или `transfer`
вопрос «сработает ли она, если отправить снова» — это не вопрос «безопасно ли отправлять её
снова»; см. ниже.

---

## Как отключить выбрасывание исключений

По умолчанию поднимается каждый код ≥ 2000. Именно это делает прямолинейную интеграцию правильной
по умолчанию: нельзя забыть проверить код, которого вы никогда не видите.

```java
import com.epptools.sdk.ResultCode;

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

Что этим **не** отключается:

- `ConnectionException` — сервер не ответил вовсе, так что и кода читать неоткуда.
- `ValidationException` и `ConfigException` — ничего не было отправлено.
- `AuthenticationException` и прочие отказы во входе, приходящие из `login()`.
- `CommandException`, который поднимает `poll().drain()`, когда ответ не является ни уведомлением,
  ни пустой очередью. Прочитать отказ как разобранную очередь — значит отчитаться об успехе, не
  прочитав ничего.

Полностью — в [Команды](commands.md#переключатель-throwonfailure).

---

## Коды ответа

В `com.epptools.sdk.ResultCode` есть именованная константа для каждого кода. Ветвитесь по ним, а не по
голым числам: `e.eppCode() == ResultCode.OBJECT_EXISTS` говорит сам за себя.

### Успех — 1xxx

| Код | Константа | Значение |
|---|---|---|
| `1000` | `SUCCESS` | выполнено |
| `1001` | `SUCCESS_PENDING` | принято; реестр завершает операцию офлайн и отчитывается через [poll](poll.md) |
| `1300` | `SUCCESS_NO_MESSAGES` | poll: очередь пуста |
| `1301` | `SUCCESS_ACK_TO_DEQUEUE` | poll: сообщение ждёт |
| `1500` | `SUCCESS_END_SESSION` | ответ на `logout` |

**Именно `1001` ловит людей врасплох.** Это код успеха, поэтому `isSuccess()` истинно и ничего не
поднимается, — а объект при этом *ещё не* создан, не продлён и не перенесён. Проверяйте
`isPending()`, прежде чем записывать что-либо выполненным, и сопоставляйте вердикт по `svTRID`,
когда придёт poll-уведомление.

### Протокол и синтаксис — 2000–2099

| Код | Константа | Значение | Исключение |
|---|---|---|---|
| `2000` | `UNKNOWN_COMMAND` | сервер не распознал команду | `CommandException` |
| `2001` | `COMMAND_SYNTAX_ERROR` | кадр не прошёл схемную проверку, обычно без указания места | `CommandException` |
| `2002` | `COMMAND_USE_ERROR` | команда неуместна в этом состоянии — например, вход уже выполнен | `CommandException` |
| `2003` | `REQUIRED_PARAMETER_MISSING` | пропущено обязательное | `CommandException` |
| `2004` | `PARAMETER_VALUE_RANGE_ERROR` | значение вне диапазона — **в том числе предел цены ниже реальной** | `CommandException` |
| `2005` | `PARAMETER_VALUE_SYNTAX_ERROR` | значение синтаксически некорректно | `CommandException` |

### Нереализованное и биллинг — 2100–2199

| Код | Константа | Значение | Исключение |
|---|---|---|---|
| `2100` | `UNIMPLEMENTED_PROTOCOL_VERSION` | `<version>` при входе должна быть 1.0 | `CommandException` |
| `2101` | `UNIMPLEMENTED_COMMAND` | этот сервер не реализует команду | `CommandException` |
| `2102` | `UNIMPLEMENTED_OPTION` | например, язык сессии, которого он не предлагает | `CommandException` |
| `2103` | `UNIMPLEMENTED_EXTENSION` | расширение здесь недоступно — например, DNSSEC в зоне, которая его запрещает | `CommandException` |
| `2104` | `BILLING_FAILURE` | недостаточно средств. Ничего не сделано | `InsufficientFundsException` |
| `2105` | `NOT_ELIGIBLE_FOR_RENEWAL` | в том числе несовпадение `curExpDate` | `CommandException` |
| `2106` | `NOT_ELIGIBLE_FOR_TRANSFER` | объект перенести нельзя | `CommandException` |

`2105` на продлении означает, что срок истечения не такой, как вы думали. Перечитайте его через
`info()`; отправлять тот же кадр снова — никогда не выход.

### Безопасность — 2200–2299

| Код | Константа | Значение | Исключение |
|---|---|---|---|
| `2200` | `AUTHENTICATION_ERROR` | не удался сам вход | `AuthenticationException` |
| `2201` | `AUTHORIZATION_ERROR` | объект не ваш, действовать с ним нельзя | `AuthorizationException` |
| `2202` | `INVALID_AUTHORIZATION` | неверный `authInfo` | `AuthorizationException` |

### Жизненный цикл объекта — 2300–2399

| Код | Константа | Значение | Исключение |
|---|---|---|---|
| `2300` | `OBJECT_PENDING_TRANSFER` | трансфер уже в ожидании | `CommandException` |
| `2301` | `OBJECT_NOT_PENDING_TRANSFER` | в ожидании нет ничего, что можно принять, отклонить, отозвать или запросить | `CommandException` |
| `2302` | `OBJECT_EXISTS` | уже зарегистрирован | `ObjectExistsException` |
| `2303` | `OBJECT_DOES_NOT_EXIST` | такого объекта нет | `ObjectDoesNotExistException` |
| `2304` | `OBJECT_STATUS_PROHIBITS_OPERATION` | мешает статус | `ObjectStatusException` |
| `2305` | `OBJECT_ASSOCIATION_PROHIBITS_OPERATION` | мешает связь — связанный контакт, подчинённый хост | `ObjectStatusException` |
| `2306` | `PARAMETER_VALUE_POLICY_ERROR` | политика реестра отклоняет это значение | `PolicyException` |
| `2307` | `UNIMPLEMENTED_OBJECT_SERVICE` | объектный сервис или зона здесь не обслуживаются | `CommandException` |
| `2308` | `DATA_MANAGEMENT_POLICY_VIOLATION` | изменение нарушило бы правила реестра по управлению данными | `PolicyException` |

### Сервер — 2400+

| Код | Константа | Значение | Исключение |
|---|---|---|---|
| `2400` | `COMMAND_FAILED` | реестр не смог выполнить команду. **Можно повторить** | `CommandException` |
| `2500` | `COMMAND_FAILED_SERVER_CLOSING` | не выполнено, и сессия завершается. **Можно повторить после переподключения** | `SessionException` |
| `2501` | `AUTHENTICATION_SERVER_CLOSING` | то же самое | `SessionException` |
| `2502` | `SESSION_LIMIT_EXCEEDED_SERVER_CLOSING` | слишком много одновременных сессий | `SessionException` |

---

## Когда трансформирующая команда упала и вы не знаете, выполнилась ли она

Тайм-аут чтения, оборванное соединение или `2500` посреди `create`, `renew`, `transfer` или
`restore` оставляют по-настоящему **неизвестный исход**: реестр мог выполнить команду и списать
деньги ещё до того, как ответ потерялся. Эта библиотека отличить одно от другого не может, и вы по
исключению — тоже. Команда либо произошла, либо нет, а кадр, который сказал бы об этом, так и не
пришёл.

**Не повторяйте команду просто так. Слепой повтор — это то, как домен регистрируется, и
оплачивается, дважды.**

Вместо этого спросите у реестра, как обстоит дело:

| Упавшая команда | Как выяснить, что произошло |
|---|---|
| `domain().create()` | `domain().info(name)`. Домен либо есть, либо в ответ приходит `2303` |
| `contact().create()` | `contact().info(id)` |
| `contact().createAuto()` | **найти его по идентификатору нельзя** — идентификатор существовал только в том ответе, который вы потеряли. Сверьтесь с собственными записями, прежде чем звать метод снова: каждый вызов генерирует новый идентификатор, поэтому повтор — это второй контакт |
| `domain().renew()` | сравните `info().expiryDate()` с тем, что вы ожидали. Продление либо сдвинуло дату, либо нет |
| `domain().transfer("request")` | `domain().transfer("query", name)` — `2300`, пока запрос в ожидании, `2301`, когда его нет |
| `domain().restore()` | `info().rgpStatus()`. Восстановленный домен вышел из `redemptionPeriod` |
| `domain().update()` / `delete()` | `info()` и сравнение с тем, что вы отправляли |

Сверка, написанная один раз:

```java
import com.epptools.sdk.exception.ConnectionException;
import com.epptools.sdk.exception.ObjectDoesNotExistException;

try {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("years", 1);
    options.put("registrant", "C-0001");
    options.put("fee", quote);

    Response r = client.domain().create(name, options);
    orders.recordCreated(name, r.svTRID(), r.expiryDate(), r.feeAmount());
} catch (ConnectionException e) {
    // The answer is lost. The command may have run, and the money may have been taken.
    orders.markUnknown(name, e.getMessage());

    client.disconnect();
    client.connect();
    client.login();

    try {
        Response info = client.domain().info(name);
        if ("EXAMPLE".equals(info.sponsor())) {
            // It happened. Record it from the registry's own answer, not from your guess.
            orders.recordCreated(name, info.svTRID(), info.expiryDate(), null);
        } else {
            // Registered, but not by us. This is not the case we retry.
            orders.escalate(name, "held by " + info.sponsor());
        }
    } catch (ObjectDoesNotExistException nothingThere) {
        // 2303 - the operation really did not happen. NOW a retry is safe.
        orders.readyToRetry(name);
    }
}
```

Три вещи, которые делают это правильным, а не просто аккуратным:

- **Переподключитесь перед сверкой.** Соединение, потерявшее ответ, было закрыто этим же сбоем, а
  потоку на неизвестном смещении нельзя доверить ответ на вопрос, который вы собираетесь задать.
- **`2303` — единственное доказательство того, что ничего не произошло.** Всё прочее — тайм-аут на
  самом `info`, `2201`, имя, которым владеет кто-то другой — по-прежнему неизвестность, а
  неизвестность — это не «нет».
- **Плата — часть неизвестного.** Удавшееся создание было оплачено, видели вы ответ или нет.
  Сверяйте деньги по ответу реестра или по своей выписке, а не по тому, что ваш собственный код
  считал сделанным.

Случай `1001` — не этот случай: у отложенного действия исход *известен* — принято и завершается
офлайн. Сохраните `svTRID` и сопоставьте вердикт, когда придёт
[poll-уведомление](poll.md#итог-отложенной-операции). Чего не должно случаться никогда — это счесть
`1001` сбоем и отправить команду заново: та же двойная регистрация, только другим путём.

**Сбой, исход которого вы определить не можете, заслуживает внимания оператора, а не автоматической
второй попытки.** Поставьте его в очередь для человека, сохраните отправленный `clTRID` и
остановитесь.

---

## Как сообщить о проблеме

Приложите **svTRID** из ответа и **clTRID**, который отправил ваш клиент. Вместе они указывают на
конкретную транзакцию в журналах реестра — именно это позволяет ответить на обращение без лишнего
круга уточнений.

```java
import com.epptools.sdk.exception.CommandException;

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

Пришлите и сами кадры, если можете, но **сначала вымарайте `<pw>`, `<newPW>` и `<authInfo>`**: это
действующие учётные данные, и библиотека по той же причине маскирует их в собственных журналах.
`raw()` у ответа возвращает кадр без маскирования.

Вопросы о библиотеке, о кадре, который отклонил реестр, или об ошибке в коде:
**https://github.com/epptools/java-sdk/issues**. Вопросы по учётной записи и счетам — вашему менеджеру в реестре.

---

См. также: [Команды](commands.md) · [Домены](domains.md) · [Баланс и цены](balance.md) ·
[Poll](poll.md) · [Ответы](responses.md)

[← Оглавление руководства](README.md)
