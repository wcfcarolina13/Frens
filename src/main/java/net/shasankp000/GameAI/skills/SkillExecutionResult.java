package net.shasankp000.GameAI.skills;

public record SkillExecutionResult(boolean success, String message) {
    public static SkillExecutionResult success(String message) {
        return new SkillExecutionResult(true, message);
    }

    public static SkillExecutionResult failure(String message) {
        return new SkillExecutionResult(false, message);
    }
}
