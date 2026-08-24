# Soul Knowledge Graph (v1: recipes + tags + phrases) — Design

**Date:** 2026-08-24 · **Status:** awaiting user review · **Target:** Frens souls pipeline (Java 21, MC 1.21.11, Fabric 0.18.4)

## Goal

Give souls grounded answers to knowledge questions ("can you make torches?", "what do we need
for a bed?") without growing the always-present prompt. Project the game's own runtime data —
registries, tags, recipes — into an in-memory lookup graph, and inject a tiny, deterministic
`RELEVANT KNOWLEDGE` block into the soul prompt only when the current message actually touches
a known topic. No authored dataset beyond the existing `SoulBlockKnowledge` phrases; nothing to
maintain across game versions; modded content included automatically.

## Non-goals (v1)

- Loot-table / mob-drop edges, POI/profession edges (v2).
- Vector/embedding matching (plain name matching first; the SQLite vector DB stays untouched).
- LLM tool-calling round-trips (an 8B can't do them reliably; latency would double).
- Non-soul consumers (CraftingHelper/ToolProvisionService adoption is a later refactor).
- Persistence of any kind — the game rebuilds the source data every launch.

## Architecture

### 1. `GameAI/Knowledge/GameKnowledgeGraph` (new)

Built lazily on first query after server start, cached for the session, rebuilt after datapack
reload (`ServerLifecycleEvents.END_DATA_PACK_RELOAD` sets a dirty flag). Build iterates
`server.getRecipeManager().values()` once (~1–2k entries, milliseconds) and the item registry.

Node key: item id path (`"torch"`). Per node:

- **`CraftEdge`** — one per recipe producing the item: station kind (crafting/smelting/
  smoking/blasting/campfire, from `RecipeType`), and ingredient requirements as a list of
  `IngredientReq(count, List<String> alternativeIdPaths)` derived from
  `Recipe.getIngredientPlacement().getIngredients()` + `Ingredient.getMatchingItems()`
  (identical alternatives merged with summed counts). Output count from the recipe's result
  display. Complex/special recipes (fireworks, dyeing, book cloning) are skipped — no
  meaningful static ingredient list.
- **Tag memberships** — `Registries.ITEM.getEntry(item).streamTags()`, path only
  (`"planks"`, `"beds"`), vanilla namespace prioritized, capped (8) per node.
- **Utility phrase** — existing `SoulBlockKnowledge.phraseFor` (annotation layer, unchanged).

**Name index** for matching: lowercase display name → id path, plus id path with underscores as
spaces. Built with the graph.

Threading: build runs on the calling thread behind a synchronized lazy holder; queries are map
reads on immutable structures, safe from any thread. Souls call it from their existing
capture/assembly path.

### 2. Plain-data projection (testability seam)

The graph's Minecraft-facing build produces plain records (`CraftEdge`, `IngredientReq`,
name-index maps). All matching/diffing/rendering logic below operates only on those records and
`String`/`Map` inputs, unit-tested without game classes — same pattern as
`SoulItemDescriber`/`SoulBlockKnowledge`.

### 3. `SoulKnowledgeRetriever` (souls package, pure)

Inputs: the player's message, the bot's carried item counts, and the id paths of nearby
facilities (for station availability). Output: at most 4 fact lines, ~90 chars each.

- **Topic matching:** lowercase the message, match name-index entries longest-name-first with
  word-boundary containment; cap at 2 topics. No match → empty result → prompt unchanged.
- **Per topic, emit:**
  - Craftability: pick the topic's best `CraftEdge` (prefer a station the bot can see or
    crafting table, else first), diff requirements against carried counts →
    `"Torch: craft 4 at crafting table from 1 stick (have 4) + 1 coal or charcoal (MISSING)"`.
  - If the topic item is carried: current count. If it has a utility phrase: append it.
  - Tag line only when it adds signal (topic has no recipe), e.g. `"Oak Log is a log (fuel,
    craftable into planks)"` — rendered from tags + the planks edge.
- **Budget:** hard cap ~380 chars total; truncate whole lines, never mid-line.

### 4. Capture + prompt wiring

- `SoulTypes.BotSnapshot` gains `Map<String, Integer> itemCounts` (id path → total across the
  36 main slots), captured in the same loop that already builds `ItemFacts`. Old constructor
  delegates with an empty map. Not rendered directly — retriever input only.
- `SituationSnapshot` gains `List<String> facilityIds` — the deduped raw id paths the
  facilities scan already sees, captured alongside the described `facilities` lines (old
  constructor delegates with empty; not rendered — retriever input only).
- Retrieval runs in `SoulConversationService` (which holds the message, the snapshot, and can
  query the graph) and its result is passed to `SoulPromptAssembler.assemble` as a new
  `List<String> relevantKnowledge` argument — the assembler stays a pure function of its
  arguments. It renders the lines as a `RELEVANT KNOWLEDGE` SYSTEM message inserted after
  witnessed events, before `PRESENT MOMENT`; omitted when the list is empty, so every existing
  prompt shape and test stays valid. The system contract line "The AUTHORITATIVE STATE message
  ... OVERRIDES" already covers precedence; the block states facts, not actions.

## Error handling

Graph build failure (any throwable) logs once and yields an empty graph — retrieval returns
nothing, souls behave exactly as today. Retrieval never throws: unknown ids, empty inventories,
and unmatched messages all produce empty output. The reload hook only flips the dirty flag.

## Testing

- Pure: name-index matching (longest-first, word boundaries, cap 2), ingredient diff
  (have/missing, alternatives, counts), line rendering + char budget, retriever end-to-end on
  synthetic graph records. Prompt assembly: block present when facts exist, absent otherwise,
  position between events and PRESENT MOMENT.
- Capture layer stays thin and field-verified (existing convention); graph build verified by
  compile + one in-game `[souls]` log line noting node/edge counts at first build.

## Rollout

Own feature branch/worktree; TDD; `mod_version` bump + deploy per repo policy; field test =
ask Jake craftability questions with and without ingredients present.
