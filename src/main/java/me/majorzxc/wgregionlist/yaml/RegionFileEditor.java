package me.majorzxc.wgregionlist.yaml;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Точечно правит файл регионов, сохраняя комментарии и оформление.
 *
 * <p>Работает на уровне дерева узлов SnakeYAML: заменяются только узлы изменённых ключей,
 * остальные узлы выводятся в том же стиле, в каком были прочитаны. Запись атомарная —
 * через временный файл, поэтому при сбое старый файл не повреждается.</p>
 */
public final class RegionFileEditor {

    /**
     * @param applied        сколько правок применено
     * @param missingRegions ID регионов, которых нет в файле (файл правили вручную)
     */
    public record Result(int applied, List<String> missingRegions) {
    }

    private RegionFileEditor() {
    }

    public static Result apply(Path file, Collection<RegionEdit> edits) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        Yaml yaml = Yamls.editorYaml(detectIndent(text));

        Node root;
        try {
            root = yaml.compose(new StringReader(text));
        } catch (YAMLException e) {
            throw new IOException("ошибка синтаксиса YAML: " + Yamls.oneLine(e), e);
        }

        List<String> missing = new ArrayList<>();
        MappingNode regions = root instanceof MappingNode rootMap ? childMapping(rootMap, "regions") : null;
        if (regions == null) {
            edits.forEach(edit -> missing.add(edit.regionId()));
            return new Result(0, missing);
        }

        int applied = 0;
        for (RegionEdit edit : edits) {
            int index = lastIndex(regions, key -> Yamls.normalizeId(key).equals(edit.regionId()));
            if (index < 0) {
                missing.add(edit.regionId());
                continue;
            }
            if (edit.isDelete()) {
                // Удаляем и возможные дубли этого ID выше по файлу
                removeAll(regions, key -> Yamls.normalizeId(key).equals(edit.regionId()));
                applied++;
                continue;
            }
            if (!(regions.getValue().get(index).getValueNode() instanceof MappingNode region)) {
                missing.add(edit.regionId());
                continue;
            }
            applyToRegion(yaml, region, edit);
            applied++;
        }

        if (applied > 0) {
            StringWriter out = new StringWriter(text.length() + 256);
            yaml.serialize(root, out);
            RegionFiles.writeAtomically(file, out.toString());
        }
        return new Result(applied, missing);
    }

    private static void applyToRegion(Yaml yaml, MappingNode region, RegionEdit edit) {
        for (String key : edit.keysToRemove()) {
            removeAll(region, k -> k.equalsIgnoreCase(key));
        }
        for (Map.Entry<String, Object> entry : edit.keysToSet().entrySet()) {
            put(yaml, region, entry.getKey(), entry.getValue());
        }
        if (edit.flagsToSet().isEmpty() && edit.flagsToRemove().isEmpty()) {
            return;
        }
        MappingNode flags = childMapping(region, "flags");
        if (flags == null) {
            put(yaml, region, "flags", new LinkedHashMap<>());
            flags = childMapping(region, "flags");
        }
        for (String name : edit.flagsToRemove()) {
            removeAll(flags, k -> k.equalsIgnoreCase(name));
        }
        for (Map.Entry<String, Object> entry : edit.flagsToSet().entrySet()) {
            put(yaml, flags, entry.getKey(), entry.getValue());
        }
    }

    /** Заменяет значение ключа (сохраняя комментарии рядом с ним) или добавляет ключ в конец. */
    private static void put(Yaml yaml, MappingNode map, String key, Object value) {
        Node newValue = yaml.represent(value);
        List<NodeTuple> tuples = map.getValue();
        int index = lastIndex(map, k -> k.equalsIgnoreCase(key));
        if (index < 0) {
            tuples.add(new NodeTuple(yaml.represent(key), newValue));
            return;
        }
        NodeTuple old = tuples.get(index);
        Node oldValue = old.getValueNode();
        if (newValue.getInLineComments() == null) {
            newValue.setInLineComments(oldValue.getInLineComments());
        }
        if (newValue.getEndComments() == null) {
            newValue.setEndComments(oldValue.getEndComments());
        }
        tuples.set(index, new NodeTuple(old.getKeyNode(), newValue));
        // Более ранние дубли этого ключа удаляем, чтобы значение было однозначным
        for (int i = index - 1; i >= 0; i--) {
            if (tuples.get(i).getKeyNode() instanceof ScalarNode scalar && scalar.getValue().equalsIgnoreCase(key)) {
                tuples.remove(i);
            }
        }
    }

    private static MappingNode childMapping(MappingNode map, String key) {
        int index = lastIndex(map, k -> k.equalsIgnoreCase(key));
        if (index >= 0 && map.getValue().get(index).getValueNode() instanceof MappingNode child) {
            return child;
        }
        return null;
    }

    private static int lastIndex(MappingNode map, Predicate<String> keyMatcher) {
        List<NodeTuple> tuples = map.getValue();
        for (int i = tuples.size() - 1; i >= 0; i--) {
            if (tuples.get(i).getKeyNode() instanceof ScalarNode scalar && keyMatcher.test(scalar.getValue())) {
                return i;
            }
        }
        return -1;
    }

    private static void removeAll(MappingNode map, Predicate<String> keyMatcher) {
        map.getValue().removeIf(tuple -> tuple.getKeyNode() instanceof ScalarNode scalar
                && keyMatcher.test(scalar.getValue()));
    }

    /**
     * Определяет отступ файла по первой вложенной строке, чтобы не переформатировать
     * файлы с отступом в 4 пробела (как у WorldGuard) в 2 и наоборот.
     */
    static int detectIndent(String text) {
        for (String line : text.split("\\R")) {
            String stripped = line.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("-")) {
                continue;
            }
            int indent = line.length() - stripped.length();
            if (indent > 0) {
                if (line.substring(0, indent).indexOf('\t') >= 0) {
                    return 2;
                }
                return Math.max(2, Math.min(indent, 10));
            }
        }
        return 2;
    }
}
