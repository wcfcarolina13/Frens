package net.wcfcarolina13.ChatUtils;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.wcfcarolina13.GameAI.BotEventHandler;
import net.wcfcarolina13.GameAI.services.CompanionCommunicationPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChatUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger("frens");

    // Configuration
    private static final int MAX_CHAT_LENGTH = 100;
    private static final long MESSAGE_DELAY_MS = 2500L;

    private static final AtomicInteger CHAT_THREAD_ID = new AtomicInteger(0);
    // Shared scheduler for delayed chat parts (avoid spawning a new Thread per delayed message part).
    private static final ScheduledExecutorService CHAT_DELAY_EXECUTOR = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "chat-utils-delay-" + CHAT_THREAD_ID.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Send a message from the bot with optional delay.
     * This method is designed to be called from any thread.
     * @param source The command source (usually the bot).
     * @param message The message to send.
     * @param withDelay Whether to add typing delays between message parts.
     */
    public static void sendChatMessages(ServerCommandSource source, String message, boolean withDelay) {
        if (message == null || message.trim().isEmpty()) {
            LOGGER.warn("Attempted to send null or empty message");
            return;
        }

        MinecraftServer server = source.getServer();
        if (server == null) {
            LOGGER.error("Server is null, cannot send message");
            return;
        }

        Recipient recipient = resolveRecipientForDialogue(server, source);
        if (recipient == null) {
            LOGGER.warn("No recipient resolved for dialogue message; dropping: '{}'", message);
            return;
        }

        LOGGER.info("Dialogue routed: botSender={} bot={} -> recipient={}",
            recipient.botSender,
            recipient.bot != null ? recipient.bot.getName().getString() : "(none)",
            recipient.player != null ? recipient.player.getName().getString() : "(null)");

        // Apply requested comms gating for bot dialogue.
        if (recipient.botSender && recipient.bot != null) {
            if (!CompanionCommunicationPolicy.canBotChatToController(recipient.bot, recipient.player)) {
                return;
            }
        }

        LOGGER.info("Sending chat message (withDelay={}): '{}'", withDelay, message);

        boolean forceTextFallback = shouldForceTextFallbackForVoiceOnly(recipient.botSender, message);

        List<String> messageParts = splitMessage(message.trim());
        LOGGER.info("Message split into {} parts", messageParts.size());

        // This is the core fix. We are queuing a single task on the server's main thread.
        // The task itself handles all logic, including delays.
        server.execute(() -> scheduleAndSendMessages(server, source, recipient.playerUuid, recipient.botSender, messageParts, 0, withDelay, forceTextFallback));
    }

    private static boolean isGlobalTextDialogueEnabled() {
        // Chat lines have no call-site tag, so they gate as the GENERAL category:
        // master ON, or GENERAL checked as a keep-visible exception in Text Adv.
        return TextLineVisibilityService.isTextAllowed(VoiceLineCategory.GENERAL);
    }

    private static boolean shouldForceTextFallbackForVoiceOnly(boolean botSender, String rawMessage) {
        if (!botSender || isGlobalTextDialogueEnabled() || !BotDialoguePlayer.isGlobalVoicedDialogueEnabled()) {
            return false;
        }
        String cleaned = stripLegacyFormatting(rawMessage == null ? "" : rawMessage.trim());
        if (cleaned.isEmpty()) {
            return false;
        }
        return DialogueTextMapper.lookup(cleaned) == null;
    }

    /**
     * A recursive helper method to handle delayed message sending on the server thread.
     * @param server The Minecraft server instance.
     * @param source The command source.
     * @param messageParts The list of message parts to send.
     * @param partIndex The current index of the message part to send.
     * @param withDelay If delays should be used.
     */
    private static void scheduleAndSendMessages(MinecraftServer server,
                                                ServerCommandSource source,
                                                UUID recipientUuid,
                                                boolean botSender,
                                                List<String> messageParts,
                                                int partIndex,
                                                boolean withDelay,
                                                boolean forceTextFallback) {
        if (partIndex >= messageParts.size()) {
            // All parts have been sent, stop the recursion.
            return;
        }

        // Send the current message part.
        sendSingleMessage(server, source, recipientUuid, botSender, messageParts.get(partIndex), partIndex, forceTextFallback);

        // Schedule the next part if there are more to send and a delay is requested.
        if (withDelay && partIndex < messageParts.size() - 1) {
            CHAT_DELAY_EXECUTOR.schedule(
                    () -> server.execute(() -> scheduleAndSendMessages(server, source, recipientUuid, botSender, messageParts, partIndex + 1, true, forceTextFallback)),
                    MESSAGE_DELAY_MS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    /**
     * Send a single message part to the chat.
     */
    private static void sendSingleMessage(MinecraftServer server,
                                          ServerCommandSource source,
                                          UUID recipientUuid,
                                          boolean botSender,
                                          String message,
                                          int partIndex,
                                          boolean forceTextFallback) {
        try {

            if (server == null || recipientUuid == null) {
                return;
            }
            ServerPlayerEntity recipient = server.getPlayerManager().getPlayer(recipientUuid);
            if (recipient == null || recipient.isRemoved()) {
                return;
            }

            // Inside sendSingleMessage
            String sourceName;
            try {
                ServerPlayerEntity p = source.getPlayer();
                sourceName = (p != null) ? p.getName().getString() : source.getName();
            } catch (Throwable t) {
                sourceName = source.getName();
            }
            String formattedMessage = message;

            // Check if the message already starts with the source name.
            if (formattedMessage.trim().startsWith(sourceName + ": ")) {
                formattedMessage = formattedMessage.replace(sourceName + ": ", "");
            }

            // Now use the formattedMessage to build the final message.
            String cleanedMessage = stripLegacyFormatting(formattedMessage);

            // Play dialogue sound for the first message part only
            // This avoids playing the sound multiple times for split messages
            if (partIndex == 0 && botSender && BotDialoguePlayer.isGlobalVoicedDialogueEnabled()) {
                BotDialoguePlayer.tryPlayDialogueDetailed(source, cleanedMessage);
            }

            boolean sendText = !botSender || isGlobalTextDialogueEnabled() || forceTextFallback;
            if (!sendText) {
                return;
            }

            String toSend = botSender ? (sourceName + ": " + cleanedMessage) : cleanedMessage;
            recipient.sendMessage(Text.literal(toSend), false);

        } catch (Exception e) {
            LOGGER.error("Failed to send message part {}: {}", partIndex, e.getMessage(), e);
        }
    }

    /**
     * Send an immediate system message from the server (no delay, no color randomization).
     * @param source The command source.
     * @param message The message to send.
     */
    public static void sendSystemMessage(ServerCommandSource source, String message) {
        if (message == null || message.trim().isEmpty()) {
            LOGGER.warn("Attempted to send null or empty system message");
            return;
        }

        LOGGER.info("Sending system message: '{}'", message);

        MinecraftServer server = source.getServer();
        if (server == null) {
            LOGGER.error("Server is null, cannot send system message");
            return;
        }

        Recipient recipient = resolveRecipientForSystem(server, source);
        if (recipient == null) {
            LOGGER.warn("No recipient resolved for system message; dropping: '{}'", message);
            return;
        }

        LOGGER.info("System routed: botSender={} bot={} -> recipient={}",
            recipient.botSender,
            recipient.bot != null ? recipient.bot.getName().getString() : "(none)",
            recipient.player != null ? recipient.player.getName().getString() : "(null)");

        // Execute immediately on the server thread to prevent threading issues.
        server.execute(() -> {
            try {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(recipient.playerUuid);
                if (player == null || player.isRemoved()) {
                    return;
                }
                String cleaned = stripLegacyFormatting(message.trim());
                String prefix = recipient.botSender && recipient.bot != null
                        ? (recipient.bot.getName().getString() + ": ")
                        : "";
                player.sendMessage(Text.literal(prefix + cleaned).formatted(Formatting.GRAY), false);
            } catch (Exception e) {
                LOGGER.error("Failed to send system message: {}", e.getMessage(), e);
            }
        });
    }

    private static Recipient resolveRecipientForDialogue(MinecraftServer server, ServerCommandSource source) {
        if (server == null || source == null) {
            return null;
        }
        ServerPlayerEntity player = null;
        try {
            player = source.getPlayer();
        } catch (Throwable ignored) {
        }

        if (player != null && BotEventHandler.isRegisteredBot(player)) {
            ServerPlayerEntity controller = CompanionCommunicationPolicy.resolveController(server, player);
            if (controller == null || controller.isRemoved()) {
                return null;
            }
            return new Recipient(controller.getUuid(), controller, true, player);
        }

        // Non-bot: send back to the issuing player (direct/private).
        if (player != null && !player.isRemoved()) {
            return new Recipient(player.getUuid(), player, false, null);
        }

        return null;
    }

    private static Recipient resolveRecipientForSystem(MinecraftServer server, ServerCommandSource source) {
        // For now, same routing as dialogue, but without gating.
        return resolveRecipientForDialogue(server, source);
    }

    private static String stripLegacyFormatting(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        // Strip legacy section-sign formatting codes (e.g., §a, §c, §l).
        return s.replaceAll("§[0-9A-FK-ORa-fk-or]", "");
    }

    private record Recipient(UUID playerUuid, ServerPlayerEntity player, boolean botSender, ServerPlayerEntity bot) {
    }

    /**
     * Split a long message into smaller parts that fit within chat limits.
     */
    private static List<String> splitMessage(String message) {
        List<String> parts = new ArrayList<>();
        if (message.length() <= MAX_CHAT_LENGTH) {
            parts.add(message);
            return parts;
        }

        String[] sentences = message.split("(?<=[.!?])\\s+");
        StringBuilder currentPart = new StringBuilder();

        for (String sentence : sentences) {
            if (currentPart.length() + sentence.length() + 1 > MAX_CHAT_LENGTH) {
                if (currentPart.length() > 0) {
                    parts.add(currentPart.toString().trim());
                    currentPart.setLength(0);
                }
                if (sentence.length() > MAX_CHAT_LENGTH) {
                    parts.addAll(splitLongSentence(sentence));
                } else {
                    currentPart.append(sentence);
                }
            } else {
                if (currentPart.length() > 0) {
                    currentPart.append(" ");
                }
                currentPart.append(sentence);
            }
        }

        if (currentPart.length() > 0) {
            parts.add(currentPart.toString().trim());
        }
        return parts;
    }

    /**
     * Split a very long sentence by words.
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
     * Get a random color code for message formatting.
     */
    public static String getRandomColorCode() {
        return "";
    }

    /**
     * Send a message with default delay behavior (delayed).
     * This is a convenience method.
     */
    public static void sendChatMessages(ServerCommandSource source, String message) {
        sendChatMessages(source, message, true);
    }
}
