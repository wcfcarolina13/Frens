package net.wcfcarolina13.Commands;

import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.wcfcarolina13.GameAI.schematic.SchematicWriter;
import net.wcfcarolina13.GameAI.schematic.SimpleSchematicBuilder;
import net.wcfcarolina13.GameAI.schematic.SchematicData;

import java.nio.file.Path;
import java.nio.file.Paths;

final class BotSkillCommands {

    private BotSkillCommands() {}

    static ArgumentBuilder<ServerCommandSource, ?> buildSkill() {
        return CommandManager.literal("skill")
                .then(CommandManager.argument("skill_name", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeSkillTargets(context,
                                StringArgumentType.getString(context, "skill_name"),
                                null))
                        .then(CommandManager.argument("arguments", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context,
                                        StringArgumentType.getString(context, "skill_name"),
                                        StringArgumentType.getString(context, "arguments"))))
                );
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildFish() {
        return CommandManager.literal("fish")
                .executes(context -> modCommandRegistry.executeSkillTargets(context, "fish", null))
                .then(CommandManager.argument("arguments", StringArgumentType.greedyString())
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "fish",
                                StringArgumentType.getString(context, "arguments"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildFlare() {
        return CommandManager.literal("flare")
                .executes(context -> modCommandRegistry.executeSkillTargets(context, "flare", null));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildShelter() {
        return CommandManager.literal("shelter")
                .then(CommandManager.literal("hovel")
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter", "hovel"))
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        "hovel " + StringArgumentType.getString(context, "options"))))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        StringArgumentType.getString(context, "target") + " hovel"))
                                .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                                StringArgumentType.getString(context, "target") + " hovel "
                                                        + StringArgumentType.getString(context, "options"))))
                        )
                )
                .then(CommandManager.literal("hovel2")
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter", "hovel2"))
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        "hovel2 " + StringArgumentType.getString(context, "options"))))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        StringArgumentType.getString(context, "target") + " hovel2"))
                                .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                                StringArgumentType.getString(context, "target") + " hovel2 "
                                                        + StringArgumentType.getString(context, "options"))))
                        )
                )
                .then(CommandManager.literal("burrow")
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter", "burrow"))
                        .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        "burrow " + StringArgumentType.getString(context, "options"))))
                        .then(CommandManager.argument("target", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                        StringArgumentType.getString(context, "target") + " burrow"))
                                .then(CommandManager.argument("options", StringArgumentType.greedyString())
                                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "shelter",
                                                StringArgumentType.getString(context, "target") + " burrow "
                                                        + StringArgumentType.getString(context, "options"))))
                        )
                );
    }

    /**
     * Build command for schematic/blueprint building.
     * Usage: /bot build <schematic_name>
     *        /bot build list
     *        /bot build preview <schematic_name>
     *        /bot build export [schematic_name]
     */
    static ArgumentBuilder<ServerCommandSource, ?> buildBuild() {
        return CommandManager.literal("build")
                .executes(context -> modCommandRegistry.executeSkillTargets(context, "build", null))
                .then(CommandManager.literal("list")
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "build", "list")))
                .then(CommandManager.literal("preview")
                        .then(CommandManager.argument("schematic", StringArgumentType.string())
                                .executes(context -> modCommandRegistry.executeSkillTargets(context, "build",
                                        "preview " + StringArgumentType.getString(context, "schematic")))))
                .then(CommandManager.literal("export")
                        .executes(context -> exportAllSchematics(context.getSource()))
                        .then(CommandManager.argument("schematic", StringArgumentType.string())
                                .executes(context -> exportSchematic(context.getSource(), 
                                        StringArgumentType.getString(context, "schematic")))))
                .then(CommandManager.argument("schematic", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "build",
                                StringArgumentType.getString(context, "schematic"))));
    }

    /**
     * Export a single schematic to NBT file.
     */
    private static int exportSchematic(ServerCommandSource source, String schematicName) {
        SchematicData schematic = SimpleSchematicBuilder.getBuiltIn(schematicName);
        if (schematic == null) {
            source.sendError(Text.literal("Unknown schematic: " + schematicName));
            return 0;
        }

        Path outputDir = Paths.get("schematics");
        Path outputPath = outputDir.resolve(schematicName + ".nbt");
        
        boolean success = SchematicWriter.writeToFile(schematic, outputPath);
        if (success) {
            source.sendFeedback(() -> Text.literal("§aExported schematic to " + outputPath), false);
            return 1;
        } else {
            source.sendError(Text.literal("Failed to export schematic"));
            return 0;
        }
    }

    /**
     * Export all built-in schematics to NBT files.
     */
    private static int exportAllSchematics(ServerCommandSource source) {
        Path outputDir = Paths.get("schematics");
        int count = SchematicWriter.exportAllBuiltIn(outputDir);
        
        if (count > 0) {
            source.sendFeedback(() -> Text.literal("§aExported " + count + " schematics to " + outputDir), false);
            return 1;
        } else {
            source.sendError(Text.literal("Failed to export schematics"));
            return 0;
        }
    }

    /**
     * Leash command for tying leashed animals to fences.
     * Usage: /bot leash
     *        /bot hitch (alias)
     */
    static ArgumentBuilder<ServerCommandSource, ?> buildLeash() {
        return CommandManager.literal("leash")
                .executes(context -> modCommandRegistry.executeSkillTargets(context, "leash", null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeSkillTargets(
                                context,
                                "leash",
                                StringArgumentType.getString(context, "target"))));
    }

    static ArgumentBuilder<ServerCommandSource, ?> buildHitch() {
        return CommandManager.literal("hitch")
                .executes(context -> modCommandRegistry.executeSkillTargets(context, "leash", null))
                .then(CommandManager.argument("target", StringArgumentType.string())
                        .executes(context -> modCommandRegistry.executeSkillTargets(
                                context,
                                "leash",
                                StringArgumentType.getString(context, "target"))));
    }

    /**
     * Fortify command for building defensive walls around a village.
     * Usage: /bot fortify              — auto-detect village, build wall
     *        /bot fortify dry_run      — preview layout only
     *        /bot fortify radius=25    — explicit radius
     *        /bot fortify center=100,200 — explicit center
     *        /bot fortify gates=2      — number of gatehouse sides
     */
    static ArgumentBuilder<ServerCommandSource, ?> buildFortify() {
        return CommandManager.literal("fortify")
                .executes(context -> modCommandRegistry.executeSkillTargets(context, "fortify_village", null))
                .then(CommandManager.argument("options", StringArgumentType.greedyString())
                        .executes(context -> modCommandRegistry.executeSkillTargets(context, "fortify_village",
                                StringArgumentType.getString(context, "options"))));
    }
}
