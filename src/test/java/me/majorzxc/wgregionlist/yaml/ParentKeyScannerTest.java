package me.majorzxc.wgregionlist.yaml;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ParentKeyScannerTest {

    @TempDir
    Path dir;

    @Test
    void extractsOnlyRegionParents() throws IOException {
        Path file = dir.resolve("regions.yml");
        Files.writeString(file, """
                #
                # WorldGuard regions file
                #
                regions:
                    house:
                        min: {x: 1.0, y: 2.0, z: 3.0}
                        max: {x: 4.0, y: 5.0, z: 6.0}
                        members: {players: [parent]}
                        flags: {greeting: parent, parent: notme}
                        owners: {}
                        type: cuboid
                        priority: 0
                        parent: City
                    shop:
                        type: poly2d
                        points:
                        - {x: 1, z: 2}
                        - {x: 3, z: 4}
                        parent: market
                    Orphan:
                        type: global
                parent: ignored
                other:
                    x:
                        parent: ignored
                """);
        assertEquals(Map.of("house", "City", "shop", "market"), ParentKeyScanner.scan(file));
    }

    @Test
    void emptyFile() throws IOException {
        Path file = dir.resolve("empty.yml");
        Files.writeString(file, "");
        assertEquals(Map.of(), ParentKeyScanner.scan(file));
    }
}
