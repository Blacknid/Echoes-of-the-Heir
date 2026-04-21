# Echoes of the Heir — Full Implementation Plan

> **Genre:** Top-Down 2D Action RPG (Java Swing)  
> **Engine:** Custom — 1280×768, 64px tiles, 60 UPS, double-buffered OpenGL pipeline  
> **Current state:** 3 maps, 2 monster types, 1 NPC template, 12 event types, combo combat, skill tree, quest system, day/night, weather, particle systems

---

## Table of Contents

1. [Story Summary](#1-story-summary)
2. [Memory Fragment System](#2-memory-fragment-system)
3. [World Structure — All Maps](#3-world-structure--all-maps)
4. [NPCs — Full Roster](#4-npcs--full-roster)
5. [Bosses — The Dark Memories](#5-bosses--the-dark-memories)
6. [Monsters — Per-Region](#6-monsters--per-region)
7. [Items & Equipment Progression](#7-items--equipment-progression)
8. [Quest Design — Full Chain](#8-quest-design--full-chain)
9. [Cutscene & Narrative System](#9-cutscene--narrative-system)
10. [Engine Upgrades Required](#10-engine-upgrades-required)
11. [Aesthetic Upgrades](#11-aesthetic-upgrades)
12. [Audio Plan](#12-audio-plan)
13. [Save System Changes](#13-save-system-changes)
14. [UI/UX Changes](#14-uiux-changes)
15. [Implementation Phases & Priority](#15-implementation-phases--priority)

---

## 1. Story Summary

**Backstory:**
A king and queen have a son — Prince Aldren. The queen dies. The grieving king adopts a second child — **you** (the player). For years, both brothers are raised as equals. But Prince Aldren watches the king grow closer to you, the adopted child who reminds him less of his pain. One night, when the king accidentally calls you by the name he'd chosen for his firstborn — a name he never gave Aldren — envy turns to hatred.

Aldren studies forbidden magic. He discovers **The Canvas Curse** — a spell that imprisons a soul inside a painted world, erasing their memory. He casts it at the king. You step in front of it. The curse swallows you. Panicking, Aldren kills the king to silence the only witness. He tells the court both you and the king died in an accident.

**The Game Begins:**
You wake in a painted world — the **Canvas Realm**. You remember nothing. The world is beautiful but wrong — colors bleed at the edges, the sky looks brushed, shadows move in patterns no sun could cast. Other souls are here too, equally lost. None of them remember how they arrived.

Your objective: **recover your memory fragments**, **piece together the truth**, and **find a way to escape the Canvas Realm**.

---

## 2. Memory Fragment System

### 2.1 Core Mechanic
Memory Fragments are the central collectible and narrative device. They are pieces of your shattered past that took physical form when the curse hit. Two types:

| Type | Source | How Obtained | Count |
|------|--------|--------------|-------|
| **Echo Fragments** | NPCs | Talk to them + fulfill a condition (quest, item, level) | ~12–15 |
| **Dark Fragments** | Bosses | Defeat the boss | 4 |
| **Hidden Fragments** | Exploration | Find secret areas, solve puzzles | 4–6 |

**Total: ~20–25 fragments**

### 2.2 Engine Implementation

**New fields in Entity.java:**
```java
// Memory Fragment system
public String memoryFragmentId = null;        // unique fragment ID (e.g., "kings_face")
public String memoryFragmentName = null;      // display name ("His Last Expression")
public String[] memoryFragmentText = null;    // 1–5 lines of flashback text
public boolean memoryFragmentClaimed = false; // already collected
```

**New class: `MemoryJournal.java`**
- Stores all collected fragments in order
- `ArrayList<MemoryFragment>` with inner class: `{id, name, text[], source, timestamp}`
- Method: `addFragment(id)`, `hasFragment(id)`, `getCount()`, `getTotal()`
- Rendered as a new game state: `journalState` — a book-like UI showing collected memories in chronological order of the STORY (not collection order)
- Each fragment has a position in the story timeline — even if collected out of order, it slots into the right page

**New class: `MemoryFlashback.java`**
- Handles the visual effect when a fragment is claimed
- Screen washes to white → sepia-toned vignette → text appears line by line (typewriter) → optional sprite/portrait shown → fade back to game
- Uses existing `transitionAlpha` system from UI.java, extended with sepia color grading

**Tiled integration (MapObjectLoader):**
```
// NPC with a memory fragment
type              = NPC_Alucard
name              = Old Knight
memoryFragmentId  = kings_face
memoryFragmentName = His Last Expression
memoryText0       = A face flashes before your eyes...
memoryText1       = A man with a golden crown, looking at you with warmth.
memoryText2       = You don't know his name. But you know he loved you.
requiredItem      = Knight's Oath     // must have this item to trigger
```

### 2.3 Fragment Collection Flow
1. Player meets NPC → normal dialogue plays
2. Player fulfills condition (quest/item/level/boss defeated)
3. On next speak → NPC says something new + `memoryFragmentClaimed = true`
4. Screen flashes white → `MemoryFlashback` plays (2–4 seconds)
5. Sound effect: unique "memory claimed" chime
6. UI notification: "Memory Fragment obtained: His Last Expression"
7. Fragment appears in the Memory Journal

### 2.4 Fragment Progression Gates
Certain areas/NPCs only become accessible after collecting enough fragments:

| Fragments Required | Unlock |
|---|---|
| 3 | Access to the **Ashen Woods** |
| 7 | Access to the **Painted Citadel** |
| 12 | Access to **The Gallery** (pre-final area) |
| 16+ | Access to **The Frame** (final boss) |

Use the existing `LevelGate` event type, extended with a new variant: `MemoryGate` (checks fragment count instead of level).

---

## 3. World Structure — All Maps

### 3.1 World Map Overview

```
                    ┌─────────────────┐
                    │   THE FRAME     │ (Final area)
                    │  (Boss 4)       │
                    └────────┬────────┘
                             │
                    ┌────────┴────────┐
                    │   THE GALLERY   │ (Pre-final)
                    │  Puzzle area    │
                    └────────┬────────┘
                             │
          ┌──────────────────┼──────────────────┐
          │                  │                  │
┌─────────┴──────┐   ┌───────┴────────┐  ┌──────┴─────────┐
│ PAINTED CITADEL│   │ ASHEN WOODS    │  │ SHATTERED LAKE │
│ (Boss 3)       │   │ (Boss 2)       │  │ (Boss 1)       │
│ 100×100        │   │ 100×100        │  │ 80×80          │
└─────────┬──────┘   └───────┬────────┘  └──────┬─────────┘
          │                  │                  │
          └──────────────────┼──────────────────┘
                             │
                    ┌────────┴────────┐
                    │  CANVAS VILLAGE │ (Hub / start)
                    │  100×100        │
                    └────────┬────────┘
                             │
                    ┌────────┴────────┐
                    │  AWAKENING CAVE │ (Tutorial)
                    │  40×30          │
                    └─────────────────┘
```

### 3.2 Detailed Map Descriptions

---

#### Map 1: Awakening Cave (`awakening_cave.tmx`) — 40×30 tiles
**Purpose:** Tutorial area. Player wakes up here with no memory.

**Tileset:** Dark stone, dripping water, faint glowing crystals, paint streaks on walls.

**Layout:**
- Small cavern — 3 rooms connected by narrow passages
- Room 1: Wake-up point. Glowing crystals. First dialogue trigger: "Where... am I?"
- Room 2: First combat encounter — 2 weak inkblot monsters (tutorial enemies). Teaches movement + attack.
- Room 3: A mirror object on the wall. Player approaches → first memory flash (your own face, but blurred). Tutorial chest with a basic sword.
- Exit: Sunlight visible. Door to Canvas Village.

**Events:**
- `DialogueTrigger` (oneShot): "I can't remember anything... Just fragments... like a dream I can't hold."
- `DialogueTrigger` (oneShot): "This place... it's drawn. The walls look painted."
- `QuestDefinition`: "Escape the Cave" (find the exit)

**NPCs:** None (isolation reinforces amnesia)

**Monsters:** 2–3 `Inkblot` (tutorial slimes, 3 HP, 1 ATK, no AI)

**Music:** Ambient cave drips + faint, melancholic piano (very soft)

**Lighting:** `ambientLight = 0.7` (dark), crystal objects with `lightRadius = 3, lightColor = #6688FF`

---

#### Map 2: Canvas Village (`canvas_village.tmx`) — 100×100 tiles
**Purpose:** Central hub. Return here between every region. NPCs live here and give quests/fragments.

**Tileset:** Idyllic painted village — green meadows, thatched cottages, cobblestone paths, a fountain square, a small market, flower patches. But the sky has visible brushstrokes. The edges of town fade into unfinished canvas (white/beige tiles with sketch lines).

**Layout:**
- **Village Square** (center): Fountain, notice board (quest log access), bench NPCs
- **Cottages** (north cluster): 4–5 houses, some enterable (1-room interiors as separate small maps or map sections)
- **Market Row** (east): Item shop NPC, equipment NPC
- **Chapel** (northwest): Healing pool event, memory journal access, save point
- **Elder's House** (north): Key story NPC lives here
- **South Gate**: Exit to Awakening Cave (locked after first exit — one-way tutorial)
- **East Gate**: Path to Shattered Lake (open from start)
- **West Gate**: Path to Ashen Woods (requires 3 fragments — `MemoryGate`)
- **North Gate**: Path to Painted Citadel (requires 7 fragments — `MemoryGate`)
- **Hidden Cellar** (under market): Secret area with Hidden Fragment

**Music:** Warm, bittersweet orchestral theme — feels safe but melancholic. Changes to a music-box version at night.

**Lighting:** `ambientLight = 0.0` (full daylight). Day/night cycle ON. Night: torchlight from lampposts.

**Weather:** `CLEAR` default. Occasional `RAIN`.

**NPCs (detailed in Section 4):**
- The Elder (Sage)
- The Knight
- The Handmaiden
- The Merchant
- The Child
- The Painter
- The Musician
- The Scholar

**Interactive Objects:**
- 6+ `IT_Pot` scattered around
- 3 `OBJ_Chest` (1 locked, 2 with minor items)
- `OBJ_Torch` lampposts along paths
- Fountain (HealingPool event)
- Notice Board (opens quest log)

---

#### Map 3: Shattered Lake (`shattered_lake.tmx`) — 80×80 tiles
**Purpose:** First real area. Water-themed. Boss 1 lives here.

**Tileset:** Broken shorelines, corrupted water (paint swirling in it), dead reeds, cracked stone bridges, small islands connected by bridges and shallow water you can wade through (slow movement). Waterfalls that pour paint instead of water.

**Layout:**
- **Lakefront** (south): Entry area, campfire save point, 2 NPCs
- **Broken Bridges** (central): Platforming-puzzle-like navigation — some bridges are collapsed, need to find alternate routes
- **Sunken Ruins** (east): Submerged stone structures, treasure chests, monster spawns
- **Paint Falls** (northwest): A waterfall of liquid paint. Hidden cave behind it (Hidden Fragment)
- **The Hollow Throne** (north): Boss arena — an underwater throne room that's risen above the surface, cracked and flooded

**Music:** Haunting water ambience. Distant choral hum. Combat: faster percussion version.

**Lighting:** `ambientLight = 0.15` (slightly dim, twilight feel). Water tiles have enhanced shimmer effect.

**Weather:** Permanent light `RAIN`

**Monsters:**
- `Inkblot` (basic, 5 HP) — everywhere
- `Painted Crab` (melee, 8 HP, armored = 2 DEF) — near water
- `Drowned Sketch` (ranged, 7 HP, shoots ink projectiles) — on islands


**Boss:** The Hollow King (see Section 5)

**NPCs:**
- A fisherman NPC (memory fragment: the king teaching you to fish)
- A lost child NPC (memory fragment: you playing with your brother as a small child)

**Key Items:** `Throne Shard` (quest item, dropped by boss), `Waterwalking Boots` (allow full-speed movement on shallow water tiles)

---

#### Map 4: Ashen Woods (`ashen_woods.tmx`) — 100×100 tiles
**Purpose:** Second region. Forest that's being "undrawn" — trees losing their color, ground fading to blank canvas.

**Tileset:** Autumn/dead forest mix. Trees transition from colored → grayscale → sketch outlines → blank stumps. Ground: dead leaves, exposed canvas, charcoal marks. Fog.

**Layout:**
- **Edge of Color** (south): Entry, still has some color. Campfire save point.
- **The Fading Path** (central): Main trail through the woods. Trees lose color the deeper you go. Fork in the path.
- **Sketch Clearing** (east branch): An area where reality is half-drawn — enemies are sketchy outlines. Hidden Fragment in a hollow tree.
- **The Lullaby Glade** (west branch): A peaceful clearing where a ghostly woman hums (Handmaiden echo). Memory fragment trigger.
- **The Gilded Altar** (north): Boss arena — a golden pedestal in a dead clearing. The altar glows. The boss materializes when approached.

**Music:** Soft woodwind + strings that slowly lose notes as you go deeper (instruments "fade" from the mix the deeper in you go). Boss arena: full orchestral hit.

**Lighting:** `ambientLight = 0.3`. Fog effect (new shader needed — see Section 10). Torches in the woods are dimmer than normal.

**Weather:** Permanent `SNOW` (ash-like particles falling — reskin snow to gray/white)

**Monsters:**
- `Shade Wolf` (fast, 6 HP, 3 ATK, hunts in pairs) — forest paths
- `Canvas Moth` (flies erratically, 4 HP, leaves dust trail that slows player) — clearings
- `Hollow Stump` (stationary trap, 10 HP, grabs player if too close) — near trees

**Boss:** The Gilded Lie (see Section 5)

**NPCs:**
- A woodcutter NPC (fragment: the king giving you a wooden sword for your birthday)
- A hermit NPC (fragment: overhearing your brother whispering to himself at night)

---

#### Map 5: Painted Citadel (`painted_citadel.tmx`) — 100×100 tiles
**Purpose:** Third region. A grand castle that's a corrupted painting of your real home.

**Tileset:** Ornate castle — red carpets, stone walls, stained glass, columns, chandeliers. But everything is slightly wrong: proportions shift, hallways loop, paintings on the walls show distorted scenes from your life. Some rooms are literally half-painted.

**Layout:**
- **Gatehouse** (south): Entry. Drawbridge over a paint moat. Save point.
- **Great Hall** (center): Massive room with long table. 3 exits (east wing, west wing, upper floors).
- **East Wing — Library** (east): Bookshelves, scrolls. Scholar NPC is here. Puzzle: arrange books in correct order to unlock a passage.
- **West Wing — Barracks** (west): Training dummies (combo practice). Armory with mid-tier equipment.
- **Upper Floors** (north): Throne room corridor. Paintings on the walls (click to examine → story text). The throne room door requires 3 `Portrait Keys` found in the wings.
- **Throne Room** (north, final room): Boss arena.
- **Dungeon** (basement, accessed from barracks): Optional area. Tough monsters. Hidden Fragment + rare equipment.

**Music:** Regal but distorted — a waltz that plays at slightly wrong intervals. Boss: intense orchestral.

**Lighting:** Interior: `ambientLight = 0.4`. Chandeliers with warm lights. Stained-glass windows create colored light patches (static colored light sources on specific tiles).

**Monsters:**
- `Painted Guard` (melee, 12 HP, 4 ATK, 2 DEF, shield-bearing — blocks frontal attacks)
- `Portrait Ghost` (ranged, 9 HP, emerges from paintings on the wall, phases in/out)
- `Ink Knight` (heavy melee, 15 HP, 5 ATK, 3 DEF, slow but hits hard)

**Boss:** The Green Shade (see Section 5)

**NPCs:**
- Court Jester (fragment: the night the king called you by the wrong name)
- Imprisoned Knight (fragment: the old knight swearing his oath to protect the royal family)

---

#### Map 6: The Gallery (`the_gallery.tmx`) — 60×60 tiles
**Purpose:** Pre-final area. A surreal space where all your collected memories are displayed as paintings on the wall. Puzzle-focused.

**Tileset:** Museum-like white marble floors, gold frames on walls, but the floor cracks and the frames float. Some areas have no floor — just canvas void.

**Layout:**
- **Entrance Hall**: All collected memory fragments displayed as paintings. Examining them replays the flashback text.
- **The Three Trials**: Three rooms, each requiring a specific fragment to open:
  - Trial of Identity: "Who are you?" — requires the fragment where you learn your name
  - Trial of Truth: "What happened?" — requires the boss fragment showing the king's death
  - Trial of Forgiveness: "What do you choose?" — requires the fragment of your brother as a child
- **The Final Door**: Opens after all 3 trials. Leads to The Frame.

**Music:** Near-silence. Footstep echoes. Occasional piano note.

**Lighting:** Bright white, no shadows. Stark contrast to every other area.

**Monsters:** None. This is a narrative/puzzle space.

---

#### Map 7: The Frame (`the_frame.tmx`) — 40×40 tiles
**Purpose:** Final boss area. The edge of the canvas — where the painted world meets the real world.

**Tileset:** The canvas literally ends here. One half of the arena is the painted world; the other half is a wooden frame with the real world visible through cracks — but blurry, unreachable. The floor transitions from painted grass to raw canvas texture to splintered wood.

**Layout:**
- **Approach**: Short corridor, final save point, final dialogue with The Elder (appears here as an echo)
- **The Frame Arena**: Open boss arena, 20×20 tiles, split down the middle (painted/real)
- **Post-Boss**: The frame cracks. Choice sequence.

**Music:** Starts silent. Builds during boss fight — starts as your brother's theme, gradually adds instruments until it becomes a full tragic orchestral piece.

**Boss:** The Brushstroke (see Section 5)

---

### 3.3 Interior Maps (small, 20×15 each)

| Map ID | Location | Contents |
|--------|----------|----------|
| `elder_house` | Canvas Village | Elder NPC, bookshelf, fireplace, memory journal table |
| `chapel_interior` | Canvas Village | Healing pool, candles, save statue |
| `shop_interior` | Canvas Village | Merchant NPC, item displays |
| `citadel_library` | Painted Citadel | Bookshelves, scholar NPC, book puzzle |
| `citadel_dungeon` | Painted Citadel | Prison cells, rare chest, hidden fragment |

**Total Maps: 12** (7 overworld/dungeon + 5 interiors)

---

## 4. NPCs — Full Roster

### 4.1 Canvas Village NPCs

---

**NPC 1: The Elder (Sage)**
- **Who they really were:** The king's royal advisor
- **Appearance:** Old man with a long beard, hunched, uses a cane. Wears faded robes.
- **Location:** Elder's House, Canvas Village
- **Behavior:** `staticNPC = true, guardMode = true`
- **Role:** Main quest giver. Guides the player. Knows the most but reveals slowly.
- **Dialogue Sets:**
  - Set 0 (first meet): "Ah... another lost soul. I don't remember much, but I know this — we don't belong here."
  - Set 1 (after 3 fragments): "You're starting to remember, aren't you? The more you find, the clearer it gets."
  - Set 2 (after 7 fragments): "I had a dream... a golden throne. A child standing before it. Was that you?"
  - Set 3 (after 12 fragments): "I remember now. I served a king. And he had two sons... one by blood, one by heart."
  - Set 4 (after all fragments): "Go. The Frame is open. Whatever you find there — whatever you choose — know that the truth was worth finding."
- **Memory Fragment:** `"The Advisor's Counsel"` — shows the Elder warning the king about Aldren's jealousy. Requires: 10+ fragments collected.
- **Quests Given:**
  - "Explore the Shattered Lake" (main quest, given at start)
  - "The Fading Woods" (given after Boss 1)
  - "Reclaim the Citadel" (given after Boss 2)
  - "Enter the Gallery" (given after Boss 3)

---

**NPC 2: The Knight**
- **Who they really were:** Captain of the Royal Guard, sworn to protect the king
- **Appearance:** Bulky man in dented, paintless armor. Paces a courtyard.
- **Location:** Canvas Village, near the fountain
- **Behavior:** `guardMode = true`, faces player, paces patrol with step chain between fountain and gate
- **Dialogue Sets:**
  - Set 0: "I swore an oath to someone. I can feel the words on my tongue, but the name... it's gone."
  - Set 1 (after finding `Knight's Oath` item in Shattered Lake): "This crest... I've held this before. I know it."
  - Set 2 (after Boss 1): "I remember a throne room. I stood at the door. Someone screamed."
- **Memory Fragment:** `"The Oath"` — the knight kneeling before the king, swearing to protect the royal family. Triggered by: giving him the `Knight's Oath` item. (`requiredItem = Knight's Oath, requiredItemDialogueSet = 1`)
- **Quest Given:** "Find the Knight's Oath" (item quest in Shattered Lake)

---

**NPC 3: The Handmaiden**
- **Who they really were:** The queen's personal handmaiden
- **Appearance:** Young woman tending a garden. Hums a lullaby.
- **Location:** Canvas Village, flower garden (east side)
- **Behavior:** `staticNPC = true`, tends flowers
- **Dialogue Sets:**
  - Set 0: "This melody... someone taught it to me. A woman with kind eyes."
  - Set 1 (after 5 fragments): "I remember a nursery. A baby in a cradle. The woman was singing this lullaby."
- **Memory Fragment:** `"A Mother's Lullaby"` — the queen holding baby-you, singing. The handmaiden watches from the door. Triggered by: 5+ fragments collected.
- **Quest:** None (passive story NPC)

---

**NPC 4: The Merchant**
- **Who they really were:** A traveling trader who visited the castle
- **Appearance:** Stout man behind a market stall, cheerful
- **Location:** Canvas Village, Market Row
- **Behavior:** `staticNPC = true`
- **Function:** Buy/sell items. Inventory expands as story progresses.
- **Sells:**
  - Act 1: Potions (5g), Keys (10g), Basic Shield (25g)
  - Act 2: Better Potions (15g), Arrows (5g/5), Iron Shield (60g)
  - Act 3: Elixirs (30g), Steel Sword (100g), Knight's Armor (150g)
- **Memory Fragment:** `"A Gift from Afar"` — the merchant visiting the castle, giving you and your brother toy wooden swords. Your brother loved his. Triggered by: buying 10+ items total.

---

**NPC 5: The Child**
- **Who they really were:** An echo of Prince Aldren as a young boy, before envy consumed him
- **Appearance:** Small boy, dark hair, looks scared. Hides behind buildings.
- **Location:** Canvas Village, alley behind the chapel. Runs away if approached too fast.
- **Behavior:** `staticNPC = false, wanderRadius = 2` (fidgets in place). Runs to a hiding spot if player gets within 2 tiles (new AI behavior needed — flee from player when not in dialogue range).
- **Dialogue Sets:**
  - Set 0: "Please don't hurt me! I... I don't know why I'm scared of you."
  - Set 1 (after 8 fragments): "Sometimes I dream about a castle. And a boy who looks like me but older. He's angry."
  - Set 2 (after Boss 3 — The Green Shade): "It was me, wasn't it? The angry boy. I was... I am... your brother."
- **Memory Fragment:** `"Before the Envy"` — you and Aldren playing in the castle courtyard as children, laughing. Triggered by: defeating Boss 3 (The Green Shade).
- **Narrative importance:** Highest. This NPC makes the player empathize with the villain before the final confrontation.

---

**NPC 6: The Painter**
- **Who they really were:** A court painter who painted the royal portraits
- **Appearance:** Paint-stained clothes, carries a palette, stares at the sky
- **Location:** Canvas Village, south side near the easel
- **Behavior:** `staticNPC = true`
- **Dialogue Sets:**
  - Set 0: "The sky is wrong. The brushstrokes are too heavy. Whoever made this world... wasn't an artist. They were desperate."
  - Set 1 (after 6 fragments): "I painted a family once. A king, two princes. The older one insisted I make him taller."
- **Memory Fragment:** `"The Royal Portrait"` — a painting of the full royal family. You're in it, standing next to the king. Aldren is on the other side, slightly turned away. Triggered by: delivering a `Canvas Fragment` item (found in Ashen Woods).
- **Quest Given:** "Bring me a Canvas Fragment" (exploration quest)

---

**NPC 7: The Musician**
- **Who they really were:** A bard who played at court feasts
- **Appearance:** Sits on a bench with a lute, plays soft notes
- **Location:** Canvas Village square, on the bench
- **Behavior:** `staticNPC = true`
- **Function:** Plays different tunes based on story progress (ambient audio change when near him). Optional: a music-box item that lets you replay collected songs.
- **Memory Fragment:** `"The Feast"` — a grand banquet. Music, laughter. The king raises a toast to "both my sons." Aldren doesn't drink. Triggered by: completing all 3 village NPC quests (Knight, Painter, Scholar).

---

**NPC 8: The Scholar**
- **Who they really were:** The royal librarian who cataloged forbidden texts
- **Appearance:** Thin, glasses, carries scrolls
- **Location:** Canvas Village, near the chapel
- **Behavior:** `staticNPC = true`
- **Dialogue Sets:**
  - Set 0: "I keep writing things I don't understand. Names, dates, spell components... Canvas Binding. Soul Thread. Frame Projection."
  - Set 1 (after Citadel Library puzzle): "These are spell notes. Someone was researching how to trap a soul in a painting."
- **Memory Fragment:** `"The Forbidden Research"` — Aldren in the library at night, copying pages from a forbidden book. The scholar catches him but says nothing out of fear. Triggered by: completing the Citadel Library book puzzle.
- **Quest Given:** "The Library Puzzle" (in Painted Citadel — arrange books to reveal the spell notes)

---

### 4.2 Field NPCs (Non-Village)

| NPC | Map | Who They Were | Fragment | Trigger |
|-----|-----|---------------|----------|---------|
| The Fisherman | Shattered Lake | A servant who taught you to fish | "The Quiet Afternoon" — you and the king fishing at the lake | Talk after clearing 5 monsters in the area |
| Lost Child | Shattered Lake | An echo of a castle servant's child who played with you | "The Hide-and-Seek" — you, Aldren, and other children playing in the castle gardens | Talk after finding `Toy Soldier` item nearby |
| Woodcutter | Ashen Woods | A lumberjack who supplied wood to the castle | "The Wooden Sword" — the king carving you a wooden sword for your birthday | Talk + have 6+ fragments |
| Hermit | Ashen Woods | A monk from the castle chapel | "The Night Whispers" — overhearing Aldren talking to himself: "It should have been me. It should always have been me." | Complete the Ashen Woods quest |
| Court Jester | Painted Citadel | The royal entertainer | "A Name That Wasn't Mine" — the king accidentally calling you by Aldren's intended name. Aldren overhears. | Defeat Boss 3 |
| Imprisoned Knight | Painted Citadel Dungeon | A guard who tried to stop Aldren | "The Night of the Curse" — the night Aldren cast the spell. This guard tried to intervene and was cursed too. | Find him in dungeon + have 12+ fragments |

---

## 5. Bosses — The Dark Memories

Each boss is a corrupted memory given physical form. They require new Entity subclasses with custom AI.

---

### Boss 1: The Hollow King
**Memory:** The moment your father was killed.  
**Location:** Shattered Lake — The Hollow Throne  
**Appearance:** A massive figure on a broken throne. Wears a shattered crown. No face — smooth painted surface where eyes should be. Royal robes, but torn and waterlogged.

**Stats:** HP 120, ATK 5, DEF 2, Speed 1

**AI Phases:**
- **Phase 1 (100–60% HP):** Slow melee swings with the royal scepter. Telegraphed — 1-second wind-up, screen shake on impact. Every 3rd attack summons 2 `Inkblot` adds.
- **Phase 2 (60–30% HP):** Stands up from the throne. Faster. New attack: slams ground → shockwave ring expands outward (player must dash through it). Water on floor rises slightly — arena shrinks.
- **Phase 3 (30–0% HP):** Desperate. Crown fragments orbit him as projectiles. Charges at player (3-tile lunge). At 10% HP, stops attacking and reaches toward the player — scripted last moment.

**Death Scene:** Dissolves into paint. Memory flashback: the king on the floor, your brother standing over him. The king's face — not fear, but sadness. He whispers: "Take care of him."

**Fragment:** `"His Last Expression"` — you learn the king's face and name for the first time.

**Drop:** `Throne Shard` (quest item) + `Royal Scepter` (weapon, ATK +4, knockback +3)

---

### Boss 2: The Gilded Lie
**Memory:** The adoption ceremony — the day you became a prince.  
**Location:** Ashen Woods — The Gilded Altar  
**Appearance:** A radiant golden figure of a woman (the queen). Beautiful, warm, motherly — but her eyes are empty gold. She floats above the altar. Gold chains trail from her hands.

**Stats:** HP 100, ATK 4, DEF 1, Speed 2

**AI Phases:**
- **Phase 1 (100–50% HP):** Doesn't attack directly. Speaks soothingly ("You're safe here, child. Don't fight."). Summons golden chains that snake across the ground — touching one roots the player for 2 seconds. Periodically drains 1 HP passively (aura) — player must stay far away.
- **Phase 2 (50–0% HP):** Drops the act. "You were NEVER meant to leave." Now attacks: golden beam (line attack, aims at player, 1-sec telegraph). Chains move faster. The arena shrinks as the edges turn to gold (solid walls closing in). At 20% HP, all chains retract and she opens her arms — final drain attack (3 HP/sec if standing still, must keep moving + attacking).

**Death Scene:** Gold cracks and peels off, revealing sketch underneath — she was never real, just a painted memory. Flashback: the queen on her deathbed, speaking to the king: "Promise me you'll love him as your own, no matter what he does — no matter what either of them does." The king promises.

**Fragment:** `"A Mother's Warning"` — the queen knew Aldren might turn.

**Drop:** `Golden Locket` (accessory, +3 DEF, passive HP regen 1/30s) + `Canvas Fragment` (quest item for Painter NPC)

---

### Boss 3: The Green Shade
**Memory:** The first time Aldren looked at you with pure hatred.  
**Location:** Painted Citadel — Throne Room  
**Appearance:** Starts as a dark silhouette. When the fight begins, it morphs into an exact copy of the player — same sprite, same animations, but with green-tinted palette and glowing green eyes.

**Stats:** HP 150, ATK = player's ATK, DEF = player's DEF - 1, Speed = player's speed

**AI Phases:**
- **Phase 1 (100–60% HP):** Mirrors your moveset. Uses combo attacks identical to yours but with slight delay. Dashes when you dash. This is Aldren's perception of you — an equal he could never surpass.
- **Phase 2 (60–30% HP):** Starts using abilities you DON'T have — teleport behind player, summon shadow clones (2 fake copies that die in 1 hit), projectile barrage. This represents how Aldren *imagined* you — more powerful than reality.
- **Phase 3 (30–0% HP):** Drops all pretense of copying you. Becomes massive (2× sprite scale), feral, uncontrolled — pure rage. Slower but devastating attacks. Green fire trails.

**Death Scene:** The shade shrinks back to normal size, then to child size. It looks up at you. For a moment, it has the face of a little boy — scared, not angry. It dissolves. Flashback: In the castle hallway, you're walking past Aldren's room. The door is ajar. He's crying, holding a letter from the king addressed to "My Sons" — but only your name is circled in the margin.

**Fragment:** `"A Name That Wasn't Mine"` — the inciting incident. The entire tragedy started with one word.

**Drop:** `Shadow Blade` (weapon, ATK +6, critical hit chance) + `Portrait Key: Final` (opens Gallery)

---

### Boss 4: The Brushstroke
**Memory:** The curse itself — the moment you were imprisoned.  
**Location:** The Frame  
**Appearance:** Abstract. A massive swirl of paint and canvas. Brushstrokes slash through the air as attacks. At its core, frozen mid-cast, is a human figure — Aldren, painted in desperate, trembling lines. He's not controlling the curse anymore. The curse is controlling itself.

**Stats:** HP 200, ATK 7, DEF 3, Speed 3

**AI Phases:**
- **Phase 1 (100–70% HP):** Arena attacks. Giant brushstrokes slash across the arena (horizontal/vertical, telegraphed by paint lines appearing 1 second before). Paint pools left behind damage the player. The Brushstroke hovers in the center, vulnerable between attack cycles (5-second windows).
- **Phase 2 (70–40% HP):** The arena itself starts being erased. Tiles at the edges turn to void — standing on them kills instantly. Fight space shrinks to 12×12. The Brushstroke starts moving, chasing the player. Melee swipes + paint projectiles.
- **Phase 3 (40–0% HP):** The Brushstroke fragment splits into two: the curse (hostile) and Aldren's frozen form (neutral, takes no damage). The curse part attacks frenetically. Each hit you land on the curse causes the arena to RESTORE slightly — fighting back literally rebuilds reality. At 0%, the curse shatters.

**Death Scene:** The curse peels away. Aldren's frozen form unfreezes. Full flashback — the entire night in sequence:
1. Aldren preparing the curse in his chambers
2. Aldren confronting the king in the throne room
3. Aldren casting the curse at the king
4. You stepping in front of it
5. The curse consuming you
6. Aldren's face — shock, horror, grief
7. Aldren killing the king out of panic
8. Aldren collapsing, alone

**Fragment:** `"The Whole Truth"` — the final piece. You now know everything.

**Post-Boss:** The Ending Choice sequence (see Section 9).

---

## 6. Monsters — Per-Region

All new monsters use the `DataDrivenMonster` system (JSON config + spritesheet).

### monsters.json additions:

| Monster | Region | HP | ATK | DEF | Speed | AI Profile | Special |
|---------|--------|-----|-----|-----|-------|-----------|---------|
| Inkblot | Tutorial/Shattered Lake | 5 | 1 | 0 | 1 | `melee_chase` | Basic slime. Splits into 2 at death (1 HP each) |
| Painted Crab | Shattered Lake | 8 | 3 | 2 | 1 | `melee_chase` | Frontal armor (takes 50% damage from front) |
| Drowned Sketch | Shattered Lake | 7 | 2 | 0 | 1 | `ranged_archer` | Shoots ink blobs. Phases in/out of water |
| Shade Wolf | Ashen Woods | 6 | 3 | 0 | 3 | `melee_chase` | Fast. Spawns in pairs. Flee at 20% HP |
| Canvas Moth | Ashen Woods | 4 | 1 | 0 | 2 | `ranged_archer` | Dust trail slows player (new debuff needed) |
| Hollow Stump | Ashen Woods | 10 | 4 | 3 | 0 | `stationary_trap` | Stationary. Grabs player at 1 tile range |
| Painted Guard | Painted Citadel | 12 | 4 | 2 | 1 | `melee_chase` | Blocks frontal attacks 50% of the time |
| Portrait Ghost | Painted Citadel | 9 | 3 | 0 | 2 | `ranged_archer` | Emerges from walls, phases in/out |
| Ink Knight | Painted Citadel | 15 | 5 | 3 | 1 | `melee_chase` | Heavy. Charges for 3 tiles. 1-sec stun on hit |

**New AI profiles needed:**
- `stationary_trap` — doesn't move. Attacks when player is within 1 tile. Grabs + holds.
- `pack_hunter` — runs toward player only when another pack member is nearby. Flees solo. (For Shade Wolf)
- `phasing` — periodically becomes transparent/invulnerable for 2 seconds. (For Portrait Ghost, Drowned Sketch)

---

## 7. Items & Equipment Progression

### 7.1 Weapons (ATK progression)

| Weapon | ATK | Knockback | Source | Required |
|--------|-----|-----------|--------|----------|
| Wooden Sword | +1 | 2 | Tutorial chest | — |
| Iron Sword | +2 | 2 | Merchant (Act 2) | 40g |
| Knight's Blade | +3 | 3 | Shattered Lake chest | — |
| Royal Scepter | +4 | 3 | Boss 1 drop | — |
| Shadow Blade | +6 | 4 | Boss 3 drop | — |
TODO : | Painted Edge (final) | +8 | 5 | The Gallery secret | All trials complete |

### 7.2 Shields (DEF progression)

| Shield | DEF | Source |
|--------|-----|--------|
| Wooden Shield | +1 | Starting / Merchant |
| Iron Shield | +2 | Merchant (Act 2) |
| Knight's Shield | +3 | Citadel Barracks |
| Golden Locket | +3 + regen | Boss 2 drop |
| Canvas Guard (final) | +5 | The Gallery secret |

TODO : ### 7.3 Quest Items

| Item | Found In | Purpose |
|------|----------|---------|
| Knight's Oath | Shattered Lake, hidden chest | Give to The Knight NPC → memory fragment |
| Toy Soldier | Shattered Lake, breakable pot | Give to Lost Child NPC → memory fragment |
| Canvas Fragment | Ashen Woods, Sketch Clearing | Give to The Painter NPC → memory fragment |
| Portrait Key: East | Citadel Library (puzzle) | Opens throne room (1 of 3) |
| Portrait Key: West | Citadel Dungeon (combat) | Opens throne room (2 of 3) |
| Portrait Key: Final | Boss 3 drop | Opens throne room (3 of 3) |
| Throne Shard | Boss 1 drop | Proof of defeating the Hollow King |
| Spell Notes | Citadel Library (puzzle + Scholar) | Triggers Scholar's memory fragment |

### 7.4 Consumables

| Item | Effect | Source |
|------|--------|--------|
| Potion | +5 HP | Merchant, drops, pots |
| Greater Potion | +15 HP | Merchant (Act 3), boss areas |
TODO : | Mana Crystal | +3 MP | Drops, chests |
| Elixir | Full HP + MP | Merchant (Act 3), rare drop |
TODO : | Memory Tonic | Reveals nearest uncollected fragment on minimap for 60s | Merchant (Act 2), rare |

---

TODO : ## 8. Quest Design — Full Chain

### 8.1 Main Quest Line

| # | Quest | Giver | Objective | Reward |
|---|-------|-------|-----------|--------|
| 1 | Escape the Cave | Auto | Exit the Awakening Cave | Basic Sword |
| 2 | Speak to the Elder | Auto | Find the Elder in Canvas Village | Quest chain begins |
| 3 | Explore the Shattered Lake | Elder | Reach the Hollow Throne area | Access to Boss 1 |
| 4 | Defeat the Hollow King | Auto | Beat Boss 1 | Fragment + Weapon + 3 fragment gate opens |
| 5 | The Fading Woods | Elder | Investigate the Ashen Woods | Access to Boss 2 |
| 6 | Defeat the Gilded Lie | Auto | Beat Boss 2 | Fragment + Locket + 7 fragment gate opens |
| 7 | Reclaim the Citadel | Elder | Clear the Painted Citadel | Access to Boss 3 |
| 8 | Defeat the Green Shade | Auto | Beat Boss 3 | Fragment + Weapon + Gallery opens |
| 9 | Enter the Gallery | Elder | Complete the 3 trials | Final area unlocks |
| 10 | Face the Truth | Auto | Defeat the Brushstroke | Ending sequence |

### 8.2 Side Quests

| Quest | Giver | Objective | Reward |
|-------|-------|-----------|--------|
| Find the Knight's Oath | The Knight | Find the crest in Shattered Lake | Memory Fragment |
| A Mother's Lullaby | The Handmaiden | Collect 5 fragments | Memory Fragment |
| The Royal Portrait | The Painter | Bring Canvas Fragment from Ashen Woods | Memory Fragment |
| The Library Puzzle | The Scholar | Solve the book puzzle in Citadel | Memory Fragment + Spell Notes |
| The Merchant's Memory | The Merchant | Buy 10 items | Memory Fragment |
| The Feast | The Musician | Complete Knight + Painter + Scholar quests | Memory Fragment |
| Monster Slayer (I-III) | Notice Board | Kill 10/25/50 monsters | Gold + EXP + Potion bundles |
| Pot Smasher | Notice Board | Break 20 pots | Gold |
| Dungeon Delver | Notice Board | Clear the Citadel Dungeon | Rare equipment + Fragment |

---

## 9. Cutscene & Narrative System

### 9.1 Engine Requirements

**Extend `CutsceneManager.java`:**
- Current: Only supports `ending` scene with phases
- Needed: Generic scene system that can play any sequence
- Add: `playScene(String sceneId)` that loads scene data
- Each scene = array of steps: `{type: "dialogue/flashback/fadeBlack/fadeWhite/wait/music/shake/choice", params}`

**New scene types needed:**

| Type | Function |
|------|----------|
| `fadeWhite` | Screen fades to white (for memory flashbacks) |
| `sepia` | Apply sepia + vignette filter (memory shader) |
| `portraitShow` | Display a character portrait (new: 128×128 pixel art next to dialogue) |
| `typeText` | Centered text, typewriter reveal, on black/sepia background |
| `choice` | Present 2–3 options, store result |
| `cameraLock` | Lock camera to a specific position (not following player) |
| `cameraUnlock` | Return camera to player |
| `entityMove` | Move an NPC to a tile during cutscene |
| `entityFace` | Change NPC facing direction |

### 9.2 Key Cutscenes

| Scene | Trigger | Duration | Content |
|-------|---------|----------|---------|
| Awakening | Game start | 30s | White screen → "Where am I?" → camera pans to cave → player appears |
| First Fragment | First memory claimed | 15s | White flash → sepia → text → return |
| Boss 1 Death | Hollow King at 0 HP | 20s | Boss dissolves → flashback sequence → fragment obtained |
| Boss 2 Death | Gilded Lie at 0 HP | 20s | Gold peels away → queen's last words → fragment |
| Boss 3 Death | Green Shade at 0 HP | 25s | Shade shrinks to child → flashback → fragment |
| The Whole Truth | Brushstroke at 0 HP | 60s | Full sequence — 8 flashback panels telling the entire backstory |
| The Choice | After Boss 4 cutscene | 30s | 3-option choice → branching ending |
| Ending A: Confront | Choice: "Break free" | 45s | Frame shatters → real world → Aldren confesses → end |
| Ending B: Sacrifice | Choice: "Stay" | 45s | Player absorbs curse → canvas heals → others escape → end |
| Ending C: Forgive | Choice: "Forgive" | 60s | Knight speaks the oath → curse collapses → everyone free → ambiguous end |

### 9.3 The Ending Choice

After defeating The Brushstroke, the frame cracks. Through the cracks, you see the real world — and Aldren, now older, gaunt, sitting on the throne, consumed by guilt. Three dialogue options appear:

1. **"I'll break free and face him."** → Ending A
   - The frame shatters. You step through. Aldren sees you and collapses. He confesses everything. The curse kills him as payment for breaking. You inherit the kingdom — alone.

2. **"I'll end the curse from here."** → Ending B
   - You absorb the curse into yourself, stabilizing the canvas. Everyone else is freed. You remain, alone in the painted world — but at peace. A letter appears in the real world from you to Aldren: "I forgive you."

3. **"The Knight... he knows the words."** → Ending C (only available if you collected the Knight's fragment)
   - You call the Knight to the frame. He speaks the oath he swore — the oath that predates the curse. Its magic is older than the canvas. The frame dissolves. Everyone is freed. Aldren survives. You stand face-to-face. No words. Credits roll on silence.

---

## 10. Engine Upgrades Required

### 10.1 Critical (Must Have)

| Feature | Current State | What to Add | Files Affected |
|---------|--------------|-------------|----------------|
| **MemoryGate event** | Only `LevelGate` exists | New `MemoryGate` event type (checks fragment count, not level) | `EventHandler.java`, `MapObjectLoader.java` |
| **MemoryJournal system** | Doesn't exist | New class storing collected fragments, new game state to view them | New: `MemoryJournal.java`. Modified: `GamePanel.java`, `UI.java`, `KeyHandler.java` |
| **Memory Flashback visual** | No sepia/memory shader | White fade → sepia tint → centered text → fade back. Reuse `MapShaderManager` pipeline | `MapShaderManager.java`, new: `MemoryFlashback.java` |
| **Boss entity system** | Only `Eye.java` exists (minimal) | `Boss.java` base class with HP phases, arena mechanics, custom AI per boss. Boss HP bar on screen. | New: `Boss.java`, `BOSS_HollowKing.java`, `BOSS_GildedLie.java`, `BOSS_GreenShade.java`, `BOSS_Brushstroke.java`. Modified: `UI.java` (boss HP bar), `GamePanel.java` |
| **More NPC templates** | Only `NPC_Alucard` | Either many subclasses (NPC_Elder, NPC_Knight, etc.) OR make NPC_Alucard fully data-driven from Tiled (preferred — all behavior from properties) | `NPC_Alucard.java` (rename to `NPC_Generic.java`), `MapObjectLoader.java` |
| **Choice dialogue** | Dialogue is linear (no branching) | Add choice support: `dialogueChoice` array, player selects option, result stored in `GameState` | `UI.java`, `Entity.java`, `GameState.java` |
| **Quest item delivery** | `requiredItem` only switches dialogue set | Need: item is consumed on delivery + triggers quest progress + fragment claim | `Entity.java` (new: `requiredItemConsumed` bool) |
| **Save fragment state** | Not saved | Add `memoryFragments[]` to `GameState.java` and `SaveLoad.java` | `GameState.java`, `SaveLoad.java` |

### 10.2 Important (Should Have)

| Feature | What to Add | Files Affected |
|---------|-------------|----------------|
| **Fog shader** | New weather type `FOG` — reduces visibility radius, applies gaussian blur at edges. For Ashen Woods | `MapShaderManager.java`, `EnvironmentManager.java` |
| **Arena walls** | Boss arenas need invisible walls that appear during boss fight (block retreat) | `EventHandler.java` — new `ArenaWall` event |
| **Status effects** | Slow (moth dust), root (chain grab), invulnerability phase (ghosts) | `Entity.java` — new fields: `slowed`, `rooted`, `phasing` |
| **Monster shield/armor** | Directional damage reduction (Painted Guard frontal block, Painted Crab frontal armor) | `Entity.java` — new: `frontalArmor` bool, modify `receiveDamage()` to check attacker direction |
| **Camera lock** | Lock camera to a position for cutscenes | `GamePanel.java` — new: `cameraLockX/Y`, `cameraLocked` |
| **Entity scaling** | Boss phase 3 of Green Shade needs 2× sprite | `Entity.java` — new: `spriteScale` float, used in `draw()` |
| **Tile erasing** | Brushstroke boss erases arena tiles | `TileManager.java` — new: `eraseTile(col, row)` sets tile to void |

### 10.3 Nice to Have

| Feature | What to Add |
|---------|-------------|
| **Character portraits** | 128×128 portraits shown next to dialogue box for key NPCs |
| **Music crossfade** | Transition between tracks smoothly instead of hard cut |
| **Parallax backgrounds** | Distant painted mountains/clouds that scroll slower |
| **Screen wipe transitions** | Paint-brush wipe instead of fade-to-black for map transitions |
| **Dynamic music layers** | Add/remove instrument layers based on story progress (Ashen Woods music) |
| **NPC schedules** | NPCs move to different locations based on time of day |

---

## 11. Aesthetic Upgrades

### 11.1 Visual Theme: "Painted World"

The game's core visual identity is that everything looks **hand-painted** but slightly wrong. Consistency rules:

| Element | Style |
|---------|-------|
| **Sky** | Visible brushstrokes. Not a flat gradient — brush texture overlay |
| **Water** | Paint swirls instead of realistic waves. Existing water shimmer shader works, add color variation |
| **Trees** | Thick painted leaves, visible stroke texture. Current trees work — just need more variety |
| **Ground** | Grass has visible paint texture. Dirt paths look smeared |
| **Edges of reality** | Where the painted world meets raw canvas — white/beige tiles with pencil sketch lines. Use in every map's border |
| **Corruption** | Areas touched by the curse: colors drain (grayscale shader), edges blur, sketch lines show through |

### 11.2 Tileset requirements

| Tileset | For | Tiles Needed |
|---------|-----|-------------|
| Cave/Stone | Awakening Cave | Dark stone walls, floor, crystals, water puddles |
| Village | Canvas Village | Cottages, cobblestone, market stalls, garden flowers, fountain, chapel |
| Lake/Water | Shattered Lake | Broken stone bridges, reeds, paint-water, sunken ruins, waterfall |
| Dead Forest | Ashen Woods | Colored→grayscale→sketch trees (3 states), dead leaves, fog tiles, altar |
| Castle | Painted Citadel | Stone walls, red carpet, columns, chandeliers, bookshelves, prison bars |
| Gallery | The Gallery | White marble, gold frames, void (no floor), trial pedestals |
| The Frame | Final area | Half-painted/half-real split, wooden frame border, canvas texture |
| Canvas edges | All maps (borders) | Raw canvas texture, pencil sketch lines, unfinished art |

### 11.3 Sprite Requirements

**Player:** Already have walking/attacking. Need:
- Idle animation (already have 2-second delay system)
- Death/KO animation
- Cutscene poses (standing still, reaching forward, kneeling)

**NPCs (8 village + 6 field = 14):** Each needs:
- 4-direction walk cycle (3 frames × 4 dirs = 12 frames)
- Idle front-facing (1 frame minimum)
- Portrait (128×128) for dialogue — optional but impactful

**Bosses (4):** Each needs:
- Idle animation (4–8 frames)
- 2–3 attack animations (4–6 frames each)
- Phase transition animation (4 frames)
- Death animation (8–12 frames)
- Estimated: ~40–60 frames per boss

**Monsters (9 new types):** Each needs:
- 4-direction walk (3 frames × 4 dirs = 12)
- Attack animation (3 frames)
- Death animation (3–4 frames, or use existing particle burst)
- Estimated: ~18–20 frames per monster

### 11.4 Particle Effects to Add

| Effect | For | Implementation |
|--------|-----|---------------|
| Paint drips | Corrupted areas — random paint drops fall from above | New `ParticlePreset`: slow fall, random color, small size |
| Pencil lines | Canvas edges — sketch lines flicker in/out | New particle: thin white lines that appear/fade |
| Golden dust | Boss 2 area + Golden Locket equip | Warm gold particles, upward float |
| Green fire | Boss 3 phase 3 trail | Green-tinted spark particles following entity |
| Void particles | The Frame — edges of erased tiles | Dark particles rising from void tiles |
| Memory sparkle | When fragment is being claimed | White + blue sparkles spiraling toward player |

---

## 12. Audio Plan

### 12.1 Music Tracks

| # | Track | Map/Scene | Mood |
|---|-------|-----------|------|
| 0 | Main Theme | Title screen | Melancholic piano + strings |
| 1 | Awakening | Awakening Cave | Ambient drips + soft piano |
| 2 | Canvas Village (Day) | Hub | Warm, bittersweet orchestral |
| 3 | Canvas Village (Night) | Hub at night | Music box version of #2 |
| 4 | Shattered Lake | Exploration | Haunting choral + water ambience |
| 5 | Ashen Woods | Exploration | Woodwind → fading instruments |
| 6 | Painted Citadel | Exploration | Distorted waltz |
| 7 | The Gallery | Puzzle area | Near-silence + footsteps + occasional piano |
| 8 | Boss Battle (generic) | Boss 1 & 2 | Intense orchestral |
| 9 | Green Shade Battle | Boss 3 | Starts as player theme, corrupts |
| 10 | The Brushstroke | Boss 4 | Silence → building → full tragic orchestral |
| 11 | Memory Flashback | Fragment claim | Soft harp + reverb |
| 12 | Victory | Boss defeated | Brief triumphant → melancholic |
| 13 | Ending A | Confront | Bittersweet resolution |
| 14 | Ending B | Sacrifice | Sad but hopeful |
| 15 | Ending C | Forgive | Silent → single violin |

**Current capacity: 30 slots. Needed: 16. Plenty of room.**

### 12.2 Sound Effects

| # | SFX | For |
|---|-----|-----|
| 14 | Memory Claim | Fragment obtained — crystalline chime |
| 15 | Bounce Back | LevelGate/MemoryGate rejection |
| 16 | Boss Roar | Boss encounter start |
| 17 | Boss Phase | Phase transition sound — dramatic hit |
| 18 | Paint Splash | Boss Brushstroke attacks |
| 19 | Chain Rattle | Boss 2 chain attacks |
| 20 | Ghost Phase | Portrait Ghost appearing/disappearing |
| 21 | Book Open | Memory Journal opened |
| 22 | Choice Select | Ending choice confirmation |
| 23 | Curse Break | Final ending — curse shattering |

---

## 13. Save System Changes

### 13.1 GameState.java additions

```java
// Memory system
public String[] collectedFragmentIds;  // array of fragment IDs
public int totalFragmentsCollected;

// Boss tracking
public boolean boss1Defeated;
public boolean boss2Defeated;
public boolean boss3Defeated;
public boolean boss4Defeated;

// Story progress
public int storyAct;           // 0=tutorial, 1=shatterLake, 2=ashenWoods, 3=citadel, 4=gallery, 5=frame
public int endingChosen;       // 0=none, 1=confront, 2=sacrifice, 3=forgive

// NPC interaction state
public String[] claimedNPCFragments;   // which NPCs have given their fragment
public String[] completedSideQuests;
```

### 13.2 Per-map entity persistence
Already exists (`savedObjects/savedNPCs` in `MapManager`). No changes needed — boss defeated states just need to prevent respawn via the boss flags.

---

## 14. UI/UX Changes

### 14.1 New UI Elements

| Element | Screen | Description |
|---------|--------|-------------|
| **Boss HP bar** | Play state, during boss fight | Large bar at top of screen. Boss name. Phase indicator (dots under bar) |
| **Memory Journal button** | Pause menu | Opens the journal — shows collected fragments in story order |
| **Fragment counter** | HUD (below minimap) | Small icon + "12/20" text |
| **Choice dialogue** | Dialogue state | 2–3 options rendered as selectable text below dialogue box |
| **Memory flash overlay** | Triggered state | Sepia screen + centered text + optional portrait |
| **Act title card** | On entering new region | "Act II: The Ashen Woods" — fades in/out over 3 seconds |
| **Boss intro card** | Before boss fight | Boss name + title: "THE HOLLOW KING — Memory of a Father Lost" |

### 14.2 Modified UI Elements

| Element | Change |
|---------|--------|
| **Dialogue box** | Add portrait slot on the left (128×128). Show speaker name more prominently |
| **Quest tracker** | Separate main quests from side quests visually |
| **Minimap** | Add boss icon (skull) and fragment icon (star) for uncollected fragments nearby |
| **Title screen** | Change from "Michi's Adventure" to "Echoes of the Heir". New background art: the painted canvas with a faint royal crest |

---

## 15. Implementation Phases & Priority

### Phase 1: Foundation (Engine Core)
**Goal:** All systems needed before content can be built.

- [ ] `MemoryJournal.java` — fragment tracking class
- [ ] `MemoryFlashback.java` — sepia flashback visual effect
- [ ] `MemoryGate` event type in EventHandler + MapObjectLoader
- [ ] Memory fragment fields on Entity (`memoryFragmentId`, etc.)
- [ ] Memory fragment loading from Tiled properties in MapObjectLoader
- [ ] `journalState` game state + key binding (J key?)
- [ ] Save/Load: fragment IDs + boss flags + story act
- [ ] Choice dialogue system in UI.java
- [ ] Boss HP bar in UI.java
- [ ] Camera lock/unlock for cutscenes

### Phase 2: Content Pipeline
**Goal:** Build the tools to create content fast.

- [ ] Make `NPC_Alucard` fully data-driven (rename to `NPC_Generic`) — all dialogue from Tiled, no hardcoded behavior
- [ ] Add 9 new monster JSON definitions to `monsters.json`
- [ ] Create `Boss.java` base class with phase system
- [ ] New `stationary_trap` and `pack_hunter` AI profiles in DataDrivenMonster
- [ ] Status effects: `slowed`, `rooted`, `phasing` on Entity
- [ ] Frontal armor/shield system on Entity
- [ ] Entity sprite scaling for boss phases

### Phase 3: World Building (Maps)
**Goal:** All maps created in Tiled and functional.

- [ ] `awakening_cave.tmx` — 40×30, tutorial
- [ ] `canvas_village.tmx` — 100×100, hub (replace current `harta.tmx`)
- [ ] `shattered_lake.tmx` — 80×80, water region
- [ ] `ashen_woods.tmx` — 100×100, forest region
- [ ] `painted_citadel.tmx` — 100×100, castle region
- [ ] `the_gallery.tmx` — 60×60, pre-final
- [ ] `the_frame.tmx` — 40×40, final
- [ ] 5 interior maps (20×15 each)
- [ ] Register all maps in MapManager

### Phase 4: Characters & Story
**Goal:** All NPCs, dialogue, quests, and fragments placed.

- [ ] 14 NPCs placed with full dialogue sets (4–5 sets each)
- [ ] 20–25 memory fragments defined with flashback text
- [ ] All fragment trigger conditions set (requiredItem, quest completion, boss defeated, fragment count)
- [ ] 10 main quests registered via QuestDefinition events
- [ ] 9+ side quests registered
- [ ] All item placements (chests, drops, shop inventory)

### Phase 5: Bosses
**Goal:** All 4 bosses implemented and playable.

- [ ] `BOSS_HollowKing.java` — 3-phase AI, arena flood mechanic
- [ ] `BOSS_GildedLie.java` — drain aura, chain attacks, shrinking arena
- [ ] `BOSS_GreenShade.java` — player mirror AI, shadow clones, scaling
- [ ] `BOSS_Brushstroke.java` — arena erasure, paint attacks, dual-target phase 3
- [ ] Boss death cutscenes (extend CutsceneManager)
- [ ] Boss drops wired up

### Phase 6: Cutscenes & Endings
**Goal:** Full narrative experience.

- [ ] Extend CutsceneManager with generic scene system
- [ ] Awakening cutscene
- [ ] 4 boss death cutscenes
- [ ] The Gallery trial sequences
- [ ] The Choice sequence (3 branches)
- [ ] 3 ending cutscenes
- [ ] Credits roll

### Phase 7: Art & Audio
**Goal:** Professional presentation.

- [ ] 8 new tilesets
- [ ] 14 NPC spritesheets (12+ frames each)
- [ ] 4 boss spritesheets (40–60 frames each)
- [ ] 9 monster spritesheets (18–20 frames each)
- [ ] NPC portraits (optional, 14 × 128×128)
- [ ] New title screen art
- [ ] 16 music tracks
- [ ] 10 new SFX
- [ ] Fog shader
- [ ] Paint drip particles
- [ ] Memory sparkle particles

### Phase 8: Polish
**Goal:** Everything feels good.

- [ ] Act title cards
- [ ] Boss intro cards
- [ ] Music crossfade between maps
- [ ] Screen wipe transitions (paint-brush style)
- [ ] Difficulty tuning (HP/ATK/DEF balancing across all content)
- [ ] Playtesting: full story run from awakening to each ending
- [ ] Bug fixing pass

---

*Plan version: 1.0 — "Echoes of the Heir" full implementation roadmap*
*Engine baseline: Java Swing 2D RPG, 1280×768, 64px tiles, 60 UPS*
*Estimated content: 12 maps, 14 NPCs, 4 bosses, 9 monster types, 20–25 memory fragments, 3 endings*
