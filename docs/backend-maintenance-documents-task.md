# Backend task: document photos on maintenance records

Paste the whole of this into Claude Code in the **C++ backend repo**. It is written to be
self-contained — it does not assume the Android-side conversation.

---

## Context

This is the hand-rolled C++ API server behind an Android app (vehicle service-history tracker).
Auth is an opaque Bearer token stored in the DB; every route derives the calling user from it and
scopes queries by `user_id`. **The client never sends a username** — not as a query param, not in a
body. Vehicle and maintenance routes live in `VehiclesCmd` and are dispatched from `RequestServer`.
Follow the existing style in those files — don't introduce a framework.

### What already exists

- `POST /vehicles/maintenance/add` — body `{"vehicleId","type","mileage","cost"?,"date"?,"notes"?}`.
  Inserts a row in `maintenance_records` with
  `nextChangeMileage = mileage + defaultIntervalKm(type)`. Pre-checks the vehicle belongs to the
  caller.
- `GET /vehicles/maintenance?id=<vehicleId>` — **latest record per service type**
  (`SELECT DISTINCT ON (maintenance_type_id) … ORDER BY last_change_mileage DESC`). Drives the
  detail screen's schedule cards, the fleet status badges, and a background reminder worker.
- `GET /vehicles/maintenance/history?id=<vehicleId>` — **every** row for the vehicle, newest first.
  Read only by the client's PDF service-history export.
- `POST /vehicles/maintenance/delete` — **not implemented yet.** Body `{"id":"<recordId>"}`.
- `POST /vehicles/add` / `GET /vehicles/image?id=` — the existing vehicle-photo transport: base64
  inside JSON, stored in the DB. **Don't copy that pattern here** — see "Transport rules" below for
  why documents deliberately diverge from it.

### The feature

The user wants to attach a **photo of a document** (a receipt, an invoice, a service protocol) to a
maintenance record when logging a service, and to see it again when browsing that vehicle's history.

---

## Transport rules, and why they hold either way

The Android side long treated this server's **intermittent response truncation**
(`"unexpected end of stream"`) as the dominant constraint on anything carrying an image. That is
believed fixed as of 2026-08-19 — **confirm it before relying on it** (there's a one-command check at
the bottom of this file). The recommendations below are written so they don't depend on the answer:
each one stands on its own merits, with the truncation angle called out separately where it applies.

### 1. The request-read path still needs checking, and it is a different code path

Whatever was fixed on the **send** side says nothing about the **read** side. This task uploads the
largest request bodies this server has ever received — everything before it was a small JSON object.

So: verify that the request reader **loops `recv()` until `Content-Length` bytes have been
consumed**, handling `EINTR`/`EAGAIN`. If it assumes one `recv()` returns the whole body, a
multi-megabyte upload will be silently cut at whatever the first read happened to return, and you
will get a corrupt image with a 200 response — intermittently, depending on network timing. That is
a worse failure mode than the response bug ever was, because nothing reports it.

### 2. Serve document bytes raw, not base64 inside JSON

Independent of truncation:

- base64 is **+33% bytes** over the wire, on mobile data;
- the server has to build the entire encoded string in memory before sending, where raw bytes can be
  streamed/`sendfile()`d;
- the client pays **double peak memory** — the base64 string *and* the decoded bitmap;
- a real `Content-Type`/`Content-Length` gives you working `ETag`/`304` and, later, range requests.

The truncation angle, if it turns out not to be fully fixed: a cut JSON body is *unparseable*, so the
client cannot tell a truncated image from a corrupt one from a server error, whereas a short read
against an accurate `Content-Length` is detectable and retryable. Note that `"unexpected end of
stream"` was only ever visible **because** this server does send accurate `Content-Length` headers —
so keep doing that.

### 3. Never put document bytes in a list payload

This one has nothing to do with truncation and does not relax. A vehicle's history is tens of
records; embedding images would multiply one photo by the whole history in a single response. On a
phone that is a latency and memory problem regardless of how correct the server is, and it is wasted
work — the user views one document at a time, on tap. Send a flag, not bytes (Part 2).

---

## Part 1 — Schema

Create a new table rather than adding a column to `maintenance_records`:

```sql
CREATE TABLE maintenance_documents (
    id           BIGSERIAL PRIMARY KEY,
    record_id    BIGINT NOT NULL REFERENCES maintenance_records(id) ON DELETE CASCADE,
    mime_type    TEXT   NOT NULL,          -- 'image/jpeg' | 'image/png' | 'image/webp'
    byte_size    INTEGER NOT NULL,
    sha256       TEXT   NOT NULL,          -- hex, of the stored bytes
    storage_path TEXT   NOT NULL,          -- relative path under the documents root
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ON maintenance_documents (record_id);
```

Why a table and not a column: a record can plausibly carry both a receipt and an inspection
protocol later. The client UI ships with **one document per record**, but a table costs almost
nothing now and saves a migration then. Return only the newest document per record until the client
asks for more.

**Store the bytes on the filesystem, not in the DB.** Recommended over a `BYTEA`/base64 column
because:

- it keeps multi-megabyte blobs out of the JSON/text path entirely;
- it lets you `sendfile()`/stream with a correct `Content-Length` and MIME type, instead of loading
  the whole thing into a string first;
- documents are append-only and numerous, and they would bloat every DB dump.

Layout: `<documents_root>/<user_id>/<record_id>/<uuid>.<ext>`. Keep `documents_root` in whatever
config mechanism the server already uses — **not** hardcoded, and **not** inside the web root.

`ON DELETE CASCADE` handles the DB rows, but **it does not delete files.** See Part 5.

---

## Part 2 — Add a document *flag* to the two existing list routes

`GET /vehicles/maintenance` and `GET /vehicles/maintenance/history` must each start returning, per
item, the id of that record's document (or its absence):

```json
{"id":"…","typeId":"…","name":"…","lastChangeMileage":123456,"nextChangeMileage":138456,
 "lastChangeDate":"2026-03-14","notes":"…","cost":89.9,"documentId":"4711"}
```

- Omit `documentId` (or send `null`) when the record has no document. The client reads either as
  "no document" and shows no affordance.
- Get it with a `LEFT JOIN` / lateral subquery picking the newest `maintenance_documents` row per
  record. One extra column, no extra round trip.

**Do NOT put the image bytes, or a base64 rendition, or a thumbnail in these responses.** A
vehicle's history is tens of records; embedding images would multiply one photo's size by the whole
history in a single response. The flag is all the client needs
to decide whether to show a "view document" button — the bytes come from Part 3, one at a time, only
when the user actually taps.

This is the same rule the existing `GET /vehicles` list follows for vehicle photos, and for the same
reason.

---

## Part 3 — `GET /vehicles/maintenance/document?id=<documentId>`

Serves one document's bytes.

- **Response is the raw file**, with `Content-Type` from the stored `mime_type` and an accurate
  `Content-Length`. Not JSON, not base64.
- Add `Cache-Control: private, max-age=86400` and an `ETag` (the stored `sha256` is already exactly
  this). Honour `If-None-Match` with a `304`. A document photo never changes once uploaded, so this
  is free and stops refetches while the user scrolls the history.
- Add `Content-Disposition: inline`. **Never echo a client-supplied filename** — if you want a
  filename, synthesise one (`document-<id>.<ext>`).
- **Ownership check, and it is three joins deep:** document → record → vehicle → `user_id` = the
  token's user. A document id must be unguessable-in-effect *and* authorised; do not rely on the id
  being hard to guess.
- `404` for unknown/deleted, `460` when it belongs to another user (the codebase's existing
  convention for that), `400` for a missing/unparseable `id`.

---

## Part 4 — Upload

### 4a. `POST /vehicles/maintenance/add` must return the new record's id

Currently the client gets only a success status. Change the 2xx body to include the inserted row's
primary key:

```json
{"id":"<recordId>"}
```

This is required for the upload flow below, and it is a strictly additive change.

### 4b. `POST /vehicles/maintenance/document`

**A separate route, deliberately not a field on `/vehicles/maintenance/add`.** The service record is
the data that matters; the photo is a convenience. A multi-megabyte upload from a phone can fail
mid-request for reasons that have nothing to do with this server — a tunnel, a cell handover, a
backgrounded app. Keeping them separate means such a failure loses only the photo: the record is
already saved and the client retries the image alone. Bundling them lets a large, failure-prone
request destroy a small reliable one, and forces the user to re-enter the whole service record
because a photo didn't make it.

- **Accept `multipart/form-data`**, not base64 in JSON: fields `recordId` and a file part. Same
  reasoning as Part 3's rule 2 — no 33% inflation, and the body can be streamed to disk as it
  arrives instead of buffered whole and decoded. If multipart is genuinely impractical here, the
  fallback is
  `Content-Type: <mime>` with the raw bytes as the whole request body and `recordId` as a query
  param — still not base64.
- Verify the record belongs to the caller **before** reading the body, so an unauthorised upload
  doesn't get to write a file first.
- **Validate the bytes, don't trust the client:**
  - cap the size (**8 MB** is generous — the client downscales to ≤1024px before sending);
  - sniff the real type from **magic bytes** (JPEG `FF D8 FF`, PNG `89 50 4E 47`, WebP
    `RIFF….WEBP`) and reject anything else; ignore any client-declared MIME except to cross-check;
  - store the sniffed type, not the declared one.
- Compute and store `sha256` and `byte_size`. Write the file, then insert the row — and if the
  insert fails, delete the file.
- Respond `201` with `{"documentId":"<id>"}`.
- **Replacing:** if the record already has a document and the client uploads another, keep it simple
  — insert the new one and let the read path return the newest. Do not delete the old one here; Part
  5 owns deletion.

Accept **images only** for now. Store the MIME anyway so PDF invoices can be allowed later without
a migration — but do not accept `application/pdf` yet, because the client has no viewer for it.

---

## Part 5 — Deletion, and the file-orphan problem

1. **`POST /vehicles/maintenance/document/delete`** — body `{"id":"<documentId>"}`. Same three-deep
   ownership check. Delete the row **and unlink the file**. Idempotent: `2xx` when it was already
   gone.
2. **`POST /vehicles/maintenance/delete`** (the route that is still unimplemented) must, when it
   deletes a record, also unlink that record's document files. `ON DELETE CASCADE` removes the rows
   and leaves the files behind forever.
3. **Deleting a vehicle** (`POST /vehicles/delete`, also still unimplemented) must do the same
   transitively for all of that vehicle's records.
4. Because a crash between "row deleted" and "file unlinked" is always possible, add a small
   **sweeper** — on startup or on a timer, delete files under `documents_root` with no matching row.
   Log what it removes. Don't make this clever; a stale file is a disk-space problem, and deleting a
   file whose row still exists is data loss, so the sweep must be driven by the DB, never by
   directory scanning alone.

---

## Part 6 — The public share page must NOT serve these

The server is also getting (or has) a public, unauthenticated share page — `GET /s/<token>` — that
renders one vehicle's history to anyone with the link, for showing a prospective buyer. Its stated
rule is that it **must not expose owner PII**.

**Document photos must be excluded from that page entirely.** A photographed invoice or receipt
routinely carries the owner's full name, address, phone number, signature, and sometimes card
digits. Publishing it behind a shareable link would leak exactly what that rule exists to prevent,
and the owner would have no idea it happened — they attached a receipt to track a service, not to
publish it.

So: no `documentId` in the share page's data, no document `<img>` tags, and
`/vehicles/maintenance/document` stays authenticated. If a "share documents too" option is ever
wanted it needs its own explicit, per-share opt-in — not a default.

---

## Acceptance checklist

- [ ] Send path loops until fully written; read path loops until `Content-Length` consumed.
- [ ] `maintenance_documents` table + index; files under a configurable root outside the web root.
- [ ] `GET /vehicles/maintenance` and `…/history` each return `documentId` per item, and **no**
      image bytes.
- [ ] `GET /vehicles/maintenance/document?id=` returns raw bytes with correct `Content-Type`,
      `Content-Length`, `ETag`, `Cache-Control`; honours `If-None-Match`.
- [ ] `POST /vehicles/maintenance/add` returns `{"id":…}`.
- [ ] `POST /vehicles/maintenance/document` accepts multipart, validates by magic bytes, caps at
      8 MB, stores sha256 + size, cleans up the file if the insert fails.
- [ ] `POST /vehicles/maintenance/document/delete` unlinks the file.
- [ ] Record-delete and vehicle-delete unlink document files.
- [ ] Orphan sweeper exists and is DB-driven.
- [ ] Every one of the new routes is scoped document → record → vehicle → `user_id`; none accepts a
      username from the client.
- [ ] `/s/<token>` exposes no documents.
- [ ] Manual test: upload to a record on vehicle A as user 1, then try to fetch that `documentId` as
      user 2 → `460`, not the image.

---

## Appendix — confirming the response-truncation bug is really gone

The natural regression test is the **largest multi-row response in the app**,
`GET /vehicles/maintenance/history` for a vehicle with many records. With a Bearer token from
`POST /users/login`:

```sh
TOKEN=...   # from POST /users/login
VID=...     # a vehicle id with a long history

for i in $(seq 1 40); do
  curl -s -H "Authorization: Bearer $TOKEN" \
       -o /tmp/h.json -w "%{http_code} %{size_download}\n" \
       "http://92.5.55.85:27778/vehicles/maintenance/history?id=$VID"
  python3 -c "import json;json.load(open('/tmp/h.json'))" || echo "  ^^ TRUNCATED / not valid JSON"
done
```

What you're looking for: **40/40 runs parse as JSON and report the same `size_download`.** The bug
was intermittent, so one green run proves nothing — a varying byte count, or any parse failure, means
it's still there. Run it against the biggest payload you have, not a small one.

Two related server behaviours worth confirming at the same time, since they were separate bugs and
fixing one doesn't fix the others:

- **Accurate `Content-Length` on large responses.** Verified present on small ones (a 460 returns
  `Content-Length: 52`). This is what makes a short read detectable rather than silent — keep it.
- **Keep-alive.** The server currently answers `HTTP/1.0` with `Connection: close` and does not reuse
  a socket. The Android client is built for exactly that (`Connection: close` + a zero-idle
  connection pool in `util/ApiClient`), so this is consistent, not broken — but if you ever start
  offering keep-alive, the client's workaround has to be revisited or every second request will
  break.
