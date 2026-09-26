package net.wcfcarolina13.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Pure step logic for the "Set up AI companions" checklist: from one {@link AiSetupStatus}
 * (or null before the server has answered) to a state and a button-enabled flag per step.
 * No Minecraft types; the screen only draws what this returns.
 */
public final class AiSetupChecklistPolicy {

    private AiSetupChecklistPolicy() {
    }

    public enum StepId { OLLAMA, MODEL, SOULS_ON, BOTS, TEST_CHAT, VOICE }

    /** ✔ done / ✖ still to do / • optional / ? unknown (not visible to this player, or no answer yet). */
    public enum State {
        DONE("✔"), TODO("✖"), OPTIONAL("•"), UNKNOWN("?");

        private final String icon;

        State(String icon) {
            this.icon = icon;
        }

        public String icon() {
            return icon;
        }
    }

    /**
     * One step. {@code buttonEnabled} false with {@code needsOperator} true means the button is
     * disabled only because the viewer can't change server settings ("Ask the server operator / host").
     */
    public record Step(StepId id, State state, boolean buttonEnabled, boolean needsOperator, String detail) {
    }

    /** One bot row under step 4. */
    public record BotRow(String name, String uuid, State state, boolean enableButton) {
    }

    /**
     * The whole checklist. {@code onServerMachine}: steps 1, 2 and 6 happen on another computer
     * (multiplayer, viewer is not the integrated host) and are labelled so. {@code testBotName}
     * is the bot "Say hi" addresses, or "" when no bot is enabled.
     */
    public record Checklist(List<Step> steps, List<BotRow> bots, String testBotName, boolean onServerMachine) {
        public Step step(StepId id) {
            for (Step s : steps) {
                if (s.id() == id) {
                    return s;
                }
            }
            throw new IllegalArgumentException(String.valueOf(id));
        }
    }

    public static Checklist evaluate(AiSetupStatus status) {
        if (status == null) {
            List<Step> steps = new ArrayList<>();
            for (StepId id : StepId.values()) {
                steps.add(new Step(id, State.UNKNOWN, false, false, "Waiting for the server…"));
            }
            return new Checklist(steps, List.of(), "", false);
        }
        boolean canEdit = status.viewer().canEditServer();
        boolean onServerMachine = !status.viewer().isIntegratedHost();
        AiSetupStatus.Ollama ollama = status.ollama();
        AiSetupStatus.Runtime runtime = status.runtime();
        List<Step> steps = new ArrayList<>();

        // 1. Ollama
        State ollamaState = ollama == null ? State.UNKNOWN : ollama.reachable() ? State.DONE : State.TODO;
        String ollamaDetail = ollama == null
                ? "Only the operator can see this. Only needed for AI chat."
                : ollama.reachable()
                        ? "Ollama " + ollama.version() + " is running."
                        : "Only needed for AI chat. Frens works fully without it.";
        steps.add(new Step(StepId.OLLAMA, ollamaState, canEdit, !canEdit, ollamaDetail));

        // 2. Model pulled and selected
        State modelState;
        String modelDetail;
        String model = runtime.model();
        if (ollama == null) {
            modelState = State.UNKNOWN;
            modelDetail = "Only the operator can see which model is set.";
        } else if (model.isBlank()) {
            modelState = State.TODO;
            modelDetail = "No model chosen yet.";
        } else if (!ollama.reachable()) {
            modelState = State.UNKNOWN;
            modelDetail = "Model " + model + " is chosen; start Ollama to check it's downloaded.";
        } else if (isModelInstalled(model, ollama.installedTags())) {
            modelState = State.DONE;
            modelDetail = "Using " + model + ".";
        } else {
            modelState = State.TODO;
            modelDetail = model + " is chosen but not downloaded.";
        }
        steps.add(new Step(StepId.MODEL, modelState, canEdit, !canEdit, modelDetail));

        // 3. Soul Chat switch
        boolean soulsOn = runtime.soulsEnabled();
        steps.add(new Step(StepId.SOULS_ON, soulsOn ? State.DONE : State.TODO,
                canEdit && !soulsOn, !canEdit && !soulsOn,
                soulsOn ? "Soul Chat is on." : "Soul Chat is off for this world."));

        // 4. Bots bound to a persona
        List<BotRow> rows = new ArrayList<>();
        boolean anyEnabled = false;
        for (AiSetupStatus.Bot bot : status.bots()) {
            if (!bot.ownedByViewer() && !canEdit) {
                continue;
            }
            boolean enabled = bot.enabled();
            anyEnabled |= enabled;
            rows.add(new BotRow(bot.name(), bot.uuid(), enabled ? State.DONE : State.TODO, !enabled));
        }
        String botsDetail = rows.isEmpty()
                ? "No companion of yours is online. Spawn or recruit one first."
                : anyEnabled ? "At least one companion has an AI persona." : "Enable a companion below.";
        steps.add(new Step(StepId.BOTS, anyEnabled ? State.DONE : State.TODO, false, false, botsDetail));

        // 5. Test chat
        String testBot = pickTestBot(status.bots(), canEdit);
        boolean replied = false;
        for (AiSetupStatus.Bot bot : status.bots()) {
            replied |= bot.repliedRecently() && bot.enabled();
        }
        String testDetail;
        if (replied) {
            testDetail = "Your companion answered.";
        } else if (testBot.isEmpty()) {
            testDetail = "Enable a companion first.";
        } else if (!runtime.ready()) {
            testDetail = "Finish the steps above, then say hi to " + testBot + ".";
        } else {
            testDetail = "Try it: say hi to " + testBot + " in chat.";
        }
        steps.add(new Step(StepId.TEST_CHAT, replied ? State.DONE : State.TODO, !testBot.isEmpty(), false,
                testDetail));

        // 6. Voice (optional)
        AiSetupStatus.Voice voice = status.voice();
        String voiceDetail = voice.enabled()
                ? (voice.detailKnown() && !voice.engine().isBlank() ? "Voice is on (" + voice.engine() + ")." : "Voice is on.")
                : "Optional: companions can also speak aloud.";
        steps.add(new Step(StepId.VOICE, voice.enabled() ? State.DONE : State.OPTIONAL, canEdit, !canEdit,
                voiceDetail));

        return new Checklist(steps, rows, testBot, onServerMachine);
    }

    /**
     * Whether Ollama lists {@code model}; a tag without a version matches its {@code :latest}
     * (Ollama reports "llama3.2" as "llama3.2:latest").
     */
    public static boolean isModelInstalled(String model, List<String> installedTags) {
        if (model == null || model.isBlank() || installedTags == null) {
            return false;
        }
        String wanted = model.trim().toLowerCase(Locale.ROOT);
        if (!wanted.contains(":")) {
            wanted = wanted + ":latest";
        }
        for (String tag : installedTags) {
            if (tag != null && tag.trim().toLowerCase(Locale.ROOT).equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The bot "Say hi" should address: an enabled bot the viewer owns and is in reach of, else
     * any enabled bot the viewer owns, else (operator view) any enabled bot; "" if none.
     */
    public static String pickTestBot(List<AiSetupStatus.Bot> bots, boolean canEdit) {
        String owned = "";
        String any = "";
        for (AiSetupStatus.Bot bot : bots) {
            if (!bot.enabled()) {
                continue;
            }
            if (bot.ownedByViewer()) {
                if (bot.inReach()) {
                    return bot.name();
                }
                if (owned.isEmpty()) {
                    owned = bot.name();
                }
            } else if (canEdit && any.isEmpty()) {
                any = bot.name();
            }
        }
        return !owned.isEmpty() ? owned : any;
    }

    /** The pre-filled chat line for "Say hi". The player sends it themselves. */
    public static String greeting(String botName) {
        return botName + ", can you hear me?";
    }

    /** Model-size RAM guidance shown under step 2, with the host's RAM when known. */
    public static String ramGuidance(double hostRamGb) {
        String base = "1B ≈4 GB RAM, 3B ≈6 GB, 8B ≈12 GB (plus Minecraft's own memory)";
        return hostRamGb > 0
                ? String.format(Locale.ROOT, "Host has %.0f GB RAM. %s", hostRamGb, base)
                : base;
    }
}
