package net.wcfcarolina13.GameAI.souls;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pure utility-phrase knowledge for functional blocks, keyed by block id path. Categories follow
 * the conventions the game and community actually use: the creative inventory's "Functional
 * Blocks" grouping for storage/workstation/utility blocks, and vanilla's villager job-site
 * (point-of-interest) assignments for profession blocks. Detection of functional blocks is
 * structural (block entity / POI, done at capture) and never depends on this table -- a block
 * missing here is still reported by name, just without a phrase.
 */
final class SoulBlockKnowledge {

    private SoulBlockKnowledge() {
    }

    // 16 (was 6, then 10): field tests 2026-08-24 kept finding singleton kinds (stonecutter,
    // potted plants, campfire) crowded out of a real base room's facility list by multiples.
    private static final int MAX_FACILITY_KINDS = 16;

    /** Exact id-path phrases. Job-site blocks name their villager profession per vanilla POI. */
    private static final Map<String, String> PHRASES = new LinkedHashMap<>();
    static {
        // Storage
        PHRASES.put("chest", "stores items");
        PHRASES.put("trapped_chest", "stores items, emits redstone when opened");
        PHRASES.put("barrel", "stores items; fisherman job site");
        PHRASES.put("ender_chest", "private storage, contents follow the owner");
        // Workstations / villager job sites
        PHRASES.put("crafting_table", "crafting station");
        PHRASES.put("furnace", "smelts ore, cooks food");
        PHRASES.put("blast_furnace", "smelts ores fast; armorer job site");
        PHRASES.put("smoker", "cooks food fast; butcher job site");
        PHRASES.put("brewing_stand", "brews potions; cleric job site");
        PHRASES.put("grindstone", "repairs gear, strips enchantments; weaponsmith job site");
        PHRASES.put("smithing_table", "upgrades gear; toolsmith job site");
        PHRASES.put("stonecutter", "cuts stone precisely; mason job site");
        PHRASES.put("loom", "weaves banner patterns; shepherd job site");
        PHRASES.put("cartography_table", "copies and expands maps; cartographer job site");
        PHRASES.put("fletching_table", "fletcher job site");
        PHRASES.put("lectern", "holds a book for reading; librarian job site");
        PHRASES.put("composter", "turns plant scraps into bone meal; farmer job site");
        PHRASES.put("cauldron", "holds water; leatherworker job site");
        // Utility
        PHRASES.put("enchanting_table", "enchants gear with lapis and XP");
        PHRASES.put("chiseled_bookshelf", "holds books");
        PHRASES.put("bookshelf", "boosts nearby enchanting table");
        PHRASES.put("respawn_anchor", "sets respawn in the Nether");
        PHRASES.put("beacon", "beams area buffs when on a pyramid");
        PHRASES.put("conduit", "underwater area buffs");
        PHRASES.put("bell", "village alarm bell");
        PHRASES.put("campfire", "cooks food slowly, sends smoke signal");
        PHRASES.put("soul_campfire", "soul fire campfire, keeps piglins away");
        PHRASES.put("lodestone", "compass anchor point");
        PHRASES.put("jukebox", "plays music discs");
        PHRASES.put("beehive", "houses bees, yields honey");
        PHRASES.put("bee_nest", "wild bee nest, yields honey");
        PHRASES.put("spawner", "spawns hostile mobs");
        PHRASES.put("trial_spawner", "spawns trial mobs for rewards");
        // Item-moving redstone (these carry block entities)
        PHRASES.put("hopper", "moves items between containers");
        PHRASES.put("dispenser", "shoots items when powered");
        PHRASES.put("dropper", "drops items when powered");
        // Natural / world blocks whose use recipes and tags cannot explain. Phrase-only:
        // these are not facilities (no block entity/POI) and surface via topic retrieval.
        PHRASES.put("pointed_dripstone", "grows into stalactites and stalagmites; drips water or lava into cauldrons; falling spikes hurt");
        PHRASES.put("dripstone_block", "grows pointed dripstone when one hangs beneath");
        PHRASES.put("budding_amethyst", "grows amethyst clusters on its faces; breaks permanently if mined");
        PHRASES.put("amethyst_cluster", "fully grown amethyst, drops shards");
        PHRASES.put("obsidian", "blast-proof; nether portal frame material");
        PHRASES.put("crying_obsidian", "crafts respawn anchors; cannot make portals");
        PHRASES.put("soul_sand", "creates upward bubble columns underwater; slows walking; grows nether wart");
        PHRASES.put("soul_soil", "lights soul fire; makes basalt with lava and blue ice");
        PHRASES.put("magma_block", "hurts to stand on; creates downward bubble columns underwater");
        PHRASES.put("sponge", "soaks up water in a radius; dry in a furnace to reuse");
        PHRASES.put("wet_sponge", "dry it in a furnace to get a usable sponge");
        PHRASES.put("sculk_sensor", "detects vibrations, emits redstone");
        PHRASES.put("sculk_catalyst", "spreads sculk where mobs die nearby");
        PHRASES.put("sculk_shrieker", "shrieks at vibrations; can summon the warden in deep dark");
        PHRASES.put("cobweb", "slows anything moving through it; shears harvest it");
        PHRASES.put("slime_block", "bouncy; sticks to blocks, moved by pistons");
        PHRASES.put("honey_block", "sticky, reduces fall damage, slows movement");
        PHRASES.put("mycelium", "spreads to dirt; mushrooms grow on it in any light");
        PHRASES.put("glowstone", "bright light source from the Nether");
    }

    /** Suffix-family phrases for colored/damaged variants sharing one function. */
    private static final Map<String, String> SUFFIX_PHRASES = new LinkedHashMap<>();
    static {
        SUFFIX_PHRASES.put("_bed", "sleep through the night, sets respawn");
        SUFFIX_PHRASES.put("shulker_box", "portable storage, keeps contents when broken");
        SUFFIX_PHRASES.put("anvil", "repairs and renames gear");
        SUFFIX_PHRASES.put("campfire", "cooks food slowly, sends smoke signal");
    }

    /**
     * Block entities that are decor/text rather than facilities -- reported nowhere, so they
     * cannot crowd real facilities out of the cap.
     */
    private static final List<String> MUNDANE_SUFFIXES =
            List.of("sign", "banner", "_head", "_skull", "decorated_pot");

    static Optional<String> phraseFor(String idPath) {
        String exact = PHRASES.get(idPath);
        if (exact != null) {
            return Optional.of(exact);
        }
        return SUFFIX_PHRASES.entrySet().stream()
                .filter(entry -> idPath.endsWith(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst();
    }

    static boolean isMundane(String idPath) {
        return MUNDANE_SUFFIXES.stream().anyMatch(idPath::endsWith);
    }

    /** Groups raw sightings by name, drops mundane ones, annotates, caps at six kinds. */
    static List<String> digestFacilities(List<SoulTypes.RawFacility> raw) {
        Map<String, long[]> counts = new LinkedHashMap<>();
        Map<String, String> idByName = new LinkedHashMap<>();
        for (SoulTypes.RawFacility facility : raw) {
            if (facility.name().isBlank() || isMundane(facility.idPath())) {
                continue;
            }
            counts.computeIfAbsent(facility.name(), k -> new long[1])[0]++;
            idByName.putIfAbsent(facility.name(), facility.idPath());
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]))
                .limit(MAX_FACILITY_KINDS)
                .map(entry -> {
                    String base = entry.getValue()[0] == 1L
                            ? entry.getKey()
                            : entry.getValue()[0] + "x " + entry.getKey();
                    return phraseFor(idByName.get(entry.getKey()))
                            .map(phrase -> base + " (" + phrase + ")")
                            .orElse(base);
                })
                .toList();
    }
}
