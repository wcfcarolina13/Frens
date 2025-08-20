package net.shasankp000.ChatUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class ChatUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger("ai-player");

    // Configuration
    private static final int MAX_CHAT_LENGTH = 100;
    private static final long MESSAGE_DELAY_MS = 2500L;
    private static final List<String> COLOR_CODES = Arrays.asList(
            "§9", "§b", "§d", "§e", "§6", "§5", "§c", "§7"
    );

    private static final Random random = new Random();
    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /**
     * Send a message with default delay behavior (delayed)
     */
    public static void sendChatMessages(ServerCommandSource source, String message) {
        sendChatMessages(source, message, true);
    }


    /**
     * Send a message with optional delay
     * @param source The command source (usually the bot)
     * @param message The message to send
     * @param withDelay Whether to add typing delays between message parts
     */
    public static void sendChatMessages(ServerCommandSource source, String message, boolean withDelay) {
        if (message == null || message.trim().isEmpty()) {
            LOGGER.warn("Attempted to send null or empty message");
            return;
        }

        LOGGER.info("Sending chat message (withDelay={}): '{}'", withDelay, message);

        MinecraftServer server = source.getServer();
        if (server == null) {
            LOGGER.error("Server is null, cannot send message");
            return;
        }

        List<String> messageParts = splitMessage(message.trim());
        LOGGER.info("Message split into {} parts", messageParts.size());

        for (int i = 0; i < messageParts.size(); i++) {
            final String part = messageParts.get(i);
            final int partIndex = i;

            if (withDelay && i > 0) {
                // Add delay for subsequent parts to simulate typing
                long delayMs = MESSAGE_DELAY_MS * i;
                scheduler.schedule(() -> {
                    sendSingleMessage(server, source, part, partIndex);
                }, delayMs, TimeUnit.MILLISECONDS);
            } else {
                // Send immediately
                sendSingleMessage(server, source, part, partIndex);
            }
        }
    }

    /**
     * Send a single message part to the chat
     */
    private static void sendSingleMessage(MinecraftServer server, ServerCommandSource source, String message, int partIndex) {
        server.execute(() -> {
            try {
                String colorCode = getRandomColorCode();
                String coloredMessage = colorCode + message;

                LOGGER.info("Broadcasting  message part {}: '{}' via source: {}", partIndex, coloredMessage, source.getPlayer().getName().toString());

                Text textComponent = Text.literal(coloredMessage);
                server.getCommandManager().executeWithPrefix(source, "/say " + textComponent.getString());

                LOGGER.debug("Successfully broadcasted message part {}", partIndex);

            } catch (Exception e) {
                LOGGER.error("Failed to send message part {}: {}", partIndex, e.getMessage(), e);
            }
        });
    }

    /**
     * Split a long message into smaller parts that fit within chat limits
     */
    private static List<String> splitMessage(String message) {
        List<String> parts = new ArrayList<>();

        if (message.length() <= MAX_CHAT_LENGTH) {
            parts.add(message);
            return parts;
        }

        // Split by sentences first
        String[] sentences = message.split("(?<=[.!?])\\s+");
        StringBuilder currentPart = new StringBuilder();

        for (String sentence : sentences) {
            // If adding this sentence would exceed the limit
            if (currentPart.length() + sentence.length() + 1 > MAX_CHAT_LENGTH) {
                // Save current part if it has content
                if (currentPart.length() > 0) {
                    parts.add(currentPart.toString().trim());
                    currentPart.setLength(0);
                }

                // If single sentence is too long, split it by words
                if (sentence.length() > MAX_CHAT_LENGTH) {
                    parts.addAll(splitLongSentence(sentence));
                } else {
                    currentPart.append(sentence);
                }
            } else {
                // Add sentence to current part
                if (currentPart.length() > 0) {
                    currentPart.append(" ");
                }
                currentPart.append(sentence);
            }
        }

        // Add remaining content
        if (currentPart.length() > 0) {
            parts.add(currentPart.toString().trim());
        }

        return parts;
    }

    /**
     * Split a very long sentence by words
     */
    private static List<String> splitLongSentence(String sentence) {
        List<String> parts = new ArrayList<>();
        String[] words = sentence.split("\\s+");
        StringBuilder currentPart = new StringBuilder();

        for (String word : words) {
            if (currentPart.length() + word.length() + 1 > MAX_CHAT_LENGTH) {
                if (currentPart.length() > 0) {
                    parts.add(currentPart.toString().trim());
                    currentPart.setLength(0);
                }

                // If even a single word is too long, truncate it
                if (word.length() > MAX_CHAT_LENGTH) {
                    parts.add(word.substring(0, MAX_CHAT_LENGTH - 3) + "...");
                } else {
                    currentPart.append(word);
                }
            } else {
                if (currentPart.length() > 0) {
                    currentPart.append(" ");
                }
                currentPart.append(word);
            }
        }

        if (currentPart.length() > 0) {
            parts.add(currentPart.toString().trim());
        }

        return parts;
    }

    /**
     * Get a random color code for message formatting
     */
    public static String getRandomColorCode() {
        return COLOR_CODES.get(random.nextInt(COLOR_CODES.size()));
    }

    /**
     * Send an immediate system message (no delay, no color randomization)
     */
    public static void sendSystemMessage(ServerCommandSource source, String message) {
        if (message == null || message.trim().isEmpty()) {
            LOGGER.warn("Attempted to send null or empty system message");
            return;
        }

        LOGGER.info("Sending system message: '{}'", message);

        MinecraftServer server = source.getServer();
        ServerCommandSource serverSource = server.getCommandSource().withSilent();
        if (server == null) {
            LOGGER.error("Server is null, cannot send system message");
            return;
        }

        server.execute(() -> {
            try {
                Text textComponent = Text.literal("§7" + message); // Gray color for system messages
                server.getCommandManager().executeWithPrefix(serverSource, "/say " + textComponent.getString());
                LOGGER.debug("Successfully sent system message");
            } catch (Exception e) {
                LOGGER.error("Failed to send system message: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * Cleanup method to shut down the scheduler
     */
    public static void shutdown() {
        LOGGER.info("Shutting down ChatUtils scheduler");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}