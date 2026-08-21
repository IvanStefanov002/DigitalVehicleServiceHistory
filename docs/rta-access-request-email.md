# Email to RTA — responsible disclosure + API access request

**To:** avto_a@rta.government.bg
**(Изпълнителна агенция „Автомобилна администрация", София 1000, ул. „ген. Й. В. Гурко" № 5)**
**Subject / Относно:** Уведомление за пропуск в защитата на публичната справка за технически прегледи и запитване за оторизиран достъп

---

## Before you send — fill these in

The draft has placeholders in `«...»`. Replace them and delete this section.

- `«ИМЕ И ФАМИЛИЯ»` — your name.
- `«ФИРМА / ФИЗИЧЕСКО ЛИЦЕ»`, `«ЕИК/БУЛСТАТ»` — the legal entity behind the app, or "физическо лице" if none yet.
- `«ИМЕ НА ПРИЛОЖЕНИЕТО»` — the app's public name (repo is *Digital Vehicle Service History*).
- `«ТЕЛЕФОН»`, `«ИМЕЙЛ ЗА ОБРАТНА ВРЪЗКА»`.
- **Decide before sending** whether you want to leave your app out of the disclosure entirely and send it as a plain security report from a citizen (see the note at the very bottom). The two asks are separable and there's a real argument for splitting them.

Also: the reCAPTCHA finding is a security matter about *their* system. It's accurate as written, but once you send it you've reported a live weakness in a government service — that's the right thing to do, and it's also why the tone stays factual and offers to help rather than boasting.

---

## Bulgarian version (send this one)

Уважаеми дами и господа,

Пиша Ви по два свързани повода относно публичната услуга за проверка на технически прегледи на адрес `https://public-eis.rta.government.bg/public-vehicle-check/vin-check`.

**1. Уведомление за пропуск в защитата (добросъвестно разкриване)**

При разработката на приложение, което напомня на водачите за изтичащи винетки, застраховки и технически прегледи, установих, че сървърната проверка на кода за сигурност (Google reCAPTCHA) на горепосочената услуга не се извършва пълноценно.

Заявката към крайната точка

`GET https://public-eis.rta.government.bg/public-vehicle-check/api/vehicle-check/history`

изисква параметър `captchaResponse`, но **стойността му не се проверява срещу услугата на Google (`siteverify`)**. Наблюдаваното поведение:

- Заявка без параметъра `captchaResponse` връща `HTTP 400` със съобщение „Parameter is required";
- Заявка с произволна, невалидна стойност на `captchaResponse` връща `HTTP 200` и обработва справката нормално.

Практически това означава, че защитата reCAPTCHA може да бъде заобиколена с произволен низ, тъй като сървърът проверява само дали параметърът присъства, но не и дали е валиден. Препоръчвам сървърът да валидира получения токен чрез `https://www.google.com/recaptcha/api/siteverify` със съответния secret key, преди да обработва заявката.

За коректност уточнявам, че тествах поведението единствено със служебен, несъществуващ идентификатор и не съм извличал данни за реални превозни средства. Целта на съобщението е единствено да Ви уведомя, за да отстраните пропуска.

**2. Запитване за оторизиран достъп до услугата**

Разработвам приложение — „«ИМЕ НА ПРИЛОЖЕНИЕТО»" — което помага на водачите да следят валидността на документите на автомобилите си (винетка, технически преглед, застраховка „Гражданска отговорност"). За проверката на винетки вече използваме публичната услуга на Националното ТОЛ управление.

Бих искал да отправя запитване дали е възможно да ни предоставите **оторизиран програмен достъп** до справката за технически прегледи по номер на рама (VIN) — например чрез API ключ или разрешен IP адрес — така че приложението да извършва проверките законно, контролирано и без да натоварва публичния интерфейс. Готови сме да се съобразим с всякакви условия за използване, ограничения на честотата на заявките и изисквания за защита на личните данни, които поставите.

Ако такъв достъп изисква сключване на споразумение или заплащане на услуга, моля да ме насочите към съответния ред и отдел.

Оставам на разположение за допълнителна информация и техническо съдействие.

С уважение,
«ИМЕ И ФАМИЛИЯ»
«ФИРМА / ФИЗИЧЕСКО ЛИЦЕ», ЕИК/БУЛСТАT «ЕИК/БУЛСТАT»
тел.: «ТЕЛЕФОН»
имейл: «ИМЕЙЛ ЗА ОБРАТНА ВРЪЗКА»

---

## English version (for your reference — do not send unless asked)

Dear Sir or Madam,

I am writing regarding two related matters concerning the public technical-inspection check service at `https://public-eis.rta.government.bg/public-vehicle-check/vin-check`.

**1. Responsible disclosure of a security gap**

While developing an application that reminds drivers about expiring vignettes, insurance and technical inspections, I found that the server-side verification of the security code (Google reCAPTCHA) on this service is not fully performed.

A request to the endpoint

`GET https://public-eis.rta.government.bg/public-vehicle-check/api/vehicle-check/history`

requires a `captchaResponse` parameter, but **its value is not verified against Google's `siteverify` service**. Observed behaviour:

- A request without `captchaResponse` returns `HTTP 400` ("Parameter is required");
- A request with an arbitrary, invalid `captchaResponse` returns `HTTP 200` and processes the query normally.

In effect the reCAPTCHA protection can be bypassed with any string, because the server only checks that the parameter is present, not that it is valid. I recommend verifying the received token via `https://www.google.com/recaptcha/api/siteverify` with the corresponding secret key before processing the request.

For the record: I tested this only with a placeholder, non-existent identifier and did not retrieve data for any real vehicle. The sole purpose of this message is to notify you so the gap can be closed.

**2. Request for authorised access**

I am building an application — "«APP NAME»" — that helps drivers track the validity of their vehicles' documents (vignette, technical inspection, third-party liability insurance). We already use the National Toll Administration's public service for vignette checks.

I would like to ask whether you could grant us **authorised programmatic access** to the technical-inspection check by VIN — for example via an API key or an allow-listed IP address — so the application can perform these checks lawfully, in a controlled way, and without loading the public interface. We are happy to comply with any terms of use, rate limits and data-protection requirements you set.

If such access requires an agreement or a fee, please point me to the appropriate procedure and department.

I remain available for any further information or technical assistance.

Kind regards,
«NAME»
«COMPANY / INDIVIDUAL», «EIK»
tel.: «PHONE»
email: «REPLY EMAIL»

---

## A judgement call worth making before you send

The two asks pull in slightly different directions and you can split them:

- **Sending both together** is efficient and honest — it's exactly how you found the API — and it frames you as a competent party they'd want to work with.
- **Sending the disclosure alone first**, as a plain citizen security report with no app attached, removes any chance the access request reads as "give me access or the weakness stays public." It's the more conservative order: report the bug, let them fix it, then a week later ask about access on its own merits.

I'd lean toward **both together** — there's no threat implied and it's the truthful account — but if you'd rather keep the security report clean of any self-interest, send section 1 by itself first and I'll prepare a standalone access request for later.

One more: whichever you send, keep a copy of the request and the date. If they grant access, that thread is what makes the ГТП card tier-1; if they go quiet, it's also the evidence that we asked properly before considering anything else.
