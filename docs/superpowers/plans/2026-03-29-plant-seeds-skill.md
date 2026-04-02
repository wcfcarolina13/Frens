# Plant Seeds Skill Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Plant Seeds" skill that scans for empty farmland in a 16-block radius and plants any seeds the bot has, plus restructure the Guide menu to show "Farming" as a category with "Build Farm" and "Plant Seeds" sub-items.

**Architecture:** New `PlantSeedsSkill` implements `Skill`, reusing planting patterns from `FarmSkill` (standing spot logic, sneak-to-avoid-trampling, `interactBlock` placement). Guide menu already supports category headers via `GuideRow.isHeader()` — just needs the existing farm topic relabeled and a new topic added under the same category.

**Tech Stack:** Java 21, Fabric 1.21.11, Minecraft `interactBlock` API for seed placement.

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `src/main/java/net/wcfcarolina13/GameAI/skills/impl/PlantSeedsSkill.java` | Create | New skill: scan radius for empty farmland, plant seeds |
| `src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java` | Modify line 66 | Register PlantSeedsSkill |
| `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java` | Modify ~line 5716 | Add "plant" aliases to normalizeSkillName |
| `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java` | Modify ~line 905 | Rename "Farm" to "Build Farm", add "Plant Seeds" topic |

---

## Task 1: Create PlantSeedsSkill

**Files:**
- Create: `src/main/java/net/wcfcarolina13/GameAI/skills/impl/PlantSeedsSkill.java`

- [ ] **Step 1: Create PlantSeedsSkill.java**

The skill scans a 16-block radius for farmland blocks with air above, walks to each, sneaks, and plants seeds via `interactBlock`. Key mechanics borrowed from `FarmSkill.plantSeeds()` (line 1342), `findStandingSpot()` (line 2205), `ensureAnySeedHotbar()` (line 2696), `countSeeds()` (line 2688).

Key design points:
- Scan radius: 16 blocks horizontal, 4 blocks vertical
- Sort empty plots by distance (nearest first) to minimize walking
- Use `MovementService.execute(DIRECT)` for movement (proven reliable per CLAUDE.md)
- Sneak when standing on farmland to avoid trampling
- Check `SkillManager.shouldAbortSkill()` between each plot
- Check `BotTerritoryAuthorizationService.authorizeBlockMutation()` before each placement
- Exit conditions: no seeds left, no empty farmland in range, abort requested

- [ ] **Step 2: Build and verify**

Run: `./gradlew build -x test`
Expected: BUILD SUCCESSFUL

---

## Task 2: Register skill and add aliases

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GameAI/skills/SkillManager.java` line 66
- Modify: `src/main/java/net/wcfcarolina13/Commands/modCommandRegistry.java` ~line 5716

- [ ] **Step 3: Register in SkillManager**

Add after `register(new FarmSkill())` at line 66:
```java
register(new net.wcfcarolina13.GameAI.skills.impl.PlantSeedsSkill());
```

- [ ] **Step 4: Add aliases in normalizeSkillName**

In `modCommandRegistry.java` `normalizeSkillName()` switch block (~line 5716), add:
```java
case "plant_seeds", "planting", "plant-seeds", "plantseed", "plantseeds" -> "plant";
```

- [ ] **Step 5: Build and verify**

Run: `./gradlew build -x test`

---

## Task 3: Restructure Guide menu

**Files:**
- Modify: `src/main/java/net/wcfcarolina13/GraphicalUserInterface/BotGuideScreen.java` ~line 905

- [ ] **Step 6: Rename existing farm topic and add plant topic**

Change the existing farm GuideTopic (line 905-917):
- `title`: "Farm" → "Build Farm"
- `summary`: keep existing

Add new GuideTopic right after it (before the `mine_dirt` topic):
```java
new GuideTopic(
    "plant",
    "Farming",
    "Plant Seeds",
    "Plants seeds on any empty tilled soil nearby.",
    List.of(
        "Scans a 16-block radius for empty farmland and plants available seeds.",
        "Supports wheat, beetroot, melon, pumpkin, potato, and carrot."
    ),
    "bot skill plant " + target,
    "No keybind by default",
    "seed plant crop sow farming"
),
```

Both topics share `category="Farming"` so they appear under the same header.

- [ ] **Step 7: Build and verify**

Run: `./gradlew build -x test`

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: Add Plant Seeds skill with Farming category in Guide menu"
```
