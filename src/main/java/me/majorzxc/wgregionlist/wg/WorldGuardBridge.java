package me.majorzxc.wgregionlist.wg;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionDifference;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.index.ConcurrentRegionIndex;
import com.sk89q.worldguard.protection.managers.storage.RegionDriver;
import com.sk89q.worldguard.protection.managers.storage.file.DirectoryYamlDriver;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Всё взаимодействие с WorldGuard в одном месте.
 *
 * <p>Используется публичное API WorldGuard. Единственное исключение — <b>чтение</b>
 * (не изменение) приватного поля {@code RegionManager.index}: у RegionManager нет метода
 * массового добавления, а {@code addRegion} перестраивает весь пространственный индекс
 * на каждый регион. Через индекс тысячи регионов добавляются за одну перестройку.
 * Если поле недоступно (WorldGuard изменился), используется медленный, но рабочий путь.</p>
 */
public final class WorldGuardBridge {

    private final Logger logger;
    private final Field indexField;
    private final Field yamlRootField;

    public WorldGuardBridge(Logger logger) {
        this.logger = logger;
        this.indexField = findField(RegionManager.class, "index", ConcurrentRegionIndex.class);
        this.yamlRootField = findField(DirectoryYamlDriver.class, "rootDir", File.class);
        if (indexField == null) {
            logger.warning("Не удалось получить доступ к индексу регионов WorldGuard — используется медленный режим "
                    + "загрузки. Плагин работает, но массовая загрузка будет дольше. Сообщите автору версию WorldGuard.");
        }
    }

    public FlagRegistry flagRegistry() {
        return WorldGuard.getInstance().getFlagRegistry();
    }

    private RegionContainer container() {
        return WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    /** Загруженные менеджеры регионов: название мира в нижнем регистре → менеджер. Потокобезопасно. */
    public Map<String, RegionManager> loadedManagers() {
        Map<String, RegionManager> managers = new HashMap<>();
        for (RegionManager manager : container().getLoaded()) {
            managers.put(manager.getName().toLowerCase(Locale.ROOT), manager);
        }
        return managers;
    }

    public boolean hasFastIndexAccess() {
        return indexField != null;
    }

    /**
     * Текущий объект индекса менеджера. Меняется, когда WorldGuard заново загружает регионы
     * мира (/rg load) — так мы узнаём, что регионы аддона нужно добавить снова.
     */
    public Object indexIdentity(RegionManager manager) {
        return index(manager);
    }

    /** Добавляет регионы одной операцией (одна перестройка индекса вместо тысячи). */
    public void addAll(RegionManager manager, Collection<? extends ProtectedRegion> regions) {
        if (regions.isEmpty()) {
            return;
        }
        ConcurrentRegionIndex index = index(manager);
        if (index != null) {
            index.addAll(List.copyOf(regions));
            return;
        }
        for (ProtectedRegion region : regions) {
            manager.addRegion(region);
        }
    }

    /**
     * Сообщает WorldGuard, что эти (не-transient) регионы нужно удалить из его хранилища.
     * Нужно после /rg redefine: WorldGuard мог успеть сохранить у себя копию региона аддона.
     *
     * @return false, если сделать это не удалось
     */
    public boolean markRemovedFromStorage(RegionManager manager, Set<ProtectedRegion> regions) {
        if (regions.isEmpty()) {
            return true;
        }
        ConcurrentRegionIndex index = index(manager);
        if (index == null) {
            return false;
        }
        index.setDirty(new RegionDifference(Set.of(), Set.copyOf(regions)));
        return true;
    }

    /** Путь к regions.yml мира у WorldGuard или null, если WorldGuard хранит регионы в базе данных. */
    public Path worldGuardRegionsFile(String worldName) {
        RegionDriver driver = container().getDriver();
        if (!(driver instanceof DirectoryYamlDriver)) {
            return null;
        }
        Path root = null;
        if (yamlRootField != null) {
            try {
                root = ((File) yamlRootField.get(driver)).toPath();
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // ниже стандартный путь
            }
        }
        if (root == null) {
            root = WorldGuard.getInstance().getPlatform().getConfigDir().resolve("worlds");
        }
        return root.resolve(worldName).resolve("regions.yml");
    }

    private ConcurrentRegionIndex index(RegionManager manager) {
        if (indexField == null) {
            return null;
        }
        try {
            return (ConcurrentRegionIndex) indexField.get(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.fine("Не удалось прочитать индекс WorldGuard: " + e);
            return null;
        }
    }

    private static Field findField(Class<?> owner, String name, Class<?> expectedType) {
        try {
            Field field = owner.getDeclaredField(name);
            if (!expectedType.isAssignableFrom(field.getType())) {
                return null;
            }
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
