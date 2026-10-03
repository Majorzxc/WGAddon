package me.majorzxc.wgregionlist.yaml;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Читает файл регионов в формате regions.yml WorldGuard.
 *
 * <p>Чтение терпимо к ошибкам: битый регион пропускается с предупреждением, остальные
 * регионы файла загружаются. Файл целиком отклоняется только при ошибке синтаксиса YAML
 * или неверной структуре корня.</p>
 */
public final class RegionFileReader {

    public ParsedFile read(Path file) {
        List<String> warnings = new ArrayList<>();
        DuplicateReportingConstructor constructor = new DuplicateReportingConstructor(Yamls.loaderOptions(false));
        DumperOptions dumper = new DumperOptions();
        Yaml yaml = new Yaml(constructor, new Representer(dumper), dumper, Yamls.loaderOptions(false));

        Object root;
        try (Reader reader = new UnicodeReader(Files.newInputStream(file))) {
            root = yaml.load(reader);
        } catch (NoSuchFileException e) {
            return ParsedFile.failed(file, "файл не найден", warnings);
        } catch (IOException e) {
            return ParsedFile.failed(file, "ошибка чтения: " + Yamls.oneLine(e), warnings);
        } catch (YAMLException e) {
            return ParsedFile.failed(file, "ошибка синтаксиса YAML: " + Yamls.oneLine(e), warnings);
        }
        warnings.addAll(constructor.duplicates);

        if (root == null) {
            return empty(file, warnings);
        }
        if (!(root instanceof Map<?, ?> rootMap)) {
            return ParsedFile.failed(file, "в начале файла должен быть ключ 'regions:'", warnings);
        }

        Object regionsRaw = Yamls.getIgnoreCase(rootMap, "regions");
        if (regionsRaw == null) {
            if (!rootMap.isEmpty() && !Yamls.containsIgnoreCase(rootMap, "regions")) {
                warnings.add("нет ключа 'regions:' — регионы в файле не найдены");
            }
            return empty(file, warnings);
        }
        if (!(regionsRaw instanceof Map<?, ?> regionsMap)) {
            return ParsedFile.failed(file, "'regions' должен содержать регионы в виде 'id: {...}'", warnings);
        }

        List<RegionDefinition> regions = new ArrayList<>(regionsMap.size());
        Set<String> invalid = new HashSet<>();
        for (Map.Entry<?, ?> entry : regionsMap.entrySet()) {
            String id = String.valueOf(entry.getKey());
            try {
                regions.add(parseRegion(id, entry.getValue(), warnings));
            } catch (InvalidRegionException e) {
                warnings.add("регион '" + id + "' пропущен: " + e.getMessage());
                invalid.add(Yamls.normalizeId(id));
            }
        }
        return new ParsedFile(file, regions, warnings, invalid, null);
    }

    private static ParsedFile empty(Path file, List<String> warnings) {
        return new ParsedFile(file, List.of(), warnings, Set.of(), null);
    }

    private static RegionDefinition parseRegion(String id, Object raw, List<String> warnings) {
        if (!Yamls.isValidId(id)) {
            throw new InvalidRegionException("недопустимый ID (разрешены буквы A-Z, цифры и символы _ , ' - + /)");
        }
        if (!(raw instanceof Map<?, ?> rawMap)) {
            throw new InvalidRegionException("описание региона должно быть набором ключей (type, min, max, ...)");
        }
        Map<String, Object> node = lowerKeys(rawMap);

        Geometry geometry = parseGeometry(id, node);
        int priority = node.get("priority") == null ? 0 : toInt(node.get("priority"), "priority");
        Map<String, Object> flags = parseFlags(node.get("flags"));
        DomainData owners = parseDomain(id, node.get("owners"), "owners", warnings);
        DomainData members = parseDomain(id, node.get("members"), "members", warnings);

        String parent = null;
        Object parentRaw = node.get("parent");
        if (parentRaw != null && !String.valueOf(parentRaw).isBlank()) {
            parent = String.valueOf(parentRaw).trim();
        }
        return new RegionDefinition(id, geometry, priority, flags, owners, members, parent);
    }

    private static Geometry parseGeometry(String id, Map<String, Object> node) {
        String type = node.get("type") == null ? null : String.valueOf(node.get("type")).trim().toLowerCase(Locale.ROOT);
        if (type == null || type.isEmpty()) {
            // Тип не указан — пытаемся понять по ключам
            if (node.containsKey("points")) {
                type = "poly2d";
            } else if (node.containsKey("min") || node.containsKey("max")) {
                type = "cuboid";
            } else if ("__global__".equals(Yamls.normalizeId(id))) {
                type = "global";
            } else {
                throw new InvalidRegionException("не указан type (cuboid, poly2d или global)");
            }
        }
        return switch (type) {
            case "cuboid" -> {
                int[] min = toVector(node.get("min"), "min");
                int[] max = toVector(node.get("max"), "max");
                yield Geometry.Cuboid.of(min[0], min[1], min[2], max[0], max[1], max[2]);
            }
            case "poly2d", "polygon" -> {
                if (node.get("min-y") == null || node.get("max-y") == null) {
                    throw new InvalidRegionException("для poly2d нужны min-y и max-y");
                }
                int minY = toInt(node.get("min-y"), "min-y");
                int maxY = toInt(node.get("max-y"), "max-y");
                yield new Geometry.Polygon(toPoints(node.get("points")), minY, maxY);
            }
            case "global" -> new Geometry.Global();
            default -> throw new InvalidRegionException("неизвестный type '" + type + "' (ожидается cuboid, poly2d или global)");
        };
    }

    private static int[] toVector(Object raw, String key) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidRegionException("ключ '" + key + "' должен быть вида {x: 0, y: 0, z: 0}");
        }
        Map<String, Object> vector = lowerKeys(map);
        return new int[]{
                toInt(required(vector, "x", key), key + ".x"),
                toInt(required(vector, "y", key), key + ".y"),
                toInt(required(vector, "z", key), key + ".z")
        };
    }

    private static List<Geometry.Point> toPoints(Object raw) {
        if (!(raw instanceof Collection<?> list)) {
            throw new InvalidRegionException("ключ 'points' должен быть списком точек вида - {x: 0, z: 0}");
        }
        List<Geometry.Point> points = new ArrayList<>(list.size());
        int index = 0;
        for (Object element : list) {
            String where = "points[" + index++ + "]";
            if (!(element instanceof Map<?, ?> map)) {
                throw new InvalidRegionException(where + " должен быть вида {x: 0, z: 0}");
            }
            Map<String, Object> point = lowerKeys(map);
            points.add(new Geometry.Point(
                    toInt(required(point, "x", where), where + ".x"),
                    toInt(required(point, "z", where), where + ".z")));
        }
        if (points.size() < 3) {
            throw new InvalidRegionException("у poly2d должно быть минимум 3 точки (сейчас " + points.size() + ")");
        }
        return points;
    }

    private static Map<String, Object> parseFlags(Object raw) {
        if (raw == null) {
            return Map.of();
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new InvalidRegionException("ключ 'flags' должен быть вида {pvp: deny, ...}");
        }
        Map<String, Object> flags = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                flags.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return flags;
    }

    private static DomainData parseDomain(String id, Object raw, String key, List<String> warnings) {
        if (raw == null) {
            return DomainData.EMPTY;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            warnings.add("регион '" + id + "': ключ '" + key + "' должен быть вида {players: [...], groups: [...]} — значение проигнорировано");
            return DomainData.EMPTY;
        }
        Map<String, Object> domain = lowerKeys(map);
        List<String> uuids = new ArrayList<>();
        for (String value : toStrings(domain.get("unique-ids"))) {
            try {
                uuids.add(UUID.fromString(value).toString());
            } catch (IllegalArgumentException e) {
                warnings.add("регион '" + id + "': неверный UUID '" + value + "' в " + key + " — пропущен");
            }
        }
        return new DomainData(toStrings(domain.get("players")), uuids, toStrings(domain.get("groups")));
    }

    /** Список строк; одиночное значение тоже принимается (players: steve). */
    private static List<String> toStrings(Object raw) {
        if (raw == null) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        if (raw instanceof Collection<?> list) {
            for (Object element : list) {
                if (element != null && !String.valueOf(element).isBlank()) {
                    result.add(String.valueOf(element).trim());
                }
            }
        } else if (!String.valueOf(raw).isBlank()) {
            result.add(String.valueOf(raw).trim());
        }
        return result;
    }

    private static Object required(Map<String, Object> map, String key, String where) {
        Object value = map.get(key);
        if (value == null) {
            throw new InvalidRegionException("в '" + where + "' не указан " + key);
        }
        return value;
    }

    /** Число из YAML: целое, дробное (округляется вниз, как в WorldGuard) или строка с числом. */
    static int toInt(Object raw, String key) {
        double value;
        if (raw instanceof Number number) {
            value = number.doubleValue();
        } else {
            try {
                value = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new InvalidRegionException("'" + key + "' должен быть числом, а не '" + raw + "'");
            }
        }
        if (Double.isNaN(value) || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new InvalidRegionException("'" + key + "' вне допустимого диапазона: " + raw);
        }
        return (int) Math.floor(value);
    }

    /** Копия карты с ключами в нижнем регистре (ключи файла не чувствительны к регистру). */
    private static Map<String, Object> lowerKeys(Map<?, ?> map) {
        Map<String, Object> result = new HashMap<>(map.size() * 2);
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()).toLowerCase(Locale.ROOT), entry.getValue());
        }
        return result;
    }

    private static final class InvalidRegionException extends RuntimeException {
        InvalidRegionException(String message) {
            super(message, null, false, false);
        }
    }

    /** Конструктор SnakeYAML, который запоминает повторяющиеся ключи (например, два региона с одним ID). */
    private static final class DuplicateReportingConstructor extends SafeConstructor {

        private final List<String> duplicates = new ArrayList<>();

        DuplicateReportingConstructor(LoaderOptions options) {
            super(options);
        }

        @Override
        protected void processDuplicateKeys(MappingNode node, boolean forceStringKeys) {
            Map<String, Integer> seen = new HashMap<>();
            for (NodeTuple tuple : node.getValue()) {
                if (tuple.getKeyNode() instanceof ScalarNode key) {
                    int line = key.getStartMark() == null ? 0 : key.getStartMark().getLine() + 1;
                    Integer previous = seen.put(key.getValue(), line);
                    if (previous != null) {
                        duplicates.add("ключ '" + key.getValue() + "' повторяется (строки " + previous + " и " + line
                                + ") — используется последнее значение");
                    }
                }
            }
            super.processDuplicateKeys(node, forceStringKeys);
        }
    }
}
