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
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegionFileEditorTest {

    private static final String SOURCE = """
            # Регионы спавна
            regions:
                # Главный спавн
                spawn:
                    type: cuboid
                    min: {x: -100, y: -64, z: -100}
                    max: {x: 100, y: 320, z: 100}
                    priority: 10 # важный
                    flags: {pvp: deny, greeting: 'Добро пожаловать'}
                    owners: {}
                    description: "свой ключ пользователя"

                # Магазин
                shop:
                    type: poly2d
                    min-y: 0
                    max-y: 100
                    points:
                    - {x: 1, z: 2}
                    - {x: 5, z: 2}
                    - {x: 5, z: 9}
                    parent: spawn
            # конец файла
            """;

    @TempDir
    Path dir;

    private Path write() throws IOException {
        Path file = dir.resolve("spawn.yml");
        Files.writeString(file, SOURCE, StandardCharsets.UTF_8);
        return file;
    }

    @Test
    void changesOnlyEditedKeysAndKeepsComments() throws IOException {
        Path file = write();
        RegionEdit edit = RegionEdit.update("Spawn")
                .set("priority", 20)
                .setFlag("build", "deny")
                .removeFlag("PVP")
                .set("owners", Map.of("players", List.of("alex")))
                .set("parent", "root");
        RegionFileEditor.Result result = RegionFileEditor.apply(file, List.of(edit));
        assertEquals(1, result.applied());
        assertTrue(result.missingRegions().isEmpty());

        String text = Files.readString(file);
        assertTrue(text.contains("# Регионы спавна"), text);
        assertTrue(text.contains("# Главный спавн"), text);
        assertTrue(text.contains("# Магазин"), text);
        assertTrue(text.contains("# конец файла"), text);
        assertTrue(text.contains("priority: 20 # важный"), text);
        assertTrue(text.contains("flags: {greeting: 'Добро пожаловать', build: deny}"), text);
        assertTrue(text.contains("description: \"свой ключ пользователя\""), text);
        assertTrue(text.contains("min: {x: -100, y: -64, z: -100}"), text);
        assertTrue(text.contains("    - {x: 5, z: 9}") || text.contains("- {x: 5, z: 9}"), text);
        assertTrue(text.contains("        parent: root"), text);
        // Отступ файла (4 пробела) сохранён
        assertTrue(text.contains("\n    spawn:\n        type: cuboid"), text);

        ParsedFile parsed = new RegionFileReader().read(file);
        assertFalse(parsed.failed());
        RegionDefinition spawn = parsed.regions().get(0);
        assertEquals(20, spawn.priority());
        assertEquals(Map.of("greeting", "Добро пожаловать", "build", "deny"), spawn.flags());
        assertEquals(List.of("alex"), spawn.owners().players());
        assertEquals("root", spawn.parentId());
    }

    @Test
    void replacesGeometryWhenTypeChanges() throws IOException {
        Path file = write();
        RegionEdit edit = RegionEdit.update("shop");
        Geometry cuboid = Geometry.Cuboid.of(0, 0, 0, 7, 8, 9);
        for (String key : Geometry.KEYS) {
            edit.remove(key);
        }
        cuboid.toYaml().forEach(edit::set);
        RegionFileEditor.apply(file, List.of(edit));

        RegionDefinition shop = new RegionFileReader().read(file).regions().get(1);
        assertEquals(cuboid, shop.geometry());
        assertEquals("spawn", shop.parentId());
        String text = Files.readString(file);
        assertFalse(text.contains("points"), text);
        assertFalse(text.contains("min-y"), text);
    }

    @Test
    void deletesRegion() throws IOException {
        Path file = write();
        RegionFileEditor.Result result = RegionFileEditor.apply(file, List.of(RegionEdit.delete("shop")));
        assertEquals(1, result.applied());
        ParsedFile parsed = new RegionFileReader().read(file);
        assertEquals(1, parsed.regions().size());
        assertEquals("spawn", parsed.regions().get(0).id());
        assertTrue(Files.readString(file).contains("# Главный спавн"));
    }

    @Test
    void reportsMissingRegionAndDoesNotTouchFile() throws IOException {
        Path file = write();
        RegionFileEditor.Result result = RegionFileEditor.apply(file, List.of(RegionEdit.update("nope").set("priority", 1)));
        assertEquals(0, result.applied());
        assertEquals(List.of("nope"), result.missingRegions());
        assertEquals(SOURCE, Files.readString(file));
    }

    @Test
    void createsFlagsSectionWhenMissing() throws IOException {
        Path file = dir.resolve("plain.yml");
        Files.writeString(file, "regions:\n  a:\n    type: global\n");
        RegionFileEditor.apply(file, List.of(RegionEdit.update("a").setFlag("pvp", "allow")));
        assertEquals(Map.of("pvp", "allow"), new RegionFileReader().read(file).regions().get(0).flags());
        assertTrue(Files.readString(file).contains("  a:\n    type: global\n    flags: {pvp: allow}"), Files.readString(file));
    }

    @Test
    void quotesAmbiguousStrings() throws IOException {
        Path file = dir.resolve("q.yml");
        Files.writeString(file, "regions:\n  a: {type: global}\n");
        RegionFileEditor.apply(file, List.of(RegionEdit.update("a").setFlag("greeting", "yes").setFlag("farewell", "123")));
        Map<String, Object> flags = new RegionFileReader().read(file).regions().get(0).flags();
        assertEquals("yes", flags.get("greeting"));
        assertEquals("123", flags.get("farewell"));
    }

    @Test
    void syntaxErrorIsReportedAndFileUntouched() throws IOException {
        Path file = dir.resolve("broken.yml");
        String broken = "regions:\n  a: {type: global\n";
        Files.writeString(file, broken);
        try {
            RegionFileEditor.apply(file, List.of(RegionEdit.update("a").set("priority", 1)));
            throw new AssertionError("ожидалась ошибка");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("YAML"));
        }
        assertEquals(broken, Files.readString(file));
    }

    @Test
    void detectsIndent() {
        assertEquals(4, RegionFileEditor.detectIndent("regions:\n    a:\n        type: global\n"));
        assertEquals(2, RegionFileEditor.detectIndent("# c\nregions:\n  a: {}\n"));
        assertEquals(2, RegionFileEditor.detectIndent("regions: {}\n"));
    }
}
