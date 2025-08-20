package net.shasankp000.PathFinding;


import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicReference;

import static net.shasankp000.PathFinding.PathFinder.*;


public class GoTo {

    public static String goTo(ServerCommandSource botSource, int x, int y, int z, boolean sprint) {


            MinecraftServer server = botSource.getServer();
            ServerPlayerEntity bot = botSource.getPlayer();
            ServerWorld world = server.getOverworld();
            String botName = botSource.getName();

            AtomicReference<String> output = new AtomicReference<>("");

            if (bot!=null) {

                System.out.println("Found bot: " + botSource.getName() );
                new Thread(() -> {
                    // ✅ Calculate the path (PathNode version)
                    List<PathNode> rawPath = calculatePath(bot.getBlockPos(), new BlockPos(x, y, z), world);

                    // ✅ Simplify + filter
                    List<PathNode> finalPath = simplifyPath(rawPath, world);

                    LOGGER.info("Path output: {}", finalPath);

                    Queue<Segment> segments = convertPathToSegments(finalPath, sprint);

                    LOGGER.info("Generated segments: {}", segments);


                    // ✅ Trace the path — your tracePath now expects PathNode
                    PathTracer.tracePath(server, botSource, botName, segments, sprint);

                    output.set(PathTracer.BotSegmentManager.tracePathOutput(botSource));

                    System.out.println("Path tracer output: " + output.get());

                }).start();


            }
            else {
                System.out.println("Bot not found!");
                output.set("Bot not found!");
            }


        return output.get();

    }


}
