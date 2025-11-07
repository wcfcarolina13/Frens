package net.shasankp000.ChatUtils;

public class ConfirmationState {
    public final String botName;
    public final String originalMessage;
    public final String prompt;
    public final String payloadJson;

    public ConfirmationState(String botName, String originalMessage, String prompt, String payloadJson) {
        this.botName = botName;
        this.originalMessage = originalMessage;
        this.prompt = prompt;
        this.payloadJson = payloadJson;
    }
}
