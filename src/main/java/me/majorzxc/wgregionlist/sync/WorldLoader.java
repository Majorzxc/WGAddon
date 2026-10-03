package me.majorzxc.wgregionlist.sync;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion.CircularInheritanceException;
import me.majorzxc.wgregionlist.config.Settings;
import me.majorzxc.wgregionlist.region.AddonRegion;
import me.majorzxc.wgregionlist.region.RegionFactory;
import me.majorzxc.wgregionlist.region.RegionSnapshot;
import me.majorzxc.wgregionlist.util.PluginLog;
import me.majorzxc.wgregionlist.wg.WorldGuardBridge;
import me.majorzxc.wgregionlist.yaml.ParentKeyScanner;
import me.majorzxc.wgregionlist.yaml.ParsedFile;
import me.majorzxc.wgregionlist.yaml.RegionDefinition;
import me.majorzxc.wgregionlist.yaml.RegionFileReader;
import me.majorzxc.wgregionlist.yaml.RegionFiles;
import me.majorzxc.wgregionlist.yaml.Yamls;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Загружает регионы одного мира из файлов и добавляет их в WorldGuard.
 *
 * <p>Принцип отказоустойчивости: ошибка в файле никогда не снимает защиту. Если файл
 * (или папка) не читается, ранее загруженные из него регионы остаются как были;
 * если битый отдельный регион — остаётся его прошлая версия.</p>
 */
final class WorldLoader {

    private final PluginLog log;
    private final Path dataFolder;
    private final Path regionsDir;
    private final WorldGuardBridge bridge;
    private final RegionFactory factory;
    private final RegionFileReader reader = new RegionFileReader();
    /** Ссылки на родителей из regions.yml WorldGuard, снятые до его запуска (см. StartupParents). */
    private final Map<Path, Map<String, String>> startupParents;

    WorldLoader(PluginLog log, Path dataFolder, Path regionsDir, WorldGuardBridge bridge, RegionFactory factory,
                Map<Path, Map<String, String>> startupParents) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.regionsDir = regionsDir;
        this.bridge = bridge;
        this.factory = factory;
        this.startupParents = new HashMap<>(startupParents);
    }

    LoadSummary load(WorldState ws, RegionManager manager, Settings settings) {
        String world = ws.worldName;
        // Свежий индекс — WorldGuard только что прочитал свои регионы (запуск, /wg reload, /rg load)
        boolean freshIndex = ws.manager != manager || ws.indexIdentity != bridge.indexIdentity(manager);
        Problems problems = new Problems(world);

        // 1. Читаем файлы всех папок мира
        List<ParsedFile> parsed = new ArrayList<>();
        List<Path> brokenFolders = new ArrayList<>();
        for (String folder : ws.folders) {
            Path dir = regionsDir.resolve(folder);
            try {
                Files.createDirectories(dir);
                for (Path file : RegionFiles.list(dir)) {
                    parsed.add(reader.read(file));
                }
            } catch (IOException | RuntimeException e) {
                brokenFolders.add(dir);
                problems.error("не удалось прочитать папку " + rel(dir) + ": " + e.getMessage());
            }
        }

        // 2. Отбираем описания регионов; при повторе ID побеждает файл, который идёт раньше
        Map<String, RegionDefinition> definitions = new LinkedHashMap<>();
        Map<String, Path> sources = new HashMap<>();
        Set<Path> failedFiles = new HashSet<>();
        Map<Path, Set<String>> invalidIds = new HashMap<>();
        Map<Path, String> fileErrors = new LinkedHashMap<>();
        for (ParsedFile file : parsed) {
            String name = rel(file.file());
            file.warnings().forEach(warning -> problems.warn(name + ": " + warning));
            if (file.failed()) {
                failedFiles.add(file.file());
                fileErrors.put(file.file(), file.fatalError());
                problems.error(name + ": " + file.fatalError() + " — файл не загружен");
                continue;
            }
            fileErrors.put(file.file(), null);
            invalidIds.put(file.file(), file.invalidIds());
            for (RegionDefinition definition : file.regions()) {
                String id = definition.normalizedId();
                Path first = sources.putIfAbsent(id, file.file());
                if (first != null) {
                    problems.warn(name + ": регион '" + definition.id() + "' пропущен — такой ID уже есть в " + rel(first));
                    continue;
                }
                definitions.put(id, definition);
            }
            log.debug(world, name + ": регионов в файле " + file.regions().size());
        }

        Map<String, AddonRegion> previous = new LinkedHashMap<>(ws.regions);
        Map<String, AddonRegion> result = new LinkedHashMap<>();
        Map<String, String> wantedParents = new HashMap<>();
        int conflicts = 0;

        // 3. Регионы из нечитаемых файлов/папок и битые регионы сохраняют прошлую версию
        int kept = 0;
        for (Map.Entry<String, AddonRegion> entry : previous.entrySet()) {
            String id = entry.getKey();
            AddonRegion old = entry.getValue();
            Path source = old.addonState().source();
            boolean sourceBroken = failedFiles.contains(source)
                    || brokenFolders.stream().anyMatch(source::startsWith)
                    || invalidIds.getOrDefault(source, Set.of()).contains(id);
            if (definitions.containsKey(id) || !sourceBroken || !belongsToWorld(ws, source)) {
                continue;
            }
            ProtectedRegion existing = manager.getRegion(id);
            if (existing != null && existing != old.region() && !AddonRegion.isAddonRegion(existing)) {
                conflicts++;
                continue;
            }
            result.put(id, old);
            putEffectiveParent(wantedParents, id, old);
            kept++;
        }
        if (kept > 0) {
            problems.warn("оставлены прежние версии регионов из файлов с ошибками: " + kept);
        }

        // 4. Создаём новые регионы или обновляем существующие на месте
        for (RegionDefinition definition : definitions.values()) {
            String id = definition.normalizedId();
            Path source = sources.get(id);
            ProtectedRegion existing = manager.getRegion(id);
            if (existing != null && !AddonRegion.isAddonRegion(existing)) {
                conflicts++;
                problems.warn(rel(source) + ": регион '" + definition.id()
                        + "' уже есть в самом WorldGuard — используется версия WorldGuard");
                continue;
            }
            AddonRegion old = previous.get(id);
            boolean attachedOld = old != null && existing == old.region();
            try {
                if (attachedOld && definition.geometry().equals(RegionFactory.geometryOf(old.region()))) {
                    // Геометрия та же — обновляем на месте, без перестройки индекса WorldGuard
                    factory.applyData(old, definition);
                    old.addonState().setSource(source);
                    result.put(id, old);
                } else {
                    result.put(id, factory.create(definition, source));
                }
                if (definition.parentId() != null) {
                    wantedParents.put(id, Yamls.normalizeId(definition.parentId()));
                }
            } catch (IllegalArgumentException e) {
                problems.warn(rel(source) + ": WorldGuard не принял регион '" + definition.id() + "': " + e.getMessage());
                if (attachedOld) {
                    result.put(id, old);
                    putEffectiveParent(wantedParents, id, old);
                }
            }
        }

        // 5. Удаляем из WorldGuard регионы, которых больше нет в файлах
        int removed = 0;
        for (Map.Entry<String, AddonRegion> entry : previous.entrySet()) {
            String id = entry.getKey();
            if (!result.containsKey(id) && manager.getRegion(id) == entry.getValue().region()) {
                manager.removeRegion(id, RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
                removed++;
            }
        }

        // 6. Родители (могут быть в любом файле аддона или в самом WorldGuard)
        resolveParents(manager, result, wantedParents, problems);

        // 7. Добавляем в WorldGuard одной операцией
        List<ProtectedRegion> toAdd = new ArrayList<>();
        for (AddonRegion region : result.values()) {
            if (manager.getRegion(region.region().getId()) != region.region()) {
                toAdd.add(region.region());
            }
        }
        bridge.addAll(manager, toAdd);

        // 8. Восстанавливаем связи «регион WorldGuard → родитель из аддона»
        int restored = 0;
        if (settings.restoreNativeParents() && freshIndex) {
            restored = restoreNativeParents(manager, result, problems);
        }

        // 9. Запоминаем состояние, соответствующее файлам
        for (AddonRegion region : result.values()) {
            region.addonState().consumeChanges(region.region());
            region.addonState().setBaseline(RegionSnapshot.of(region.region()));
        }

        ws.regions.clear();
        ws.regions.putAll(result);
        ws.manager = manager;
        ws.indexIdentity = bridge.indexIdentity(manager);
        ws.conflicts = conflicts;
        ws.problems = problems.count;
        ws.files.clear();
        Map<Path, Integer> perFile = new HashMap<>();
        result.values().forEach(region -> perFile.merge(region.addonState().source(), 1, Integer::sum));
        fileErrors.forEach((file, error) -> ws.files.put(file, new WorldState.FileStatus(perFile.getOrDefault(file, 0), error)));

        LoadSummary summary = new LoadSummary(world, fileErrors.size(), result.size(), problems.count, conflicts, removed, restored);
        logSummary(summary, toAdd.size());
        return summary;
    }

    private void resolveParents(RegionManager manager, Map<String, AddonRegion> result,
                                Map<String, String> wanted, Problems problems) {
        // Циклы внутри аддона (a → b → a) находим заранее
        Set<String> cyclic = new HashSet<>();
        for (String id : wanted.keySet()) {
            Set<String> seen = new HashSet<>();
            String current = id;
            while (true) {
                String parent = wanted.get(current);
                if (parent == null || !result.containsKey(parent)) {
                    break;
                }
                if (parent.equals(id)) {
                    cyclic.add(id);
                    break;
                }
                if (!seen.add(parent)) {
                    break;
                }
                current = parent;
            }
        }

        // Шаг А: снимаем родителей, которые поменяются (иначе возможны временные циклы)
        for (Map.Entry<String, AddonRegion> entry : result.entrySet()) {
            ProtectedRegion region = entry.getValue().region();
            ProtectedRegion target = cyclic.contains(entry.getKey()) ? null
                    : findParent(wanted.get(entry.getKey()), manager, result);
            if (region.getParent() != null && region.getParent() != target) {
                region.clearParent();
            }
        }

        // Шаг Б: назначаем новых
        for (Map.Entry<String, AddonRegion> entry : result.entrySet()) {
            String id = entry.getKey();
            AddonRegion addon = entry.getValue();
            String parentId = wanted.get(id);
            if (parentId == null) {
                addon.addonState().setUnresolvedParentId(null);
                continue;
            }
            if (cyclic.contains(id)) {
                problems.warn("регион '" + id + "': циклическое наследование через '" + parentId + "' — родитель не назначен");
                addon.addonState().setUnresolvedParentId(parentId);
                continue;
            }
            ProtectedRegion target = findParent(parentId, manager, result);
            if (target == null) {
                problems.warn("регион '" + id + "': родитель '" + parentId + "' не найден ни в аддоне, ни в WorldGuard");
                addon.addonState().setUnresolvedParentId(parentId);
                continue;
            }
            if (addon.region().getParent() == target) {
                continue;
            }
            try {
                addon.region().setParent(target);
            } catch (CircularInheritanceException e) {
                problems.warn("регион '" + id + "': циклическое наследование через '" + parentId + "' — родитель не назначен");
                addon.addonState().setUnresolvedParentId(parentId);
            }
        }
    }

    private static ProtectedRegion findParent(String parentId, RegionManager manager, Map<String, AddonRegion> result) {
        if (parentId == null) {
            return null;
        }
        AddonRegion addon = result.get(parentId);
        if (addon != null) {
            return addon.region();
        }
        ProtectedRegion found = manager.getRegion(parentId);
        // Старые объекты аддона, которые сейчас удаляются, родителями не считаем
        return found != null && !AddonRegion.isAddonRegion(found) ? found : null;
    }

    /**
     * WorldGuard при чтении своего regions.yml не знает о регионах аддона и отбрасывает
     * ссылку на такого родителя. Читаем файл WorldGuard (потоково) и восстанавливаем связи.
     */
    private int restoreNativeParents(RegionManager manager, Map<String, AddonRegion> result, Problems problems) {
        if (result.isEmpty()) {
            return 0;
        }
        Path file = bridge.worldGuardRegionsFile(manager.getName());
        if (file == null) {
            return 0; // WorldGuard хранит регионы в базе данных
        }
        Map<String, String> declared = new HashMap<>();
        if (Files.isRegularFile(file)) {
            try {
                declared.putAll(ParentKeyScanner.scan(file));
            } catch (IOException e) {
                problems.warn("не удалось прочитать " + file + " для восстановления родителей: " + e.getMessage());
            }
        }
        // При первой загрузке мира добавляем ссылки, снятые до запуска WorldGuard: он мог
        // уже переписать файл без них (например, при UUID-миграции)
        Map<String, String> early = startupParents.remove(file.toAbsolutePath().normalize());
        if (early != null) {
            early.forEach(declared::putIfAbsent);
        }
        int restored = 0;
        for (Map.Entry<String, String> entry : declared.entrySet()) {
            AddonRegion parent = result.get(Yamls.normalizeId(entry.getValue()));
            if (parent == null) {
                continue;
            }
            ProtectedRegion child = manager.getRegion(entry.getKey());
            if (child == null || AddonRegion.isAddonRegion(child) || child.getParent() != null) {
                continue;
            }
            try {
                child.setParent(parent.region());
                restored++;
            } catch (CircularInheritanceException e) {
                problems.warn("регион WorldGuard '" + child.getId() + "': циклическое наследование через '"
                        + parent.region().getId() + "' — родитель не восстановлен");
            }
        }
        return restored;
    }

    private void logSummary(LoadSummary summary, int added) {
        StringBuilder text = new StringBuilder("регионов аддона: ").append(summary.regions())
                .append(" (файлов: ").append(summary.files()).append(')');
        if (summary.removed() > 0) {
            text.append(", удалено: ").append(summary.removed());
        }
        if (summary.restoredParents() > 0) {
            text.append(", восстановлено связей с родителями: ").append(summary.restoredParents());
        }
        if (summary.conflicts() > 0) {
            text.append(", конфликтов ID с WorldGuard: ").append(summary.conflicts());
        }
        if (summary.problems() > 0) {
            text.append(", предупреждений: ").append(summary.problems());
        }
        log.info(summary.world(), text.toString());
        log.debug(summary.world(), "добавлено в индекс WorldGuard: " + added);
    }

    private boolean belongsToWorld(WorldState ws, Path source) {
        for (String folder : ws.folders) {
            if (source.startsWith(regionsDir.resolve(folder))) {
                return true;
            }
        }
        return false;
    }

    private static void putEffectiveParent(Map<String, String> wanted, String id, AddonRegion region) {
        ProtectedRegion parent = region.region().getParent();
        String parentId = parent != null ? parent.getId() : region.addonState().unresolvedParentId();
        if (parentId != null) {
            wanted.put(id, parentId);
        }
    }

    private String rel(Path path) {
        return RegionFiles.relativeName(dataFolder, path);
    }

    /** Счётчик проблем с выводом в лог. */
    private final class Problems {
        private final String world;
        private int count;

        Problems(String world) {
            this.world = world;
        }

        void warn(String message) {
            count++;
            log.warn(world, message);
        }

        void error(String message) {
            count++;
            log.error(world, message);
        }
    }
}
