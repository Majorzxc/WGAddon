package me.majorzxc.wgregionlist.sync;

import me.majorzxc.wgregionlist.util.PluginLog;
import me.majorzxc.wgregionlist.yaml.ParentKeyScanner;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Снимок ссылок на родителей из regions.yml WorldGuard, сделанный до его запуска.
 *
 * <p>WorldGuard при чтении своих файлов отбрасывает ссылки на родителей, которых у него нет
 * (они в аддоне). Обычно файл при этом не меняется, и аддон восстанавливает связь по нему.
 * Но при запуске WorldGuard может сразу переписать файл (UUID-миграция) — тогда ссылка
 * пропала бы навсегда. Поэтому файлы читаются заранее, в onLoad, когда WorldGuard ещё
 * не включён. Чтение потоковое, только ключи parent.</p>
 */
public final class StartupParents {

    private StartupParents() {
    }

    /**
     * @param worldsDir папка plugins/WorldGuard/worlds
     * @return абсолютный путь к regions.yml → (нормализованный ID региона → ID родителя)
     */
    public static Map<Path, Map<String, String>> capture(Path worldsDir, PluginLog log) {
        Map<Path, Map<String, String>> result = new HashMap<>();
        if (!Files.isDirectory(worldsDir)) {
            return result;
        }
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(worldsDir, Files::isDirectory)) {
            for (Path world : worlds) {
                Path file = world.resolve("regions.yml");
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    Map<String, String> parents = ParentKeyScanner.scan(file);
                    if (!parents.isEmpty()) {
                        result.put(file.toAbsolutePath().normalize(), parents);
                    }
                } catch (IOException | RuntimeException e) {
                    log.warn("Не удалось прочитать " + file + " (родители регионов WorldGuard): " + e.getMessage());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Не удалось прочитать папку " + worldsDir + ": " + e.getMessage());
        }
        return result;
    }
}
