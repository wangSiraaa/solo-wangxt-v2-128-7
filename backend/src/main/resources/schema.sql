-- ============================================================
-- 可回放核算链 - PostgreSQL schema
--
-- 原则：
--   1. business_event 是唯一事实源，仅追加(append-only)，不更新不删除。
--   2. 投影表(projection_*)是事件的派生缓存，可整体清空后重放重建。
--   3. 快照(snapshot_*)仅加速读取；禁止用快照反向补造历史。
--   4. 幂等：source_key 在 (source_system, source_key) 上唯一，
--      重复文件/并发文件导入同一成交或公司行动时只登记一次。
--
-- 注意：本脚本配合 spring.sql.init.separator=^ 使用，
-- 顶层语句之间以单独一行的 ^ 分隔（PL/pgSQL 函数体内可自由使用分号）。
-- ============================================================

CREATE TABLE IF NOT EXISTS business_event (
    id              BIGSERIAL PRIMARY KEY,
    event_type      VARCHAR(32)  NOT NULL CHECK (event_type IN ('TRADE','STOCK_SPLIT','CASH_DIVIDEND','RIGHTS_OFFER')),
    account_id      VARCHAR(64)  NOT NULL,
    instrument      VARCHAR(32)  NOT NULL,
    business_date   DATE         NOT NULL,
    settlement_date DATE,
    record_date     DATE,
    payment_date    DATE,
    allotment_date  DATE,
    payload         JSONB        NOT NULL,
    source_system   VARCHAR(64)  NOT NULL,
    source_key      VARCHAR(256) NOT NULL,
    idempotency_key VARCHAR(256) NOT NULL,
    late_flag       BOOLEAN      NOT NULL DEFAULT FALSE,
    ingested_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_event_idempotency UNIQUE (source_system, source_key),
    CONSTRAINT uq_event_idem_key UNIQUE (idempotency_key)
)
^
CREATE INDEX IF NOT EXISTS idx_event_replay
    ON business_event (account_id, business_date, id)
^
CREATE INDEX IF NOT EXISTS idx_event_record ON business_event (account_id, record_date)
^
CREATE INDEX IF NOT EXISTS idx_event_payment ON business_event (account_id, payment_date)
^
CREATE INDEX IF NOT EXISTS idx_event_settle ON business_event (account_id, settlement_date)
^
CREATE OR REPLACE FUNCTION reject_event_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'business_event is append-only; % is forbidden', TG_OP;
END;
$$ LANGUAGE plpgsql
^
DROP TRIGGER IF EXISTS trg_event_no_update ON business_event
^
CREATE TRIGGER trg_event_no_update BEFORE UPDATE ON business_event
    FOR EACH ROW EXECUTE FUNCTION reject_event_mutation()
^
DROP TRIGGER IF EXISTS trg_event_no_delete ON business_event
^
CREATE TRIGGER trg_event_no_delete BEFORE DELETE ON business_event
    FOR EACH ROW EXECUTE FUNCTION reject_event_mutation()
^

-- ---------- 事件准入（迟到/归属窗口）：账本本体完全不可变，迟到状态在此登记 ----------
CREATE TABLE IF NOT EXISTS event_admission (
    event_id        BIGINT PRIMARY KEY REFERENCES business_event(id),
    account_id      VARCHAR(64) NOT NULL,
    admitted_to_date DATE,
    late            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
)
^
CREATE INDEX IF NOT EXISTS idx_admission_account
    ON event_admission (account_id, admitted_to_date)
^

-- ---------- 导入批次（Spring Batch 启动） ----------
CREATE TABLE IF NOT EXISTS import_batch (
    id              BIGSERIAL PRIMARY KEY,
    job_execution_id BIGINT,
    file_name       VARCHAR(512) NOT NULL,
    sha256          CHAR(64)     NOT NULL,
    status          VARCHAR(24)  NOT NULL DEFAULT 'RECEIVED'
                        CHECK (status IN ('RECEIVED','RUNNING','COMPLETED','FAILED','DUPLICATE')),
    total_rows      INTEGER NOT NULL DEFAULT 0,
    inserted_events INTEGER NOT NULL DEFAULT 0,
    duplicate_rows  INTEGER NOT NULL DEFAULT 0,
    error_message   TEXT,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    CONSTRAINT uq_import_sha UNIQUE (sha256)
)
^

-- ============================================================
-- 投影（派生、可重建）
-- ============================================================
CREATE TABLE IF NOT EXISTS projection_cursor (
    account_id      VARCHAR(64) PRIMARY KEY,
    last_event_id   BIGINT NOT NULL DEFAULT 0,
    last_business_date DATE,
    last_stage      VARCHAR(48),
    last_effect_key VARCHAR(128),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
)
^

CREATE TABLE IF NOT EXISTS projection_lot (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    lot_key         VARCHAR(160) NOT NULL,
    instrument      VARCHAR(32) NOT NULL,
    opening_event_id BIGINT NOT NULL,
    source_event_type VARCHAR(32) NOT NULL,
    acquired_date   DATE NOT NULL,
    open_qty        NUMERIC(28,8) NOT NULL,
    remaining_qty   NUMERIC(28,8) NOT NULL,
    unit_cost       NUMERIC(28,6) NOT NULL,
    total_cost      NUMERIC(28,2) NOT NULL,
    remaining_cost  NUMERIC(28,2) NOT NULL,
    fractional      BOOLEAN NOT NULL DEFAULT FALSE,
    closed          BOOLEAN NOT NULL DEFAULT FALSE,
    derived_from_lot_key VARCHAR(160),
    adjusted_by_event_id BIGINT,
    CONSTRAINT uq_lot UNIQUE (account_id, lot_key)
)
^
CREATE INDEX IF NOT EXISTS idx_lot_open
    ON projection_lot (account_id, instrument, closed, acquired_date, id)
^

CREATE TABLE IF NOT EXISTS projection_lot_consumption (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    instrument      VARCHAR(32) NOT NULL,
    lot_key         VARCHAR(160) NOT NULL,
    selling_event_id BIGINT NOT NULL,
    qty             NUMERIC(28,8) NOT NULL,
    cost_released   NUMERIC(28,2) NOT NULL,
    proceeds        NUMERIC(28,2) NOT NULL,
    at_date         DATE NOT NULL,
    CONSTRAINT uq_lot_consumption UNIQUE (account_id, selling_event_id, lot_key)
)
^

CREATE TABLE IF NOT EXISTS projection_cash_entry (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    value_date      DATE NOT NULL,
    event_id        BIGINT NOT NULL,
    effect_key      VARCHAR(128) NOT NULL,
    direction       VARCHAR(8) NOT NULL CHECK (direction IN ('IN','OUT')),
    amount          NUMERIC(28,2) NOT NULL,
    category        VARCHAR(32) NOT NULL CHECK (category IN
                        ('TRADE_BUY','TRADE_SELL','COMMISSION','DIVIDEND','RIGHTS_PAYMENT')),
    idem_key        VARCHAR(256) NOT NULL,
    CONSTRAINT uq_cash_idem UNIQUE (idem_key)
)
^
CREATE INDEX IF NOT EXISTS idx_cash_account
    ON projection_cash_entry (account_id, value_date, id)
^

CREATE TABLE IF NOT EXISTS projection_entitlement (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    instrument      VARCHAR(32) NOT NULL,
    event_id        BIGINT NOT NULL,
    kind            VARCHAR(16) NOT NULL CHECK (kind IN ('CASH_DIVIDEND','RIGHTS')),
    record_date     DATE NOT NULL,
    payment_date    DATE,
    eligible_qty    NUMERIC(28,8) NOT NULL,
    amount_per_share NUMERIC(28,6),
    gross_amount    NUMERIC(28,2),
    status          VARCHAR(24) NOT NULL DEFAULT 'CALCULATED'
                        CHECK (status IN ('CALCULATED','PAID','EXPIRED','SUBSCRIBED','PARTIALLY_SUBSCRIBED')),
    subscribed_qty  NUMERIC(28,8) NOT NULL DEFAULT 0,
    idem_key        VARCHAR(256) NOT NULL,
    CONSTRAINT uq_entitlement_idem UNIQUE (idem_key)
)
^

CREATE TABLE IF NOT EXISTS projection_checkpoint (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    stage           VARCHAR(48) NOT NULL,
    event_id        BIGINT NOT NULL,
    effect_key      VARCHAR(128) NOT NULL,
    shares_hash     VARCHAR(64) NOT NULL,
    cash_balance    NUMERIC(28,2) NOT NULL,
    open_cost       NUMERIC(28,2) NOT NULL,
    realized_pnl    NUMERIC(28,2) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_checkpoint_effect UNIQUE (account_id, effect_key)
)
^
CREATE INDEX IF NOT EXISTS idx_checkpoint_pos
    ON projection_checkpoint (account_id, id)
^

-- ============================================================
-- 日终发布：草稿绑定事件水位，发布后不可变
-- ============================================================
CREATE TABLE IF NOT EXISTS eod_draft (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    watermark_event_id BIGINT NOT NULL,
    late_event_ids  BIGINT[] NOT NULL DEFAULT '{}',
    qty_balanced    BOOLEAN NOT NULL DEFAULT FALSE,
    cash_balanced   BOOLEAN NOT NULL DEFAULT FALSE,
    cost_balanced   BOOLEAN NOT NULL DEFAULT FALSE,
    earliest_mismatch_event_id BIGINT,
    mismatch_detail TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'DRAFT'
                        CHECK (status IN ('DRAFT','PUBLISHED','BLOCKED','SUPERSEDED')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    CONSTRAINT uq_eod_draft UNIQUE (account_id, business_date)
)
^

CREATE TABLE IF NOT EXISTS eod_published_watermark (
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    watermark_event_id BIGINT NOT NULL,
    published_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_published_wm PRIMARY KEY (account_id, business_date)
)
^

CREATE TABLE IF NOT EXISTS snapshot_position (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    instrument      VARCHAR(32) NOT NULL,
    qty             NUMERIC(28,8) NOT NULL,
    fractional_qty  NUMERIC(28,8) NOT NULL DEFAULT 0,
    open_cost       NUMERIC(28,2) NOT NULL,
    avg_cost        NUMERIC(28,6) NOT NULL,
    realized_pnl    NUMERIC(28,2) NOT NULL,
    watermark_event_id BIGINT NOT NULL,
    CONSTRAINT uq_snapshot_pos UNIQUE (account_id, business_date, instrument)
)
^

CREATE TABLE IF NOT EXISTS snapshot_lot (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    lot_key         VARCHAR(160) NOT NULL,
    instrument      VARCHAR(32) NOT NULL,
    opening_event_id BIGINT NOT NULL,
    source_event_type VARCHAR(32) NOT NULL,
    acquired_date   DATE NOT NULL,
    remaining_qty   NUMERIC(28,8) NOT NULL,
    unit_cost       NUMERIC(28,6) NOT NULL,
    total_cost      NUMERIC(28,2) NOT NULL,
    fractional      BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_snapshot_lot UNIQUE (account_id, business_date, lot_key)
)
^

CREATE TABLE IF NOT EXISTS snapshot_cash (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    inflow          NUMERIC(28,2) NOT NULL,
    outflow         NUMERIC(28,2) NOT NULL,
    balance         NUMERIC(28,2) NOT NULL,
    watermark_event_id BIGINT NOT NULL,
    CONSTRAINT uq_snapshot_cash UNIQUE (account_id, business_date)
)
^

-- 账实核对：外部对账单（托管行，非真实券商连接；仅核对，绝不反写历史）
CREATE TABLE IF NOT EXISTS external_statement (
    id              BIGSERIAL PRIMARY KEY,
    account_id      VARCHAR(64) NOT NULL,
    business_date   DATE NOT NULL,
    instrument      VARCHAR(32) NOT NULL,
    external_qty    NUMERIC(28,8) NOT NULL,
    external_fractional_qty NUMERIC(28,8) NOT NULL DEFAULT 0,
    external_cash   NUMERIC(28,2),
    external_cost   NUMERIC(28,2),
    uploaded_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_external_stmt UNIQUE (account_id, business_date, instrument)
)
^
