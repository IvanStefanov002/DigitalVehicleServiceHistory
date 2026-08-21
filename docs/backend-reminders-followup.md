# Backend follow-up: document reminders

Companion to `backend-vignette-task.md` (already implemented). The Android side now surfaces document
validity on the Автопарк list, in the fleet banner, and as a daily notification.

## Nothing here is required

The reminder integration works against the API exactly as it stands. There is **no new endpoint and
no schema change.** Everything below is either a load characteristic to sanity-check or an optional
optimisation.

---

## 1. Two things to verify, not build

**a) `GET /vehicles/vignette?id=` must check the cache before any upstream call.**
The client now fans out **one request per vehicle** in two places:

- `MainActivity.loadDocumentStatuses()` — on every vehicle-list load and every pull-to-refresh.
- `ServiceReminderWorker` — once daily per user, synchronously.

For a 4-car fleet that's 4 calls per app open. All of them should be cache hits. If any of them
reaches `check.bgtoll.bg`, an app open turns into N calls against a third party's undocumented
endpoint, which is the thing the cache exists to prevent.

**b) Guard against a same-plate stampede.**
`loadDocumentStatuses()` fires all N requests **in parallel**, and each retries up to 3× on failure.
So concurrent requests for the *same* plate are possible — two vehicles sharing a plate (a sold car
re-added, a company fleet), or a user pulling to refresh twice. Take a per-plate lock (or a
single-flight map) so one cold cache entry produces **one** upstream call, not three.

Worth a quick check that a 502 path is cheap, too: when upstream is down the client will retry 3× per
vehicle, so that path should fail fast from cache state rather than re-attempting the upstream call
each time.

---

## 2. Optional optimisation: put the vignette in `GET /vehicles`

Right now an Автопарк load costs **1 + 2N** requests: one for the list, then per vehicle one
`/vehicles/maintenance` and one `/vehicles/vignette`. Folding a vignette summary into the list
response takes it to **1 + N**.

Add to each vehicle object:

```json
"vignette": { "status": "VALID" | "NONE" | "UNKNOWN", "validTo": "2027-01-14" }
```

**The one hard constraint: this must be served cache-only.** `GET /vehicles` must never trigger an
upstream call — otherwise loading the list fans out N calls to `check.bgtoll.bg` on a cold cache, and
the list gets as slow as the slowest third-party request. Emit `"status": "UNKNOWN"` when there is no
cache row (fresh or stale), and let the client fall back to its per-vehicle call for detail.

Which means, to make this actually worthwhile, it wants a companion:

**A server-side daily refresh job** that walks distinct (plate, country) pairs across all vehicles and
refreshes any row older than ~24 h, spaced out and rate-limited. With that in place the cache is
essentially always warm, `GET /vehicles` alone answers the whole Автопарк tab, and the client's
per-vehicle fan-out disappears except on the compliance screen itself. It also moves the upstream load
off user actions entirely, so request volume becomes a flat, predictable "once per car per day"
regardless of how often people open the app.

If you do add the field: the client ignores unknown JSON keys, so shipping it early is safe — I'd wire
the client to prefer it and skip the fan-out in a follow-up.

---

## 3. Not needed, to be explicit

- No endpoint for ГТП or ГО. Still captcha-gated, still no API — see `backend-vignette-task.md`.
- No notification/push infrastructure. Reminders are computed on-device by WorkManager from the same
  endpoints the UI reads, so a badge and a notification cannot disagree. Nothing server-side pushes.
- No new columns. The three declared dates added in the previous task are all the reminder logic uses.
