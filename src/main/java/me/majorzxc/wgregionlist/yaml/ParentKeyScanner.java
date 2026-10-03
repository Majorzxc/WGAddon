package me.majorzxc.wgregionlist.yaml;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;
import org.yaml.snakeyaml.events.Event;
import org.yaml.snakeyaml.events.ScalarEvent;
import org.yaml.snakeyaml.reader.UnicodeReader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Потоково извлекает из regions.yml WorldGuard пары «регион → родитель».
 *
 * <p>Файл WorldGuard может быть очень большим, поэтому он не загружается в память целиком:
 * парсер идёт по событиям SnakeYAML и запоминает только значения ключей
 * {@code regions.<id>.parent}. Расход памяти не зависит от размера файла.</p>
 */
public final class ParentKeyScanner {

    private ParentKeyScanner() {
    }

    /** @return нормализованный ID региона → ID родителя (как записан в файле) */
    public static Map<String, String> scan(Path file) throws IOException {
        Map<String, String> parents = new HashMap<>();
        Yaml yaml = new Yaml(Yamls.loaderOptions(false));
        try (Reader reader = new UnicodeReader(new BufferedInputStream(Files.newInputStream(file)))) {
            Deque<Frame> stack = new ArrayDeque<>();
            for (Event event : yaml.parse(reader)) {
                switch (event.getEventId()) {
                    case MappingStart -> stack.push(open(stack, true));
                    case SequenceStart -> stack.push(open(stack, false));
                    case MappingEnd, SequenceEnd -> close(stack);
                    case Scalar -> onScalar(stack, ((ScalarEvent) event).getValue(), parents);
                    case Alias -> nodeDone(stack.peek());
                    default -> {
                        // начало/конец документа и потока не интересны
                    }
                }
            }
        } catch (YAMLException e) {
            throw new IOException("ошибка синтаксиса YAML: " + Yamls.oneLine(e), e);
        }
        return parents;
    }

    private static Frame open(Deque<Frame> stack, boolean mapping) {
        Frame parent = stack.peek();
        boolean isKey = parent != null && parent.mapping && parent.expectKey;
        // Имя коллекции — ключ, под которым она лежит в родительской карте
        String name = parent != null && parent.mapping && !parent.expectKey ? parent.key : null;
        return new Frame(mapping, name, isKey);
    }

    private static void close(Deque<Frame> stack) {
        Frame done = stack.pop();
        Frame parent = stack.peek();
        if (done.isKey) {
            // Закончился составной ключ (экзотика) — дальше идёт его значение
            if (parent != null) {
                parent.key = null;
                parent.expectKey = false;
            }
        } else {
            nodeDone(parent);
        }
    }

    private static void onScalar(Deque<Frame> stack, String value, Map<String, String> parents) {
        Frame top = stack.peek();
        if (top == null || !top.mapping) {
            return;
        }
        if (top.expectKey) {
            top.key = value;
            top.expectKey = false;
            return;
        }
        // Значение: интересует только regions -> <id> -> parent (глубина 3 от корня)
        if (stack.size() == 3 && "parent".equalsIgnoreCase(top.key) && top.name != null && !value.isBlank()) {
            Iterator<Frame> it = stack.iterator();
            it.next();
            Frame regions = it.next();
            if (regions.mapping && "regions".equalsIgnoreCase(regions.name)) {
                parents.put(Yamls.normalizeId(top.name), value.trim());
            }
        }
        nodeDone(top);
    }

    /** Узел (ключ или значение) в карте прочитан. */
    private static void nodeDone(Frame frame) {
        if (frame == null || !frame.mapping) {
            return;
        }
        if (frame.expectKey) {
            frame.key = null;
            frame.expectKey = false;
        } else {
            frame.key = null;
            frame.expectKey = true;
        }
    }

    private static final class Frame {
        final boolean mapping;
        final String name;
        final boolean isKey;
        boolean expectKey = true;
        String key;

        Frame(boolean mapping, String name, boolean isKey) {
            this.mapping = mapping;
            this.name = name;
            this.isKey = isKey;
        }
    }
}
