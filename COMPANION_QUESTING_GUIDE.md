# Companion Questing Guide (Survival Recruitment)

This guide explains **how to progress the survival companion questline** and what progression unlocks.

It’s written for players (not mod devs). The rules are **server-authoritative**: the UI can show options, but the server decides what actually works.

---

## TL;DR

- The companion questline has stages **0 → 4**.
- **Stage 4 (“Permanent”)** is the key unlock:
  - The companion becomes a **permanent companion**.
  - **Companion spells/commands** (Come / Summon / Home) stop being rejected with “not a permanent companion yet”.

Separately, *how you access spells* depends on items/nearby blocks:

- **Enchanting Table nearby** → full spells, no cooldown
- **Spellbook token** → full spells anywhere, no cooldown
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

- **Enchanting Table nearby**: full spells (Come / Summon / Home)
- **Spellbook token**: full spells anywhere
- **Eye of Ender**: limited spells (Summon-only) + cooldown

---

## Admin tools (testing / debugging)

The bot inventory Conversation overlay includes Admin tools.

### Operator requirement

Most admin actions are **operator-only** on the server. Even if the client UI shows a button, the server may reject it if you are not an operator.

### Set quest stage

There are admin actions like:

- **Set stage 0/1/2/3/4**

Stage 4 should set the companion to **Permanent** immediately.

### Useful admin checks

- **Admin → Status**: shows recruited/permanent/stage/anchor state for the world.

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
