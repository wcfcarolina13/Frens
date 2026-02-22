package net.wcfcarolina13.GameAI.skills;

public interface Skill {
    String name();
    SkillExecutionResult execute(SkillContext context);
}
