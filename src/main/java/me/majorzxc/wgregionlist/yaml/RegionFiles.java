package me.majorzxc.wgregionlist.yaml;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** Работа с файлами регионов на диске. */
public final class RegionFiles {

    private static final int MAX_DEPTH = 32;

    private RegionFiles() {
    }

    /**
     * Все файлы регионов в папке (рекурсивно), отсортированные по относительному пути —
     * порядок важен: при повторе ID побеждает файл, который идёт раньше.
     * Скрытые файлы и папки (имя начинается с точки) пропускаются.
     */
    public static List<Path> list(Path folder) throws IOException {
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.walk(folder, MAX_DEPTH)) {
            return stream
                    .filter(path -> !isHidden(folder, path))
                    .filter(RegionFiles::isRegionFile)
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> relativeName(folder, path)))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    public static boolean isRegionFile(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }

    private static boolean isHidden(Path folder, Path path) {
        for (Path part : folder.relativize(path)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /** Относительный путь с прямыми слэшами — для логов и сортировки. */
    public static String relativeName(Path base, Path path) {
        try {
            return base.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    /** Атомарная запись: сначала во временный файл рядом, затем замена. */
    public static void writeAtomically(Path target, String content) throws IOException {
        Path temp = target.resolveSibling(target.getFileName() + ".wgrl-tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
