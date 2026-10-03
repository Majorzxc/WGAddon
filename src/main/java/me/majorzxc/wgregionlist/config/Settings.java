package me.majorzxc.wgregionlist.config;

import me.majorzxc.wgregionlist.yaml.Yamls;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Настройки плагина из config.yml (неизменяемый снимок).
 *
 * @param worlds название мира → имена папок внутри regions/
 */
public record Settings(Map<String, List<String>> worlds, boolean syncEnabled, int syncIntervalSeconds,
                       boolean deleteRemoved, boolean restoreNativeParents, boolean debug) {

    public Settings {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        worlds.forEach((world, folders) -> copy.put(world, List.copyOf(folders)));
        worlds = Collections.unmodifiableMap(copy);
    }

    public static Settings load(Path file, Consumer<String> warn) throws IOException {
        Object root = Yamls.loadPlain(file);
        return parse(root instanceof Map<?, ?> map ? map : Map.of(), warn);
    }

    public static Settings parse(Map<?, ?> root, Consumer<String> warn) {
        Map<String, List<String>> worlds = parseWorlds(root.get("worlds"), warn);

        Map<?, ?> sync = root.get("sync") instanceof Map<?, ?> map ? map : Map.of();
        boolean syncEnabled = bool(sync.get("enabled"), true);
        int interval = integer(sync.get("interval-seconds"), 5);
        if (interval < 1 || interval > 3600) {
            warn.accept("sync.interval-seconds должен быть от 1 до 3600 — используется 5");
            interval = 5;
        }
        boolean deleteRemoved = bool(sync.get("delete-removed"), true);
        boolean restoreParents = bool(root.get("restore-native-parents"), true);
        boolean debug = bool(root.get("debug"), false);
        return new Settings(worlds, syncEnabled, interval, deleteRemoved, restoreParents, debug);
    }

    private static Map<String, List<String>> parseWorlds(Object raw, Consumer<String> warn) {
        Map<String, List<String>> worlds = new LinkedHashMap<>();
        if (raw == null) {
            warn.accept("в config.yml нет раздела 'worlds' — регионы аддона не загружаются");
            return worlds;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            warn.accept("раздел 'worlds' должен быть вида 'название_мира: папка'");
            return worlds;
        }
        // Папка (без учёта регистра) → мир, которому она уже назначена
        Map<String, String> folderOwners = new HashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String world = String.valueOf(entry.getKey()).trim();
            List<String> folders = new ArrayList<>();
            for (String folder : folderNames(entry.getValue())) {
                if (!isSafeFolderName(folder)) {
                    warn.accept("мир '" + world + "': недопустимое имя папки '" + folder
                            + "' (нельзя использовать / \\ : .. и начинать с точки)");
                    continue;
                }
                String owner = folderOwners.putIfAbsent(folder.toLowerCase(Locale.ROOT), world);
                if (owner != null) {
                    warn.accept("папка '" + folder + "' уже привязана к миру '" + owner + "' — для мира '"
                            + world + "' она пропущена (папка может принадлежать только одному миру)");
                    continue;
                }
                folders.add(folder);
            }
            if (folders.isEmpty()) {
                warn.accept("для мира '" + world + "' не указано ни одной корректной папки");
                continue;
            }
            worlds.merge(world, folders, (a, b) -> {
                List<String> merged = new ArrayList<>(a);
                merged.addAll(b);
                return merged;
            });
        }
        return worlds;
    }

    private static List<String> folderNames(Object raw) {
        List<String> names = new ArrayList<>();
        if (raw instanceof Collection<?> list) {
            for (Object element : list) {
                if (element != null && !String.valueOf(element).isBlank()) {
                    names.add(String.valueOf(element).trim());
                }
            }
        } else if (raw != null && !String.valueOf(raw).isBlank()) {
            names.add(String.valueOf(raw).trim());
        }
        return names;
    }

    static boolean isSafeFolderName(String name) {
        return !name.isEmpty()
                && !name.startsWith(".")
                && !name.contains("/")
                && !name.contains("\\")
                && !name.contains(":")
                && !name.contains("..");
    }

    private static boolean bool(Object raw, boolean def) {
        if (raw instanceof Boolean value) {
            return value;
        }
        if (raw != null) {
            String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("yes") || text.equals("on")) {
                return true;
            }
            if (text.equals("false") || text.equals("no") || text.equals("off")) {
                return false;
            }
        }
        return def;
    }

    private static int integer(Object raw, int def) {
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return def;
    }
}
