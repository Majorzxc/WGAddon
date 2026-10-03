package me.majorzxc.wgregionlist.sync;

import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.majorzxc.wgregionlist.config.Settings;
import me.majorzxc.wgregionlist.region.AddonRegion;
import me.majorzxc.wgregionlist.region.RegionFactory;
import me.majorzxc.wgregionlist.util.PluginLog;
import me.majorzxc.wgregionlist.wg.WorldGuardBridge;
import me.majorzxc.wgregionlist.yaml.RegionFiles;
import me.majorzxc.wgregionlist.yaml.Yamls;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * Центральный сервис плагина.
 *
 * <p>Вся работа (чтение и запись файлов, добавление регионов в WorldGuard, поиск изменений)
 * выполняется в одном отдельном потоке «WGRegionList-Worker». Основной поток сервера только
 * ставит задачи в очередь, поэтому плагин не создаёт лагов. Один поток — значит, задачи не
 * мешают друг другу и состояние не нужно синхронизировать.</p>
 */
public final class RegionService {

    /** Как часто проверять, не перезагрузил ли WorldGuard регионы (/wg reload, /rg load, загрузка мира). */
    private static final long CHECK_INTERVAL_MILLIS = 1000;

    public record WorldInfo(String world, List<String> folders, boolean loaded, int files, int failedFiles,
                            int regions, int conflicts, int problems) {
    }

    public record StatusInfo(boolean syncEnabled, int syncInterval, boolean fastIndex, List<WorldInfo> worlds) {
    }

    public record FindResult(String world, String location, boolean addon) {
    }

    public record ReloadResult(List<LoadSummary> loaded, List<String> notLoaded) {
    }

    private final PluginLog log;
    private final Path dataFolder;
    private final Path regionsDir;
    private final WorldGuardBridge bridge;
    private final WorldLoader loader;
    private final ChangeSynchronizer synchronizer;
    private final ScheduledExecutorService worker;

    // Поля ниже меняются только в рабочем потоке
    private final Map<String, WorldState> worlds = new LinkedHashMap<>();
    private ScheduledFuture<?> syncTask;

    private volatile Settings settings;
    /** Отсортированные ID регионов аддона — для подсказок команд (читаются из основного потока). */
    private volatile List<String> knownIds = List.of();

    public RegionService(PluginLog log, Path dataFolder, WorldGuardBridge bridge) {
        this.log = log;
        this.dataFolder = dataFolder;
        this.regionsDir = dataFolder.resolve("regions");
        this.bridge = bridge;
        RegionFactory factory = new RegionFactory(bridge.flagRegistry());
        this.loader = new WorldLoader(log, dataFolder, regionsDir, bridge, factory);
        this.synchronizer = new ChangeSynchronizer(log, dataFolder, bridge, factory);
        this.worker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "WGRegionList-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public Path regionsDir() {
        return regionsDir;
    }

    /**
     * Первая загрузка. Основной поток ждёт её завершения: сервер ещё не принимает игроков,
     * а регионы должны защищать территорию с первой секунды.
     */
    public void start(Settings initial, long timeoutSeconds) {
        Future<?> first = worker.submit(safely("загрузка регионов", () -> {
            applySettings(initial);
            checkWorlds();
        }));
        try {
            first.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Загрузка регионов занимает больше " + timeoutSeconds + " с — продолжается в фоне");
        } catch (ExecutionException e) {
            log.error("Ошибка при загрузке регионов", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker.scheduleWithFixedDelay(safely("проверка миров", this::checkWorlds),
                CHECK_INTERVAL_MILLIS, CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
    }

    /** Перечитать настройки и все файлы регионов. */
    public CompletableFuture<ReloadResult> reload(Settings newSettings) {
        return supply(() -> {
            saveAll();
            applySettings(newSettings);
            Map<String, RegionManager> managers = bridge.loadedManagers();
            List<LoadSummary> loaded = new ArrayList<>();
            List<String> notLoaded = new ArrayList<>();
            for (WorldState ws : worlds.values()) {
                RegionManager manager = managers.get(ws.key);
                if (manager == null) {
                    detach(ws);
                    notLoaded.add(ws.worldName);
                    continue;
                }
                LoadSummary summary = loadSafely(ws, manager);
                if (summary != null) {
                    loaded.add(summary);
                } else {
                    notLoaded.add(ws.worldName + " (ошибка, см. консоль)");
                }
            }
            refreshKnownIds();
            return new ReloadResult(loaded, notLoaded);
        });
    }

    /** Немедленно сохранить изменения из игры. @return сколько файлов записано */
    public CompletableFuture<Integer> saveNow() {
        return supply(this::saveAll);
    }

    public CompletableFuture<StatusInfo> status() {
        return supply(() -> {
            List<WorldInfo> list = new ArrayList<>();
            for (WorldState ws : worlds.values()) {
                int failed = (int) ws.files.values().stream().filter(file -> file.error() != null).count();
                list.add(new WorldInfo(ws.worldName, ws.folders, ws.attached(), ws.files.size(), failed,
                        ws.regions.size(), ws.conflicts, ws.problems));
            }
            Settings current = settings;
            return new StatusInfo(current.syncEnabled(), current.syncIntervalSeconds(), bridge.hasFastIndexAccess(), list);
        });
    }

    /** В каком файле лежит регион (во всех мирах). */
    public CompletableFuture<List<FindResult>> find(String rawId) {
        return supply(() -> {
            String id = Yamls.normalizeId(rawId);
            List<FindResult> results = new ArrayList<>();
            for (WorldState ws : worlds.values()) {
                AddonRegion region = ws.regions.get(id);
                if (region != null) {
                    results.add(new FindResult(ws.worldName,
                            RegionFiles.relativeName(dataFolder, region.addonState().source()), true));
                }
            }
            for (RegionManager manager : bridge.loadedManagers().values()) {
                ProtectedRegion region = manager.getRegion(id);
                if (region != null && !AddonRegion.isAddonRegion(region)) {
                    results.add(new FindResult(manager.getName(), "WorldGuard", false));
                }
            }
            return results;
        });
    }

    public List<String> knownIds() {
        return knownIds;
    }

    /** Попросить проверить миры прямо сейчас (например, после загрузки мира). */
    public void requestCheck() {
        try {
            worker.execute(safely("проверка миров", this::checkWorlds));
        } catch (RejectedExecutionException ignored) {
            // плагин выключается
        }
    }

    /** Остановка: сохраняем изменения из игры и завершаем поток. Регионы остаются в WorldGuard. */
    public void shutdown() {
        Future<?> last = worker.submit(safely("сохранение изменений", () -> {
            if (settings != null && settings.syncEnabled()) {
                saveAll();
            }
        }));
        try {
            last.get(30, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("Сохранение изменений не завершилось за 30 секунд");
        } catch (ExecutionException e) {
            log.error("Ошибка при сохранении изменений", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ───────────────────────── ниже — только рабочий поток ─────────────────────────

    private void applySettings(Settings next) {
        this.settings = next;
        log.setDebug(next.debug());

        Map<String, WorldState> updated = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : next.worlds().entrySet()) {
            String key = entry.getKey().toLowerCase(Locale.ROOT);
            WorldState ws = updated.get(key);
            if (ws != null) {
                // Один мир указан дважды в разном регистре — объединяем папки
                List<String> folders = new ArrayList<>(ws.folders);
                folders.addAll(entry.getValue());
                ws.folders = List.copyOf(folders);
                continue;
            }
            ws = worlds.remove(key);
            if (ws == null) {
                ws = new WorldState(entry.getKey());
            }
            ws.folders = entry.getValue();
            updated.put(key, ws);
        }
        for (WorldState removed : worlds.values()) {
            unbind(removed);
        }
        worlds.clear();
        worlds.putAll(updated);

        if (syncTask != null) {
            syncTask.cancel(false);
            syncTask = null;
        }
        if (next.syncEnabled()) {
            long interval = next.syncIntervalSeconds();
            syncTask = worker.scheduleWithFixedDelay(safely("синхронизация", this::syncAll),
                    interval, interval, TimeUnit.SECONDS);
        }
    }

    /** Следит, чтобы регионы аддона были в актуальном менеджере WorldGuard каждого мира. */
    private void checkWorlds() {
        Map<String, RegionManager> managers = bridge.loadedManagers();
        boolean changed = false;
        for (WorldState ws : worlds.values()) {
            RegionManager manager = managers.get(ws.key);
            if (manager == null) {
                if (ws.attached()) {
                    detach(ws);
                    log.info(ws.worldName, "мир выгружен или в нём отключены регионы — регионы аддона отключены");
                    changed = true;
                }
                continue;
            }
            Object index = bridge.indexIdentity(manager);
            if (manager == ws.manager && index == ws.indexIdentity) {
                continue;
            }
            if (manager == ws.failedManager && index == ws.failedIndex) {
                continue; // уже пытались и получили ошибку — ждём /wgrl reload или новой загрузки мира
            }
            if (ws.attached()) {
                flushIfEnabled(ws);
                log.info(ws.worldName, "WorldGuard заново загрузил регионы мира — подключаю регионы аддона");
            }
            changed = true;
            loadSafely(ws, manager);
        }
        if (changed) {
            refreshKnownIds();
        }
    }

    private void syncAll() {
        syncWorlds();
    }

    /** Синхронизация, если она включена; @return сколько файлов записано. */
    private int saveAll() {
        return settings.syncEnabled() ? syncWorlds() : 0;
    }

    /** Переносит изменения из игры в файлы во всех подключённых мирах. */
    private int syncWorlds() {
        checkWorlds();
        boolean changed = false;
        int written = 0;
        for (WorldState ws : worlds.values()) {
            if (!ws.attached()) {
                continue;
            }
            ChangeSynchronizer.Result result = synchronizer.sync(ws, settings);
            written += result.filesWritten();
            changed |= result.membershipChanged();
            if (result.reloadRequired()) {
                log.info(ws.worldName, "WorldGuard заново загрузил регионы мира во время проверки — подключаю регионы аддона");
                flushIfEnabled(ws);
                RegionManager current = bridge.loadedManagers().get(ws.key);
                if (current == null) {
                    detach(ws);
                } else {
                    loadSafely(ws, current);
                }
                changed = true;
            }
        }
        if (changed) {
            refreshKnownIds();
        }
        return written;
    }

    /**
     * Загрузка мира с защитой от непредвиденных ошибок. При ошибке мир остаётся отключённым
     * (синхронизация его не трогает, файлы не меняются), а повтор будет после /wgrl reload
     * или следующей перезагрузки регионов WorldGuard.
     */
    private LoadSummary loadSafely(WorldState ws, RegionManager manager) {
        try {
            LoadSummary summary = loader.load(ws, manager, settings);
            ws.failedManager = null;
            ws.failedIndex = null;
            return summary;
        } catch (RuntimeException | LinkageError error) {
            log.error("[" + ws.worldName + "] не удалось подключить регионы аддона", error);
            ws.detach();
            ws.failedManager = manager;
            ws.failedIndex = bridge.indexIdentity(manager);
            return null;
        }
    }

    private void flushIfEnabled(WorldState ws) {
        if (settings.syncEnabled()) {
            synchronizer.flush(ws);
        }
    }

    private void detach(WorldState ws) {
        if (ws.attached()) {
            flushIfEnabled(ws);
        }
        ws.detach();
    }

    /** Мир убран из config.yml — убираем его регионы из WorldGuard. */
    private void unbind(WorldState ws) {
        if (!ws.attached()) {
            return;
        }
        flushIfEnabled(ws);
        int removed = 0;
        for (Map.Entry<String, AddonRegion> entry : ws.regions.entrySet()) {
            if (ws.manager.getRegion(entry.getKey()) == entry.getValue().region()) {
                ws.manager.removeRegion(entry.getKey(), RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
                removed++;
            }
        }
        log.info(ws.worldName, "мир убран из config.yml — регионы аддона отключены: " + removed);
        ws.regions.clear();
        ws.detach();
    }

    private void refreshKnownIds() {
        TreeSet<String> ids = new TreeSet<>();
        for (WorldState ws : worlds.values()) {
            ids.addAll(ws.regions.keySet());
        }
        knownIds = List.copyOf(ids);
    }

    private <T> CompletableFuture<T> supply(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, worker);
    }

    /** Исключение в периодической задаче остановило бы её навсегда — перехватываем всё. */
    private Runnable safely(String name, Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable error) {
                log.error("Ошибка (" + name + ")", error);
            }
        };
    }
}
