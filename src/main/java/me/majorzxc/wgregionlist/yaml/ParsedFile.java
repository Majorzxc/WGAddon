package me.majorzxc.wgregionlist.yaml;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Результат чтения одного файла регионов.
 *
 * @param regions    корректные регионы в порядке следования в файле
 * @param warnings   некритичные проблемы (битый регион, дубль ключа и т.п.)
 * @param invalidIds нормализованные ID регионов, которые не удалось разобрать
 * @param fatalError причина, по которой файл не прочитан целиком, или null
 */
public record ParsedFile(Path file, List<RegionDefinition> regions, List<String> warnings,
                         Set<String> invalidIds, String fatalError) {

    public ParsedFile {
        regions = List.copyOf(regions);
        warnings = List.copyOf(warnings);
        invalidIds = Set.copyOf(invalidIds);
    }

    static ParsedFile failed(Path file, String error, List<String> warnings) {
        return new ParsedFile(file, List.of(), warnings, Set.of(), error);
    }

    public boolean failed() {
        return fatalError != null;
    }
}
