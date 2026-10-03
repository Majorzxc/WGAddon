package me.majorzxc.wgregionlist.yaml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileReaderTest {

    @TempDir
    Path dir;

    private ParsedFile read(String yaml) throws IOException {
        Path file = dir.resolve("test.yml");
        Files.writeString(file, yaml, StandardCharsets.UTF_8);
        return new RegionFileReader().read(file);
    }

    @Test
    void readsWorldGuardFormat() throws IOException {
        ParsedFile parsed = read("""
                regions:
                    spawn:
                        min: {x: 10.0, y: 0.0, z: -5.0}
                        max: {x: -10.0, y: 255.0, z: 5.0}
                        members: {}
                        flags: {pvp: deny, greeting: 'Привет'}
                        owners:
                            players: [steve]
                            unique-ids: [0f5e6c6e-31d1-4b42-9f3c-0c9d6c1a9a10]
                            groups: [admin]
                        type: cuboid
                        priority: 10
                    shop:
                        type: poly2d
                        min-y: 100
                        max-y: 60
                        points:
                        - {x: 1, z: 2}
                        - {x: 5, z: 2}
                        - {x: 5.7, z: -9.2}
                        priority: 1
                        parent: spawn
                    __global__:
                        type: global
                        flags: {build: deny}
                """);
        assertFalse(parsed.failed());
        assertTrue(parsed.warnings().isEmpty(), parsed.warnings().toString());
        assertEquals(3, parsed.regions().size());

        RegionDefinition spawn = parsed.regions().get(0);
        assertEquals(new Geometry.Cuboid(-10, 0, -5, 10, 255, 5), spawn.geometry());
        assertEquals(10, spawn.priority());
        assertEquals(Map.of("pvp", "deny", "greeting", "Привет"), spawn.flags());
        assertEquals(List.of("steve"), spawn.owners().players());
        assertEquals(List.of("0f5e6c6e-31d1-4b42-9f3c-0c9d6c1a9a10"), spawn.owners().uniqueIds());
        assertEquals(List.of("admin"), spawn.owners().groups());
        assertNull(spawn.parentId());

        RegionDefinition shop = parsed.regions().get(1);
        Geometry.Polygon polygon = (Geometry.Polygon) shop.geometry();
        assertEquals(60, polygon.minY());
        assertEquals(100, polygon.maxY());
        assertEquals(new Geometry.Point(5, -10), polygon.points().get(2));
        assertEquals("spawn", shop.parentId());

        assertEquals(new Geometry.Global(), parsed.regions().get(2).geometry());
    }

    @Test
    void isTolerantToMissingOptionalKeys() throws IOException {
        ParsedFile parsed = read("""
                regions:
                  Arena:
                    MIN: {X: 0, Y: 0, Z: 0}
                    max: {x: 5, y: 5, z: 5}
                """);
        assertFalse(parsed.failed());
        RegionDefinition arena = parsed.regions().get(0);
        assertEquals("arena", arena.normalizedId());
        assertEquals(0, arena.priority());
        assertTrue(arena.flags().isEmpty());
        assertEquals(DomainData.EMPTY, arena.owners());
        assertEquals("cuboid", arena.geometry().type());
    }

    @Test
    void skipsBrokenRegionButKeepsOthers() throws IOException {
        ParsedFile parsed = read("""
                regions:
                  good:
                    type: cuboid
                    min: {x: 0, y: 0, z: 0}
                    max: {x: 1, y: 1, z: 1}
                  bad:
                    type: cuboid
                    min: {x: 0, y: 0}
                    max: {x: 1, y: 1, z: 1}
                  "bad id!":
                    type: global
                  tiny:
                    type: poly2d
                    min-y: 0
                    max-y: 5
                    points: [{x: 0, z: 0}, {x: 1, z: 1}]
                  words:
                    type: cuboid
                    min: {x: abc, y: 0, z: 0}
                    max: {x: 1, y: 1, z: 1}
                """);
        assertFalse(parsed.failed());
        assertEquals(1, parsed.regions().size());
        assertEquals("good", parsed.regions().get(0).id());
        assertEquals(4, parsed.warnings().size(), parsed.warnings().toString());
        assertTrue(parsed.invalidIds().containsAll(List.of("bad", "tiny", "words")));
    }

    @Test
    void reportsDuplicateKeys() throws IOException {
        ParsedFile parsed = read("""
                regions:
                  a:
                    type: global
                    priority: 1
                  a:
                    type: global
                    priority: 2
                """);
        assertEquals(1, parsed.regions().size());
        assertEquals(2, parsed.regions().get(0).priority());
        assertEquals(1, parsed.warnings().size());
        assertTrue(parsed.warnings().get(0).contains("'a'"), parsed.warnings().get(0));
    }

    @Test
    void syntaxErrorFailsWholeFile() throws IOException {
        ParsedFile parsed = read("regions:\n  a: {type: global\n  b: [\n");
        assertTrue(parsed.failed());
        assertTrue(parsed.fatalError().startsWith("ошибка синтаксиса YAML"), parsed.fatalError());
    }

    @Test
    void emptyAndCommentOnlyFilesAreEmpty() throws IOException {
        assertTrue(read("").regions().isEmpty());
        ParsedFile commented = read("# только комментарии\n#regions:\n#  a: {}\n");
        assertFalse(commented.failed());
        assertTrue(commented.regions().isEmpty());
        assertTrue(commented.warnings().isEmpty());
    }

    @Test
    void warnsWhenRegionsKeyMissing() throws IOException {
        ParsedFile parsed = read("spawn:\n  type: global\n");
        assertFalse(parsed.failed());
        assertTrue(parsed.regions().isEmpty());
        assertEquals(1, parsed.warnings().size());
    }

    @Test
    void wrongRootStructureFails() throws IOException {
        assertTrue(read("- a\n- b\n").failed());
        assertTrue(read("regions: [a, b]\n").failed());
    }

    @Test
    void invalidUuidIsSkippedWithWarning() throws IOException {
        ParsedFile parsed = read("""
                regions:
                  a:
                    type: global
                    members: {unique-ids: [not-a-uuid], players: alex}
                """);
        assertEquals(List.of(), parsed.regions().get(0).members().uniqueIds());
        assertEquals(List.of("alex"), parsed.regions().get(0).members().players());
        assertEquals(1, parsed.warnings().size());
    }

    @Test
    void handlesBom() throws IOException {
        ParsedFile parsed = read("﻿regions:\n  a: {type: global}\n");
        assertFalse(parsed.failed());
        assertEquals(1, parsed.regions().size());
    }
}
