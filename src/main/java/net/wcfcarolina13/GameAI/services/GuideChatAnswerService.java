package net.wcfcarolina13.GameAI.services;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.wcfcarolina13.guide.GuideCatalog;
import net.wcfcarolina13.guide.GuideChatAnswer;
import net.wcfcarolina13.guide.GuideQuestionMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

/**
 * A fren answers a how-to question from the Guide, with no LLM: "Jake, how do I fast travel?"
 * gets the matching card's title and summary, its command, and an [Open in Guide] link. The
 * reply goes to the asker only. It never starts an action: the only thing a click does is open
 * the Guide on the asker's screen.
 *
 * <p>Server thread only. The matching rules are in {@link GuideQuestionMatcher}, the words in
 * {@link GuideChatAnswer}. Logs one {@code [guide-answer]} INFO line per answer (topic ids and
 * scores, never the player's words).
 */
public final class GuideChatAnswerService {

    private static final Logger LOGGER = LoggerFactory.getLogger("frens");
    /** Topics a chat answer may draw on: the best one plus one runner-up. */
    public static final int MAX_MATCHES = 2;

    private GuideChatAnswerService() {
    }

    /** What {@code player} may see in the Guide: admin topics need an operator or the host. */
    public static GuideQuestionMatcher.Visibility visibilityFor(ServerPlayerEntity player) {
        boolean admin = net.wcfcarolina13.network.BotAccessGate.isServerAdmin(player);
        boolean questing = net.wcfcarolina13.Frens.CONFIG != null
                && net.wcfcarolina13.Frens.CONFIG.isQuestingModeEnabled();
        return new GuideQuestionMatcher.Visibility(admin, questing);
    }

    /** The Guide topics that answer {@code prompt} for {@code player}; empty when it isn't a how-to question. */
    public static List<GuideQuestionMatcher.Match> matchesFor(ServerPlayerEntity player, String prompt) {
        try {
            return GuideQuestionMatcher.answer(prompt, GuideCatalog.topics(), visibilityFor(player), MAX_MATCHES);
        } catch (RuntimeException e) {
            // A broken Guide file must never break chat.
            LOGGER.warn("[guide-answer] could not match against the Guide: {}", e.toString());
            return List.of();
        }
    }

    /**
     * {@code bot} answers {@code sender}'s question from the Guide when the line is a how-to
     * question that matches a topic. Returns whether it answered.
     */
    public static boolean tryAnswer(ServerPlayerEntity bot, ServerPlayerEntity sender, String prompt) {
        if (bot == null || sender == null || prompt == null || prompt.isBlank()) {
            return false;
        }
        List<GuideQuestionMatcher.Match> matches = matchesFor(sender, prompt);
        String botName = bot.getName().getString();
        Optional<GuideChatAnswer.Answer> answer = GuideChatAnswer.build(matches, botName);
        if (answer.isEmpty()) {
            return false;
        }
        GuideChatAnswer.Answer a = answer.get();
        sender.sendMessage(Text.literal(botName + ": " + a.spoken()), false);
        sender.sendMessage(linkLine(a, botName), false);
        LOGGER.info("[guide-answer] bot={} player={} topic={} score={} also={}",
                botName, sender.getName().getString(), a.topicId(), matches.get(0).score(),
                a.alsoTopicId().isEmpty() ? "-" : a.alsoTopicId());
        return true;
    }

    /**
     * After a soul's spoken reply to a how-to question: the same [Open in Guide] link line, so
     * the exact source is one click away from whatever the model said.
     */
    public static void sendLinkAfterSoulReply(ServerPlayerEntity bot, ServerPlayerEntity player, String prompt) {
        if (bot == null || player == null) {
            return;
        }
        List<GuideQuestionMatcher.Match> matches = matchesFor(player, prompt);
        String botName = bot.getName().getString();
        GuideChatAnswer.build(matches, botName).ifPresent(a -> {
            GuideChatAnswer.Answer linkOnly = new GuideChatAnswer.Answer(a.spoken(), "", a.topicId(),
                    a.topicTitle(), a.alsoTopicId(), a.alsoTopicTitle());
            player.sendMessage(linkLine(linkOnly, botName), false);
            LOGGER.info("[guide-answer] soul-link bot={} player={} topic={} score={}",
                    botName, player.getName().getString(), a.topicId(), matches.get(0).score());
        });
    }

    /** "  Try /bot ...  [Open in Guide: Title]  [Also: Title]": command in yellow, links in green. */
    static MutableText linkLine(GuideChatAnswer.Answer a, String botName) {
        MutableText line = Text.literal("  ").formatted(Formatting.GRAY);
        if (!a.commandHint().isEmpty()) {
            line.append(Text.literal("Try ").formatted(Formatting.GRAY));
            if (a.commandHint().startsWith("/")) {
                // Suggest, never run: the click only fills the chat box. Guests without /bot
                // permission would get an error from a run, and chat must not start actions.
                String hint = a.commandHint();
                line.append(Text.literal(hint).styled(s -> s
                        .withColor(Formatting.YELLOW)
                        .withClickEvent(new ClickEvent.SuggestCommand(hint))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("Click to put this in the chat box")))));
            } else {
                line.append(Text.literal(a.commandHint()).formatted(Formatting.GRAY));
            }
            line.append(Text.literal("  "));
        }
        line.append(link("[Open in Guide: " + a.topicTitle() + "]", a.topicId(), botName));
        if (!a.alsoTopicId().isEmpty()) {
            line.append(Text.literal("  "));
            line.append(link("[Also: " + a.alsoTopicTitle() + "]", a.alsoTopicId(), botName));
        }
        return line;
    }

    private static MutableText link(String label, String topicId, String botName) {
        return Text.literal(label).styled(s -> s
                .withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand(GuideChatAnswer.openCommand(topicId, botName)))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal("Open this card in the Guide"))));
    }
}
