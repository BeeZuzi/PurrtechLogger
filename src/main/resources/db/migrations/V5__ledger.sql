-- Item ledger for templates with mode ledger. These items carry no UUID, so instead of following
-- single items the plugin books every change of a player's count of the item, with its cause.
-- cause is one of PICKUP, DROP, CRAFT, CLOSE, COMMAND, ISSUED, UNEXPLAINED, OUT, CLICK
-- delta is the net change of the player's count in that tick, total_after the count afterwards.
-- No foreign keys on purpose, because this is a history log.
CREATE TABLE IF NOT EXISTS ledger (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    at INTEGER NOT NULL,
    player_uuid TEXT,
    template_key TEXT NOT NULL,
    delta INTEGER NOT NULL,
    cause TEXT NOT NULL,
    total_after INTEGER,
    detail TEXT
);
CREATE INDEX IF NOT EXISTS idx_ledger_player_time ON ledger(player_uuid, at);
CREATE INDEX IF NOT EXISTS idx_ledger_template_time ON ledger(template_key, at);
