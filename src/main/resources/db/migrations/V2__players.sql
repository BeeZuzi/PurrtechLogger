ALTER TABLE events ADD COLUMN gamemode TEXT;

CREATE TABLE IF NOT EXISTS players (
    uuid TEXT PRIMARY KEY,
    current_name TEXT NOT NULL,
    first_joined_at INTEGER NOT NULL,
    last_joined_at INTEGER NOT NULL,
    last_seen_at INTEGER,
    online INTEGER NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_players_online ON players(online);

CREATE TABLE IF NOT EXISTS player_name_history (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    player_uuid TEXT NOT NULL,
    name TEXT NOT NULL,
    changed_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_player_name_history_player ON player_name_history(player_uuid);
