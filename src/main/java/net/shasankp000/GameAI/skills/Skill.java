package net.shasankp000.GameAI.skills;

public interface Skill {
    String name();
    SkillExecutionResult execute(SkillContext context);
}
