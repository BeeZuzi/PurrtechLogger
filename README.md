# PurrTechDetailLogger

Paper plugin (Minecraft 1.21.11, Java 21), který sleduje itemy a bloky podle konfigurovatelných
šablon — kde přesně se každá sledovaná jednotka nachází, celou její historii a jestli nedošlo
k duplikaci. Vše se zapisuje do lokální SQLite databáze.

## Co plugin dělá

Podle šablon definovaných v `templates.yml` (nebo importovaných přímo ve hře z drženého itemu)
plugin každé nově objevené jednotce přiřadí UUID a od té chvíle sleduje:

- **Kde se nachází** — hráčův inventář, země, blokový kontejner (chest/barrel/furnace/hopper/...),
  entitní kontejner (minecart chest, minecart hopper), enderchest, shulker box nesený jako item
  (vnořené umístění), nebo obecné GUI menu jiného pluginu.
- **Celou historii** — genesis, přesuny, sloučení/rozdělení hromádek, zničení (láva, void, exploze,
  creative smazání) — s časovými razítky.
- **Bloky stejně jako itemy** — přechod item → blok (postavení) a blok → item (těžba), včetně
  posunu pístem a pádu gravitačních bloků (písek/štěrk).
- **Skládání itemů** — když hráč spojí dvě sledované jednotky do jedné hromádky (nebo naopak
  hromádku rozdělí), plugin opraví identitu jednotek tak, aby žádná nezmizela ani se
  nezdvojila.
- **Duplikace** — periodický sweep porovnává, co je fyzicky u online hráčů, s tím, co říká
  databáze. Duplikace v creative módu je považovaná za povolenou (nová kopie dostane nové UUID
  s odkazem na originál), cokoliv jiného vyvolá alert.

Databáze je vždy zdroj pravdy — NBT na itemu/bloku je jen rychlá lokální nápověda.

## Požadavky

- Paper server 1.21.11 (nebo kompatibilní), Java 21
- Volitelně: plugin **DisplayGUI** pro in-game admin GUI (bez něj fungují jen textové
  `/purrlog` příkazy)

## Sestavení

```bash
./gradlew build
```

Výsledný `.jar` (se zabaleným SQLite driverem) najdeš v `build/libs/`.

## Konfigurace

Šablony ke sledování se definují v `plugins/PurrTechDetailLogger/templates.yml`:

```yaml
templates:
  legendary_sword:
    material: DIAMOND_SWORD
    custom-model-data: 1001
    track-items: true
    track-blocks: false
  special_chest:
    material: CHEST
    pdc-marker:
      key: "someplugin:special"
      value: "true"
    track-items: true
    track-blocks: true
```

Šablonu jde vytvořit i přímo ve hře: drž item v ruce a spusť `/purrlog template import <klíč>`.
Po úpravě configu spusť `/purrlog reload`.

## Příkazy

Všechny vyžadují permission `purrtechdetaillogger.admin` (výchozí: OP).

| Příkaz | Popis |
|---|---|
| `/purrlog gui` | otevře admin GUI (vyžaduje nainstalovaný DisplayGUI) |
| `/purrlog history <uuid>` | zobrazí celou historii sledované jednotky |
| `/purrlog alerts` | vypíše nevyřešené dupe alerty |
| `/purrlog sweep` | ručně spustí reconciliation sweep |
| `/purrlog reload` | znovu načte `templates.yml` |
| `/purrlog template import <klíč>` | vytvoří šablonu z právě drženého itemu |
| `/purrlog dbtest` | diagnostika — ověří zápis/čtení do DB |

## Architektura (stručně)

- **DB vrstva** — SQLite, asynchronní zápisová fronta s jedním writer vláknem (respektuje SQLite
  single-writer omezení) a fronta pro čtení mimo hlavní vlákno hry.
- **PDC tagging** — `TrackedItemTag`/`TrackedBlockTag`/`TrackedEntityTag` čtou/zapisují UUID do
  persistent data containeru; jednotky ve slučitelných hromádkách nesou seznam UUID místo
  jednoho.
- **Tracking listenery** — pokrývají životní cyklus itemů i bloků (`ItemLifecycleListener`,
  `ContainerListener`, `BlockLifecycleListener`, `ShulkerNestingListener`, ...).
- **Anti-dupe** — `ReconciliationSweepTask` periodicky porovnává fyzický stav u online hráčů
  s databází.
- **Volitelná GUI integrace** — `AdminGuiService` postavené na API pluginu DisplayGUI; funguje
  jako měkká závislost, plugin běží normálně i bez ní.

## Testy

```bash
./gradlew test
```

Pokrývají čistou logiku bez potřeby běžícího serveru (přerozdělování UUID při split/merge
hromádek, parsování `templates.yml`).
