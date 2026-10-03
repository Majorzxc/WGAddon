package me.majorzxc.wgregionlist.region;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;

/**
 * Регион, загруженный из файлов аддона.
 *
 * <p>Все такие регионы создаются как <b>transient</b>: WorldGuard хранит их в памяти и
 * использует наравне со своими, но никогда не сохраняет в своё хранилище (regions.yml или
 * базу данных). Поэтому файлы WorldGuard остаются нетронутыми.</p>
 */
public interface AddonRegion {

    String PACKAGE_PREFIX = "me.majorzxc.wgregionlist.";

    AddonRegionState addonState();

    default ProtectedRegion region() {
        return (ProtectedRegion) this;
    }

    /**
     * Принадлежит ли регион аддону. Проверка по имени класса нужна на случай перезагрузки
     * плагина через сторонние менеджеры плагинов (тогда класс загружен другим загрузчиком).
     */
    static boolean isAddonRegion(ProtectedRegion region) {
        return region instanceof AddonRegion || region.getClass().getName().startsWith(PACKAGE_PREFIX);
    }
}
