# Баланс и цены

На этой странице живут два механизма. Друг от друга они независимы, и общее у них одно: оба имеют
дело с деньгами, а деньги в этой библиотеке — всегда точная десятичная **строка**.

- **Запрос баланса** — сколько лежит на вашей учётной записи и сколько с неё ещё можно потратить.
  Он идёт в собственном расширении баланса реестра, и его пространство имён клиент читает из
  `<greeting>`. Эта команда не описана ни в одном RFC, поэтому реестр может её и не предлагать: там,
  где не предлагает, `balance()` бросает `ConfigException` с перечнем того, что сервер объявил, а не
  отправляет кадр, который тот проигнорирует. См.
  [Команды](commands.md#собственные-расширения-вашего-реестра).
- **Расширение fee (RFC 8748)** — стандартный EPP. Спросить, во что обойдётся операция, и
  ограничить, сколько трансформирующей команде позволено с вас списать.

Всё здесь предполагает подключённый клиент с выполненным входом — см. [Сессия](session.md).

**Деньги возвращаются точной десятичной строкой, а не числом с плавающей точкой.** В двоичной
плавающей арифметике `0.1 + 0.2` — это не `0.3`, и баланс, просуммированный так, уплывает на сотую
здесь и сотую там, пока ваш учёт и учёт реестра не разойдутся. Берите `BigDecimal` или целые числа в
минорных единицах. То же правило действует для любой суммы на этой странице: для котировки, для
предела, для списанной платы, для кредитного лимита.

Суммы в примерах приведены для иллюстрации. Это не тариф реестра.

## Обзор вызовов и аксессоров

| Вызов | Отправляет | Отвечает |
|---|---|---|
| `client.balance(): Response` | `<info><balance:info>` | кредитный лимит, баланс, доступные средства |
| `domain().check(List<String> names, Map<String, Object> fee, String currency): Response` | `domain:check` + `<fee:check>` | доступность **и** цены |
| `domain().create(name, options)` с ключом `"fee"` | `domain:create` + `<fee:create>` | создание, с пределом |
| `domain().renew(name, curExpDate, years, fee)` | `domain:renew` + `<fee:renew>` | продление, с пределом |
| `domain().transfer("request", name, authInfo, years, fee)` | `domain:transfer` + `<fee:transfer>` | трансфер, с пределом |
| `domain().restore(name, fee)` | `domain:update` + `<fee:update>` | восстановление, с пределом |

| Аксессор | Что читает |
|---|---|
| `balance(): Map<String, String>` | весь блок баланса или `null` |
| `threshold(): String` | цифра, при пересечении которой в очередь встало уведомление о низком балансе, или `null` на обычном отчёте |
| `creditLimit(): String` · `currentBalance(): String` · `availableCredit(): String` | по одной цифре каждый |
| `fees(): Map<String, Object>` | все котировки из ответа на check, по именам |
| `feeFor(String name, String operation, int years): String` | одну котировку |
| `feeClass(String name): String` · `isPremium(String name): boolean` | в каком прайс-листе находится имя |
| `chargedFee(): Map<String, String>` · `feeAmount(): String` · `feeCurrency(): String` | сколько трансформирующая команда списала на самом деле |

---

## balance

```java
public Response balance();
```

**На проводе:** `<command><info><balance:info/></info>` в балансовом пространстве имён реестра
(`http://registry.example/epp/balance-1.0`). Это чтение: ничего не списывается и ничего не меняется.

Метод висит на самом клиенте, а не на обработчике команд объекта, потому что речь об учётной
записи, а не об объекте.

```java
Response b = client.balance();

b.creditLimit();       // "5000.00"  - how far the account may go negative
b.currentBalance();    // "1240.50"  - what is on it now
b.availableCredit();   // "6240.50"  - what you can still spend: balance plus the limit
```

Все три — десятичные строки в валюте вашей учётной записи. Весь блок одним вызовом:

```java
b.balance();
// {creditLimit=5000.00, balance=1240.50, availableCredit=6240.50}
```

`currentBalance()` существует потому, что `balance()` — это весь блок, а `balance` — одна цифра
внутри него. `b.balance().get("balance")` даёт то же значение; именованный аксессор нужен, чтобы
строка про деньги не выглядела опечаткой.

**`balance()` возвращает `null`, когда в ответе нет блока баланса.** Проверять надо именно это, а не
исходить из того, что цифры на месте:

```java
Response b = client.balance();

if (b.balance() == null) {
    // Это не ответ о балансе. При throwOnFailure(false) отказ приходит сюда обычным Response,
    // а не исключением, и цифр он не несёт.
    throw new IllegalStateException(b.message() != null ? b.message() : "no balance in the reply");
}
```

### Проверка перед пакетом

Вызывают его ради решения, которое принимается до трат. Сравнивайте через `BigDecimal`, а не
оператором `<` над числами с плавающей точкой:

```java
BigDecimal available = new BigDecimal(client.balance().availableCredit());
BigDecimal needed = new BigDecimal("2400.00");   // 24 registrations at an illustrative 100.00

if (available.compareTo(needed) < 0) {
    // Stop here, not on the thirteenth name, halfway through the batch.
    alertBilling("available " + available + ", need " + needed);
    return;
}
```

Пакет, у которого деньги кончились на середине, — не катастрофа: реестр отклоняет каждую оставшуюся
платную команду с `2104` и ничего не списывает, — но разбираться с наполовину выполненным заказом
придётся вам. Почему `2104` означает «остановить пакет», а не «пропустить имя», — в
[Ошибки](errors.md#insufficientfundsexception-2104).

### Уведомление о низком балансе

Реестр может прислать эти цифры и сам. [poll-уведомление](poll.md#уведомление-о-низком-балансе) о
низком балансе несёт тот же блок, поэтому читается теми же аксессорами:

```java
client.poll().drain(notice -> {
    if (notice.balance() != null) {
        alertBilling(notice.currentBalance());   // a decimal string - do not convert it
    }
});
```

**Коды ответа:** `1000`, с цифрами в кадре. Отказ — сервис не предложен этой сессии, учётной записи
не разрешено его читать — приходит `CommandException`, как и любой другой; если вы его увидели,
проверьте, был ли балансовый URI объявлен при входе. По умолчанию вход объявляет ровно те сервисы,
которые предложило приветствие, поэтому дело обычно в том, что `Config.extUris` задаёт
собственный список — см. [Сессия](session.md).

---

## Цены: расширение fee (RFC 8748)

Одно расширение, два совершенно разных применения. Держите их в голове раздельно — остальное
сложится само:

| Применение | Где | Что делает |
|---|---|---|
| **Спросить** | `domain().check()` | котирует цену. Ничего не меняет, ничего не стоит |
| **Ограничить** | `create`, `renew`, `transfer`, `restore` | называет максимум, на который вы согласны. Более высокая реальная цена отклоняет команду |

Предел — это **не** цена, которую назначаете вы. Реестр списывает по своему тарифу. Предел даёт вам
другое: если тариф оказался выше того, на что вы согласились, команда не выполняется, а не
выставляет вам счёт.

И то и другое необязательно. Команда без блока цены выполняется как обычно, и списывается
собственная цена реестра.

---

### Запрос цены при check

```java
public Response check(List<String> names, Map<String, Object> fee, String currency);
```

`fee` — это карта `операция => годы`. Операции: `create`, `renew`, `transfer`, `restore`, `update` и
`delete`.

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);
fee.put("renew", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, null);

r.isAvailable("example.com.ua");             // TRUE - the name is free
r.feeFor("example.com.ua", "create", 1);     // "100.00"
r.feeFor("example.com.ua", "renew", 1);      // "90.00"
r.fees().get("_currency");                   // "UAH"
```

Один обмен отвечает на оба вопроса — свободно ли имя и во что оно обойдётся. Ценовой блок относится
к каждому имени в команде, и в ответе на каждое имя приходит своя запись:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);

Response r = client.domain().check(
        Arrays.asList("one.com.ua", "two.com.ua", "three.com.ua"), fee, null);

for (Map.Entry<String, Boolean> entry : r.availability().entrySet()) {
    if (Boolean.TRUE.equals(entry.getValue())) {
        String quote = r.feeFor(entry.getKey(), "create", 1);
        System.out.printf("%-16s %s %s%n", entry.getKey(),
                quote != null ? quote : "-", r.fees().get("_currency"));
    }
}
```

Ответ о доступности — снимок, и цена тоже. Между проверкой и созданием имя могут занять, а тариф —
сдвинуться; ровно для этого и нужен
[предел](#ограничение-суммы-которую-вы-согласны-заплатить).

---

### Несколько периодов в одной команде

**Список** лет спрашивает про одну и ту же операцию для каждого периода, поэтому целая таблица цен
стоит одного обмена вместо пяти:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 3, 5, 10));

Response table = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

table.feeFor("example.com.ua", "create", 1);    // "100.00"
table.feeFor("example.com.ua", "create", 5);    // "480.00"
table.feeFor("example.com.ua", "create", 10);   // "950.00"
```

Одиночные значения и списки свободно смешиваются, и несколько операций могут ехать вместе:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));
fee.put("renew", Arrays.asList(1, 2));
fee.put("restore", 1);

client.domain().check(Arrays.asList("example.com.ua"), fee, null);
```

**В кадр помещается не больше 20 ценовых позиций.** Позиция — это одна пара *(операция, период)*,
поэтому в примере выше их шесть: три создания, два продления, одно восстановление. Число имён здесь
ни при чём — двадцать позиций остаются двадцатью, спрашиваете вы про одно имя или про пятьдесят.

```java
List<Object> everyYear = new ArrayList<Object>();
for (int years = 1; years <= 30; years++) {
    everyYear.add(years);
}
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", everyYear);

client.domain().check(Arrays.asList("example.com.ua"), fee, null);
// ValidationException: a fee query carries at most 20 entries; this one has 30
```

Отказ приходит здесь, до того как построен кадр, а не в реестре, где слишком длинный запрос
возвращается кодом `2306`, не называющим ничего конкретного. Разбейте его на два вызова.

Период меньше единицы отправляется как единица, поэтому `0` спрашивает цену за один год, а не «ни
за что».

---

### Указание валюты

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");
```

Валюта едет в `<fee:currency>` и приводится к верхнему регистру за вас. Опустите её — и реестр даст
котировку в своей.

**Валюта, в которой реестр не котирует, возвращается как недоступная, с причиной, а не
пересчитанной наугад.** В этом различии всё дело: пересчитанная цифра выглядела бы как котировка,
под которую можно ставить предел, и была бы неверной.

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "JPY");

Object entry = r.fees().get("example.com.ua");
if (entry instanceof Map) {
    Map<?, ?> quoted = (Map<?, ?>) entry;
    if (Boolean.FALSE.equals(quoted.get("avail"))) {
        System.out.println(quoted.get("reason"));     // e.g. "Currency not supported"
    }
}
```

**Валюта без единой операции отклоняется** с `ValidationException`, которое говорит, что валюта сама по
себе ничего не оценивает. RFC 8748 требует от `fee:checkType` хотя бы одного `<fee:command>`,
так что кадр, который собрался бы, реестр ответить не сможет. А отказ лучше тихого
отбрасывания валюты: вы спросили, сколько стоит имя в этой валюте, и тишина выглядела бы как
ответ. Называйте операции, котировка которых вам нужна:
`check(names, Collections.singletonMap("create", 1), "UAH")`.

---

### transfer и restore — операции на один год

**Сколько бы лет вы ни запросили, `transfer` и `restore` котируются как один год**, и в ответе
отражается тот период, который реально был бы списан. Поэтому запрашивайте их на один год и читайте
обратно на один год:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("transfer", 1);
fee.put("restore", 1);

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, null);

r.feeFor("example.com.ua", "transfer", 1);   // "120.00"
r.feeFor("example.com.ua", "restore", 1);    // "1000.00"
r.feeFor("example.com.ua", "restore", 3);    // null - there was no three-year quote
```

Запросить `restore` на три года не ошибка: ответ придёт про один год, потому что операция именно
такая. Чтение обратно на трёх годах даст `null`, а `null`, принятый за «бесплатно», — это то, как
восстановление попадает в учёт с нулевой ценой. Если хотите увидеть период, который реестр
действительно оценил, читайте список `periods`:

```java
Object periods = ((Map<?, ?>) r.fees().get("example.com.ua")).get("periods");
for (Object quote : (List<?>) periods) {
    // {op=restore, years=1, fee=1000.00} - years here is the period that was PRICED
    System.out.println(quote);
}
```

То же верно для трансфера с обязательным продлением: продление — отдельная строка каталога, поэтому
запрашивайте `transfer` и `renew` вместе, если нужна сумма целиком.

---

### Чтение ответа

```java
public Map<String, Object> fees();
public String feeFor(String name, String operation, int years);
public String feeClass(String name);
public boolean isPremium(String name);
```

`fees()` — это весь ответ, разложенный по именам, с валютой рядом:

```java
Map<String, Object> fee = new LinkedHashMap<String, Object>();
fee.put("create", Arrays.asList(1, 2, 5));

Response r = client.domain().check(Arrays.asList("example.com.ua"), fee, "UAH");

r.fees();
// {
//   _currency=UAH,
//   example.com.ua={
//       avail=true,                 // whether the registry could PRICE it - see below
//       reason=null,                // why it could not, when avail is false
//       class=premium,              // present only when the registry sent a class
//       commands={create={years=1, fee=100.00}},
//       periods=[{op=create, years=1, fee=100.00},
//                {op=create, years=2, fee=200.00},
//                {op=create, years=5, fee=480.00}]
//   }
// }
```

Три вещи об этой форме:

- **В `commands` по одной записи на операцию** — для первого периода, который вы запросили. Если
  периодов было несколько, читайте `feeFor()` или `periods`. Цикл по `commands` после запроса
  `[1, 2, 5]` молча покажет годовую цену для всех трёх.
- **`avail` здесь про цену, а не про имя.** `false` означает, что реестр не смог дать котировку —
  зона, которую он не обслуживает, валюта, в которой он не котирует, — а `reason` говорит, что
  именно. Свободно ли само *имя*, отвечает `isAvailable()`: другой вопрос и другой ответ.
- **Внутри периода котировка может быть `null`**, а рядом с ней — `reason`: реестр оценил имя, но не
  эту операцию. `null` — это «котировки нет», и никогда — «бесплатно».

```java
String quote = r.feeFor("example.com.ua", "create", 1);
if (quote == null) {
    throw new IllegalStateException("no create quote for example.com.ua — do not assume a price");
}
```

`feeClass()` и `isPremium()` говорят, в каком прайс-листе находится имя:

```java
r.feeClass("example.com.ua");    // "premium" | "standard" | null
r.isPremium("example.com.ua");   // true when a class is present and is not "standard"
```

Оба имеют форму без имени, которая отвечает про первое имя в ответе, у которого класс есть.
**Цену берите из `fees()`, а не из класса.** Класс говорит, какой прайс-лист применяется, а не
сколько это стоит, и `false` от `isPremium()` означает лишь «в ответе не объявлен особый класс», а
не обещание стандартной цены.

Другая вещь с похожим названием: `prices()` и `priceChannel()` в ответе `domain:info` — это
собственные ценовые подсказки реестра для домена, который у вас уже есть, а не котировки RFC 8748.
Они описаны в [Ответы](responses.md#домен).

---

## Ограничение суммы, которую вы согласны заплатить

То же расширение, направленное в другую сторону. В трансформирующей команде вы называете максимум,
который согласны заплатить, и реестр скорее отклонит команду, чем спишет больше.

| Команда | Как передаётся предел | На проводе |
|---|---|---|
| `create` | ключом `"fee"` | `<fee:create>` |
| `renew` | четвёртым аргументом | `<fee:renew>` |
| `transfer` | пятым аргументом (у `request`) | `<fee:transfer>` |
| `restore` | вторым аргументом | `<fee:update>` — восстановление *и есть* обновление |
| `update` | ключом `"fee"` | `<fee:update>` |

Формы всего две, и всюду одни и те же: голая сумма либо сумма вместе с валютой, в которой она
названа. Аргумент объявлен как `Object`, чтобы обе формы помещались в один параметр: `String` для
первой, `Map` для второй.

```java
Object bare = "100.00";                        // сумма, в собственной валюте реестра

Map<String, Object> withCurrency = new LinkedHashMap<String, Object>();
withCurrency.put("amount", "100.00");
withCurrency.put("currency", "UAH");           // …и валюта, в которой она названа
```

```java
// Create
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");
options.put("fee", "100.00");
client.domain().create("example.com.ua", options);

// Renew
Map<String, Object> cap = new LinkedHashMap<String, Object>();
cap.put("amount", "90.00");
cap.put("currency", "UAH");
client.domain().renew("example.com.ua", "2027-04-01", 1, cap);

// Incoming transfer
client.domain().transfer("request", "example.com.ua", "the-code", 1, "120.00");

// Restore
client.domain().restore("example.com.ua", "1000.00");
```

В [билдерах](builders.md) это называется `maxFee()` — чем оно и является:

```java
client.domain().createBuilder("example.com.ua")
        .years(1)
        .registrant("C-0001")
        .maxFee("100.00", "UAH")
        .send();
```

`maxFee()` заодно проверяет, что сумма — обычное десятичное число (`100`, `100.5`, `100.00`), и,
если это не так, поднимает `ValidationException` до того, как что-либо будет отправлено. Опция,
переданная напрямую, уходит в реестр вашей строкой как есть, и испорченная возвращается голым
`2004`/`2005`, не называющим ни одного поля, — уже после того, как команда была предпринята.

### Что означает отказ с кодом 2004

**`2004` на команде, которая несла предел, означает, что реальная цена выше предела. Ничего не
сделано и ничего не списано.**

```java
import com.epptools.sdk.ResultCode;
import com.epptools.sdk.exception.CommandException;

try {
    Map<String, Object> options = new LinkedHashMap<String, Object>();
    options.put("years", 1);
    options.put("registrant", "C-0001");
    options.put("fee", "100.00");

    client.domain().create("rare.com.ua", options);
} catch (CommandException e) {
    if (e.eppCode() == ResultCode.PARAMETER_VALUE_RANGE_ERROR) {
        // Домен НЕ зарегистрирован, и с вас НИЧЕГО не списали. Запросите цену заново и решайте
        // заново — не расширяйте предел в цикле, пока команда не пройдёт: именно так премиум-имя
        // и покупается по цене, которую никто не одобрял.
        Map<String, Object> fee = new LinkedHashMap<String, Object>();
        fee.put("create", 1);
        String quote = client.domain().check(Arrays.asList("rare.com.ua"), fee, null)
                .feeFor("rare.com.ua", "create", 1);
        askAHumanAbout("rare.com.ua", quote);
    }
}
```

В этом вся ценность предела: смена тарифа, имя, о премиальности которого вы не знали, или
устаревшая цена в вашем собственном кеше превращаются в отказ, на который вы смотрите, а не в счёт,
который вы находите позже. `2004` — ещё и общий код «значение вне диапазона», поэтому он может
означать период, которого зона не предлагает; что именно — скажет `reasons()` у исключения, а
предел стоит проверять первым, если команда его несла.

Автоматическое расширение предела сводит его на нет. Если создание упало с `2004`, запросите цену
заново через `check()` и либо осознанно примите новую, либо оставьте имя в покое.

---

## Как узнать, сколько трансформирующая команда списала на самом деле

```java
public Map<String, String> chargedFee();
public String feeAmount();
public String feeCurrency();
```

Успешная трансформирующая команда, которая несла ценовое соглашение, отражает в ответе списанное:

```java
Map<String, Object> options = new LinkedHashMap<String, Object>();
options.put("years", 1);
options.put("registrant", "C-0001");
options.put("fee", "100.00");

Response r = client.domain().create("example.com.ua", options);

r.chargedFee();     // {currency=UAH, fee=100.00}
r.feeAmount();      // "100.00"
r.feeCurrency();    // "UAH"
```

**В заказ записывайте `feeAmount()`, а не ту цифру, которую вам назвали котировкой в `check`.**
Котировка была утверждением про определённый момент; здесь — то, что реестр выставил. Совпадают они
почти всегда, и весь смысл хранить второе — в тех случаях, когда не совпали.

`null` означает, что в ответе не было ценового блока, — обычный ответ для команды, отправленной без
предела в реестр, который не отражает цены без запроса. Это никогда не означает «бесплатно».

Значение читается из того блока трансформации, который несёт ответ (`creData`, `renData`, `trnData`,
`updData`, `delData`), поэтому одни и те же три аксессора работают после создания, продления,
трансфера, восстановления и удаления.

---

## Регистрация с проверкой цены, от начала до конца

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.ResultCode;
import com.epptools.sdk.exception.CommandException;
import com.epptools.sdk.exception.EppException;
import com.epptools.sdk.exception.InsufficientFundsException;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RegisterAtAKnownPrice {

    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .caFile("/path/to/registry-ca.pem")
                .build();

        try (Client client = new Client(config)) {
            client.connect();
            client.login();

            String name = "example.com.ua";

            // 1. Есть ли вообще деньги на эту операцию?
            String available = client.balance().availableCredit();

            // 2. Спросить доступность и цену одним обменом.
            Map<String, Object> fee = new LinkedHashMap<String, Object>();
            fee.put("create", Arrays.asList(1, 2));
            Response check = client.domain().check(Arrays.asList(name), fee, "UAH");

            if (!Boolean.TRUE.equals(check.isAvailable(name))) {
                String reason = check.unavailableReason(name);
                System.out.println(name + " is not available: "
                        + (reason != null ? reason : "no reason given"));
                client.logout();
                return;
            }

            String quote = check.feeFor(name, "create", 1);
            if (quote == null) {
                throw new IllegalStateException("no create quote — refusing to register at an unknown price");
            }
            if (check.isPremium(name)) {
                System.out.println("premium name, class " + check.feeClass(name));
            }
            if (new BigDecimal(available).compareTo(new BigDecimal(quote)) < 0) {
                System.out.println("available " + available + " is short of " + quote);
                client.logout();
                return;
            }

            // 3. Зарегистрировать с пределом по цене, которую только что назвали.
            Map<String, Object> contacts = new LinkedHashMap<String, Object>();
            contacts.put("admin", "C-0001");
            contacts.put("tech", "C-0001");

            Map<String, Object> options = new LinkedHashMap<String, Object>();
            options.put("years", 1);
            options.put("registrant", "C-0001");
            options.put("contacts", contacts);
            options.put("authInfo", "D0main-Pw");
            options.put("fee", quote);

            Response r = client.domain().create(name, options);

            // 4. Сохранить то, что списали на самом деле, и собственные даты и идентификаторы реестра.
            System.out.println("registered " + r.objectName() + " until " + r.expiryDate());
            System.out.println("charged    " + r.feeAmount() + " " + r.feeCurrency());
            System.out.println("svTRID     " + r.svTRID());

            if (r.isPending()) {
                // 1001: поставлено в очередь. Домен ещё не зарегистрирован; вердикт придёт poll-уведомлением.
                markPending(r.svTRID());
            }

            client.logout();
        } catch (InsufficientFundsException e) {
            alertBilling(e.getMessage());        // остановитесь: каждая следующая платная команда упадёт так же
        } catch (CommandException e) {
            if (e.eppCode() == ResultCode.PARAMETER_VALUE_RANGE_ERROR) {
                System.out.println("the price moved above the cap — nothing was registered or charged");
            } else {
                System.out.println("EPP " + e.eppCode() + ": " + e.getMessage());
            }
        } catch (EppException e) {
            System.out.println("EPP error: " + e.getMessage());
        }
    }

    private static void markPending(String svTRID) { }
    private static void alertBilling(String message) { }
}
```

Четыре привычки из этой программы стоит сохранить: котировать и ограничивать **одной и той же**
цифрой; хранить то, что списано, а не то, что было в котировке; проверять `isPending()` до того, как
записать что-либо выполненным; и считать `2104` поводом остановиться, а не перейти к следующему
имени.

---

## Коды ответа на этой странице

| Код | Значение | Исключение |
|---|---|---|
| `1000` | выполнено — цифры или котировки лежат в кадре | — |
| `1001` | трансформирующая команда принята и завершается офлайн; плата идёт следом за ней | — |
| `2004` | реальная цена выше согласованного вами предела либо период вне диапазона. **Ничего не списано** | `CommandException` |
| `2005` | сумма платы, которую реестр не может прочитать как число | `CommandException` |
| `2103` | ценовое расширение не предлагается для этой зоны | `CommandException` |
| `2104` | недостаточно средств; ничего не сделано | `InsufficientFundsException` |
| `2306` | политика реестра отклоняет запрос или соглашение | `PolicyException` |

Запрос цен более чем на 20 позиций и сумма в `maxFee()`, не являющаяся обычным десятичным числом, —
и то и другое эта библиотека отклоняет с `ValidationException` до того, как что-либо будет
отправлено.

---

См. также: [Домены](domains.md) · [Poll](poll.md) · [Ответы](responses.md) ·
[Билдеры](builders.md) · [Ошибки](errors.md)

[← Оглавление руководства](README.md)
