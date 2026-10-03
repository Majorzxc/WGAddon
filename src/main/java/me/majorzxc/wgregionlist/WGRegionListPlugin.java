package me.majorzxc.wgregionlist;

import me.majorzxc.wgregionlist.command.WgrlCommand;
import me.majorzxc.wgregionlist.config.Settings;
import me.majorzxc.wgregionlist.listener.WorldListener;
import me.majorzxc.wgregionlist.sync.RegionService;
import me.majorzxc.wgregionlist.util.PluginLog;
import me.majorzxc.wgregionlist.wg.WorldGuardBridge;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * WGRegionList — дополнительные файлы регионов для WorldGuard.
 *
 * <p>Регионы из plugins/WGRegionList/regions/&lt;папка&gt;/*.yml добавляются в WorldGuard
 * как обычные регионы мира, к которому привязана папка. Регионы самого WorldGuard
 * и его файлы не изменяются.</p>
 */
public final class WGRegionListPlugin extends JavaPlugin {

    /** Сколько основной поток ждёт первую загрузку регионов при старте сервера. */
    private static final long STARTUP_TIMEOUT_SECONDS = 120;

    private PluginLog log;
    private RegionService service;
    private volatile Settings settings;

    @Override
    public void onEnable() {
        log = new PluginLog(getLogger());
        Path dataFolder = getDataFolder().toPath();
        saveDefaultConfig();
        if (!Files.exists(dataFolder.resolve("regions"))) {
            // Первый запуск — кладём пример файла (полностью закомментирован)
            saveResource("regions/world/example.yml", false);
        }

        Settings initial;
        try {
            initial = loadSettings();
        } catch (Exception e) {
            log.error("config.yml не прочитан: " + e.getMessage()
                    + " — регионы аддона не загружены. Исправьте файл и выполните /wgrl reload");
            initial = Settings.parse(Map.of("worlds", Map.of()), warning -> { });
            settings = initial;
        }

        try {
            service = new RegionService(log, dataFolder, new WorldGuardBridge(getLogger()));
            service.start(initial, STARTUP_TIMEOUT_SECONDS);
        } catch (LinkageError | RuntimeException e) {
            log.error("Не удалось подключиться к WorldGuard — проверьте, что установлены WorldGuard 7.0.13+ "
                    + "и WorldEdit для вашей версии сервера", e);
            service = null;
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getServer().getPluginManager().registerEvents(new WorldListener(service), this);
        PluginCommand command = getCommand("wgregionlist");
        if (command != null) {
            WgrlCommand executor = new WgrlCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    @Override
    public void onDisable() {
        if (service != null) {
            // Сохраняем изменения из игры. Регионы из WorldGuard не убираем: при остановке
            // сервера это бессмысленно, а дочерние регионы WorldGuard потеряли бы родителей.
            service.shutdown();
            service = null;
        }
    }

    /** Читает config.yml; при успехе запоминает настройки. */
    public Settings loadSettings() throws IOException {
        Path file = getDataFolder().toPath().resolve("config.yml");
        Settings loaded = Settings.load(file, warning -> getLogger().warning("config.yml: " + warning));
        settings = loaded;
        return loaded;
    }

    public Settings settings() {
        Settings current = settings;
        return current != null ? current : Settings.parse(Map.of("worlds", Map.of()), warning -> { });
    }

    public RegionService service() {
        return service;
    }

    public List<String> knownRegionIds() {
        RegionService current = service;
        return current != null ? current.knownIds() : List.of();
    }

    /** Выполнить в основном потоке сервера (например, ответ на команду). */
    public void runOnMainThread(Runnable task) {
        if (!isEnabled()) {
            return;
        }
        try {
            getServer().getScheduler().runTask(this, task);
        } catch (IllegalPluginAccessException ignored) {
            // плагин выключается
        }
    }
}
