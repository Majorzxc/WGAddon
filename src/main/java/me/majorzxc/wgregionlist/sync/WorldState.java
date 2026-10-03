package me.majorzxc.wgregionlist.sync;

import com.sk89q.worldguard.protection.managers.RegionManager;
import me.majorzxc.wgregionlist.region.AddonRegion;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Состояние одного мира из config.yml. Изменяется только рабочим потоком плагина.
 */
final class WorldState {

    final String worldName;
    final String key;
    List<String> folders = List.of();

    /** Менеджер регионов WorldGuard, в который сейчас добавлены регионы аддона (null — мир не загружен). */
    RegionManager manager;
    /** Объект индекса менеджера на момент загрузки — меняется после /rg load. */
    Object indexIdentity;
    /** Менеджер и индекс, загрузка в которые упала с ошибкой, — чтобы не повторять её каждую секунду. */
    RegionManager failedManager;
    Object failedIndex;

    /** Нормализованный ID → регион аддона. */
    final Map<String, AddonRegion> regions = new LinkedHashMap<>();
    /** Файл → результат его последней загрузки. */
    final Map<Path, FileStatus> files = new LinkedHashMap<>();
    int conflicts;
    int problems;

    WorldState(String worldName) {
        this.worldName = worldName;
        this.key = worldName.toLowerCase(Locale.ROOT);
    }

    boolean attached() {
        return manager != null;
    }

    void detach() {
        manager = null;
        indexIdentity = null;
    }

    /** @param error причина, по которой файл не загружен, или null */
    record FileStatus(int regions, String error) {
    }
}
