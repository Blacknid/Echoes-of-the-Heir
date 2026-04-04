# Quest System — Complete Guide

Everything about creating, triggering, progressing, and rewarding quests in Michi's Adventure.

---

## Table of Contents

1. [How It Works](#how-it-works)
2. [quests.json — Defining Quests](#questsjson--defining-quests)
3. [Starting a Quest](#starting-a-quest)
4. [Progressing a Quest](#progressing-a-quest)
5. [Completing & Rewards](#completing--rewards)
6. [Quest Chaining](#quest-chaining)
7. [Removing a Quest](#removing-a-quest)
8. [Using Quests in Tiled](#using-quests-in-tiled)
9. [NPC-Driven Quests in Tiled](#npc-driven-quests-in-tiled)
10. [Full Walkthrough Example](#full-walkthrough-example)
11. [Save & Load](#save--load)
12. [Java API Reference](#java-api-reference)
13. [Quick Decision Guide](#quick-decision-guide)

---

## How It Works

```
quests.json                    defines all quests (id, name, target, rewards, chain)
       ↓
QuestManager                   tracks active quests, progress, completion
       ↓
Triggers (Tiled / Code / NPC)  start and progress quests at runtime
       ↓
Rewards fire automatically     coins, items, fragments, next quest in chain
```

All quest **definitions** live in `ceva/src/res/data/quests.json`.  
All quest **logic** is handled by `QuestManager.java` — you rarely need to touch it.  
All quest **triggers** come from Tiled map events, NPC properties, or simple one-line Java calls.

---

## quests.json — Defining Quests

Location: `ceva/src/res/data/quests.json`

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

1. Open `ceva/src/res/data/quests.json`
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

## NPC-Driven Quests in Tiled

All quest-related NPC properties are set directly on the NPC object in the **NPCs** layer.

### Pattern 1: Progress by talking

The simplest quest interaction — just talk to the NPC.

```
type              = NPC_Generic
name              = Elder Voss
staticNPC         = true
sprite            = /res/npc/Alucard_walking-sheet
dialogue_0_0      = Thank you for coming, traveler.
onSpeakQuestId    = talk_to_elder
onSpeakQuestAmount = 1
```

**Step-by-step in Tiled:**
1. **NPCs** layer → press **P** → click where the NPC should stand
2. Set **Class** = `NPC_Generic`
3. Add properties:
   - `name` (String) = `Elder Voss`
   - `staticNPC` (bool) = `true`
   - `sprite` (String) = `/res/npc/Alucard_walking-sheet`
   - `dialogue_0_0` (String) = `Thank you for coming, traveler.`
   - `onSpeakQuestId` (String) = `talk_to_elder`
   - `onSpeakQuestAmount` (int) = `1`

### Pattern 2: NPC gives item + starts a quest

The NPC hands the player an item and a quest begins.

```
type              = NPC_Generic
name              = Sword Giver
guardMode         = true
sprite            = /res/npc/Alucard_walking-sheet

giftItem          = wooden_sword
giftDialogueSet   = 0
giftQuestId       = sword_giver_bandage
giftQuestName     = Aid the Sword Giver
giftQuestDesc     = A wounded traveler needs a bandage.
giftQuestTarget   = 1

dialogue_0_0      = Take this sword.\nBut I need a bandage in return...
dialogue_1_0      = Have you found a bandage yet?
```

Flow: First talk → player gets `wooden_sword` → quest "Aid the Sword Giver" starts → NPC switches to dialogue set 1.

### Pattern 3: NPC receives item (delivery) + completes quest

Add delivery properties to the same NPC (or a different one):

```
deliveryItem            = bandage
deliveryDialogueSet     = 2
deliveryConsumeItem     = true
deliveryQuestId         = sword_giver_bandage
deliveryQuestAmount     = 1
deliveryPostDialogueSet = 3
deliveryRewardCoins     = 25

dialogue_2_0     = Thank you! You saved me.
dialogue_3_0     = I'm doing much better now.
```

Flow: Player has `bandage` → talks to NPC → bandage consumed → quest progresses → 25 coins → NPC permanently uses dialogue set 3.

### Pattern 4: Full gift-then-delivery cycle (all on one NPC)

Combine patterns 2 and 3 on a single NPC:

```
type                    = NPC_Generic
name                    = Sword Giver
guardMode               = true
sprite                  = /res/npc/Alucard_walking-sheet

giftItem                = wooden_sword
giftDialogueSet         = 0
giftQuestId             = sword_giver_bandage
giftQuestName           = Aid the Sword Giver
giftQuestDesc           = A wounded traveler needs a bandage.
giftQuestTarget         = 1

walkToDialogueSet       = 1

deliveryItem            = bandage
deliveryDialogueSet     = 2
deliveryConsumeItem     = true
deliveryQuestId         = sword_giver_bandage
deliveryPostDialogueSet = 3
deliveryRewardCoins     = 25

dialogue_0_0 = Take this sword. But please, bring me a bandage...
dialogue_1_0 = Have you found a bandage yet?
dialogue_2_0 = Thank you! You saved my life.
dialogue_3_0 = I'm doing much better now. Travel safely.
```

This produces a 4-phase flow:
1. **First talk** → NPC gives sword + quest starts → dialogue 0
2. **Waiting phase** → NPC asks for bandage → dialogue 1
3. **Delivery phase** → bandage consumed + coins rewarded → dialogue 2
4. **Post-quest** → permanent completed dialogue → dialogue 3

### NPC Quest Property Reference

| Property | Type | Description |
|----------|------|-------------|
| `onSpeakQuestId` | String | Quest progressed when player talks to this NPC |
| `onSpeakQuestAmount` | int | Amount added per talk (default `1`) |
| `giftItem` | String | Item given on first interaction (ItemFactory id) |
| `giftDialogueSet` | int | Dialogue set used when giving the item |
| `giftQuestId` | String | Quest started when the gift is given |
| `giftQuestName` | String | Quest display name |
| `giftQuestDesc` | String | Quest description |
| `giftQuestTarget` | int | Quest target |
| `deliveryItem` | String | Item the player must bring back |
| `deliveryDialogueSet` | int | Dialogue set on delivery |
| `deliveryConsumeItem` | bool | Remove the item from inventory |
| `deliveryQuestId` | String | Quest progressed on delivery |
| `deliveryQuestAmount` | int | Amount added on delivery |
| `deliveryPostDialogueSet` | int | Permanent dialogue set after delivery |
| `deliveryRewardCoins` | int | Coin reward on delivery |
| `deliveryRewardItem` | String | Item reward on delivery (ItemFactory id) |
| `deliveryRewardFragmentId` | String | Memory fragment on delivery |

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
- `current` — current progress
- `target` — completion threshold

**Format in save.dat:**
```
quests.size=2
quests.0.id=find_exit
quests.0.name=Find the Exit
quests.0.desc=Retrieve the cave memory fragment
quests.0.current=1
quests.0.target=1
quests.1.id=talk_to_elder
quests.1.name=Talk to the Elder
quests.1.desc=Seek out the village elder
quests.1.current=0
quests.1.target=1
```

On load, `restoreQuest()` is called for each entry — no announcement messages, preserves exact progress.

---

## Java API Reference

### QuestManager methods

| Method | Description |
|--------|-------------|
| `addQuest(String id)` | Start a quest by id (looks up quests.json) |
| `addQuest(String id, String name, String desc, int target)` | Start with explicit values (no JSON lookup) |
| `progress(String id, int amount)` | Add progress. Returns `true` if quest just completed |
| `isComplete(String id)` | Check if a quest is done |
| `hasQuest(String id)` | Check if a quest is currently tracked |
| `removeQuest(String id)` | Remove a quest entirely. Returns `true` if found |
| `clearQuests()` | Remove all quests (used before loading a save) |
| `getQuestStates()` | Get a snapshot of all quests (for save system) |
| `restoreQuest(id, name, desc, target, current)` | Restore from save (no announcement) |
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
| Start quest on game begin | Set `"autoStart": true` in quests.json |
| Start quest on a specific map | `QuestDefinition` event in Tiled |
| Start quest from NPC gift | `giftQuestId` on the NPC in Tiled |
| Start quest from code | `gp.questManager.addQuest("id")` |
| Progress at a location | `QuestTrigger` event in Tiled |
| Progress by talking | `onSpeakQuestId` on NPC in Tiled |
| Progress by item delivery | `deliveryQuestId` on NPC in Tiled |
| Progress from code | `gp.questManager.progress("id", 1)` |
| Give coins on completion | `"rewardCoins": 50` in quests.json |
| Give item on completion | `"rewardItemId": "iron_sword"` in quests.json |
| Give memory fragment | `"rewardFragmentId": "frag_cave"` in quests.json |
| Auto-start next quest | `"chainQuestId": "next_quest"` in quests.json |
| Remove a quest at runtime | `gp.questManager.removeQuest("id")` |
| Check if quest is done | `gp.questManager.isComplete("id")` |
