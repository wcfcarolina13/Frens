package net.shasankp000.GameAI.schematic;

import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Writes SchematicData to Minecraft structure NBT files.
 * 
 * Structure file format (Java Edition):
 * - size: [x, y, z] dimensions
 * - palette: list of block states {Name, Properties}
 * - blocks: list of {state: paletteIndex, pos: [x, y, z]}
 */
public final class SchematicWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger("schematic-writer");

    private SchematicWriter() {}

    /**
     * Write a schematic to an NBT file.
     * 
     * @param schematic The schematic data to write
     * @param outputPath The path to write the .nbt file
     * @return true if successful
     */
    public static boolean writeToFile(SchematicData schematic, Path outputPath) {
        if (schematic == null || outputPath == null) {
            return false;
        }

        try {
            NbtCompound nbt = toNbt(schematic);
            if (nbt == null) {
                return false;
            }

            // Ensure parent directory exists
            Path parent = outputPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            NbtIo.writeCompressed(nbt, outputPath);
            LOGGER.info("Wrote schematic '{}' to {}", schematic.name(), outputPath);
            return true;
        } catch (IOException e) {
            LOGGER.error("Failed to write schematic to {}: {}", outputPath, e.getMessage());
            return false;
        }
    }

    /**
     * Convert SchematicData to an NbtCompound.
     */
    public static NbtCompound toNbt(SchematicData schematic) {
        if (schematic == null) {
            return null;
        }

        NbtCompound root = new NbtCompound();

        // Size array
        NbtList sizeList = new NbtList();
        sizeList.add(net.minecraft.nbt.NbtInt.of(schematic.sizeX()));
        sizeList.add(net.minecraft.nbt.NbtInt.of(schematic.sizeY()));
        sizeList.add(net.minecraft.nbt.NbtInt.of(schematic.sizeZ()));
        root.put("size", sizeList);

        // DataVersion (1.21.x uses around 3953+)
        root.putInt("DataVersion", 3955);

        // Build palette from blocks
        Map<BlockState, Integer> paletteMap = new LinkedHashMap<>();
        List<BlockState> paletteList = new ArrayList<>();
        
        for (SchematicData.BlockPlacement placement : schematic.blocks()) {
            BlockState state = schematic.getState(placement.paletteIndex());
            if (state != null && !paletteMap.containsKey(state)) {
                paletteMap.put(state, paletteList.size());
                paletteList.add(state);
            }
        }

        // Palette NBT
        NbtList paletteNbt = new NbtList();
        for (BlockState state : paletteList) {
            paletteNbt.add(blockStateToNbt(state));
        }
        root.put("palette", paletteNbt);

        // Blocks NBT
        NbtList blocksNbt = new NbtList();
        for (SchematicData.BlockPlacement placement : schematic.blocks()) {
            BlockState state = schematic.getState(placement.paletteIndex());
            if (state == null || state.isAir()) continue;

            Integer newPaletteIndex = paletteMap.get(state);
            if (newPaletteIndex == null) continue;

            NbtCompound blockNbt = new NbtCompound();
            blockNbt.putInt("state", newPaletteIndex);

            NbtList posNbt = new NbtList();
            BlockPos pos = placement.relativePos();
            posNbt.add(net.minecraft.nbt.NbtInt.of(pos.getX()));
            posNbt.add(net.minecraft.nbt.NbtInt.of(pos.getY()));
            posNbt.add(net.minecraft.nbt.NbtInt.of(pos.getZ()));
            blockNbt.put("pos", posNbt);

            blocksNbt.add(blockNbt);
        }
        root.put("blocks", blocksNbt);

        // Empty entities list (required by format)
        root.put("entities", new NbtList());

        return root;
    }

    /**
     * Convert a BlockState to NBT format.
     */
    private static NbtCompound blockStateToNbt(BlockState state) {
        NbtCompound nbt = new NbtCompound();
        
        // Block name
        String blockId = Registries.BLOCK.getId(state.getBlock()).toString();
        nbt.putString("Name", blockId);

        // Properties (if any non-default)
        Collection<Property<?>> properties = state.getProperties();
        if (!properties.isEmpty()) {
            NbtCompound propsNbt = new NbtCompound();
            for (Property<?> property : properties) {
                String valueStr = getPropertyValueString(state, property);
                propsNbt.putString(property.getName(), valueStr);
            }
            if (!propsNbt.isEmpty()) {
                nbt.put("Properties", propsNbt);
            }
        }

        return nbt;
    }

    /**
     * Get the string representation of a property value.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Comparable<T>> String getPropertyValueString(BlockState state, Property<T> property) {
        T value = state.get(property);
        return property.name(value);
    }

    /**
     * Export all built-in schematics to a directory.
     * 
     * @param outputDir The directory to write .nbt files to
     * @return Number of schematics exported
     */
    public static int exportAllBuiltIn(Path outputDir) {
        int count = 0;
        for (String name : SimpleSchematicBuilder.listBuiltIn()) {
            SchematicData schematic = SimpleSchematicBuilder.getBuiltIn(name);
            if (schematic != null) {
                Path outputPath = outputDir.resolve(name + ".nbt");
                if (writeToFile(schematic, outputPath)) {
                    count++;
                }
            }
        }
        LOGGER.info("Exported {} schematics to {}", count, outputDir);
        return count;
    }
}
