package me.majorzxc.wgregionlist.sync;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.majorzxc.wgregionlist.config.Settings;
import me.majorzxc.wgregionlist.region.AddonRegion;
import me.majorzxc.wgregionlist.region.AddonRegionState;
import me.majorzxc.wgregionlist.region.RegionDiff;
import me.majorzxc.wgregionlist.region.RegionFactory;
import me.majorzxc.wgregionlist.region.RegionSnapshot;
import me.majorzxc.wgregionlist.util.PluginLog;
import me.majorzxc.wgregionlist.wg.WorldGuardBridge;
import me.majorzxc.wgregionlist.yaml.RegionEdit;
import me.majorzxc.wgregionlist.yaml.RegionFileEditor;
import me.majorzxc.wgregionlist.yaml.RegionFiles;

import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Переносит изменения, сделанные в игре, обратно в файлы аддона.
 *
 * <p>Работает в рабочем потоке плагина. Стоимость проверки — один поиск по хеш-таблице
 * на регион; снимок состояния строится только для регионов, которые действительно менялись.</p>
 */
final class ChangeSynchronizer {

    /**
     * @param reloadRequired    WorldGuard заново загрузил регионы мира, а индекс недоступен — нужна перезагрузка
     * @param membershipChanged набор регионов аддона изменился (удаление/замена)
     * @param filesWritten      сколько файлов записано
     */
    record Result(boolean reloadRequired, boolean membershipChanged, int filesWritten) {
        static final Result RELOAD = new Result(true, false, 0);
    }

    /** Отложенная правка: регион (null для удаления), файл, правка и новое состояние. */
    private record Pending(AddonRegion region, Path file, RegionEdit edit, RegionSnapshot snapshot) {
    }

    private final PluginLog log;
    private final Path dataFolder;
    private final WorldGuardBridge bridge;
    private final RegionFactory factory;
    /** Файлы, запись которых сейчас не удаётся, — чтобы не засорять лог одной и той же ошибкой. */
    private final Set<Path> failingFiles = new HashSet<>();

    ChangeSynchronizer(PluginLog log, Path dataFolder, WorldGuardBridge bridge, RegionFactory factory) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.bridge = bridge;
        this.factory = factory;
    }

    /** Полная проверка мира, к которому подключены регионы аддона. */
    Result sync(WorldState ws, Settings settings) {
        RegionManager manager = ws.manager;

        // Проход 1: только классифицируем, ничего не меняя (отметки изменений не трогаем)
        List<AddonRegion> unchanged = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<Map.Entry<String, AddonRegion>> replacedEntries = new ArrayList<>();
        for (Map.Entry<String, AddonRegion> entry : ws.regions.entrySet()) {
            ProtectedRegion current = manager.getRegion(entry.getKey());
            if (current == entry.getValue().region()) {
                unchanged.add(entry.getValue());
            } else if (current == null) {
                removed.add(entry.getKey());
            } else {
                replacedEntries.add(entry);
            }
        }

        // Удаление из файла необратимо, поэтому убеждаемся, что WorldGuard не перезагрузил
        // регионы мира прямо во время проверки (/rg load, /wg reload). Иначе — переподключение.
        if (!removed.isEmpty()) {
            boolean indexStable = bridge.indexIdentity(manager) == ws.indexIdentity
                    && bridge.loadedManagers().get(ws.key) == manager;
            boolean allGone = removed.size() == ws.regions.size() && !bridge.hasFastIndexAccess();
            if (!indexStable || allGone) {
                return Result.RELOAD;
            }
        }

        // Проход 2: применяем
        List<Pending> pending = new ArrayList<>();
        for (AddonRegion addon : unchanged) {
            collectChanges(addon, pending);
        }
        for (String id : removed) {
            AddonRegion addon = ws.regions.remove(id);
            Path source = addon.addonState().source();
            if (settings.deleteRemoved()) {
                pending.add(new Pending(null, source, RegionEdit.delete(id), null));
                log.info(ws.worldName, "регион '" + id + "' удалён в игре — удаляю его из " + rel(source));
            } else {
                log.info(ws.worldName, "регион '" + id + "' удалён в игре; в файле " + rel(source)
                        + " он остался и вернётся после /wgrl reload");
            }
        }

        List<AddonRegion> adopted = new ArrayList<>();
        Set<ProtectedRegion> replaced = new HashSet<>();
        for (Map.Entry<String, AddonRegion> entry : replacedEntries) {
            String id = entry.getKey();
            AddonRegion addon = entry.getValue();
            ProtectedRegion current = manager.getRegion(id);
            if (current == null || current == addon.region()) {
                continue; // успело измениться — разберёмся при следующей проверке
            }
            if (current instanceof AddonRegion other) {
                entry.setValue(other);
            } else if (!current.isTransient()) {
                // WorldGuard подменил регион обычным (так делает /rg redefine) — забираем его обратно в аддон
                AddonRegion copy = factory.adopt(current, addon);
                if (copy == null) {
                    ws.regions.remove(id);
                    log.warn(ws.worldName, "регион '" + id + "' заменён регионом неизвестного типа ("
                            + current.getClass().getName() + ") — аддон больше не отслеживает его");
                    continue;
                }
                copy.addonState().setBaseline(addon.addonState().baseline());
                entry.setValue(copy);
                adopted.add(copy);
                replaced.add(current);
                log.info(ws.worldName, "регион '" + id + "' изменён командой WorldGuard — сохраняю в "
                        + rel(addon.addonState().source()));
            } else {
                ws.regions.remove(id);
                log.warn(ws.worldName, "регион '" + id + "' заменён регионом другого плагина — аддон больше не отслеживает его");
            }
        }

        if (!adopted.isEmpty()) {
            List<ProtectedRegion> regions = new ArrayList<>(adopted.size());
            adopted.forEach(region -> regions.add(region.region()));
            bridge.addAll(manager, regions);
            if (!bridge.markRemovedFromStorage(manager, replaced)) {
                log.warn(ws.worldName, "выполните /rg save -w " + ws.worldName
                        + ", чтобы WorldGuard убрал копии этих регионов из своего хранилища");
            }
            for (AddonRegion region : adopted) {
                region.addonState().markChanged();
                collectChanges(region, pending);
            }
        }

        int written = write(ws.worldName, pending);
        return new Result(false, !removed.isEmpty() || !replacedEntries.isEmpty(), written);
    }

    /**
     * Мир отключается (выгружен или WorldGuard перезагружает регионы):
     * сохраняем только накопленные изменения объектов регионов.
     */
    int flush(WorldState ws) {
        List<Pending> pending = new ArrayList<>();
        for (AddonRegion region : ws.regions.values()) {
            collectChanges(region, pending);
        }
        return write(ws.worldName, pending);
    }

    private static void collectChanges(AddonRegion addon, List<Pending> pending) {
        AddonRegionState state = addon.addonState();
        if (!state.consumeChanges(addon.region())) {
            return;
        }
        RegionSnapshot now = RegionSnapshot.of(addon.region());
        RegionSnapshot before = state.baseline();
        if (before == null) {
            state.setBaseline(now);
            return;
        }
        RegionEdit edit = RegionDiff.between(addon.region().getId(), before, now);
        if (edit != null) {
            pending.add(new Pending(addon, state.source(), edit, now));
        }
    }

    private int write(String world, List<Pending> pending) {
        if (pending.isEmpty()) {
            return 0;
        }
        Map<Path, List<Pending>> byFile = new LinkedHashMap<>();
        for (Pending item : pending) {
            byFile.computeIfAbsent(item.file(), file -> new ArrayList<>()).add(item);
        }

        int written = 0;
        for (Map.Entry<Path, List<Pending>> entry : byFile.entrySet()) {
            Path file = entry.getKey();
            List<Pending> items = entry.getValue();
            List<RegionEdit> edits = items.stream().map(Pending::edit).toList();
            try {
                RegionFileEditor.Result result = RegionFileEditor.apply(file, edits);
                if (failingFiles.remove(file)) {
                    log.info(world, "файл " + rel(file) + " снова записывается");
                }
                acceptSnapshots(items);
                for (String id : result.missingRegions()) {
                    boolean deletion = items.stream().anyMatch(item -> item.edit().isDelete()
                            && item.edit().regionId().equals(id));
                    if (!deletion) {
                        log.warn(world, rel(file) + ": регион '" + id + "' не найден в файле — изменение из игры "
                                + "не сохранено (файл правили вручную? выполните /wgrl reload)");
                    }
                }
                if (result.applied() > 0) {
                    written++;
                    log.debug(world, "сохранено в " + rel(file) + ": " + edits);
                }
            } catch (NoSuchFileException e) {
                acceptSnapshots(items);
                log.warn(world, "файл " + rel(file) + " удалён — изменения " + edits + " не сохранены");
            } catch (IOException | RuntimeException e) {
                if (failingFiles.add(file)) {
                    log.error(world, "не удалось сохранить " + rel(file) + ": " + e.getMessage()
                            + " — повторю при следующей проверке");
                }
                for (Pending item : items) {
                    if (item.region() != null) {
                        item.region().addonState().markChanged();
                    } else {
                        log.warn(world, "удалите регион '" + item.edit().regionId() + "' из " + rel(file) + " вручную");
                    }
                }
            }
        }
        return written;
    }

    private static void acceptSnapshots(List<Pending> items) {
        for (Pending item : items) {
            if (item.region() != null) {
                item.region().addonState().setBaseline(item.snapshot());
            }
        }
    }

    private String rel(Path path) {
        return RegionFiles.relativeName(dataFolder, path);
    }
}
