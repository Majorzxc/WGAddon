package me.majorzxc.wgregionlist.yaml;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.reader.UnicodeReader;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Общие настройки SnakeYAML и вспомогательные функции.
 * Используется SnakeYAML, который уже встроен в Paper, — дополнительных библиотек не нужно.
 */
public final class Yamls {

    /** Тот же шаблон, что у WorldGuard (ProtectedRegion#isValidId). */
    private static final Pattern VALID_ID = Pattern.compile("^[A-Za-z0-9_,'\\-+/]+$");

    private Yamls() {
    }

    /**
     * Нормализует ID региона так же, как WorldGuard (Normal#normalize):
     * нижний регистр + Unicode NFC.
     */
    public static String normalizeId(String id) {
        return Normalizer.normalize(id.toLowerCase(), Normalizer.Form.NFC);
    }

    public static boolean isValidId(String id) {
        return VALID_ID.matcher(id).matches();
    }

    static LoaderOptions loaderOptions(boolean processComments) {
        LoaderOptions options = new LoaderOptions();
        options.setProcessComments(processComments);
        // По умолчанию SnakeYAML не читает файлы больше ~3 МБ — снимаем ограничение.
        options.setCodePointLimit(Integer.MAX_VALUE);
        options.setAllowDuplicateKeys(true);
        return options;
    }

    /** Настройки вывода, похожие на формат regions.yml самого WorldGuard. */
    static DumperOptions dumperOptions(int indent) {
        DumperOptions options = new DumperOptions();
        options.setProcessComments(true);
        options.setIndent(indent);
        // AUTO: простые карты/списки пишутся в одну строку — {x: 1, y: 2, z: 3}, как у WorldGuard
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.AUTO);
        options.setSplitLines(false);
        options.setWidth(Integer.MAX_VALUE);
        options.setAllowUnicode(true);
        return options;
    }

    /** YAML для правки файлов с сохранением комментариев. */
    static Yaml editorYaml(int indent) {
        LoaderOptions loader = loaderOptions(true);
        DumperOptions dumper = dumperOptions(indent);
        return new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
    }

    /** Читает YAML-файл в обычные Map/List (без комментариев). Возвращает null для пустого файла. */
    public static Object loadPlain(Path file) throws IOException {
        LoaderOptions loader = loaderOptions(false);
        DumperOptions dumper = new DumperOptions();
        Yaml yaml = new Yaml(new SafeConstructor(loader), new Representer(dumper), dumper, loader);
        try (Reader reader = new UnicodeReader(Files.newInputStream(file))) {
            return yaml.load(reader);
        }
    }

    /** Поиск ключа без учёта регистра; при дублях побеждает последний (как в SnakeYAML). */
    static Object getIgnoreCase(Map<?, ?> map, String key) {
        Object found = null;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                found = entry.getValue();
            }
        }
        return found;
    }

    static boolean containsIgnoreCase(Map<?, ?> map, String key) {
        for (Object k : map.keySet()) {
            if (key.equalsIgnoreCase(String.valueOf(k))) {
                return true;
            }
        }
        return false;
    }

    /** Сообщение исключения в одну строку — для логов. */
    static String oneLine(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.replaceAll("\\s*\\R\\s*", " ").trim();
    }
}
