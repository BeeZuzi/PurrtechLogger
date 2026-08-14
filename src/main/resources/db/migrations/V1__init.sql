CREATE TABLE IF NOT EXISTS templates (
    id INTEGER PRIMARY KEY,
    key TEXT UNIQUE NOT NULL,
    material TEXT NOT NULL,
    custom_model_data INTEGER,
    pdc_marker_key TEXT,
    pdc_marker_value TEXT,
    name_pattern TEXT,
    lore_pattern TEXT,
    track_items INTEGER NOT NULL,
    track_blocks INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);

INSERT OR IGNORE INTO templates (id, key, material, track_items, track_blocks, created_at)
VALUES (0, '__dbtest__', 'STONE', 0, 0, 0);

CREATE TABLE IF NOT EXISTS tracked_units (
    uuid TEXT PRIMARY KEY,
    template_id INTEGER NOT NULL REFERENCES templates(id),
    kind TEXT NOT NULL CHECK(kind IN ('ITEM','BLOCK')),
    origin TEXT NOT NULL,
    duplicated_from_uuid TEXT,
    genesis_at INTEGER NOT NULL,
    alive INTEGER NOT NULL DEFAULT 1,
    destroyed_at INTEGER,
    destroyed_cause TEXT
);
CREATE INDEX IF NOT EXISTS idx_tracked_units_template ON tracked_units(template_id);
CREATE INDEX IF NOT EXISTS idx_tracked_units_alive ON tracked_units(alive);

CREATE TABLE IF NOT EXISTS locations (
    unit_uuid TEXT PRIMARY KEY REFERENCES tracked_units(uuid),
    location_type TEXT NOT NULL,
    player_uuid TEXT,
    slot INTEGER,
    world TEXT,
    x INTEGER,
    y INTEGER,
    z INTEGER,
    entity_uuid TEXT,
    container_type TEXT,
    parent_shulker_uuid TEXT REFERENCES tracked_units(uuid),
    menu_name TEXT,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_locations_coords ON locations(world, x, y, z);
CREATE INDEX IF NOT EXISTS idx_locations_type ON locations(location_type);

CREATE TABLE IF NOT EXISTS events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    unit_uuid TEXT REFERENCES tracked_units(uuid),
    event_type TEXT NOT NULL,
    timestamp INTEGER NOT NULL,
    world TEXT,
    x INTEGER,
    y INTEGER,
    z INTEGER,
    player_uuid TEXT,
    detail TEXT
);
CREATE INDEX IF NOT EXISTS idx_events_unit_time ON events(unit_uuid, timestamp);
CREATE INDEX IF NOT EXISTS idx_events_type_time ON events(event_type, timestamp);

CREATE TABLE IF NOT EXISTS dupe_alerts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    template_id INTEGER,
    unit_uuid TEXT,
    detected_at INTEGER NOT NULL,
    expected_alive_count INTEGER,
    observed_count INTEGER,
    world TEXT,
    x INTEGER,
    y INTEGER,
    z INTEGER,
    player_uuid TEXT,
    severity TEXT,
    resolved INTEGER NOT NULL DEFAULT 0,
    resolved_at INTEGER,
    note TEXT
);
CREATE INDEX IF NOT EXISTS idx_dupe_alerts_resolved ON dupe_alerts(resolved);
