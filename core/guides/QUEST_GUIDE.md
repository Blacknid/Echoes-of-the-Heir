# Quest System — Complete Guide

Everything about creating, triggering, progressing, and rewarding quests in Michi's Adventure.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [quests.json — Defining Quests](#questsjson--defining-quests)
3. [Step System — Multi-Step Quests](#step-system--multi-step-quests)
4. [Starting a Quest](#starting-a-quest)
5. [Progressing a Quest](#progressing-a-quest)
6. [Completing & Rewards](#completing--rewards)
7. [Quest Chaining](#quest-chaining)
8. [Removing a Quest](#removing-a-quest)
9. [Using Quests in Tiled](#using-quests-in-tiled)
10. [NPC-Driven Quests](#npc-driven-quests)
11. [Full Walkthrough Example](#full-walkthrough-example)
12. [Save & Load](#save--load)
13. [Java API Reference](#java-api-reference)
14. [Quick Decision Guide](#quick-decision-guide)

---

## How It Works

```
quests.json                    defines all quests (id, name, steps or target, rewards, chain)
       ↓
QuestManager                   tracks active quests, progress, completion
       ↓
Triggers (Tiled / Code / NPC)  start and progress quests at runtime
       ↓
Rewards fire automatically     coins, items, fragments, next quest in chain
```

All quest **definitions** live in `core/assets/res/data/quests.json`.  
All quest **logic** is handled by `QuestManager.java` — you rarely need to touch it.  
QuestManager supports two quest formats:

| Format | Use when | Step tracking |
|--------|----------|---------------|
| **Step quests** (`steps` array) | Quest involves NPC talk/delivery/collect in sequence | Per-step, automatic |
| **Flat quests** (`target` int) | Simple collect/kill/explore counters | `current/target` |

---

## quests.json — Defining Quests

Location: `core/assets/res/data/quests.json`

Each quest is a JSON object in an array:

```json
[
  {
    "id":              "find_exit",
    "name":            "Find the Exit",
    "description":     "Retrieve the cave memory fragment",
    "target":          1,
    "autoStart":       true,
    "rewardCoins":     0,
    "rewardItemId":    "",
    "rewardFragmentId":"",
    "chainQuestId":    ""
  }
]
```

### Field Reference

| Field | Type | Required | Default | Description |
|-------|------|----------|---------|-------------|
| `id` | String | **yes** | — | Unique key. Referenced everywhere (Tiled, code, save files) |
| `name` | String | **yes** | — | Display name shown in the quest log and HUD tracker |
| `description` | String | no | `""` | One-liner shown below the quest name in the log. Use `\n` for line breaks |
| `target` | int | no | `1` | How many `progress()` calls needed to complete the quest |
| `autoStart` | bool | no | `false` | `true` = quest starts automatically when the game begins |
| `rewardCoins` | int | no | `0` | Coins given to the player on completion. `0` = no coin reward |
| `rewardItemId` | String | no | `""` | ItemFactory id (from `items.json`) given on completion. `""` = no item |
| `rewardFragmentId` | String | no | `""` | Memory fragment id added to the journal on completion. `""` = none |
| `chainQuestId` | String | no | `""` | Quest id auto-started when this quest completes. `""` = no chain |

### Adding a New Quest

---

## Step System — Multi-Step Quests

The step system lets you define **exactly what happens at each stage** of a quest, right inside `quests.json`. NPCs don't need Tiled gift/delivery properties — the quest drives everything.

### Step Actions

| Action | What happens | Advances automatically? |
|--------|-------------|------------------------|
| `talk` | NPC plays dialogue. Optionally gives player an item. | Yes — immediately on interaction |
| `deliver` | Player must have a specific item. If missing, plays `failDialogue`. If present, optionally consumes it. | Yes — on successful delivery |
| `collect` | Player must `progress()` the quest N times (pick up items, enter areas). | Via `progress()` calls |
| `kill` | Player must kill N enemies tracked by `progress()` calls. | Via `progress()` calls |
| `go` | Player must reach a location tracked by a `QuestTrigger` event. | Via `progress()` calls |

### Step Fields

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `action` | String | yes | `talk` \| `deliver` \| `collect` \| `kill` \| `go` |
| `npc` | String | for talk/deliver | NPC `objectId` from npcs.json (matches the key, e.g. `"sword_giver_npc"`) |
| `dialogue` | String | no | Named dialogue key to play on success (e.g. `"intro"`, `"thanks"`) |
| `failDialogue` | String | no | Named dialogue key played when player lacks the required item (deliver only) |
| `give` | String | no | ItemFactory id given to the player on this step |
| `item` | String | for deliver | ItemFactory id the player must have |
| `consume` | bool | no | `true` = remove the item from inventory on delivery |
| `count` | int | no | Target count for collect/kill/go steps (default `1`) |
| `description` | String | no | Short text shown in the HUD tracker and quest log for this step |

### Step Quest Example

```json
{
  "id": "sword_giver_bandage",
  "name": "Aid the Wounded Soldier",
  "description": "Help the soldier you found in the cave.",
  "prerequisite": "",
  "steps": [
    {
      "action": "talk",
      "npc": "sword_giver_npc",
      "dialogue": "intro",
      "give": "wooden_sword",
      "description": "Talk to the wounded soldier"
    },
    {
      "action": "deliver",
      "npc": "sword_giver_npc",
      "item": "bandage",
      "consume": true,
      "dialogue": "thanks",
      "failDialogue": "waiting",
      "description": "Bring a bandage to the soldier"
    }
  ],
  "rewardCoins": 25,
  "chainQuestId": "meet_hurt_soldier"
}
```

### Auto-Start on First Talk

When a quest's **first step is `talk`** and the player speaks to that NPC, QuestManager automatically starts the quest (if its prerequisite is met) and executes step 0. You don't need any trigger or code call — just set up the quest and NPC.

### Quest Prerequisites

```json
"prerequisite": "find_exit"
```

`addQuest()` silently ignores the quest if the prerequisite isn't complete. This prevents step-based quests from auto-starting before the player is ready. Leave as `""` for no requirement.

---

1. Open `core/assets/res/data/quests.json`
2. Add a new object to the array:
   ```json
   {
     "id":              "my_new_quest",
     "name":            "My New Quest",
     "description":     "Do something cool",
     "target":          3,
     "autoStart":       false,
     "rewardCoins":     50,
     "rewardItemId":    "iron_sword",
     "rewardFragmentId":"",
     "chainQuestId":    ""
   }
   ```
3. Save the file
4. Sync resources (Ctrl+Shift+B runs Compile which includes Sync Resources)
5. The quest is now available — start it from Tiled or code

### Removing a Quest Definition

Delete the object from the JSON array. If the quest was already active in a save file, it will persist in that save but won't be startable again.

---

## Starting a Quest

There are **4 ways** to start a quest:

| Method | Where | When it starts |
|--------|-------|----------------|
| `"autoStart": true` in quests.json | Automatic | Game begins / QuestManager created |
| `QuestDefinition` event in Tiled | Map load | Player enters the map |
| `giftQuestId` on an NPC in Tiled | NPC interaction | NPC gives an item for the first time |
| `questManager.addQuest("id")` in Java | Code | Whenever you call it |

### autoStart (simplest)

Set `"autoStart": true` in quests.json. The quest appears in the log immediately when the game starts. Use this for the very first quest or tutorial objectives.

### QuestDefinition in Tiled

In the **Events** layer, place a Point or Rectangle:

```
type      = QuestDefinition
questId   = my_new_quest
questName = My New Quest
questDesc = Do something cool
target    = 3
```

The quest registers when the player loads this map. If the id already exists in quests.json, the JSON rewards/chain still apply.

### NPC giftQuestId in Tiled

On an NPC in the **NPCs** layer:

```
giftQuestId     = my_new_quest
giftQuestName   = My New Quest
giftQuestDesc   = Do something cool
giftQuestTarget = 3
```

The quest starts when the NPC gives its `giftItem` to the player for the first time.

### Java code

```java
gp.questManager.addQuest("my_new_quest");        // looks up quests.json by id
// or with explicit values (backward-compatible, no JSON lookup):
gp.questManager.addQuest("my_new_quest", "My New Quest", "Do something cool", 3);
```

---

## Progressing a Quest

There are **4 ways** to add progress to an active quest:

| Method | Where | How it triggers |
|--------|-------|-----------------|
| `QuestTrigger` event in Tiled | Events layer | Player walks into the trigger area |
| `onSpeakQuestId` on an NPC | NPCs layer | Player talks to the NPC |
| `deliveryQuestId` on an NPC | NPCs layer | Player delivers an item to the NPC |
| `questManager.progress("id", amount)` | Java code | Whenever you call it |

### QuestTrigger in Tiled

In the **Events** layer, place a Point or Rectangle:

```
type     = QuestTrigger
questId  = my_new_quest
progress = 1
oneShot  = true
```

- `questId` — must match an active quest
- `progress` — how much to add (usually `1`)
- `oneShot` — `true` = fires once and never again. `false` = fires every time the player enters

**Example — 3 collectible locations:** Place 3 separate QuestTrigger rectangles:
```
Trigger 1:  questId = gather_herbs,  progress = 1,  oneShot = true
Trigger 2:  questId = gather_herbs,  progress = 1,  oneShot = true
Trigger 3:  questId = gather_herbs,  progress = 1,  oneShot = true
```
Player walks over all 3 → quest completes (3/3).

### NPC onSpeakQuestId in Tiled

On an NPC in the **NPCs** layer:

```
onSpeakQuestId    = talk_to_elder
onSpeakQuestAmount = 1
```

Every time the player talks to this NPC, the quest gains +1 progress.

### NPC deliveryQuestId in Tiled

On an NPC in the **NPCs** layer:

```
deliveryItem       = bandage
deliveryQuestId    = sword_giver_bandage
deliveryQuestAmount = 1
deliveryConsumeItem = true
```

When the player talks to the NPC while holding `bandage` in inventory → item consumed → quest gains +1.

### Java code

```java
gp.questManager.progress("my_new_quest", 1);  // adds 1 toward target
```

---

## Completing & Rewards

A quest completes automatically when `current >= target`. You never need to "complete" it manually.

When a quest completes:
1. Message: "Quest complete: My New Quest!" (green text)
2. `rewardCoins` → added to `player.coin` + message shown
3. `rewardItemId` → item created via ItemFactory, placed in inventory
4. `rewardFragmentId` → memory fragment added to the journal
5. `chainQuestId` → next quest auto-starts (see below)

All rewards are defined in quests.json. If the quest was started via `QuestDefinition` in Tiled (not in quests.json), no automatic rewards fire — use NPC delivery rewards instead.

---

## Quest Chaining

Set `chainQuestId` to automatically start another quest when the current one completes.

```json
[
  {
    "id": "find_exit",
    "name": "Find the Exit",
    "description": "Escape the cave",
    "target": 1,
    "autoStart": true,
    "chainQuestId": "talk_to_elder"
  },
  {
    "id": "talk_to_elder",
    "name": "Talk to the Elder",
    "description": "Seek out the village elder",
    "target": 1,
    "autoStart": false,
    "chainQuestId": "gather_herbs"
  },
  {
    "id": "gather_herbs",
    "name": "Gather Herbs",
    "description": "Collect 3 herbs for the elder",
    "target": 3,
    "autoStart": false,
    "rewardCoins": 50,
    "rewardItemId": "iron_sword",
    "chainQuestId": "return_to_elder"
  },
  {
    "id": "return_to_elder",
    "name": "Return to the Elder",
    "description": "Bring the herbs back",
    "target": 1,
    "autoStart": false,
    "rewardCoins": 100,
    "rewardFragmentId": "frag_cave",
    "chainQuestId": ""
  }
]
```

Flow:
```
find_exit (autoStart) → complete → talk_to_elder starts
    → complete → gather_herbs starts
    → collect 3 herbs → complete → 50 coins + iron sword → return_to_elder starts
    → talk to elder → complete → 100 coins + memory fragment → chain ends
```

---

## Removing a Quest

### From a save (at runtime)

```java
gp.questManager.removeQuest("my_new_quest");  // returns true if found
```

### From the game entirely

Delete the entry from `quests.json`. Existing saves that already had the quest will still show it until the player starts a new game.

### Clear all quests (used internally by save/load)

```java
gp.questManager.clearQuests();
```

---

## Using Quests in Tiled

### QuestDefinition — start a quest when a map loads

**Layer:** Events  
**Shape:** Point or Rectangle (position doesn't matter, it triggers on map load)

| Property | Type | Description |
|----------|------|-------------|
| `questId` | String | Must match an id in quests.json (or creates an ad-hoc quest) |
| `questName` | String | Display name |
| `questDesc` | String | Description |
| `target` | int | Completion threshold |

**Step-by-step in Tiled:**
1. Open your `.tmx` map
2. Select the **Events** layer (create one if missing: Layers → right-click → New → Object Layer → name it `Events`)
3. Press **P** (Insert Point) and click anywhere on the map
4. In the Properties panel, set **Class** (or **type**) = `QuestDefinition`
5. Click the **+** button to add custom properties:
   - `questId` (String) = `my_quest`
   - `questName` (String) = `My Quest`
   - `questDesc` (String) = `Do the thing`
   - `target` (int) = `1`
6. Save the map

> **Tip:** If the quest id exists in quests.json with `autoStart: true`, you do NOT need a QuestDefinition — it starts automatically. Use QuestDefinition only for quests that should start on a specific map.

### QuestTrigger — progress a quest when the player walks into an area

**Layer:** Events  
**Shape:** Point or Rectangle (rectangle = area trigger, point = single-tile trigger)

| Property | Type | Description |
|----------|------|-------------|
| `questId` | String | Must match an active quest |
| `progress` | int | How much to add (usually `1`) |
| `oneShot` | bool | `true` = triggers once. `false` = triggers every time |

**Step-by-step in Tiled:**
1. Select the **Events** layer
2. Press **R** (Insert Rectangle) and draw a rectangle over the trigger area
3. Set **Class** = `QuestTrigger`
4. Add custom properties:
   - `questId` (String) = `gather_herbs`
   - `progress` (int) = `1`
   - `oneShot` (bool) = `true`
5. Save the map

**Common uses:**
- "Go to this location" → place a QuestTrigger rectangle at the destination
- "Collect 3 things" → place 3 separate QuestTrigger rectangles at item locations
- "Discover an area" → place a large QuestTrigger rectangle covering the area entrance

---

## NPC-Driven Quests

The **recommended** approach is to define everything in `npcs.json` and `quests.json`. Tiled only needs `type = NPC_Generic` and `npcId = your_npc`.

### How It Works

```
quests.json                 step[0]: talk npc=soldier, dialogue="intro", give=wooden_sword
                            step[1]: deliver npc=soldier, item=bandage, dialogue="thanks"
       ↓
npcs.json (soldier)         dialogues: { "intro": [...], "waiting": [...], "thanks": [...] }
       ↓
NPC_Generic.speak()         calls QuestManager.executeStepForNpc(objectId, this)
       ↓
QuestManager                finds the active step for this NPC, executes action, advances
```

The NPC's `objectId` (set automatically to the `npcId` key) links quest steps to NPC dialogue.

### Named Dialogues

Use string keys in `npcs.json` instead of numbers. The engine resolves them at runtime:

```json
"dialogues": {
  "intro":   ["Take this sword. But please, bring me a bandage."],
  "waiting": ["You're back... but I still need a bandage."],
  "thanks":  ["You found one... good. Thank you."]
}
```

Reference them by name in quest steps:
```json
{ "action": "talk",    "dialogue": "intro"                           },
{ "action": "deliver", "dialogue": "thanks", "failDialogue": "waiting" }
```

Numeric keys (`"0"`, `"1"`) still work for backward compatibility.

### NPC States After Quest Events

Use states in `npcs.json` to change NPC behaviour automatically after quest completion:

```json
"states": [
  {
    "id": "post_quest",
    "dialogue": "done",
    "stationary": true,
    "requiredQuestComplete": "sword_giver_bandage"
  }
]
```

`"dialogue"` is a **named string key**. `"dialogueSet"` (int index) is also supported.

### Tiled Setup (Minimal)

```
type  = NPC_Generic
npcId = sword_giver_npc
```

That's it. All dialogue, quest logic, states, and sprites come from JSON.

### Legacy: NPC Quest Properties in Tiled

Old Tiled-only NPCs (without `npcId`) still support `giftItem`, `deliveryItem`, `giftQuestId`, etc.  
These are **legacy** — prefer the JSON approach for new NPCs.

| Property | Type | Description |
|----------|------|-------------|
| `onSpeakQuestId` | String | Quest progressed when player talks to this NPC |
| `onSpeakQuestAmount` | int | Amount added per talk (default `1`) |
| `giftItem` | String | Item given on first interaction (ItemFactory id) |
| `giftDialogueSet` | int | Dialogue set used when giving the item |
| `giftQuestId` | String | Quest started when the gift is given |
| `deliveryItem` | String | Item the player must bring back |
| `deliveryDialogueSet` | int | Dialogue set on delivery |
| `deliveryConsumeItem` | bool | Remove the item from inventory |
| `deliveryQuestId` | String | Quest progressed on delivery |
| `deliveryPostDialogueSet` | int | Permanent dialogue set after delivery |
| `deliveryRewardCoins` | int | Coin reward on delivery |

See [TILED_ENTITY_GUIDE.md](TILED_ENTITY_GUIDE.md) for the full legacy property list.

---

## Full Walkthrough Example

Here's how to set up the full quest chain from the current `quests.json`:

### Step 1 — quests.json (already done)

```json
[
  { "id": "find_exit",       "autoStart": true,  "target": 1, "chainQuestId": "talk_to_elder" },
  { "id": "talk_to_elder",   "autoStart": false, "target": 1, "chainQuestId": "gather_herbs"  },
  { "id": "gather_herbs",    "autoStart": false, "target": 3, "rewardCoins": 50, "rewardItemId": "iron_sword", "chainQuestId": "return_to_elder" },
  { "id": "return_to_elder", "autoStart": false, "target": 1, "rewardCoins": 100, "rewardFragmentId": "frag_cave", "chainQuestId": "" }
]
```

### Step 2 — Tiled: awakening_cave.tmx

`find_exit` has `autoStart: true` so it starts automatically. Place a trigger at the cave exit:

**Events layer → Rectangle at the exit:**
```
type     = QuestTrigger
questId  = find_exit
progress = 1
oneShot  = true
```

Player reaches exit → `find_exit` completes → `talk_to_elder` auto-starts via chain.

### Step 3 — Tiled: canvas_village.tmx

Place the Elder NPC:

**NPCs layer → Point:**
```
type               = NPC_Generic
name               = Elder Voss
staticNPC          = true
sprite             = /res/npc/Alucard_walking-sheet
dialogue_0_0       = You've escaped the cave!\nI need you to gather 3 herbs.
dialogue_1_0       = Still looking for herbs? Check the forest.
onSpeakQuestId     = talk_to_elder
onSpeakQuestAmount = 1
```

Player talks to Elder → `talk_to_elder` completes → `gather_herbs` auto-starts via chain.

### Step 4 — Tiled: canvas_village.tmx (or another map)

Place 3 herb pickup zones:

**Events layer → 3 Rectangles:**
```
Each one:
  type     = QuestTrigger
  questId  = gather_herbs
  progress = 1
  oneShot  = true
```

Player collects all 3 → `gather_herbs` completes → 50 coins + iron sword → `return_to_elder` auto-starts.

### Step 5 — Tiled: canvas_village.tmx

Place a trigger area around the Elder for the return:

**Events layer → Rectangle near Elder Voss:**
```
type     = QuestTrigger
questId  = return_to_elder
progress = 1
oneShot  = true
```

Player walks back to elder → `return_to_elder` completes → 100 coins + `frag_cave` fragment.

---

## Save & Load

Quest state is fully saved and restored automatically.

**What gets saved per quest:**
- `id` — unique key
- `name` — display name
- `description` — description text
- `current` — current progress (flat quests)
- `target` — completion threshold
- `step` — current step index (step quests; `-1` for flat quests)
- `stepProgress` — progress within the current step (for collect/kill/go steps)

**Format in save.dat:**
```
quests.size=1
quests.0.id=sword_giver_bandage
quests.0.name=Aid the Wounded Soldier
quests.0.desc=Help the soldier you found in the cave.
quests.0.current=0
quests.0.target=1
quests.0.step=1
quests.0.stepProgress=0
```

On load, `restoreQuest()` is called for each entry — no announcement messages, preserves exact step.

---

## Java API Reference

### QuestManager methods

| Method | Description |
|--------|-------------|
| `addQuest(String id)` | Start a quest by id (looks up quests.json, checks prerequisite) |
| `addQuest(String id, String name, String desc, int target)` | Start with explicit values (no JSON lookup, no prerequisite check) |
| `executeStepForNpc(String npcId, Entity npc)` | Execute current quest step for an NPC. Called automatically by `NPC_Generic.speak()`. Returns `true` if a step was handled |
| `progress(String id, int amount)` | Add progress for collect/kill/go steps (or flat quests). Returns `true` if quest just completed |
| `isComplete(String id)` | Check if a quest is done |
| `hasQuest(String id)` | Check if a quest is currently tracked |
| `removeQuest(String id)` | Remove a quest entirely. Returns `true` if found |
| `clearQuests()` | Remove all quests (used before loading a save) |
| `getQuestStates()` | Get a snapshot of all quests (for save system) |
| `restoreQuest(id, name, desc, target, current)` | Restore flat quest from save (no announcement) |
| `restoreQuest(id, name, desc, target, current, step, stepProgress)` | Restore step quest from save |
| `toggleLog()` | Open/close the quest log screen |
| `isLogOpen()` | Check if the log is open |
| `drawTracker(Graphics2D g2)` | Draw the HUD tracker overlay |
| `drawLog(Graphics2D g2)` | Draw the full quest log screen |

### Common code patterns

```java
// Start a quest from quests.json
gp.questManager.addQuest("my_quest");

// Progress from any game event
gp.questManager.progress("my_quest", 1);

// Check completion before unlocking something
if (gp.questManager.isComplete("my_quest")) {
    // unlock a door, change NPC dialogue, etc.
}

// Remove a quest the player abandoned
gp.questManager.removeQuest("my_quest");
```

---

## Quick Decision Guide

| I want to... | Do this |
|---|---|
| Define a quest | Add entry to `quests.json` |
| Create a multi-step NPC quest | Add `"steps": [...]` array in quests.json |
| Start quest on game begin | Set `"autoStart": true` in quests.json |
| Start quest on first NPC talk | First step is `"action": "talk"` with NPC id — auto-starts |
| Block quest until another is done | Set `"prerequisite": "other_quest_id"` in quests.json |
| Start quest on a specific map | `QuestDefinition` event in Tiled |
| Start quest from code | `gp.questManager.addQuest("id")` |
| NPC gives item when talked to | Step with `"action": "talk"`, `"give": "item_id"` |
| NPC receives item from player | Step with `"action": "deliver"`, `"item": "item_id"`, `"consume": true` |
| NPC plays different dialogue if item missing | `"failDialogue": "named_key"` on the deliver step |
| Progress at a location | `QuestTrigger` event in Tiled (for collect/go steps) |
| Progress by talking (old style) | `onSpeakQuestId` on NPC in Tiled (legacy / flat quests) |
| Progress by item delivery (old style) | `deliveryQuestId` on NPC in Tiled (legacy / Tiled NPCs only) |
| Progress from code | `gp.questManager.progress("id", 1)` |
| Give coins on completion | `"rewardCoins": 50` in quests.json |
| Give item on completion | `"rewardItemId": "iron_sword"` in quests.json |
| Give memory fragment | `"rewardFragmentId": "frag_cave"` in quests.json |
| Auto-start next quest | `"chainQuestId": "next_quest"` in quests.json |
| NPC changes state after quest | Add entry to `states` array in npcs.json with `requiredQuestComplete` |
| Check if quest is done | `gp.questManager.isComplete("id")` |
