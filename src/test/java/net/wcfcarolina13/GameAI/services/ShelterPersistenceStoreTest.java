package net.wcfcarolina13.GameAI.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.wcfcarolina13.GameAI.services.ShelterPersistenceStore.ShelterRecord;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The record survives the file format (Gson, records, nested int arrays, null cap and side). */
class ShelterPersistenceStoreTest {

    private static final Type FILE_TYPE = new TypeToken<Map<String, Map<String, ShelterRecord>>>() {}.getType();

    @Test
    void wallOffRecordRoundTrips() {
        ShelterRecord record = new ShelterRecord("minecraft:overworld", "WALL_OFF", new int[]{1, 64, 2}, "NORTH",
                new int[]{1, 64, 3}, new int[][]{{1, 64, 2}, {1, 65, 2}});
        Gson gson = new Gson();
        String json = gson.toJson(Map.of("jake", Map.of("world-key", record)), FILE_TYPE);
        Map<String, Map<String, ShelterRecord>> back = gson.fromJson(json, FILE_TYPE);
        ShelterRecord r = back.get("jake").get("world-key");
        assertEquals("minecraft:overworld", r.dimension());
        assertEquals("WALL_OFF", r.type());
        assertArrayEquals(new int[]{1, 64, 2}, r.cap());
        assertEquals("NORTH", r.entryDir());
        assertArrayEquals(new int[]{1, 64, 3}, r.interior());
        assertEquals(2, r.placedBlocks().length);
        assertArrayEquals(new int[]{1, 65, 2}, r.placedBlocks()[1]);
    }

    @Test
    void digDownRecordWithNullSideRoundTrips() {
        ShelterRecord record = new ShelterRecord("minecraft:overworld", "DIG_DOWN", new int[]{0, 62, 0}, null,
                new int[]{0, 60, 0}, new int[0][]);
        Gson gson = new Gson();
        ShelterRecord r = gson.fromJson(gson.toJson(record), ShelterRecord.class);
        assertNull(r.entryDir());
        assertEquals(0, r.placedBlocks().length);
        assertArrayEquals(new int[]{0, 60, 0}, r.interior());
    }
}
