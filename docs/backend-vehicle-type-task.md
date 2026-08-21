# Backend task — `vehicleType` on a vehicle

The Android client now records **what kind of vehicle** each row is, so the Автопарк tab can group
cars, motorcycles, vans and trucks into sections, and so the oil advisor can refuse to answer for
anything that isn't a car. The client side is done and shipped; this is the server half.

**Scope: one new column on `vehicles`, carried by three existing routes. No new endpoint.**

---

## 1. The value

A short lowercase string. Exactly four values are allowed:

| value | meaning |
|---|---|
| `car` | лек автомобил |
| `motorcycle` | мотоциклет |
| `van` | бус |
| `truck` | камион |

`car` is the **default** and the fallback for anything absent, empty or unrecognised. Every vehicle
that existed before this column was added is a car, and the client normalises unknown input to `car`
as well (`util/VehicleType.fromApi`) — so please do the same rather than storing `NULL` or echoing
an unknown value back.

The client sends only these four. It also *accepts* a few synonyms on read (`motorbike`, `bike`,
`scooter`, `minivan`, `bus`, `lorry`, …) purely as insurance for hand-seeded rows; don't rely on
that, and don't emit them.

## 2. Migration

```sql
ALTER TABLE vehicles
    ADD COLUMN vehicle_type TEXT NOT NULL DEFAULT 'car';

-- Optional but recommended: reject anything the client can't render.
ALTER TABLE vehicles
    ADD CONSTRAINT vehicles_vehicle_type_chk
    CHECK (vehicle_type IN ('car', 'motorcycle', 'van', 'truck'));
```

`NOT NULL DEFAULT 'car'` is the whole migration — existing rows become cars, which is correct.

## 3. Route changes

All three already exist. The JSON key is **`vehicleType`** (camelCase, like every other key in this
API); the column is `vehicle_type`.

### `GET /vehicles`

Add `vehicleType` to each element of the `vehicles` array.

```json
{ "id": "12", "make": "Yamaha", "model": "MT-07", "vehicleType": "motorcycle", "year": 2019, ... }
```

**This is the one that matters most.** The client groups the Автопарк list purely from this payload
and makes no per-vehicle call for it, so a missing key means every vehicle renders as a car and the
grouping silently disappears. Emit it always, `"car"` included — never omit it for cars.

### `POST /vehicles/add`

The body now carries `vehicleType`. Store it. A missing or unrecognised value must be stored as
`car`, not rejected — the add form always sends one, but an older client build won't.

```json
{ "make": "Yamaha", "model": "MT-07", "year": 2019, "licensePlate": "CB1234AB",
  "mileage": 12000, "fuelType": "Petrol", "vin": "...", "color": "...",
  "vehicleType": "motorcycle", "imageBase64": "" }
```

### `POST /vehicles/update`

The body now carries `vehicleType` too. **This route rewrites every column**, so it must write this
one as well — otherwise a user who edits the type gets a success toast and no change. As with add:
unknown or missing ⇒ `car`.

Both write routes stay scoped to the token's user exactly as they are today; nothing about ownership
changes.

## 4. What deliberately does *not* change

- **Maintenance types stay common to all vehicle types.** `GET /maintenance/types` must **not** be
  filtered by vehicle type, and `POST /vehicles/maintenance/add` must not start validating the type
  against the vehicle. A motorcycle's oil change and a car's are the same catalog entry as far as
  this app is concerned; the user picks what they had done. Please don't add per-type catalogs.
- **The oil advisor stays car-only, and that rule lives in the client.** `GET /oil/recommend` needs
  no `vehicleType` parameter — the client never offers a non-car to it. (Motorcycle oil is a
  different product: wet-clutch friction requirements, JASO MA/MA2 rather than ACEA/OEM approvals.
  If you ever want motorcycle recommendations, that's a **separate table and a separate route**, not
  a filter on the existing one, and it needs its own spec column set. Not now.)
- **No new endpoint, no type-filtered list route.** Grouping and filtering happen client-side from
  the list the client already has.

## 5. Worth knowing, not asking for yet

Two things become slightly wrong once motorcycles exist, both of them ours to decide later — flagged
so nobody "fixes" them on the server by guessing:

- **The e-vignette does not apply to motorcycles** (BG toll rules exempt category L). **Handled
  client-side already** — the app no longer calls `GET /vehicles/vignette?id=` for a motorcycle at
  all, from any of its three callers, and shows „не се изисква“ instead. So expect vignette traffic
  to drop off for those vehicles; **the endpoint needs no change and must not start inferring
  exemption itself.** (Trucks are still checked: over 3.5 t they're on ТОЛ rather than a vignette,
  but that depends on a weight neither side stores.)
- **ГТП periodicity differs by type** (motorcycles and cars are on different schedules, and trucks
  differ again). Those dates are user-declared columns, so nothing server-side depends on it; only
  the client's „+1 година“ renewal shortcut would eventually need to know.

## 6. Acceptance

1. `GET /vehicles` returns `vehicleType` on every row, `"car"` for pre-existing rows.
2. Adding a motorcycle from the app, then pulling to refresh, shows it under a **МОТОЦИКЛЕТИ**
   header with the car(s) under **АВТОМОБИЛИ**.
3. Editing that vehicle's type to „Бус“ and reopening it shows Бус — i.e. update persists it.
4. Editing an unrelated field (e.g. mileage) does **not** reset the type to `car` — the classic
   rewrite-every-column trap on this route.
5. `GET /maintenance/types` is unchanged, and logging a service against the motorcycle still works
   with the same catalog.
