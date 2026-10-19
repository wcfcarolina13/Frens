package net.wcfcarolina13.network;

/**
 * Size limits for the config JSON payloads ({@code frens:save_config}, {@code frens:open_config}).
 *
 * <p>Finding 15 (2026-10-18): a settings save past 32,767 chars made netty throw
 * {@code EncoderException: String too big} later in the pipeline, which disconnected the player.
 * The codecs now allow {@link #MAX_CONFIG_JSON_CHARS}, the C2S save payload is registered with
 * {@code registerLarge} up to {@link #MAX_SAVE_PAYLOAD_BYTES}, and senders check
 * {@link #fitsConfigPayload(String)} first so an overflow is skipped instead of kicking anyone.
 */
public final class ConfigPayloadSizePolicy {
    /** Largest config JSON (in chars) the save/open codecs accept. Same as {@code ConfigSyncPayload}. */
    public static final int MAX_CONFIG_JSON_CHARS = 262_144;

    /**
     * {@code registerLarge} ceiling for {@code frens:save_config}, in bytes. A string of
     * {@link #MAX_CONFIG_JSON_CHARS} chars is at most 3 bytes per char in UTF-8 plus a varint prefix,
     * which stays under this. Do not raise it: non-op saves are refused only after decoding.
     */
    public static final int MAX_SAVE_PAYLOAD_BYTES = 1_048_576;

    private ConfigPayloadSizePolicy() {}

    /** True when {@code json} is small enough for the save/open config codecs. */
    public static boolean fitsConfigPayload(String json) {
        return json != null && json.length() <= MAX_CONFIG_JSON_CHARS;
    }

    /** Client save guard: true when the save payload can be sent without overflowing. */
    public static boolean fitsSavePayload(String json) {
        return fitsConfigPayload(json);
    }

    /** Server open-config guard: true when the open payload can be sent without overflowing. */
    public static boolean fitsOpenPayload(String json) {
        return fitsConfigPayload(json);
    }
}
