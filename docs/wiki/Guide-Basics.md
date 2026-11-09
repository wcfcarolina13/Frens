<!-- Generated from src/main/resources/assets/frens/guide/topics.json by ./gradlew wikiExport. Edit that file, not this page. -->

# Basics

[All Guide topics](Guide.md)

## Set up AI companions

Optional: let companions talk back in their own words. Frens works fully without it.

- Everything else in Frens (skills, following, building, scripted lines) works with no AI at all.
- AI chat runs a local model through Ollama on the machine that runs the world (the server machine in multiplayer). Nothing is sent to an online service.
- Open the checklist with the AI setup button at the top right of this guide, or Setup… on the Soul Chat row in Bot Controls.
- It walks you through: install Ollama, download a model (1B ≈4 GB RAM, 3B ≈6 GB, 8B ≈12 GB, plus Minecraft's own memory), turn Soul Chat on, enable each companion, then say hi in chat.
- Soul Chat is the world-wide switch. Each companion also has to be enabled. The checklist shows both.
- In multiplayer only an operator or the host can change the server's AI settings. You can still enable your own companions.
- Voice is optional and can be added last.

**Command:** UI: Guide &gt; AI setup (or /bot soul system on, /bot soul enable &lt;bot&gt;)

**Where:** Guide header: AI setup | Bot Controls: Soul Chat &gt; Setup…

## Talk to Nearby Bots (Group Chat)

Start chat with Bots or All bots instead of naming each companion.

- Type in normal chat, without a slash: Bots, what should we do next?
- All bots, how is everyone doing? works too.
- With Souls and Soul Party enabled, up to four nearby companions with active Soul profiles join the conversation, nearest first.
- They must be within 32 blocks in the same world. Normally only your owned bots qualify. Operators can also address other owners' bots.
- If only one companion qualifies, they receive the message directly. A group conversation does not require every bot to reply.
- Voice input works the same way if your dictation sends the words into normal Minecraft chat. Frens does not provide microphone transcription itself.
- Use Bots or All bots explicitly: Everyone and Guys are not group-address shortcuts.

**Command:** Normal chat: Bots, what should we do next? (no slash)

**Where:** Open Guide: ] | Search: group chat, earshot, or voice

## Stop

Immediately cancels whatever the bot is doing.

- Use this if the bot behavior is wrong or unsafe.
- Stop clears the current task and lets you issue a new one.

**Command:** `/bot stop <bot>`

**Where:** Shortcut: \ (Stop looked-at bot)

## Stand Down

Bot pauses follow + drop-sweep for 60s, then auto-resumes follow.

- Use when you're sniping mobs and don't want the bot running out to grab XP, drops, or chase.
- Different from Stop: Stop clears all state. Stand Down keeps the follow target so the bot rejoins formation when the timer expires.
- Drop-sweep is suppressed for the full 60s, so drops on the ground are left alone.
- Issuing any other command (stop, follow, come) cancels the auto-resume.

**Command:** `/bot standdown <bot>`

**Where:** Shortcut: hold \ → press 1 (Stand Down looked-at bot)

## Resume

Resumes a paused pending task when available.

- If the bot asks whether to resume or stop, Resume continues that saved task.
- If no pending task exists, the command safely does nothing.

**Command:** `/bot resume <bot>`

**Where:** Shortcut: (optional) bind Resume key in controls

## Switch Active Bot (Inventory)

Cycle to another companion from the inventory UI without retyping commands.

- In Bot Inventory, press [ and ] to switch previous/next companion.
- You can also click &lt; / &gt; in the switch chip on the bot stats row or overlay header.
- Switching reuses /bot open &lt;alias&gt;, so normal ownership and permission rules still apply.

**Command:** `/bot open <alias> (or /bot open for last targeted)`

**Where:** In Bot Inventory: [ previous, ] next

## Bot Controls Panel

Configure spawn behavior and per-bot preferences in one place.

- Open from Admin tab: Bot Controls &gt;.
- Spawn modes are Questing and Admin.
- General settings include Global Text Dialogue and Global Voiced Dialogue toggles.
- Also includes gameplay, survival, behavior, and pathfinder toggles.
- Use this as the primary settings hub instead of memorizing many commands.
- See also: Modes topics for progression context.

**Command:** UI-only action (Admin &gt; Bot Controls &gt;)

**Where:** Actions overlay: Admin tab &gt; Bot Controls &gt;

## Fast-Travel Gates

Which items/conditions let the bot fast-travel, and at what speed.

- Tier 2 (1.0×, instant-class): any one of these alone qualifies:
  - Bot holds a lodestone compass bound to a target
  - Bot or owner has an Eye of Ender
  - Bot or owner has a Wizard's Tome
  - Bot or owner is within 6 blocks of an Enchanting Table
  - Both bot and owner hold Ender Pearls
- Surface Tier 1: needs at least one gate (Tier 2 unmet):
  - Map + Compass and destination pixel rendered on one of the bot's maps, OR
  - Smoke signal: destination is inside a labeled base, lit campfire on hay at base centre, bot within 5× base radius
  - Spyglass in bot inventory shaves one tier step
  - 1 gate → 3.0× delay, 2 gates → 2.0× delay
- Underground (stricter):
  - Map + Compass plus torch/lantern → 2.0×
  - Lodestone compass without bound target → 2.0×
  - Smoke signal within 2× base radius → 3.0× (tight range)
  - Otherwise refused, bot walks
- Note: these gates apply to automatic fast-travel (sunset return, sunrise resume, /bot home). The Summon button in the spellbook uses the Eye of Ender (60s cooldown) as its own gate. The Remote Guidance and Chorus Recall spells in Bot Inventory are separate manual spells with their own pearl/chorus costs.
- Chat tells you which gate activated each time fast-travel fires, so you can tune your setup.

**Command:** UI-only

**Where:** See chat when bot departs: '(fast-travel: ...)' tag

## Home & Bases Explained

Two kinds of rows in the Base Manager, and two meanings of [Home].

- Yellow [Base] rows are registered bases, saved locations with a protection radius (r=40). Use 'Set Home' to mark one as the bot's preferred home.
- White rows are lodestone compasses in the bot's inventory. They have no protection. They're just named travel targets.
- [Home] before a yellow name = this base is the bot's preferred home. Used by sunset-return and most 'go home' logic.
- [Home] before a white name = this lodestone compass is the bot's designated home compass. Used by the fast-travel mechanics.
- You can have a preferred home base AND a designated home compass at the same time. They are independent.
- Sunset-return picks the CLOSEST of: last-slept bed, preferred home, or nearest base. If your preferred home is far away and Spawn is closer, the bot may go to Spawn instead.
- To force a specific base on return, either delete the closer alternatives or keep the bot geographically near the preferred home.
- Admins can set who owns a base with /bot base owner &lt;base&gt; &lt;player|server|none&gt;. Put base names with spaces in quotes, for example /bot base owner "My Base" server. Operators and the host only.

**Command:** UI-only

**Where:** Inventory &gt; Bases button
