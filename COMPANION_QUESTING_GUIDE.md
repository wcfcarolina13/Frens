# Companion Questing Guide (Survival Recruitment)

This guide explains **how to progress the survival companion questline** and what progression unlocks.

It’s written for players (not mod devs). The rules are **server-authoritative**: the UI can show options, but the server decides what actually works.

---

## TL;DR

- The companion questline has stages **0 → 4**.
- **Stage 4 (“Permanent”)** is the key unlock:
  - The companion becomes a **permanent companion**.
  - **Companion spells/commands** (Regroup / Summon / Home) stop being rejected with “not a permanent companion yet”.

Separately, *how you access spells* depends on items/nearby blocks:

- **Enchanting Table nearby** → full spells, no cooldown
- **Spellbook token** → full spells anywhere, no cooldown
- **Goat Horn** → *limited* access (Regroup-only)
- **Eye of Ender** → *limited* spells (Summon-only), with a cooldown

> Note: Right now there isn’t “per-spell progression”; the limitation is based on access method (Eye vs full).

---

## Preconditions (things that must be true)

Spells and quest progression only apply when:

- **Survival recruitment mode** is enabled for the world
- A companion has been **recruited** in this world

If you haven’t recruited yet, the companion questline isn’t active.

---

## The anchor: where the settlement is evaluated

Most quest checks are evaluated around an **anchor position**.

- By default, the system falls back to your current position.
- You can explicitly set the anchor using the dialogue topic:
  - **“Set this as our home”** (or similar wording)

### Important caveat

Moving the anchor effectively changes what “the settlement” means. If you’re not permanent yet, moving the anchor may reset progress.

---

## Stages and requirements (0 → 4)

These are the current stage names and goals:

### Stage 0 — Shelter

Goal: basic “this could be a home” requirements.

- At least **2 beds** near the anchor
- At least **1 storage block** near the anchor (chest or barrel)

### Stage 1 — Lighting

Goal: stop the settlement from being a deathtrap at night.

- Improve lighting coverage near the anchor

(The system samples lighting and requires a minimum coverage ratio.)

### Stage 2 — Meeting Point

Goal: give the place a center and signs of life.

- A **bell** near the anchor
- Plus either:
  - **2+ villagers** nearby, or
  - **3+ beds** nearby

### Stage 3 — Perimeter

Goal: build a defensible boundary.

- A perimeter ring of fences/walls/gates around the settlement edge
- Needs **24+** perimeter blocks on the ring

### Stage 4 — Permanent

This is the final stage.

When you reach Stage 4, the system sets:

- `permanentCompanion = true`

This is what unlocks server-side companion spells/commands.

---

## How to advance stages (normal play)

Use the companion dialogue topics that perform a “check” and advance if you’ve met requirements.

Typical flow:

1. **Companion status** — shows current stage and what it sees
2. **Village missing** — lists what’s missing for the current stage
3. **Village projects / Check progress** — performs the advancement attempt

---

## Spells: what they are, what unlocks them

There are two layers:

### 1) Spell *unlock* (quest progression)

- Spells become usable when the companion is **Permanent**.
- That is **Stage 4**.

If the server says:

> “They’re not a permanent companion yet.”

…then you have not actually reached Stage 4 permanent status (or the server state didn’t update).

### 2) Spell *access method* (items / proximity)

Even with a permanent companion, you still need an access method:

- **Enchanting Table nearby**: full spells (Regroup / Summon / Home)
- **Spellbook token**: full spells anywhere
- **Goat Horn**: limited access (Regroup-only)
- **Eye of Ender**: limited spells (Summon-only) + cooldown

> Note: the older `/bot come` wording is still supported as a legacy alias, but current UI copy increasingly prefers **Regroup** because it better describes the intent.

---

## Admin tools (testing / debugging)

The bot inventory Conversation overlay includes Admin tools.

### Operator requirement

Most admin actions are **operator-only** on the server. The client now hides most admin-only topics for guests, but server checks still remain authoritative.

### Set quest stage

There are admin actions like:

- **Set stage 0/1/2/3/4**

Stage 4 should set the companion to **Permanent** immediately.

### Useful admin checks

- **Admin → Status**: shows recruited/permanent/stage/anchor state for the world.

### Learning Mode (what that admin area is)

The admin overlay also includes **Learning** actions such as:

- **Learning Status**
- **Learning Start**
- **Learning Stop (Success / Failure / Abort)**

These are **operator-only tuning tools**, not quest progression steps.

What they do:

- record demonstration traces from a player session
- capture movement / camera / interaction context for later analysis
- support bot-control tuning and future ML / optional LLM roadmap work

What they do **not** do:

- they do not advance companion quest stages
- they do not unlock spells by themselves
- they are not required for normal survival progression

If you just want normal companion gameplay, you can ignore the Learning section entirely.

---

## Multi-bot inventory switching (QoL)

If you have multiple companions, you can switch between them quickly from the inventory/actions UI:

- Open a companion inventory (`/bot open <alias>` or right-click companion).
- In that screen, press `[` / `]` for previous/next companion.
- Or click `<` / `>` in the switch chip (stats row / expanded overlay header).

Notes:

- Switching uses `/bot open <alias>` behind the scenes.
- Server-side permission checks still apply (ownership/operator rules).
- If there is no other available companion target, the UI now shows a short hint instead of silently doing nothing.

---

## Protected Zone Governance (Quick Tips)

If your settlement keeps getting accidentally modified (or you want shared ownership rules), use protected zones.

### Core zone commands

- `/bot zone protect <radius> [label]`
  - Creates a protected zone centered on the block you are looking at.
- `/bot zone list`
  - Shows zone center, radius, owner, access mode, and permit count.
- `/bot zone remove <label>`
  - Removes a zone (owner or operator).

### Access control commands

- `/bot zone mode <label> <owner_only|allowlist|public>`
- `/bot zone permit <label> <owner>`
- `/bot zone revoke <label> <owner>`

`<owner>` can be a player name, UUID, or a bot alias owner.

### Mapped villages vs protected zones

If you do not want to build a fort wall but still want bots to leave a settlement alone, use **Map Village** in the Base Manager.

- A mapped village saves the settlement perimeter as a shared world-level no-go zone.
- It is meant for cultural/settlement preservation, not ownership or permission management.
- Bots use mapped villages to avoid destructive gathering inside the village footprint, including mining, woodcutting, dirt collection, several ambient forage actions, and passive-mob hunting.
- Use protected zones when you need ownership, permits, or explicit access control instead of a simple shared exclusion area.

### Mode behavior

- `owner_only`: only the zone owner can authorize bot edits inside the zone.
- `allowlist`: owner plus explicitly permitted owners.
- `public`: everyone can authorize bot edits.

> Tip: for multiplayer co-op bases, `allowlist` + explicit `permit` entries is the safest default.

---

## Troubleshooting

### “Spells menu opens but summon says not permanent companion”

This means the *client UI opened*, but the **server** still has `permanentCompanion=false`.

Fixes:

- Use **Admin → Status** and confirm:
  - stage is 4
  - permanent is true
- Use **Admin → Set stage 4** (operator-only)
- Make sure you are running the updated mod build on both client and server (singleplayer = integrated server still counts)

### “I set stage 4 but it didn’t stick”

- Confirm you’re an operator.
- Confirm you’re modifying the correct world’s survival recruitment state (progression is **per-world**).

---

## Notes for future expansion

If/when we add per-spell progression (“all spells you’ve unlocked”), this guide will gain an additional section describing spell unlock tiers.
