# Backend task — oil advisor (`GET /oil/engines`, `GET /oil/recommend`)

The Android side is **done and merged** (`OilRecommendationActivity`, entry point on the Поддръжка
tab). It is read-only: two idempotent GETs, no writes, no new auth concepts. Until these routes
exist the screen shows „Списъкът с двигатели не се зареди“ and „Препоръката не бе заредена“.

## Why the engine code is the key

Make/model/year does **not** determine the oil — one model year ships several engines with different
requirements (a Golf VI is a 1.4 TSI *and* a 2.0 TDI, and they need different approvals). The
requirement follows the engine, so the lookup key is the manufacturer engine code (`EA288`, `M57`,
`ARL`) plus fuel type as a tiebreaker. This is also why no third-party API was usable for this
feature: the free ones key on VIN or make/model and none of them resolve to an engine code for the
European used-car park. A hand-curated table is the right call.

**The approvals are the product, not the brand.** `viscosity` is what the user reads off the bottle,
but `specs` (VW 507 00, MB 229.51, BMW LL-04, ACEA C3 …) is what actually binds — a 5W-30 without the
right approval still ruins a DPF. The app renders them as a separate labelled block for that reason,
and `products` is explicitly the least load-bearing field in the payload.

## Schema

```sql
CREATE TABLE oil_engines (
    code            TEXT PRIMARY KEY,          -- 'EA288', 'M57', 'ARL' — uppercase, no spaces
    display_name    TEXT NOT NULL,             -- '2.0 TDI CR (EA288)'
    fuel_type       TEXT NOT NULL,             -- 'diesel' | 'petrol'
    makes           TEXT NOT NULL,             -- 'VW,Audi,Skoda,Seat' — used to sort the dropdown
    viscosity       TEXT NOT NULL,             -- '5W-30'
    alt_viscosity   TEXT,                      -- second accepted grade, NULL when there isn't one
    specs           TEXT NOT NULL,             -- 'VW 507 00,ACEA C3' — comma-separated
    capacity_liters NUMERIC(4,1),              -- with filter; NULL = not recorded
    interval_km     INTEGER,                   -- NULL/0 = not recorded
    interval_months INTEGER,
    note            TEXT                       -- caveat shown in an amber box; NULL for none
);

CREATE TABLE oil_products (
    id          SERIAL PRIMARY KEY,
    engine_code TEXT NOT NULL REFERENCES oil_engines(code) ON DELETE CASCADE,
    name        TEXT NOT NULL,                 -- 'Castrol Edge Professional LongLife III'
    viscosity   TEXT,                          -- '5W-30' — usually the same as the engine's
    specs       TEXT,                          -- what this product is approved to
    sort_order  INTEGER NOT NULL DEFAULT 0
);
```

`NULL` matters here in the same way `maintenance_records.cost` does: the client renders a missing
capacity/interval as **nothing at all**, never as 0. Do not backfill guesses.

## `GET /oil/engines`

The dropdown catalog. No parameters. Requires the Bearer token like every other route except login
and register (the app sends it automatically).

```json
{
  "engines": [
    {
      "code": "EA288",
      "displayName": "2.0 TDI CR (EA288)",
      "fuelType": "diesel",
      "makes": ["VW", "Audi", "Skoda", "Seat"]
    },
    { "code": "M57", "displayName": "3.0d (M57)", "fuelType": "diesel", "makes": ["BMW"] }
  ]
}
```

- `makes` is an **array** in JSON even though the column is comma-separated — split it server-side.
- An empty `{"engines":[]}` is a valid response (unseeded table) and the client shows the
  „въведете кода ръчно“ hint. **Unlike `/maintenance/types` there is no hardcoded client fallback** —
  an oil approval the app invented would be indistinguishable from one the catalog vouches for.
- `displayName` should contain the code or the client appends it; either is fine.

## `GET /oil/recommend`

| Param | Required | Notes |
|---|---|---|
| `engineCode` | yes | Match **case-insensitively** and ignore surrounding whitespace. The field is typeable, so expect `ea288`, `EA 288`, `ea288 ` |
| `fuelType` | no | `diesel` / `petrol` / `lpg`. A **tiebreaker, not a filter** — see below |
| `mileage` | no | Vehicle odometer, km. Only sent when non-zero. Use it to pick `alt_viscosity` advice if you want; ignoring it is fine |

```
GET /oil/recommend?engineCode=EA288&fuelType=diesel&mileage=210000
```

```json
{
  "engineCode": "EA288",
  "engineName": "2.0 TDI CR (EA288)",
  "viscosity": "5W-30",
  "altViscosity": "",
  "specs": ["VW 507 00", "ACEA C3"],
  "capacityLiters": 4.3,
  "intervalKm": 15000,
  "intervalMonths": 12,
  "note": "С DPF: задължително масло с одобрение VW 507 00 (low-SAPS). Не използвайте 505 01.",
  "products": [
    { "name": "Castrol Edge Professional LongLife III", "viscosity": "5W-30", "specs": "VW 504 00 / 507 00" },
    { "name": "Motul Specific 504 00 507 00", "viscosity": "5W-30", "specs": "VW 504 00 / 507 00" }
  ]
}
```

Rules the client depends on:

- **`fuelType` must not turn a hit into a miss.** The app prefills it from the vehicle's own
  `fuelType` column, which the user typed and may have wrong. Match on `engineCode` first; only use
  `fuelType` to disambiguate if a code ever maps to more than one row.
- **`fuelType=lpg` must fall back to the petrol row** for that code and *append* an LPG caveat to
  `note` (an LPG conversion is a petrol engine; it runs hotter and drier, so the interval is usually
  shortened). LPG conversions are a large share of the Bulgarian petrol park — this is not an edge
  case. Suggested wording: „На газова инсталация маслото се износва по-бързо — сменяйте на около
  7 000 – 8 000 км.“
- **Unknown code → `404`.** The client renders „За този двигател още няма данни“ and does **not**
  retry. Do not answer with an empty 200 — the app treats an unusable 200 the same way, but a 404 is
  the honest answer and keeps the two cases apart in your logs.
- `400` on a missing/blank `engineCode`.
- `specs` may be empty **only if** `viscosity` is set — the client needs at least one of the two and
  refuses to render a card with neither (`OilRecommendation.isUsable()`).
- `note` is displayed verbatim in an amber box, in **Bulgarian**. It's the right place for
  „PD инжектори: само 505 01“, „известно износване на ангренажната верига“, and the LPG caveat.
- Response must be small — it is one row plus a handful of products, so none of the truncation
  drama of the image routes applies.

## Seed: 20 engines for the Bulgarian park

My read of what actually drives here: overwhelmingly 10–25-year-old imports, VW group first, then
BMW / Opel / Mercedes / Renault-Dacia / PSA-Ford, with a large LPG-converted petrol tail. So this
list is **14 diesel + 6 petrol**, weighted that way on purpose.

**Verify the approvals against the manufacturer's own documentation before you ship them.** They are
right to the best of my knowledge and they are the kind of data where being confidently wrong costs
someone an engine — that is also why the screen carries a footnote pointing the user at their own
owner's manual.

| # | Code | Engine | Fuel | Viscosity | Approvals | Litres | Interval | Makes |
|---|---|---|---|---|---|---|---|---|
| 1 | `ARL` | 1.9 TDI PD 150 hp | diesel | 5W-40 | VW 505 01 | 4.5 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 2 | `AXR` | 1.9 TDI PD 101 hp | diesel | 5W-40 | VW 505 01 | 4.3 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 3 | `BXE` | 1.9 TDI PD 105 hp | diesel | 5W-40 | VW 505 01 | 4.3 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 4 | `BKD` | 2.0 TDI PD 140 hp | diesel | 5W-30 | VW 507 00 (с DPF), ACEA C3 | 4.3 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 5 | `CFFB` | 2.0 TDI CR 140 hp (EA189) | diesel | 5W-30 | VW 507 00, ACEA C3 | 4.3 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 6 | `CRLB` | 2.0 TDI CR 150 hp (EA288) | diesel | 5W-30 | VW 507 00, ACEA C3 | 4.3 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 7 | `M47` | BMW 2.0d (E46/E87/E90) | diesel | 5W-30 | BMW LL-04 (с DPF), BMW LL-01 (без) | 4.5 | 15 000 / 12 | BMW |
| 8 | `N47` | BMW 2.0d | diesel | 5W-30 | BMW LL-04, ACEA C3 | 5.2 | 15 000 / 12 | BMW |
| 9 | `M57` | BMW 3.0d | diesel | 5W-30 | BMW LL-04 | 6.5 | 15 000 / 12 | BMW |
| 10 | `OM646` | MB 2.2 CDI | diesel | 5W-30 | MB 229.51, ACEA C3 | 7.5 | 15 000 / 12 | Mercedes-Benz |
| 11 | `OM651` | MB 2.1 CDI | diesel | 5W-30 | MB 229.52, MB 229.51 | 6.5 | 15 000 / 12 | Mercedes-Benz |
| 12 | `Z19DTH` | Opel 1.9 CDTI 150 hp | diesel | 5W-40 | GM dexos2, ACEA C3 | 4.6 | 15 000 / 12 | Opel, Vauxhall, Fiat, Saab |
| 13 | `K9K` | 1.5 dCi | diesel | 5W-30 | RN0720 (с DPF), ACEA C4 | 4.4 | 15 000 / 12 | Renault, Dacia, Nissan |
| 14 | `DV6` | 1.6 HDi / 1.6 TDCi | diesel | 5W-30 | PSA B71 2290, ACEA C2 | 3.8 | 15 000 / 12 | Peugeot, Citroën, Ford, Volvo, Mini |
| 15 | `AGU` | 1.8T 20V 150 hp | petrol | 5W-40 | VW 502 00 | 4.6 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 16 | `BSE` | 1.6 MPI 102 hp | petrol | 5W-40 | VW 502 00 | 4.5 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 17 | `CAXA` | 1.4 TSI 122 hp (EA111) | petrol | 5W-30 | VW 504 00 | 3.6 | 15 000 / 12 | VW, Audi, Seat, Skoda |
| 18 | `Z16XER` | Opel 1.6 16V 115 hp | petrol | 5W-30 | GM dexos2, ACEA A3/B4 | 4.5 | 15 000 / 12 | Opel, Vauxhall, Chevrolet |
| 19 | `K4M` | Renault 1.6 16V | petrol | 5W-40 | RN0700, ACEA A3/B4 | 4.8 | 15 000 / 12 | Renault, Dacia, Nissan |
| 20 | `1ZZ-FE` | Toyota 1.8 VVT-i | petrol | 5W-30 | API SN, ILSAC GF-5 | 3.7 | 10 000 / 12 | Toyota |

Notes worth putting in the `note` column:

- **1, 2, 3, 4 (PD engines)** — „Само масло с одобрение VW 505 01: помпа-дюзите износват гърбичния
  вал с неподходящо масло.“ For `BKD`, add that a DPF version needs **507 00** instead and that the
  two are not interchangeable.
- **8 (`N47`)** — „Известно износване на ангренажната верига. Спазвайте интервала и не удължавайте
  смяната на маслото.“
- **13 (`K9K`), 14 (`DV6`)** — low-SAPS is mandatory on the DPF versions; `DV6` is also known for
  turbo oil-feed screen clogging, so „не удължавайте интервала“ belongs there.
- **15–20 (petrol)** — the LPG caveat, since this is where conversions live.

### The next ones to add

Also very common here and the obvious second batch: `Z17DTH` (Opel 1.7 CDTI), `DW10` (2.0 HDi /
TDCi), `M54` and `N46` (BMW petrol), `OM611`/`OM612` (older MB CDI), `1ND-TV` and `2AD` (Toyota
D-4D), `CJZA`/`CZCA` (EA211 1.4 TSI), `BAR`/`CDNC` (2.0 TFSI), `199A` (Fiat/Opel 1.3 MultiJet),
`KKDA` (Ford 1.8 TDCi).

## Not in scope (deliberately)

- **No VIN-based lookup.** VIN → engine code needs a licensed database; nothing free and legal
  resolves it for the European park. The user picks the code.
- **No `engineCode` column on `vehicles`** — yet. The app currently remembers the chosen engine per
  vehicle in its own `"oil"` SharedPreferences file. Adding the column is the proper fix (it would
  then ride along on `GET /vehicles` / `POST /vehicles/update` like the document dates, and
  `Vehicle.toUpdateJson()` would carry it), at which point the client-side cache can be deleted.
  Nothing else has to change on the server for the screen to work today.
- **No prices or shop links.** `products` is illustrative; turning it into a storefront is a
  different feature with different obligations.
