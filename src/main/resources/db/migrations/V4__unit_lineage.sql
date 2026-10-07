-- Which unit an item was made from, or turned into (anvil rename, enchant, grindstone, smithing,
-- crafting). A modified item is a NEW unit, and this table links it to the unit(s) it came from.
-- child_uuid is NULL when the result is not tracked, for example 9 diamonds crafted into a plain
-- block, and detail then says what it became. No foreign keys on purpose, because this is a
-- history log and a missing unit row must never make a lineage write fail.
-- NOTE: the migrator splits this file on the semicolon character, so comments must not contain it.
CREATE TABLE IF NOT EXISTS unit_lineage (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    parent_uuid TEXT NOT NULL,
    child_uuid TEXT,
    relation TEXT NOT NULL,
    detail TEXT,
    at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_lineage_parent ON unit_lineage(parent_uuid);
CREATE INDEX IF NOT EXISTS idx_lineage_child ON unit_lineage(child_uuid);
