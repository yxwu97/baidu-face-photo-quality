CREATE TABLE source_root (
    root_id INTEGER PRIMARY KEY,
    canonical_path TEXT NOT NULL UNIQUE,
    display_path TEXT NOT NULL
);

CREATE TABLE photo (
    photo_id INTEGER PRIMARY KEY,
    root_id INTEGER NOT NULL REFERENCES source_root,
    relative_path TEXT NOT NULL,
    filename TEXT NOT NULL,
    search_filename TEXT NOT NULL,
    capture_date TEXT,
    capture_time TEXT,
    capture_status TEXT,
    rec INTEGER,
    live INTEGER,
    UNIQUE (root_id, relative_path)
);

CREATE TABLE photo_revision (
    revision_id INTEGER PRIMARY KEY,
    photo_id INTEGER NOT NULL REFERENCES photo,
    content_sha256 TEXT NOT NULL,
    file_size INTEGER,
    modified_ms INTEGER,
    UNIQUE (photo_id, content_sha256)
);

CREATE TABLE measurement_profile (
    fingerprint TEXT PRIMARY KEY,
    engine_id TEXT NOT NULL,
    engine_version TEXT NOT NULL,
    metrics_version TEXT NOT NULL,
    platform TEXT,
    architecture TEXT,
    native_hash TEXT,
    model_hash TEXT,
    configuration_hash TEXT,
    adapter_version TEXT,
    decode_version TEXT,
    details_json TEXT NOT NULL
);

CREATE TABLE rule_set (
    rule_set_id INTEGER PRIMARY KEY,
    version TEXT NOT NULL UNIQUE,
    engine_id TEXT,
    metrics_version TEXT,
    rules_json TEXT NOT NULL,
    created_at TEXT NOT NULL
);

CREATE TABLE application_setting (
    setting_id INTEGER PRIMARY KEY CHECK (setting_id = 1),
    default_rule_set_id INTEGER NOT NULL REFERENCES rule_set
);

CREATE TABLE batch (
    batch_id INTEGER PRIMARY KEY,
    root_id INTEGER NOT NULL REFERENCES source_root,
    state TEXT NOT NULL CHECK (state IN ('READY','CHECKING','RUNNING','CANCELLING','CANCELLED','PAUSED','COMPLETE')),
    profile_fingerprint TEXT REFERENCES measurement_profile (fingerprint),
    rule_set_id INTEGER NOT NULL REFERENCES rule_set,
    total INTEGER NOT NULL CHECK (total >= 0),
    unreadable_directories INTEGER NOT NULL CHECK (unreadable_directories >= 0),
    created_at TEXT NOT NULL,
    completed_at TEXT,
    error_code TEXT
);

CREATE TABLE batch_item (
    item_id INTEGER PRIMARY KEY,
    batch_id INTEGER NOT NULL REFERENCES batch,
    photo_id INTEGER NOT NULL REFERENCES photo,
    sort_order INTEGER NOT NULL,
    state TEXT NOT NULL CHECK (state IN ('PENDING','PROCESSING','COMMITTED')),
    current_measurement_id INTEGER REFERENCES measurement,
    active_request TEXT,
    UNIQUE (batch_id, photo_id),
    UNIQUE (batch_id, sort_order),
    CHECK ((state = 'COMMITTED') = (current_measurement_id IS NOT NULL)),
    CHECK ((state = 'PROCESSING') = (active_request IS NOT NULL))
);

CREATE TABLE measurement (
    measurement_id INTEGER PRIMARY KEY,
    item_id INTEGER NOT NULL REFERENCES batch_item,
    revision_id INTEGER REFERENCES photo_revision,
    fingerprint TEXT NOT NULL REFERENCES measurement_profile,
    source_width INTEGER,
    source_height INTEGER,
    width INTEGER,
    height INTEGER,
    orientation INTEGER,
    duration_ms INTEGER,
    measurement_json TEXT,
    error_json TEXT,
    reusable INTEGER NOT NULL CHECK (reusable IN (0,1)),
    CHECK ((measurement_json IS NULL) <> (error_json IS NULL))
);

CREATE TABLE decision (
    decision_id INTEGER PRIMARY KEY,
    measurement_id INTEGER NOT NULL REFERENCES measurement,
    rule_set_id INTEGER NOT NULL REFERENCES rule_set,
    status TEXT NOT NULL CHECK (status IN ('PASS','FAIL','REVIEW','ERROR')),
    decided_at TEXT NOT NULL,
    UNIQUE (measurement_id, rule_set_id)
);

CREATE TABLE decision_reason (
    decision_id INTEGER NOT NULL REFERENCES decision,
    code TEXT NOT NULL,
    reason_order INTEGER NOT NULL,
    evidence_json TEXT NOT NULL,
    PRIMARY KEY (decision_id, code)
);

CREATE TABLE manual_review (
    revision_id INTEGER PRIMARY KEY REFERENCES photo_revision,
    conclusion TEXT CHECK (conclusion IN ('PASS', 'FAIL', 'REVIEW')),
    note TEXT NOT NULL CHECK (length(note) <= 300),
    updated_at TEXT NOT NULL
);

CREATE INDEX batch_root ON batch (root_id, batch_id DESC);
CREATE INDEX batch_item_status ON batch_item (batch_id, state, sort_order);
CREATE INDEX photo_capture_date ON photo (root_id, capture_date);
CREATE INDEX decision_status ON decision (rule_set_id, status);
CREATE INDEX decision_reason_code ON decision_reason (code, decision_id);
