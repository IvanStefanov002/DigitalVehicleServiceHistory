# Backend task: vignette proxy + document-validity columns

Paste the whole of this into Claude Code in the **C++ backend repo**. It is written to be
self-contained — it does not assume the Android-side conversation.

---

## Context

This is the hand-rolled C++ API server behind an Android app (vehicle service-history tracker).
Auth is an opaque Bearer token stored in the DB; every route derives the calling user from it and
scopes queries by `user_id`. Vehicle routes live in `VehiclesCmd` and are dispatched from
`RequestServer`. Follow the existing style in those files — don't introduce a framework.

The Android client already ships the feature that consumes this work, and degrades gracefully until
you land it. Two pieces are needed.

---

## Part 1 — Four new `vehicles` columns

The app now tracks three time-limited documents per vehicle. Two of them (periodic technical
inspection «ГТП» and third-party liability insurance «Гражданска отговорност») **cannot be verified
programmatically** — both authorities' public check pages are behind a server-enforced CAPTCHA — so
they are dates the user types in, and they live as plain vehicle columns.

Add to `vehicles`:

| Column | Type | Notes |
|---|---|---|
| `country` | `text NOT NULL DEFAULT 'BG'` | ISO alpha-2 country of registration. Needed for the vignette check: a plate is only unique within its register. |
| `inspection_valid_to` | `date NULL` | ГТП expiry. |
| `insurance_valid_to` | `date NULL` | ГО policy expiry. |
| `insurance_next_installment` | `date NULL` | Next instalment due date. Separate from the expiry on purpose: most Bulgarian ГО policies are paid in 2–4 instalments and a missed instalment terminates cover mid-term, so "expires in March" is not the only date that matters. |

All three dates are **nullable with no default** — `NULL` means "the user hasn't told us", which is
a different answer from any particular date, and the client renders it as "no data" rather than
as an expiry.

Then:

- **`GET /vehicles`** — add all four to the `SELECT` and the JSON, as
  `country`, `inspectionValidTo`, `insuranceValidTo`, `insuranceNextInstallment`.
  Emit `""` (or omit) for `NULL`. **If you skip this the compliance screen opens blank on every
  fresh app launch**, because that list is the client's only source for these fields.
- **`POST /vehicles/update`** — accept the same four keys in the body and write them.
  Treat `""` as `NULL`. Reject a value that is neither empty nor a valid `YYYY-MM-DD` with 400.
  The client always sends the full vehicle through this route (it rewrites every column), so
  the four keys will always be present once the app is updated — but tolerate their absence so an
  older client build doesn't fail.

---

## Part 2 — `GET /vehicles/vignette?id=<vehicleId>`

A **cached server-side proxy** of the National Toll Administration's public e-vignette check.

### Why it is server-side and not in the app

- The upstream endpoint is **undocumented and unversioned**. When it changes or starts blocking us,
  a proxy is a deploy; a client-side call is an app update every user has to install.
- The cache is what keeps our request volume against a third party's infrastructure courteous.
- The app's background reminder worker will read the same route, so a notification and an in-app
  badge can never disagree.

### ⚠️ This route needs a capability the server may not have yet

The server currently only *serves* HTTP; this is the first route that makes an **outbound HTTPS**
call. If there is no HTTP client linked in yet, add one (libcurl with TLS is the least invasive) and
give it a short timeout — **3 s connect, 5 s total** — plus a sane `User-Agent`. Never let an
upstream stall hold our own request thread open indefinitely.

### Upstream contract

```
GET https://check.bgtoll.bg/check/vignette/plate/<ISO2_COUNTRY>/<PLATE>
```

Unauthenticated, no captcha, no cookie, ~330 ms. Verified working. Companion endpoint
`GET https://check.bgtoll.bg/countries` returns the valid country codes if you want to validate.

**Three upstream quirks you must handle:**

1. **HTTP status is always 200.** The real answer is in the body.
2. **"No valid vignette" arrives as `status.code: 500`:**
   ```json
   {"vignette":null,"ok":false,"status":{"code":500,"message":"public.ui.ok.noVignette"}}
   ```
   That **500 is the not-found answer, not a server error.** Do not treat it as a failure, do not
   retry it, and do not log it as an incident.
3. A malformed plate is `status.code: 400`, `message: "public.ui.error.licensePlateNumber"`.

On a hit, `vignette` carries `vignetteNumber`, `vehicleClass`, `emissionsClass`,
`validityDateFromFormated`, `validityDateToFormated`, `price`, `status` (note the upstream's
`Formated` spelling). **Verify these field names against one real plate that has a live vignette
before shipping** — they were read out of the site's JS bundle, not from documentation.

### Plate normalisation — do this before every upstream call

Bulgarian plates are printed in Cyrillic glyphs that merely *look* Latin. Transliterate, uppercase,
strip spaces and dashes:

```
А→A  В→B  Е→E  К→K  М→M  Н→H  О→O  Р→P  С→C  Т→T  У→Y  Х→X
```

(The Guarantee Fund's own check page does exactly this in a `cyrlat()` JS function, so it's the
expected input form.)

### Our response

```json
{
  "status": "VALID",
  "validFrom": "2026-01-15",
  "validTo": "2027-01-14",
  "vignetteNumber": "1234567890",
  "vehicleClass": "3",
  "emissionsClass": "6",
  "price": 87.00,
  "checkedAt": "2026-08-18T07:43:39Z",
  "cached": true
}
```

- `status` is `"VALID"` or `"NONE"`. Optional fields may be omitted or `null`.
- `validFrom` / `validTo` reformatted to **`YYYY-MM-DD`** — the format the client parses everywhere.
- `checkedAt` is when *we* actually asked upstream (not when we answered), ISO-8601 UTC.
- `cached` tells the client whether this came from our cache.
- `price`: omit or send `null` when upstream didn't report one. **Never 0 as a stand-in** — the
  client treats a negative/absent price as "not recorded" and 0 as a genuine zero.

### Error semantics — the important part

**Three outcomes must stay distinguishable, not two:**

| Situation | Response |
|---|---|
| Upstream says there's a live vignette | `200` + `status: "VALID"` |
| Upstream says there is none | `200` + `status: "NONE"` |
| Upstream unreachable/garbled **and** no cached row | **non-2xx (502)** |

That last row matters most. **Never return `NONE` when you simply couldn't reach the authority.**
The client renders a non-2xx as "проверката е недостъпна" and a `NONE` as "няма валидна винетка" —
collapsing them would announce a lapsed vignette to the user every time the network hiccuped. This
is the same rule as the existing `GET /vehicles` "failure is not an empty list" behaviour.

Also:

- `400` on a missing or unparseable `id`.
- `460` if the vehicle isn't the caller's (pre-check ownership separately, the way `addMaintenance`
  does, so "not yours" stays distinct from "no vignette").
- If the vehicle has no plate stored, `400` — don't call upstream with an empty plate.

### Caching

New table:

```sql
CREATE TABLE vignette_checks (
  plate        text NOT NULL,
  country      text NOT NULL,
  status       text NOT NULL,          -- 'VALID' | 'NONE'
  valid_from   date NULL,
  valid_to     date NULL,
  number       text NULL,
  vehicle_class text NULL,
  emissions_class text NULL,
  price        numeric NULL,
  checked_at   timestamptz NOT NULL,
  PRIMARY KEY (plate, country)
);
```

- Keyed on **plate + country, not vehicle id** — two users with the same car (a sold vehicle, a
  company fleet) should share one upstream call.
- Fresh for **24 hours**. Serve from cache within that window without touching upstream.
- On an upstream failure with a **stale** row present: **serve the stale row** with its real
  `checkedAt` and `cached: true`. A day-old answer with an honest timestamp beats "unavailable" —
  and the client shows `checkedAt` to the user precisely so they can judge that themselves.
- No row and upstream failed → `502`.

### Rate limiting

Cap outbound calls to `check.bgtoll.bg` globally (e.g. a few per second) and never fan out one
client request into several. Nothing in the app needs a burst — the cache absorbs normal use.

---

## Tests to write

1. Upstream `noVignette` body (`code: 500`) → our `200` + `status: "NONE"`. **Not a 502.**
2. Upstream connection failure, no cached row → `502`. **Not `NONE`.**
3. Upstream connection failure, cached row 3 days old → `200`, the cached data, `cached: true`,
   original `checkedAt`.
4. Second request within 24 h makes **no** upstream call.
5. A plate stored in Cyrillic (`СВ1234АВ`) reaches upstream as `CB1234AB`.
6. Another user's vehicle id → `460`.
7. `POST /vehicles/update` with `"inspectionValidTo": ""` clears the column to `NULL`;
   with `"2027-03-01"` sets it; with `"garbage"` → `400`.
8. `GET /vehicles` includes all four new keys.

## Do NOT build

- Any check for ГТП or ГО. Both authorities' pages are captcha-gated (verified: `АА`'s
  `checkinsp.php` returns `{"validation":{...,"captchaValid":false,...}}` for a POST without one,
  and the Guarantee Fund's form re-renders with «Грешен код»). Neither offers an API. Do not add a
  captcha-solving path or a headless browser — those are anti-automation controls, and the app is
  designed around user-declared dates for those two instead.
