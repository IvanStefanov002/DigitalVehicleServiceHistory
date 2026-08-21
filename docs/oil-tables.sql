-- =============================================================================================
--  Oil advisor tables + seed  (PostgreSQL)
--
--  Backs GET /oil/engines and GET /oil/recommend — see docs/backend-oil-recommendation-task.md
--  for the endpoint contract and CLAUDE.md → "Oil advisor" for the client-side rules.
--
--  Idempotent: safe to re-run. The seed uses ON CONFLICT so re-running updates rather than
--  duplicating, and products are re-inserted from scratch per engine.
--
--  !! VERIFY THE APPROVALS against manufacturer documentation before trusting this in production.
--  The viscosity/spec pairs below are correct to the best of my knowledge, but this is data where
--  being confidently wrong costs someone an engine.
-- =============================================================================================

BEGIN;

-- ---------------------------------------------------------------------------------------------
--  Engines: one row per engine code. This is the lookup key — see the task doc for why it is the
--  engine and not make/model/year.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oil_engines (
    code            TEXT        PRIMARY KEY,
    display_name    TEXT        NOT NULL,
    fuel_type       TEXT        NOT NULL,
    makes           TEXT        NOT NULL DEFAULT '',
    viscosity       TEXT        NOT NULL,
    alt_viscosity   TEXT,
    specs           TEXT        NOT NULL,
    capacity_liters NUMERIC(4,1),
    interval_km     INTEGER,
    interval_months INTEGER,
    note            TEXT,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    -- The API's vocabulary, not the UI's. 'lpg' is deliberately NOT allowed as a stored value:
    -- an LPG conversion is a petrol engine, so /oil/recommend?fuelType=lpg resolves to the petrol
    -- row and appends a caveat instead of there being a separate row for it.
    CONSTRAINT oil_engines_fuel_type_ck CHECK (fuel_type IN ('diesel', 'petrol')),

    -- Uppercase, no surrounding whitespace: the client uppercases what the user types, and the
    -- lookup is case-insensitive, so a lowercase row here would just be a trap.
    CONSTRAINT oil_engines_code_ck CHECK (code = upper(btrim(code)) AND code <> ''),

    -- NULL means "not recorded" and the client hides the row. A 0 would render as a real answer,
    -- so keep zeros out rather than relying on the client to second-guess them.
    CONSTRAINT oil_engines_capacity_ck CHECK (capacity_liters IS NULL OR capacity_liters > 0),
    CONSTRAINT oil_engines_interval_km_ck CHECK (interval_km IS NULL OR interval_km > 0),
    CONSTRAINT oil_engines_interval_months_ck CHECK (interval_months IS NULL OR interval_months > 0),

    -- The client refuses to render a card with neither (OilRecommendation.isUsable()), so don't
    -- let a half-empty row into the table in the first place.
    CONSTRAINT oil_engines_answer_ck CHECK (viscosity <> '' OR specs <> '')
);

-- The lookup index. /oil/recommend must match case-insensitively and trimmed, because the engine
-- field in the app is typeable — expect 'ea288', 'EA 288 ', 'Ea288'.
CREATE UNIQUE INDEX IF NOT EXISTS oil_engines_code_uidx
    ON oil_engines (upper(btrim(code)));

-- ---------------------------------------------------------------------------------------------
--  Suggested products: illustrative only. The approvals in oil_engines.specs are what bind; the
--  app renders these as the quietest part of the card for exactly that reason. Brand names and
--  their approvals drift over time — treat this table as the disposable half.
-- ---------------------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS oil_products (
    id          SERIAL  PRIMARY KEY,
    engine_code TEXT    NOT NULL REFERENCES oil_engines(code) ON UPDATE CASCADE ON DELETE CASCADE,
    name        TEXT    NOT NULL,
    viscosity   TEXT,
    specs       TEXT,
    sort_order  INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX IF NOT EXISTS oil_products_engine_idx
    ON oil_products (engine_code, sort_order);

-- =============================================================================================
--  Seed: 20 engines, weighted the way the Bulgarian car park actually skews (14 diesel, 6 petrol)
-- =============================================================================================
INSERT INTO oil_engines
    (code, display_name, fuel_type, makes, viscosity, alt_viscosity, specs,
     capacity_liters, interval_km, interval_months, note)
VALUES
-- ---- Diesel ---------------------------------------------------------------------------------
('ARL', '1.9 TDI PD 150 к.с. (ARL)', 'diesel', 'VW,Audi,Seat,Skoda',
 '5W-40', NULL, 'VW 505 01', 4.5, 15000, 12,
 'Само масло с одобрение VW 505 01. С неподходящо масло помпа-дюзите износват гърбичния вал.'),

('AXR', '1.9 TDI PD 101 к.с. (AXR)', 'diesel', 'VW,Audi,Seat,Skoda',
 '5W-40', NULL, 'VW 505 01', 4.3, 15000, 12,
 'Само масло с одобрение VW 505 01. С неподходящо масло помпа-дюзите износват гърбичния вал.'),

('BXE', '1.9 TDI PD 105 к.с. (BXE)', 'diesel', 'VW,Audi,Seat,Skoda',
 '5W-40', NULL, 'VW 505 01', 4.3, 15000, 12,
 'Само масло с одобрение VW 505 01. С неподходящо масло помпа-дюзите износват гърбичния вал.'),

('BKD', '2.0 TDI PD 140 к.с. (BKD)', 'diesel', 'VW,Audi,Seat,Skoda',
 '5W-30', '5W-40', 'VW 507 00,ACEA C3', 4.3, 15000, 12,
 'С DPF: задължително VW 507 00 (low-SAPS). Без DPF се допуска VW 505 01 5W-40. Двете не са взаимозаменяеми.'),

('CFFB', '2.0 TDI CR 140 к.с. (EA189)', 'diesel', 'VW,Audi,Seat,Skoda',
 '5W-30', NULL, 'VW 507 00,ACEA C3', 4.3, 15000, 12,
 'С DPF: задължително масло с одобрение VW 507 00 (low-SAPS). Не използвайте 505 01.'),

('CRLB', '2.0 TDI CR 150 к.с. (EA288)', 'diesel', 'VW,Audi,Seat,Skoda',
 '5W-30', NULL, 'VW 507 00,ACEA C3', 4.3, 15000, 12,
 'С DPF: задължително масло с одобрение VW 507 00 (low-SAPS). Не използвайте 505 01.'),

('M47', 'BMW 2.0d (M47)', 'diesel', 'BMW',
 '5W-30', '0W-40', 'BMW Longlife-04,BMW Longlife-01', 4.5, 15000, 12,
 'С DPF: BMW Longlife-04. Без DPF се допуска и Longlife-01.'),

('N47', 'BMW 2.0d (N47)', 'diesel', 'BMW',
 '5W-30', NULL, 'BMW Longlife-04,ACEA C3', 5.2, 15000, 12,
 'Известно износване на ангренажната верига. Спазвайте интервала и не удължавайте смяната на маслото.'),

('M57', 'BMW 3.0d (M57)', 'diesel', 'BMW',
 '5W-30', NULL, 'BMW Longlife-04', 6.5, 15000, 12,
 'С DPF: задължително BMW Longlife-04 (low-SAPS).'),

('OM646', 'Mercedes 2.2 CDI (OM646)', 'diesel', 'Mercedes-Benz',
 '5W-30', '5W-40', 'MB 229.51,ACEA C3', 7.5, 15000, 12,
 'С DPF: MB 229.51. Без DPF се допуска MB 229.5 5W-40.'),

('OM651', 'Mercedes 2.1 CDI (OM651)', 'diesel', 'Mercedes-Benz',
 '5W-30', NULL, 'MB 229.52,MB 229.51', 6.5, 15000, 12,
 NULL),

('Z19DTH', 'Opel 1.9 CDTI 150 к.с. (Z19DTH)', 'diesel', 'Opel,Vauxhall,Fiat,Saab',
 '5W-40', '5W-30', 'GM dexos2,ACEA C3', 4.6, 15000, 12,
 'С DPF: задължително масло по ACEA C3 / dexos2 (low-SAPS).'),

('K9K', '1.5 dCi (K9K)', 'diesel', 'Renault,Dacia,Nissan',
 '5W-30', '5W-40', 'RN0720,ACEA C4', 4.4, 15000, 12,
 'С DPF: RN0720 (ACEA C4). Без DPF се допуска RN0710 5W-40. Не удължавайте интервала.'),

('DV6', '1.6 HDi / 1.6 TDCi (DV6)', 'diesel', 'Peugeot,Citroen,Ford,Volvo,Mini',
 '5W-30', NULL, 'PSA B71 2290,ACEA C2', 3.8, 15000, 12,
 'С FAP: задължително PSA B71 2290 (low-SAPS). Двигателят е известен със запушване на мрежичката за масло към турбината — не удължавайте интервала.'),

-- ---- Petrol ---------------------------------------------------------------------------------
('AGU', '1.8T 20V 150 к.с. (AGU)', 'petrol', 'VW,Audi,Seat,Skoda',
 '5W-40', NULL, 'VW 502 00,ACEA A3/B4', 4.6, 15000, 12,
 'На газова инсталация маслото се износва по-бързо — сменяйте на около 7 000 – 8 000 км.'),

('BSE', '1.6 MPI 102 к.с. (BSE)', 'petrol', 'VW,Audi,Seat,Skoda',
 '5W-40', '5W-30', 'VW 502 00,ACEA A3/B4', 4.5, 15000, 12,
 'На газова инсталация маслото се износва по-бързо — сменяйте на около 7 000 – 8 000 км.'),

('CAXA', '1.4 TSI 122 к.с. (EA111)', 'petrol', 'VW,Audi,Seat,Skoda',
 '5W-30', NULL, 'VW 504 00', 3.6, 15000, 12,
 'Турбо двигател с директно впръскване — използвайте само масло с одобрение VW 504 00.'),

('Z16XER', 'Opel 1.6 16V 115 к.с. (Z16XER)', 'petrol', 'Opel,Vauxhall,Chevrolet',
 '5W-30', '5W-40', 'GM dexos2,ACEA A3/B4', 4.5, 15000, 12,
 'На газова инсталация маслото се износва по-бързо — сменяйте на около 7 000 – 8 000 км.'),

('K4M', 'Renault 1.6 16V (K4M)', 'petrol', 'Renault,Dacia,Nissan',
 '5W-40', NULL, 'RN0700,ACEA A3/B4', 4.8, 15000, 12,
 'На газова инсталация маслото се износва по-бързо — сменяйте на около 7 000 – 8 000 км.'),

('1ZZ-FE', 'Toyota 1.8 VVT-i (1ZZ-FE)', 'petrol', 'Toyota',
 '5W-30', '5W-40', 'API SN,ILSAC GF-5', 3.7, 10000, 12,
 'Ранните серии имат повишен разход на масло — проверявайте нивото между смените.')

ON CONFLICT (code) DO UPDATE SET
    display_name    = EXCLUDED.display_name,
    fuel_type       = EXCLUDED.fuel_type,
    makes           = EXCLUDED.makes,
    viscosity       = EXCLUDED.viscosity,
    alt_viscosity   = EXCLUDED.alt_viscosity,
    specs           = EXCLUDED.specs,
    capacity_liters = EXCLUDED.capacity_liters,
    interval_km     = EXCLUDED.interval_km,
    interval_months = EXCLUDED.interval_months,
    note            = EXCLUDED.note,
    updated_at      = now();

-- ---------------------------------------------------------------------------------------------
--  Products. Wiped and re-inserted for the seeded engines so re-running this file doesn't stack
--  duplicates. Grouped by the approval they satisfy, not by engine, because that is what actually
--  decides whether a bottle fits.
-- ---------------------------------------------------------------------------------------------
DELETE FROM oil_products WHERE engine_code IN (
    'ARL','AXR','BXE','BKD','CFFB','CRLB','M47','N47','M57','OM646','OM651','Z19DTH','K9K','DV6',
    'AGU','BSE','CAXA','Z16XER','K4M','1ZZ-FE'
);

-- VW 505 01 (PD)
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order)
SELECT code, 'Liqui Moly Diesel High Tech', '5W-40', 'VW 505 01 / 505 00', 1 FROM oil_engines WHERE code IN ('ARL','AXR','BXE')
UNION ALL
SELECT code, 'Motul Specific 505 01 502 00', '5W-40', 'VW 505 01 / 502 00', 2 FROM oil_engines WHERE code IN ('ARL','AXR','BXE');

-- VW 507 00 (low-SAPS, DPF)
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order)
SELECT code, 'Castrol Edge Professional LongLife III', '5W-30', 'VW 504 00 / 507 00', 1 FROM oil_engines WHERE code IN ('BKD','CFFB','CRLB','CAXA')
UNION ALL
SELECT code, 'Liqui Moly Top Tec 4200', '5W-30', 'VW 504 00 / 507 00', 2 FROM oil_engines WHERE code IN ('BKD','CFFB','CRLB','CAXA');

-- BMW Longlife-04
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order)
SELECT code, 'Castrol Edge Professional LL04', '5W-30', 'BMW Longlife-04', 1 FROM oil_engines WHERE code IN ('M47','N47','M57')
UNION ALL
SELECT code, 'Liqui Moly Top Tec 4200', '5W-30', 'BMW Longlife-04', 2 FROM oil_engines WHERE code IN ('M47','N47','M57');

-- MB 229.51 / 229.52
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order)
SELECT code, 'Mobil 1 ESP Formula', '5W-30', 'MB 229.51 / 229.52', 1 FROM oil_engines WHERE code IN ('OM646','OM651')
UNION ALL
SELECT code, 'Liqui Moly Top Tec 4600', '5W-30', 'MB 229.51 / 229.52', 2 FROM oil_engines WHERE code IN ('OM646','OM651');

-- GM dexos2 / ACEA C3
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order)
SELECT code, 'Liqui Moly Top Tec 4100', '5W-40', 'GM dexos2, ACEA C3', 1 FROM oil_engines WHERE code IN ('Z19DTH','Z16XER')
UNION ALL
SELECT code, 'Castrol Edge C3', '5W-40', 'GM dexos2, ACEA C3', 2 FROM oil_engines WHERE code IN ('Z19DTH','Z16XER');

-- Renault RN0720 / RN0700
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order) VALUES
('K9K', 'Elf Evolution Full-Tech FE', '5W-30', 'RN0720, ACEA C4', 1),
('K9K', 'Motul Specific 0720', '5W-30', 'RN0720', 2),
('K4M', 'Elf Evolution 900 SXR', '5W-40', 'RN0700, ACEA A3/B4', 1),
('K4M', 'Total Quartz 9000 Energy', '5W-40', 'RN0700, ACEA A3/B4', 2);

-- PSA B71 2290 (FAP)
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order) VALUES
('DV6', 'Total Quartz INEO ECS', '5W-30', 'PSA B71 2290, ACEA C2', 1),
('DV6', 'Motul Specific 2290', '5W-30', 'PSA B71 2290, ACEA C2', 2);

-- VW 502 00
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order)
SELECT code, 'Liqui Moly Molygen New Generation', '5W-40', 'VW 502 00 / 505 00', 1 FROM oil_engines WHERE code IN ('AGU','BSE')
UNION ALL
SELECT code, 'Castrol Edge A3/B4', '5W-40', 'VW 502 00 / 505 00', 2 FROM oil_engines WHERE code IN ('AGU','BSE');

-- Toyota
INSERT INTO oil_products (engine_code, name, viscosity, specs, sort_order) VALUES
('1ZZ-FE', 'Toyota Genuine Motor Oil', '5W-30', 'API SN, ILSAC GF-5', 1),
('1ZZ-FE', 'Mobil Super 3000 X1 Formula FE', '5W-30', 'API SN, ILSAC GF-5', 2);

COMMIT;

-- =============================================================================================
--  The two queries the endpoints run (commented out: they carry $1/$2 placeholders, so
--  running this file in psql would error on them)
-- =============================================================================================

-- GET /oil/engines
--   makes is split on ',' into a JSON array by the C++ side.
--   SELECT code, display_name, fuel_type, makes
--   FROM oil_engines
--   ORDER BY fuel_type, code;

-- GET /oil/recommend?engineCode=...&fuelType=...
--   $1 = engineCode as the client sent it, $2 = fuelType ('diesel' | 'petrol' | 'lpg' | '').
--
--   fuel_type is a TIEBREAKER, NOT A FILTER: it is prefilled in the app from the user-typed
--   Vehicle.fuelType, so it can simply be wrong, and a filter would turn a hit into a 404. Hence
--   the ORDER BY — a matching fuel wins, a mismatching one still answers. 'lpg' maps to the petrol
--   row (an LPG conversion is a petrol engine); append the LPG caveat to note in the handler.
--   SELECT code, display_name, fuel_type, viscosity, alt_viscosity, specs,
--          capacity_liters, interval_km, interval_months, note
--   FROM oil_engines
--   WHERE upper(btrim(code)) = upper(btrim($1))
--   ORDER BY (fuel_type = CASE WHEN $2 = 'lpg' THEN 'petrol' ELSE $2 END) DESC
--   LIMIT 1;
--   No row → HTTP 404 (the client shows „За този двигател още няма данни“ and does not retry).

-- Products for the matched engine.
--   SELECT name, viscosity, specs
--   FROM oil_products
--   WHERE engine_code = $1
--   ORDER BY sort_order, id;
