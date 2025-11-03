package net.shasankp000.GameAI.Knowledge;

import java.util.List;

public record BlockMetadata(
        String id,
        String name,
        double hardness,
        String preferredTool,
        List<String> toolOptions,
        List<String> dropSummary,
        List<String> notes
) {
}
