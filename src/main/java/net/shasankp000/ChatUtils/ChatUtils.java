package net.shasankp000.ChatUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ChatUtils {
    public static final Logger LOGGER = LoggerFactory.getLogger("ai-player");
    private static final int MAX_CHAT_LENGTH = 100;

    private static final List<String> FORMATTERS = Arrays.asList("§9", "§b", "§d", "§e", "§6", "§5", "§c", "§7");
    private static final Random RAND = new Random();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    public static String chooseFormatterRandom() {
        return FORMATTERS.get(RAND.nextInt(FORMATTERS.size()));
    }

    public static List<String> splitMessage(String message) {
        List<String> messages = new ArrayList<>();
        String[] sentences = message.split("(?<=[.!?])\\s*");

        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (current.length() + sentence.length() + 1 > MAX_CHAT_LENGTH) {
                messages.add(current.toString().trim());
                current.setLength(0);
            }
            if (!current.isEmpty()) current.append(" ");
            current.append(sentence);
        }
        if (!current.isEmpty()) messages.add(current.toString().trim());
        return messages;
    }

    public static void sendChatMessages(ServerCommandSource source, String message) {
        MinecraftServer server = source.getServer();
        List<String> messages = splitMessage(message);

        for (int i = 0; i < messages.size(); i++) {
            String msg = messages.get(i);
            long delayMillis = i * 2500L;

            scheduler.schedule(() -> {
                server.execute(() -> {
                    String formatter = chooseFormatterRandom();
                    server.getCommandManager().executeWithPrefix(source, "/say " + formatter + msg);
                });
            }, delayMillis, TimeUnit.MILLISECONDS);
        }
    }
}
