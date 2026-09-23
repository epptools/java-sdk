# Poll

Реєстр не передзвонює вам. Усе, що стається поза вашим власним потоком команд — трансфер, який хтось
запитав, результат операції, яку реєстр обробив офлайн, наближення завершення терміну реєстрації,
низький баланс, — потрапляє в **чергу повідомлень** для кожного реєстратора, і ви читаєте її
командою `<poll>` (RFC 5730 §2.9.2.3).

Доступ — через `client.poll()`. Усе тут припускає підключений клієнт із виконаним входом — див.
[Сесія](session.md).

**Спорожняйте чергу за розкладом.** Черга, якої ніхто не читає, — це запит на трансфер, на який
ніхто не відповів, а після строку реєстр вирішує за вас.

## Методи

| Метод | Команда EPP |
|---|---|
| `request(): Response` | `<poll op="req"/>` |
| `ack(String messageId): Response` | `<poll op="ack" msgID="…"/>` |
| `drain(Consumer<Response> handler, int limit): int` | `req` → ваш обробник → `ack`, і так по колу |

---

## request

```java
public Response request()
```

**У каналі передачі:** `<command><poll op="req"/>`. Він читає голову черги, **не вилучаючи її**.
Викликаний двічі, він двічі поверне те саме сповіщення.

На нього відповідають два коди успіху, і різниця між ними — це весь протокол:

| код | значення |
|---|---|
| `1301` | повідомлення чекає; сповіщення в цьому кадрі |
| `1300` | черга порожня |

```java
Response msg = client.poll().request();

if (msg.messageId() != null) {
    msg.messageId();          // "10021" — the id you pass to ack()
    msg.messageCount();       // 3 — how many remain, this one included
    msg.queueMessage();       // "Transfer requested." — the text of the NOTICE ITSELF
    msg.queueMessageLang();   // "uk" | "ru" | "en"
    msg.queueDate();          // when the registry queued it
}
```

**Читайте `queueMessage()`, а не `message()`.** Це різні елементи. `message()` — це `<result><msg>`,
шапка результату команди, яка на кожному сповіщенні, що чекає, читається як «Command completed
successfully; ack to dequeue». `queueMessage()` — це `<msgQ><msg>`, саме сповіщення. Клієнт, який
пише в журнал `message()`, записує той самий незмінний рядок для кожної події, яку колись отримав, і
викидає зміст, а `ack` потім знищує оригінал.

`queueMessageLang()` повідомляє мову, якою сформовано сповіщення. Це властивість сповіщення, а не
вашої сесії, тож не припускайте, що вона збігається з мовою, з якою ви ввійшли.

`messageCount()` рахує те, що є в черзі, включно зі сповіщенням, яке ви тримаєте, тож на останньому
він дорівнює `1`, а наступний `request()` відповідає `1300`.

---

## ack

```java
public Response ack(String messageId)
```

**У каналі передачі:** `<command><poll op="ack" msgID="…"/>`.

**Підтвердження назавжди видаляє сповіщення в реєстрі. Повернути його неможливо.** Відповідь несе
нову голову черги: `1301` з наступними `messageId()` та `messageCount()`, поки повідомлення
лишаються, і `1300`, щойно черга порожня.

```java
Response next = client.poll().ack("10021");
System.out.println(next.messageCount() + " remaining");
```

Порядок — це і є вся суть:

```java
// Right: the notice is safe on your side before it stops existing on theirs.
Response msg = client.poll().request();
if (msg.messageId() != null) {
    store(msg.queueMessage(), msg.raw());   // if this throws, the notice stays in the queue
    client.poll().ack(msg.messageId());
}
```

Цикл, який спершу підтверджує, а обробляє другим кроком, втрачає кожне сповіщення, обробка якого
зірвалася — запит на трансфер, результат create, що очікував, — і не лишає ні з чого повторити, ні
сліду про те, що щось загубилося. Спершу збережіть, підтверджуйте другим кроком.

З другою половиною поспішати не треба. Повідомлення, яке вам уже видали, лишається придатним для
підтвердження, навіть якщо вікно зберігання для доставлення спливе, поки воно у вас: термін
зберігання стосується доставлення, а не вашого підтвердження. Прочитати, зберегти, підтвердити
пізніше — саме такий контракт, і «пізніше» має право вийти за межі вікна.

---

## drain

```java
public int drain(Consumer<Response> handler, int limit)
```

Той самий цикл, написаний один раз і правильно. Кожне сповіщення передається вашому обробнику й
підтверджується **лише після того, як обробник поверне керування**. Повертає кількість сповіщень,
які ваш обробник успішно обробив.

```java
int processed = client.poll().drain(notice ->
        store(notice.messageId(), notice.queueMessage(), notice.pendingActionData()));

System.out.println("processed " + processed + " notices");
```

Чотири речі, які це гарантує, і кожна з них — свідоме рішення:

- **Якщо ваш обробник кидає виняток, сповіщення не підтверджується.** Воно лишається на початку
  черги, а виняток доходить до вас. Усуньте причину і спорожніть чергу знову; нічого не втрачено.
  Наслідок такий: обробник, який кидає виняток завжди, щоразу бачитиме те саме сповіщення — і це
  навмисно, бо альтернатива — його викинути.
- **Доставлення відбувається щонайменше один раз.** Якщо зірветься саме підтвердження — з'єднання
  обірветься між поверненням із обробника і надходженням `ack`, — сповіщення лишається в черзі, і
  наступне спорожнення віддасть його вам знову. Робіть обробник ідемпотентним і беріть
  `messageId()` за ключ дедуплікації: це власний ідентифікатор реєстру для цього сповіщення.
- **Цикл завершує тільки `1300`.** Якщо виводити «порожньо» з відсутності сповіщення, то відмова —
  сесію закрито, обліковий запис призупинено — виглядатиме точно як спорожнена черга, і цикл
  відзвітує про успіх, хоча не прочитано нічого. Відповідь, яка не є ні сповіщенням, ні `1300`,
  викликає `CommandException`. Це діє навіть із `throwOnFailure(false)`.
- **`limit` обмежує обсяг роботи.** `drain(handler, 50)` зупиняється після п'ятдесяти сповіщень.
  `0` означає «доки черга не спорожніє»: це правильно для черги, за якою ви встигаєте, і
  неправильно для тієї, що наповнюється швидше, ніж ви її спорожнюєте, — такий виклик не поверне
  керування ніколи. Коротша форма `drain(handler)` означає саме нуль.

```java
// A cron-friendly pass: a bounded amount of work, and a de-duplication key that survives redelivery.
client.poll().drain(notice -> {
    if (alreadySeen(notice.messageId())) {
        return;                     // a plain return still acks it — which is what you want
    }
    handle(notice);
    markSeen(notice.messageId());
}, 200);
```

---

## Що несуть сповіщення

Кожне сповіщення має конверт `<msgQ>` — ідентифікатор, лічильник, дата, текст, — а більшість несе ще
й структуроване корисне навантаження в `<resData>`. Навантаження читається тими самими аксесорами,
що й відповідь на відповідну команду, тож один парсер обслуговує обидва випадки.

| Сповіщення | Навантаження | Чим читати |
|---|---|---|
| Трансфер запитано / схвалено / відхилено / скасовано | `trnData` | `transfer()`, `transferStatus()`, `objectName()` |
| Домен зареєстровано, продовжено, видалено; результат відновлення | `infData` | `objectName()`, `expiryDate()`, `statuses()`, `rgpStatus()` |
| Реєстрація, що дійшла до реєстру й очікує рішення ТАМ | `creData` | `objectName()`, `createdDate()`, `expiryDate()` |
| Результат відкладеної (`1001`) операції | `panData` | `pendingActionData()` |
| Низький баланс | `balance:infData` | `balance()`, `currentBalance()` |

### Запит на трансфер

```java
Map<String, String> t = notice.transfer();
// {status=pending, requestedBy=DELTA, requestedAt=2026-04-01T09:15:00Z,
//  actingClient=EXAMPLE, actBy=2026-04-06T09:15:00Z, expiryDate=2028-04-01T09:15:00Z}
```

`actBy` — це строк, і саме це поле коштує грошей: **мовчання завершує трансфер.** Після цієї дати
реєстр його підтверджує. Ставтеся до сповіщення про трансфер як до того, на що треба відповісти, —
див. [Домени → transfer](domains.md#transfer).

### Результат відкладеної дії
Саме так операція, яка відповіла `1001`, зрештою звітує про себе. Ви надсилаєте create, отримуєте
`1001` та `svTRID`; за якийсь час сповіщення приносить вирок.

```java
Map<String, Object> pan = notice.pendingActionData();
// {object=example.com.ua, success=true,
//  clTRID=SRV-20260401091500-24191-0007, svTRID=SRV-…, date=2026-04-01T10:00:00Z}

if (pan != null) {
    if (!Boolean.TRUE.equals(pan.get("success"))) {
        markFailed(pan.get("svTRID"));   // the svTRID of the ORIGINAL command, not of this notice
        return;
    }
    markCompleted(pan.get("svTRID"), pan.get("object"), pan.get("date"));
}
```

Три речі про нього:

- **`success` — єдине поле, яке каже, чи спрацювала операція.** Навколишній `<result code="1301">`
  означає «ось повідомлення», а не «ваша операція вдалася». Читати натомість код відповіді —
  класична помилка: тоді кожна відповідь poll виглядає як успіх. Відсутній вирок вважається
  невдачею — відсутнє «так» не є «так».
- **`svTRID` каже, про яку з ваших операцій, що очікують, ідеться.** Звіряйте його з тим, який вам
  дали разом із `1001`. Не припускайте, що йдеться про найсвіжішу: poll — це черга, і сповіщення
  надходять у тому порядку, у якому реєстр завершував роботу.
- **`date` — це коли дія завершилася**, а не коли ви опитали чергу.

### Сповіщення про низький баланс
```java
if (notice.balance() != null) {
    alertBilling(notice.currentBalance());   // an exact decimal string — never coerce it to double
}
```

Це той самий елемент, що й у [запиті балансу](balance.md), тож читають його ті самі аксесори.
Реагуйте на нього: щойно кошти вичерпано, платні команди відхиляються з `2104`, і реєстрація, яку ви
збиралися виконати, зривається через брак коштів, а не через щось не те в самому запиті.

### Зміна, яку реєстр зробив з вашим об'єктом (RFC 8590)

Деякі сповіщення описують те, що сталося з вашим об'єктом без вашої команди: він перестав існувати
в реєстрі або пішов трансфером. Саме на них треба реагувати автоматично — припинити тарифікацію,
повідомити клієнта, прибрати зі свого обліку, — а речення в `<msg>` написане мовою сповіщень
вашого облікового запису, тож розбирати його ненадійно.

```java
Map<String, String> chg = notice.change();
if (chg != null) {
    chg.get("operation");   // "delete" | "transfer" | "renew" | "update" | "restore" | "autoRenew" | …
    chg.get("state");       // "before" | "after"
    chg.get("who");         // "Registry"
    chg.get("date");
    chg.get("svTRID");
    chg.get("reason");
    notice.objectName();
}
```

**`state` каже, як читати об'єкт поруч.** `after` описує об'єкт таким, яким він є зараз.
`before` — таким, яким він був востаннє; для домену, якого вже немає, інакше й не опишеш. Записати
блок `before` у власне сховище як *поточний* стан — це саме те, як вилучений домен оживає у ваших
записах, тож розгалужуйтесь за цим полем перед збереженням.

`change()` не повертає нічого, коли сповіщення не несе блоку зміни, — а це кожне сповіщення, якщо
ви не оголосили `urn:ietf:params:xml:ns:changePoll-1.0` під час входу. Ця бібліотека віддзеркалює
greeting сервера в `<svcs>`, тож реєстр, який його пропонує, оголошується за вас.

На відміну від правила про переміщення, відсутність оголошень **не** дає вам `changeData`: реєстр
надсилає його лише клієнту, який назвав простір імен, бо клієнт, що ніколи його не бачив, може
відкинути весь кадр. Речення `<msg>` не змінюється в обох випадках, тож оголошення ніколи не
забирає того, що ви вже читаєте.

### Навантаження з розширення, якого ви не оголосили (RFC 9038)

Сповіщення потрапляє у вашу чергу ще до того, як реєстр знає, яка сесія його забере, тож воно може
містити елемент із простору імен розширення. Якщо ваш вхід перелічив URI у `<svcExtension>` і цього
простору імен серед них не було, реєстр переносить елемент із `<resData>` в `<extValue>` усередині
`<result>`, а не викидає його.

Кадр усе одно розбирається, і **ви все одно можете виконати `ack`**, тож черга продовжує
спорожнюватися. Дані нікуди не діваються:

```java
for (Map<String, Object> ext : notice.extValues()) {
    ext.get("element");     // e.g. "infData" — which element the registry moved
    ext.get("namespace");   // the namespace to announce to get it back as resData
    ext.get("values");      // its children by local name
    ext.get("reason");      // the registry's explanation
}
```

Оголошуйте ті розширення, які ви розбираєте, якщо хочете типізовану форму; вхід, який не надсилає
`<svcExtension>` узагалі, читається як «без обмежень» і отримує кожне навантаження як `<resData>`.
Див. [Сесія](session.md), щоб дізнатися, як обираються служби під час входу.

---

## Повний опитувач черги

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.EppException;

import java.util.Map;

public class PollWorker {
    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .caFile("/path/to/registry-ca.pem")
                .build();

        try (Client client = new Client(config)) {
            client.connect();
            client.login();

            int count = client.poll().drain(notice -> {
                String id = notice.messageId();

                // Idempotent on messageId: a redelivery after a lost ack must not process twice.
                if (alreadySeen(id)) {
                    return;
                }

                Map<String, String> transfer = notice.transfer();
                Map<String, Object> pending = notice.pendingActionData();

                if (transfer != null && "pending".equals(transfer.get("status"))) {
                    // Answer before actBy, or the registry decides.
                    client.domain().transfer(
                            customerAuthorisedTheMove(notice.objectName()) ? "approve" : "reject",
                            notice.objectName());
                } else if (pending != null) {
                    recordOutcome(pending.get("svTRID"), pending.get("success"), pending.get("object"));
                } else if (notice.balance() != null) {
                    alertBilling(notice.currentBalance());
                } else {
                    store(id, notice.queueMessage(), notice.queueDate(), notice.raw());
                }

                markSeen(id);
                // A plain return IS the acknowledgement. An exception leaves the notice queued.
            }, 200);

            System.out.println("drained " + count);
            client.logout();
        } catch (EppException e) {
            System.out.println("EPP error: " + e.getMessage());
        }
    }

    // Ваше власне зберігання, яким би воно не було. Ці шість методів — усе, чого цикл чекає від вас.
    private static boolean alreadySeen(String messageId) { return false; }
    private static void markSeen(String messageId) { }
    private static void store(Object... parts) { }
    private static void recordOutcome(String svTRID, boolean success, String object) { }
    private static void alertBilling(String balance) { }
    private static boolean customerAuthorisedTheMove(String name) { return false; }
}
```

Зберігати `raw()` поруч із розібраними полями дешево і варто того: сповіщення — єдина копія події, а
після підтвердження в реєстру не лишається жодної.

---

## Коди відповіді на цій сторінці

| Код | Значення | Виняток |
|---|---|---|
| `1301` | повідомлення чекає (`req`) або повідомлення ще лишаються (`ack`) | — |
| `1300` | черга порожня | — |
| `2303` | `ack` для ідентифікатора повідомлення, якого немає або який уже підтверджено | `ObjectDoesNotExistException` |
| `2400` | реєстр не зміг це завершити; може бути тимчасовим | `CommandException` (`isRetryable()`) |
| `2500`–`2502` | сесія завершилася; підключіться і ввійдіть знову | `SessionException` |

`1300` і `1301` — обидва коди успіху, тож `isSuccess()` істинний для кожного з них; саме тому
«порожньо» визначає код `1300`, а ніколи не відсутність навантаження. `ResultCode` називає їх
`SUCCESS_NO_MESSAGES` та `SUCCESS_ACK_TO_DEQUEUE`; повна таксономія — у [Помилки](errors.md).

---

Див. також: [Домени](domains.md) · [Контакти](contacts.md) · [Баланс і ціни](balance.md) ·
[Відповіді](responses.md) · [Помилки](errors.md)

[← Зміст посібника](README.md)
