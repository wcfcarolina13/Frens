package net.wcfcarolina13.GameAI.schematic;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtSizeTracker;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Reads Minecraft structure files (.nbt) and converts them to SchematicData.
 * 
 * Structure file format (Java Edition):
 * - size: [x, y, z] dimensions
 * - palette: list of block states
 * - blocks: list of {state: paletteIndex, pos: [x, y, z], nbt: optional}
 */
public final class SchematicReader {

    private static final Logger LOGGER = LoggerFactory.getLogger("schematic-reader");

    private SchematicReader() {}

    /**
     * Load a schematic from a file path.
     */
    public static Optional<SchematicData> loadFromFile(Path path) {
        if (path == null || !Files.exists(path)) {
            LOGGER.warn("Schematic file does not exist: {}", path);
            return Optional.empty();
        }

        try (InputStream is = Files.newInputStream(path)) {
            NbtCompound nbt = NbtIo.readCompressed(is, NbtSizeTracker.ofUnlimitedBytes());
            String name = path.getFileName().toString().replace(".nbt", "");
            return parseNbt(name, nbt);
        } catch (IOException e) {
            LOGGER.error("Failed to read schematic file: {}", path, e);
            return Optional.empty();
        }
    }

    /**
     * Load a schematic from the mod's resources (assets/frens/schematics/).
     */
    public static Optional<SchematicData> loadFromResources(String schematicName) {
        String resourcePath = "/assets/frens/schematics/" + schematicName + ".nbt";
        try (InputStream is = SchematicReader.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                LOGGER.warn("Schematic not found in resources: {}", resourcePath);
                return Optional.empty();
            }
            NbtCompound nbt = NbtIo.readCompressed(is, NbtSizeTracker.ofUnlimitedBytes());
            return parseNbt(schematicName, nbt);
        } catch (IOException e) {
            LOGGER.error("Failed to read schematic from resources: {}", resourcePath, e);
            return Optional.empty();
        }
    }

    /**
     * Parse an NbtCompound into SchematicData.
     */
    public static Optional<SchematicData> parseNbt(String name, NbtCompound nbt) {
        if (nbt == null) {
            return Optional.empty();
        }

        try {
            // Read size
            Optional<NbtList> sizeListOpt = nbt.getList("size");
            if (sizeListOpt.isEmpty() || sizeListOpt.get().size() != 3) {
                LOGGER.warn("Schematic '{}' missing or invalid size tag", name);
                return Optional.empty();
            }
            NbtList sizeList = sizeListOpt.get();
            int sizeX = sizeList.getInt(0).orElse(0);
            int sizeY = sizeList.getInt(1).orElse(0);
            int sizeZ = sizeList.getInt(2).orElse(0);

            // Read palette
            List<BlockState> palette = new ArrayList<>();
            Optional<NbtList> paletteListOpt = nbt.getList("palette");
            if (paletteListOpt.isEmpty() || paletteListOpt.get().isEmpty()) {
                LOGGER.warn("Schematic '{}' missing or empty palette", name);
                return Optional.empty();
            }
            NbtList paletteList = paletteListOpt.get();

            for (int i = 0; i < paletteList.size(); i++) {
                Optional<NbtCompound> blockNbtOpt = paletteList.getCompound(i);
                if (blockNbtOpt.isEmpty()) continue;
                BlockState state = parseBlockState(blockNbtOpt.get());
                palette.add(state);
            }

            // Read blocks
            List<SchematicData.BlockPlacement> blocks = new ArrayList<>();
            Optional<NbtList> blockListOpt = nbt.getList("blocks");
            if (blockListOpt.isEmpty()) {
                LOGGER.warn("Schematic '{}' missing blocks tag", name);
                return Optional.empty();
            }
            NbtList blockList = blockListOpt.get();

            for (int i = 0; i < blockList.size(); i++) {
                Optional<NbtCompound> blockNbtOpt = blockList.getCompound(i);
                if (blockNbtOpt.isEmpty()) continue;
                NbtCompound blockNbt = blockNbtOpt.get();
                
                int paletteIndex = blockNbt.getInt("state").orElse(0);
                Optional<NbtList> posListOpt = blockNbt.getList("pos");
                if (posListOpt.isEmpty() || posListOpt.get().size() != 3) continue;
                NbtList posList = posListOpt.get();

                int x = posList.getInt(0).orElse(0);
                int y = posList.getInt(1).orElse(0);
                int z = posList.getInt(2).orElse(0);

                // Skip air blocks
                if (paletteIndex < palette.size()) {
                    BlockState state = palette.get(paletteIndex);
                    if (state.isAir()) continue;
                }

                blocks.add(new SchematicData.BlockPlacement(new BlockPos(x, y, z), paletteIndex));
            }

            LOGGER.info("Loaded schematic '{}': {}x{}x{} with {} blocks ({} palette entries)",
                    name, sizeX, sizeY, sizeZ, blocks.size(), palette.size());

            return Optional.of(new SchematicData(name, sizeX, sizeY, sizeZ, palette, blocks));

        } catch (Exception e) {
            LOGGER.error("Failed to parse schematic '{}': {}", name, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Parse a block state from NBT.
     * Format: {Name: "minecraft:stone", Properties: {variant: "granite"}}
     */
    private static BlockState parseBlockState(NbtCompound nbt) {
        String blockName = nbt.getString("Name").orElse("");
        if (blockName.isEmpty()) {
            return Blocks.AIR.getDefaultState();
        }

        Identifier id = Identifier.tryParse(blockName);
        if (id == null) {
            LOGGER.warn("Invalid block identifier: {}", blockName);
            return Blocks.AIR.getDefaultState();
        }

        Block block = Registries.BLOCK.get(id);
        if (block == null || block == Blocks.AIR && !blockName.equals("minecraft:air")) {
            LOGGER.warn("Unknown block: {} (might be from a different mod)", blockName);
            return Blocks.AIR.getDefaultState();
        }

        BlockState state = block.getDefaultState();

        // Apply properties if present
        Optional<NbtCompound> propertiesOpt = nbt.getCompound("Properties");
        if (propertiesOpt.isPresent()) {
            NbtCompound properties = propertiesOpt.get();
            for (String key : properties.getKeys()) {
                String value = properties.getString(key).orElse("");
                if (value.isEmpty()) continue;
                Property<?> property = block.getStateManager().getProperty(key);
                if (property != null) {
                    state = applyProperty(state, property, value);
                }
            }
        }

        return state;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockState applyProperty(BlockState state, Property property, String value) {
        Optional<?> parsed = property.parse(value);
        if (parsed.isPresent()) {
            return state.with(property, (Comparable) parsed.get());
        }
        return state;
    }

    /**
     * List all available schematics from the mod's resources.
     */
    public static List<String> listAvailableSchematics() {
        // This is a simplified version - in production you might scan the directory
        // For now, return hardcoded list of bundled schematics
        return List.of(
                "small_hut",
                "watchtower",
                "bridge_5x1"
        );
    }
}
