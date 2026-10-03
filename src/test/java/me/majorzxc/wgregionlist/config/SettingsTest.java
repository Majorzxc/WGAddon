package me.majorzxc.wgregionlist.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsTest {

    private final List<String> warnings = new ArrayList<>();

    private Settings parse(String yaml) {
        Map<?, ?> root = new Yaml().load(yaml);
        return Settings.parse(root, warnings::add);
    }

    @Test
    void parsesWorldsAndDefaults() {
        Settings settings = parse("""
                worlds:
                  world: spawn
                  world_nether: [nether, arenas]
                """);
        assertEquals(Map.of("world", List.of("spawn"), "world_nether", List.of("nether", "arenas")), settings.worlds());
        assertTrue(settings.syncEnabled());
        assertEquals(5, settings.syncIntervalSeconds());
        assertTrue(settings.deleteRemoved());
        assertTrue(settings.restoreNativeParents());
        assertFalse(settings.debug());
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void rejectsUnsafeAndDuplicateFolders() {
        Settings settings = parse("""
                worlds:
                  world: [main, ../etc, .hidden, a/b]
                  other: [MAIN, extra]
                sync:
                  enabled: false
                  interval-seconds: 0
                """);
        assertEquals(Map.of("world", List.of("main"), "other", List.of("extra")), settings.worlds());
        assertFalse(settings.syncEnabled());
        assertEquals(5, settings.syncIntervalSeconds());
        assertEquals(5, warnings.size(), warnings.toString());
    }

    @Test
    void missingWorldsSectionWarns() {
        Settings settings = parse("debug: true\n");
        assertTrue(settings.worlds().isEmpty());
        assertTrue(settings.debug());
        assertEquals(1, warnings.size());
    }
}
