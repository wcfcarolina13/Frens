package net.wcfcarolina13.FilingSystem;

import java.util.List;

public final class ModelAvailabilityPolicy {
    public enum Status { UNKNOWN, LOADING, READY, UNAVAILABLE, FAILED }

    private static final String LEGACY_UNAVAILABLE = "Ollama is not reachable!";

    private ModelAvailabilityPolicy() {}

    public static String sanitizeSelection(String selected) {
        return selected == null || selected.isBlank() || LEGACY_UNAVAILABLE.equals(selected)
                ? "" : selected;
    }

    public static List<String> sanitizeModelList(List<String> models) {
        return models == null ? List.of() : models.stream()
                .filter(model -> !sanitizeSelection(model).isEmpty())
                .toList();
    }

    public static String selectionToSave(String selected, String previous) {
        String sanitized = sanitizeSelection(selected);
        return sanitized.isEmpty() ? sanitizeSelection(previous) : sanitized;
    }

    public static Status statusForResult(List<String> models) {
        return sanitizeModelList(models).isEmpty() ? Status.UNAVAILABLE : Status.READY;
    }

    public static boolean shouldLogUnavailableAtInfo(boolean alreadyLoggedOnce) {
        return !alreadyLoggedOnce;
    }
}
