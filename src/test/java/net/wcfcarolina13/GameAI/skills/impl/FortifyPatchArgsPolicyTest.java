package net.wcfcarolina13.GameAI.skills.impl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FortifyPatchArgsPolicyTest {

    private static void check(String in, String name, boolean auto) {
        assertEquals(new FortifyPatchArgsPolicy.PatchArgs(name, auto), FortifyPatchArgsPolicy.parse(in), in);
    }

    @Test
    void autoAloneAutoDetectsAndRepeats() {
        check("auto", null, true);
        check("  AUTO ", null, true);
    }

    @Test
    void nameWithAutoRepeats() {
        check("mywall auto", "mywall", true);
        check("my wall auto", "my wall", true);
        check("my   wall   Auto", "my   wall", true);
    }

    @Test
    void nameAloneDoesNotRepeat() {
        check("mywall", "mywall", false);
        check("my wall", "my wall", false);
        check("autowall", "autowall", false);
    }

    @Test
    void blankOrNullMeansNoNameNoRepeat() {
        check("", null, false);
        check("   ", null, false);
        check(null, null, false);
    }
}
