package net.shasankp000.GameAI.Knowledge;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

public final class BlockKnowledgeRetriever {

    private BlockKnowledgeRetriever() {
    }

    public static Optional<BlockContext> lookup(String blockIdentifier) {
        return BlockKnowledgeBase.findById(blockIdentifier).map(BlockKnowledgeRetriever::toContext);
    }

    public static List<BlockContext> suggest(String partialName) {
        String lowered = partialName == null ? "" : partialName.toLowerCase(Locale.ROOT);
        return BlockKnowledgeBase.all().stream()
                .filter(meta -> meta.name().toLowerCase(Locale.ROOT).contains(lowered)
                        || meta.id().toLowerCase(Locale.ROOT).contains(lowered))
                .limit(5)
                .map(BlockKnowledgeRetriever::toContext)
                .collect(Collectors.toList());
    }

    private static BlockContext toContext(BlockMetadata metadata) {
        String summary = String.format(
                Locale.ROOT,
                "%s (id=%s) hardness %.2f; preferred tool: %s; drops: %s.",
                metadata.name(),
                metadata.id(),
                metadata.hardness(),
                metadata.preferredTool(),
                String.join(", ", metadata.dropSummary())
        );
        String notes = metadata.notes().isEmpty()
                ? "No special handling requirements noted."
                : String.join(" ", metadata.notes());

        return new BlockContext(summary, metadata.toolOptions(), notes);
    }

    public record BlockContext(String summary, List<String> recommendedTools, String notes) {
        public String toPromptString() {
            return summary + " Recommended tools: " + String.join(", ", recommendedTools) + ". " + notes;
        }
    }
}
