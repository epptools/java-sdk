# Poll

Реестр не звонит вам в ответ. Всё, что происходит вне вашего собственного потока команд —
запрошенный кем-то трансфер, итог операции, которую реестр обработал офлайн, приближающееся
истечение срока, низкий баланс, — складывается в **очередь сообщений** отдельно для каждого
регистратора, и вы читаете её командой `<poll>` (RFC 5730 §2.9.2.3).

Доступ — через `client.poll()`. Всё здесь предполагает подключённый клиент с выполненным входом —
см. [Сессия](session.md).

**Разбирайте очередь по расписанию.** Очередь, которую никто не читает, — это запрос трансфера, на
который никто не ответил, а после крайнего срока решение за вас примет реестр.

## Методы

| Метод | Команда EPP |
|---|---|
| `request(): Response` | `<poll op="req"/>` |
| `ack(String messageId): Response` | `<poll op="ack" msgID="…"/>` |
| `drain(Consumer<Response> handler, int limit): int` | `req` → ваш обратный вызов → `ack`, и так по кругу |

---

## request

```java
public Response request();
```

**На проводе:** `<command><poll op="req"/>`. Команда читает голову очереди, **не убирая её оттуда**.
Вызовете дважды — получите одно и то же уведомление дважды.

Ответить на неё могут два кода успеха, и разница между ними — это весь протокол:

| код | значение |
|---|---|
| `1301` | сообщение ждёт; уведомление лежит в этом кадре |
| `1300` | очередь пуста |

```java
Response msg = client.poll().request();

if (msg.messageId() != null) {
    msg.messageId();          // "10021" - the id you pass to ack()
    msg.messageCount();       // 3 - how many remain, this one included
    msg.queueMessage();       // "Transfer requested." - the text of the NOTICE ITSELF
    msg.queueMessageLang();   // "uk" | "ru" | "en"
    msg.queueDate();          // when the registry queued it
}
```

**Читайте `queueMessage()`, а не `message()`.** Это разные элементы. `message()` — это
`<result><msg>`, баннер результата команды, который на каждом ожидающем уведомлении гласит
«Command completed successfully; ack to dequeue». `queueMessage()` — это `<msgQ><msg>`, само
уведомление. Клиент, который пишет в журнал `message()`, записывает одну и ту же постоянную строку
на каждое полученное событие и выбрасывает содержимое, а подтверждение затем уничтожает оригинал.

`queueMessageLang()` сообщает язык, на котором уведомление было сформировано. Это свойство самого
уведомления, а не вашей сессии, поэтому не считайте, что он совпадает с языком, с которым вы вошли.

`messageCount()` считает то, что лежит в очереди, вместе с уведомлением, которое вы держите в
руках, поэтому на последнем он равен `1`, а следующий `request()` отвечает `1300`.

---

## ack

```java
public Response ack(String messageId);
```

**На проводе:** `<command><poll op="ack" msgID="…"/>`.

**Подтверждение удаляет уведомление в реестре навсегда. Вернуть его нельзя.** В ответе приходит
новая голова очереди: `1301` со следующими `messageId()` и `messageCount()`, пока сообщения
остаются, и `1300`, как только очередь опустела.

```java
Response next = client.poll().ack("10021");
System.out.println(next.messageCount() + " remaining");
```

Весь смысл — в порядке действий:

```java
// Right: the notice is safely yours before it stops existing at theirs.
Response msg = client.poll().request();
if (msg.messageId() != null) {
    store(msg.queueMessage(), msg.raw());   // if this throws, the notice is still queued
    client.poll().ack(msg.messageId());
}
```

Цикл, который сначала подтверждает, а обрабатывает вторым шагом, теряет каждое уведомление,
обработка которого сорвалась — запрос трансфера, итог отложенного создания, — и не оставляет ни
материала для повтора, ни следа о том, что что-то потеряно. Сначала сохраните, подтверждайте
вторым шагом.

Со второй половиной спешить некуда. Выданное вам сообщение остаётся подтверждаемым, даже если срок
хранения на доставку истечёт, пока оно у вас: хранение относится к доставке, а не к вашему
подтверждению. Контракт именно такой — прочитать, сохранить, подтвердить позже, — и «позже» может
выйти за границы окна.

---

## drain

```java
public int drain(Consumer<Response> handler, int limit);
public int drain(Consumer<Response> handler);
```

Тот же цикл, написанный один раз и правильно. Каждое уведомление передаётся вашему обратному вызову
и подтверждается **только после того, как обратный вызов вернул управление**. Возвращает число
уведомлений, которые ваш обратный вызов обработал успешно.

```java
int processed = client.poll().drain(notice -> {
    store(notice.messageId(), notice.queueMessage(), notice.pendingActionData());
});

System.out.println("processed " + processed + " notices");
```

Четыре гарантии, и каждая из них — осознанное решение:

- **Если ваш обратный вызов бросит исключение, уведомление не подтверждается.** Оно остаётся в
  голове очереди, а исключение доходит до вас. Устраните причину и разберите очередь снова — ничего
  не потеряно. Отсюда и следствие: обратный вызов, который падает всегда, всякий раз видит одно и
  то же уведомление — намеренно, потому что альтернатива — выбросить его.
- **Доставка — не менее одного раза.** Если сорвётся само подтверждение — соединение оборвалось
  между возвратом из вашего обратного вызова и доставкой `ack`, — уведомление остаётся в очереди, и
  следующий разбор отдаст его вам снова. Сделайте обратный вызов идемпотентным и берите
  `messageId()` как ключ дедупликации: это собственный идентификатор уведомления в реестре.
- **Цикл завершает только `1300`.** Если выводить «пусто» из отсутствия уведомления, то отказ —
  закрытая сессия, приостановленная учётная запись — будет выглядеть в точности как разобранная
  очередь, и цикл отчитается об успехе, не прочитав ничего. Ответ, который не является ни
  уведомлением, ни `1300`, поднимает `CommandException`. Это верно и при `throwOnFailure(false)`.
- **`limit` ограничивает объём работы.** `drain(handler, 50)` останавливается после пятидесяти
  уведомлений. `0` означает «пока очередь не опустеет» — верно для очереди, за которой вы
  поспеваете, и неверно для той, что наполняется быстрее, чем вы её разбираете: такой вызов не
  вернётся никогда.

```java
// A cron-friendly pass: the work is bounded, and the de-duplication key survives a redelivery.
client.poll().drain(notice -> {
    if (alreadySeen(notice.messageId())) {
        return;                     // a normal return still acks - which is what we want
    }
    handle(notice);
    markSeen(notice.messageId());
}, 200);
```

---

## Что несут уведомления

У каждого уведомления есть конверт `<msgQ>` — идентификатор, счётчик, дата, текст, — а у
большинства есть ещё и структурированная полезная нагрузка в `<resData>`. Она читается теми же
аксессорами, что и ответ на соответствующую команду, поэтому один парсер обслуживает оба случая.

| Уведомление | Нагрузка | Чем читать |
|---|---|---|
| Трансфер запрошен / подтверждён / отклонён / отменён | `trnData` | `transfer()`, `transferStatus()`, `objectName()` |
| Домен зарегистрирован, продлён, удалён; итог восстановления | `infData` | `objectName()`, `expiryDate()`, `statuses()`, `rgpStatus()` |
| Регистрация, дошедшая до реестра и ожидающая решения ТАМ | `creData` | `objectName()`, `createdDate()`, `expiryDate()` |
| Итог отложенной (`1001`) операции | `panData` | `pendingActionData()` |
| Низкий баланс | `balance:infData` | `balance()`, `currentBalance()` |

### Запрос трансфера

```java
Map<String, String> t = notice.transfer();
// {status=pending, requestedBy=DELTA, requestedAt=2026-04-01T09:15:00Z,
//  actingClient=EXAMPLE, actBy=2026-04-06T09:15:00Z, expiryDate=2028-04-01T09:15:00Z}
```

`actBy` — крайний срок, и это то поле, которое стоит денег: **молчание завершает трансфер.** После
этой даты реестр его подтверждает. Относитесь к уведомлению о трансфере как к тому, на что нужно
ответить, — см. [Домены → transfer](domains.md#transfer).

### Итог отложенной операции

Так операция, ответившая `1001`, в конце концов отчитывается. Вы отправляете создание, получаете
`1001` и `svTRID`; спустя какое-то время приходит уведомление с вердиктом.

```java
Map<String, Object> pan = notice.pendingActionData();
// {object=example.com.ua, success=true,
//  clTRID=SRV-20260401091500-24191-0007, svTRID=SRV-…, date=2026-04-01T10:00:00Z}

if (pan != null) {
    if (!Boolean.TRUE.equals(pan.get("success"))) {
        markFailed((String) pan.get("svTRID"));   // the svTRID of the ORIGINAL command, not this notice
        return;
    }
    markCompleted((String) pan.get("svTRID"), (String) pan.get("object"), (String) pan.get("date"));
}
```

Три вещи про него:

- **`success` — единственное поле, которое говорит, сработала ли операция.** Обрамляющий
  `<result code="1301">` означает «вот сообщение», а не «ваша операция удалась». Читать вместо него
  код ответа — классическая ошибка: тогда любой ответ poll выглядит успехом. Отсутствующий вердикт
  считается неудачей — отсутствующее «да» не есть «да».
- **`svTRID` говорит, о какой из ваших отложенных операций идёт речь.** Сопоставьте его с тем,
  который вам выдали вместе с `1001`. Не считайте, что это самая свежая операция: poll — это
  очередь, и уведомления приходят в том порядке, в каком реестр заканчивал работу.
- **`date` — это когда действие завершилось**, а не когда вы опросили очередь.

### Уведомление о низком балансе

```java
if (notice.balance() != null) {
    alertBilling(notice.currentBalance());   // an exact decimal string - never a double
}
```

Это тот же элемент, что и в [запросе баланса](balance.md), поэтому читается он теми же аксессорами.
Реагируйте на него: как только средства закончились, платные команды отклоняются с `2104`, и
регистрация, которую вы собирались выполнить, сорвётся из-за нехватки средств, а не из-за ошибки в
запросе.

### Изменение, которое реестр сделал с вашим объектом (RFC 8590)

Некоторые уведомления описывают то, что произошло с вашим объектом без вашей команды: он перестал
существовать в реестре или ушёл трансфером. Именно на них нужно реагировать автоматически —
прекратить тарификацию, сообщить клиенту, убрать из своего учёта, — а предложение в `<msg>`
написано на языке уведомлений вашей учётной записи, поэтому разбирать его ненадёжно.

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

**`state` говорит, как читать объект рядом.** `after` описывает объект таким, каков он сейчас.
`before` — таким, каким он был в последний раз; домен, которого уже нет, иначе и не описать.
Записать блок `before` в своё хранилище как *текущее* состояние — это ровно то, как удалённый домен
оживает в ваших записях, поэтому ветвитесь по этому полю до сохранения.

`change()` не возвращает ничего, когда уведомление не несёт блока изменения, — а это каждое
уведомление, если вы не объявили `urn:ietf:params:xml:ns:changePoll-1.0` при входе. Эта библиотека
зеркалит greeting сервера в `<svcs>`, поэтому реестр, который его предлагает, объявляется за вас.

В отличие от правила о переносе, отсутствие объявлений **не** даёт вам `changeData`: реестр
отправляет его только клиенту, который назвал пространство имён, потому что клиент, никогда его не
видевший, может отвергнуть весь кадр. Предложение `<msg>` не меняется в обоих случаях, поэтому
объявление никогда не забирает то, что вы уже читаете.

### Нагрузка от расширения, которое вы не объявили (RFC 9038)

Уведомление попадает в вашу очередь раньше, чем реестр узнаёт, какая сессия его заберёт, поэтому
оно может нести элемент из пространства имён расширения. Если ваш вход перечислил URI в
`<svcExtension>` и этого пространства имён среди них не было, реестр не выбрасывает элемент, а
переносит его из `<resData>` в `<extValue>` внутри `<result>`.

Кадр по-прежнему разбирается, и **подтвердить его вы по-прежнему можете**, поэтому очередь
продолжает опустошаться. Данные никуда не делись:

```java
for (Map<String, Object> ext : notice.extValues()) {
    ext.get("element");     // e.g. "infData" - which element the registry moved
    ext.get("namespace");   // the namespace to announce to receive it as resData
    ext.get("values");      // its children, by local name
    ext.get("reason");      // the registry's explanation
}
```

Объявляйте те расширения, которые вы разбираете, если хотите типизированную форму; вход, который не
отправляет `<svcExtension>` вовсе, читается как «без ограничений» и получает все нагрузки как
`<resData>`. Как выбираются сервисы при входе, см. в [Сессия](session.md).

---

## Готовый поллер

```java
import com.epptools.sdk.Client;
import com.epptools.sdk.Config;
import com.epptools.sdk.Response;
import com.epptools.sdk.exception.EppException;

import java.util.Map;

public final class Poller {

    public static void main(String[] args) {
        Config config = Config.builder("epp.registry.example", "EXAMPLE", "your-secret")
                .caFile("/path/to/registry-ca.pem")
                .build();

        try (Client client = new Client(config)) {
            client.connect();
            client.login();

            int count = client.poll().drain(notice -> {
                String id = notice.messageId();

                // Идемпотентность по messageId: повторная доставка после потерянного ack не должна
                // обрабатываться дважды.
                if (alreadySeen(id)) {
                    return;
                }

                Map<String, String> transfer = notice.transfer();
                Map<String, Object> pending = notice.pendingActionData();

                if (transfer != null && "pending".equals(transfer.get("status"))) {
                    // Ответьте до actBy, иначе решит реестр.
                    String name = notice.objectName();
                    client.domain().transfer(customerAuthorisedTheMove(name) ? "approve" : "reject", name);
                } else if (pending != null) {
                    recordOutcome((String) pending.get("svTRID"),
                            Boolean.TRUE.equals(pending.get("success")),
                            (String) pending.get("object"));
                } else if (notice.balance() != null) {
                    alertBilling(notice.currentBalance());
                } else {
                    store(id, notice.queueMessage(), notice.queueDate(), notice.raw());
                }

                markSeen(id);
                // Нормальный возврат и есть подтверждение. Брошенное исключение оставляет
                // уведомление в очереди.
            }, 200);

            System.out.println("drained " + count);
            client.logout();
        } catch (EppException e) {
            System.err.println("EPP error: " + e.getMessage());
        }
    }

    // Ваше собственное хранение, каким бы оно ни было. Эти шесть методов — всё, что цикл ждёт от вас.
    private static boolean alreadySeen(String messageId) { return false; }
    private static void markSeen(String messageId) { }
    private static void store(Object... parts) { }
    private static void recordOutcome(String svTRID, boolean success, String object) { }
    private static void alertBilling(String balance) { }
    private static boolean customerAuthorisedTheMove(String name) { return false; }
}
```

Хранить `raw()` рядом с разобранными полями дёшево и того стоит: уведомление — единственная копия
события, а после подтверждения у реестра не остаётся ни одной.

---

## Коды ответа на этой странице

| Код | Значение | Исключение |
|---|---|---|
| `1301` | сообщение ждёт (`req`) или сообщения ещё остаются (`ack`) | — |
| `1300` | очередь пуста | — |
| `2303` | `ack` для идентификатора сообщения, которого нет или который уже подтверждён | `ObjectDoesNotExistException` |
| `2400` | реестр не смог выполнить операцию; может быть временным | `CommandException` (`isRetryable()`) |
| `2500`–`2502` | сессия завершена; переподключитесь и войдите снова | `SessionException` |

`1300` и `1301` — оба коды успеха, поэтому `isSuccess()` истинно для любого из них; именно поэтому
«пусто» решается кодом `1300` и никогда — отсутствием полезной нагрузки. В `ResultCode` они
называются `SUCCESS_NO_MESSAGES` и `SUCCESS_ACK_TO_DEQUEUE`; полная таксономия — в
[Ошибки](errors.md).

---

См. также: [Домены](domains.md) · [Контакты](contacts.md) · [Баланс и цены](balance.md) ·
[Ответы](responses.md) · [Ошибки](errors.md)

[← Оглавление руководства](README.md)
