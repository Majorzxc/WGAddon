package me.majorzxc.wgregionlist.sync;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.internal.platform.WorldGuardPlatform;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.managers.RegionDifference;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.managers.RemovalStrategy;
import com.sk89q.worldguard.protection.managers.index.ChunkHashTable;
import com.sk89q.worldguard.protection.managers.index.PriorityRTreeIndex;
import com.sk89q.worldguard.protection.managers.storage.RegionDatabase;
import com.sk89q.worldguard.protection.managers.storage.RegionDriver;
import com.sk89q.worldguard.protection.managers.storage.StorageException;
import com.sk89q.worldguard.protection.managers.storage.file.DirectoryYamlDriver;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import me.majorzxc.wgregionlist.config.Settings;
import me.majorzxc.wgregionlist.region.AddonRegion;
import me.majorzxc.wgregionlist.util.PluginLog;
import me.majorzxc.wgregionlist.wg.WorldGuardBridge;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Сценарий работы аддона на настоящих классах WorldGuard (без сервера Minecraft):
 * загрузка, родители, изменения из игры, /rg redefine, /rg remove, /rg load, /wg reload,
 * битые файлы и перезагрузка.
 */
class RegionServiceTest {

    @TempDir
    Path root;

    private final List<RegionManager> loaded = new CopyOnWriteArrayList<>();
    private Path wgWorlds;
    private Path data;
    private RegionService service;
    private FlagRegistry flags;

    /** Хранилище WorldGuard в памяти, которое запоминает всё, что WorldGuard пытается сохранить. */
    static final class RecordingDatabase implements RegionDatabase {
        final String world;
        Set<ProtectedRegion> stored = new HashSet<>();
        final List<ProtectedRegion> everSaved = new ArrayList<>();

        RecordingDatabase(String world, Set<ProtectedRegion> initial) {
            this.world = world;
            this.stored = new HashSet<>(initial);
        }

        @Override
        public String getName() {
            return world;
        }

        /**
         * Как настоящее хранилище: каждый раз новые объекты, а связь с родителем,
         * которого нет в этом же хранилище, отбрасывается (так делает WorldGuard).
         */
        @Override
        public Set<ProtectedRegion> loadAll(FlagRegistry registry) throws StorageException {
            Map<String, ProtectedRegion> copies = new HashMap<>();
            for (ProtectedRegion region : stored) {
                ProtectedRegion copy = region instanceof GlobalProtectedRegion
                        ? new GlobalProtectedRegion(region.getId())
                        : new ProtectedCuboidRegion(region.getId(), region.getMinimumPoint(), region.getMaximumPoint());
                copy.setPriority(region.getPriority());
                copy.setFlags(region.getFlags());
                copy.setOwners(region.getOwners());
                copy.setMembers(region.getMembers());
                copies.put(region.getId(), copy);
            }
            for (ProtectedRegion region : stored) {
                ProtectedRegion parent = region.getParent();
                if (parent != null && copies.containsKey(parent.getId())) {
                    try {
                        copies.get(region.getId()).setParent(copies.get(parent.getId()));
                    } catch (ProtectedRegion.CircularInheritanceException e) {
                        throw new StorageException("cycle", e);
                    }
                }
            }
            return new HashSet<>(copies.values());
        }

        @Override
        public void saveAll(Set<ProtectedRegion> regions) {
            stored = new HashSet<>(regions);
            everSaved.addAll(regions);
        }

        @Override
        public void saveChanges(RegionDifference difference) {
            // Так ведёт себя SQL-хранилище WorldGuard: пишет только изменения
            everSaved.addAll(difference.getChanged());
            stored.addAll(difference.getChanged());
            stored.removeAll(difference.getRemoved());
        }
    }

    @BeforeEach
    void setUp() {
        wgWorlds = root.resolve("WorldGuard/worlds");
        data = root.resolve("WGRegionList");
        flags = WorldGuard.getInstance().getFlagRegistry();
        RegionContainer container = new RegionContainer() {
            @Override
            protected RegionManager load(World world) {
                return null;
            }

            @Override
            public List<RegionManager> getLoaded() {
                return List.copyOf(loaded);
            }

            @Override
            public RegionDriver getDriver() {
                return new DirectoryYamlDriver(wgWorlds.toFile(), "regions.yml");
            }
        };
        WorldGuardPlatform platform = (WorldGuardPlatform) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{WorldGuardPlatform.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getRegionContainer" -> container;
                    case "getConfigDir" -> root.resolve("WorldGuard");
                    case "toString" -> "TestPlatform";
                    case "hashCode" -> 0;
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        WorldGuard.getInstance().setPlatform(platform);
    }

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.shutdown();
        }
    }

    private RegionManager newManager(String world, Set<ProtectedRegion> initial) throws Exception {
        RegionManager manager = new RegionManager(new RecordingDatabase(world, initial),
                new ChunkHashTable.Factory(new PriorityRTreeIndex.Factory()), flags);
        manager.load();
        return manager;
    }

    private static RecordingDatabase database(RegionManager manager) throws Exception {
        var field = RegionManager.class.getDeclaredField("store");
        field.setAccessible(true);
        return (RecordingDatabase) field.get(manager);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static void assertNoAddonRegions(Iterable<ProtectedRegion> regions) {
        for (ProtectedRegion region : regions) {
            assertFalse(AddonRegion.isAddonRegion(region), "WorldGuard сохранил регион аддона: " + region.getId());
        }
    }

    @Test
    void fullScenario() throws Exception {
        // ── WorldGuard: родные регионы; kid в regions.yml ссылается на родителя из аддона
        RegionManager world = newManager("world", Set.of(
                new ProtectedCuboidRegion("city", BlockVector3.at(-500, 0, -500), BlockVector3.at(500, 255, 500)),
                new ProtectedCuboidRegion("kid", BlockVector3.at(0, 0, 0), BlockVector3.at(5, 5, 5)),
                new ProtectedCuboidRegion("clash", BlockVector3.at(0, 0, 0), BlockVector3.at(1, 1, 1))));
        loaded.add(world);
        ProtectedRegion city = world.getRegion("city");
        ProtectedRegion kid = world.getRegion("kid");
        ProtectedRegion clash = world.getRegion("clash");
        write(wgWorlds.resolve("world/regions.yml"), """
                regions:
                    city: {type: cuboid, priority: 0}
                    kid: {type: cuboid, priority: 0, parent: spawn}
                    clash: {type: cuboid, priority: 0}
                """);

        Path a = data.resolve("regions/main/a.yml");
        Path b = data.resolve("regions/main/sub/b.yml");
        write(a, """
                # Файл спавна
                regions:
                    # главный регион
                    spawn:
                        type: cuboid
                        min: {x: -50, y: 0, z: -50}
                        max: {x: 50, y: 100, z: 50}
                        priority: 5 # приоритет
                        flags: {pvp: deny, greeting: 'Привет', custom-flag: hello}
                    house:
                        min: {x: 10, y: 0, z: 10}
                        max: {x: 20, y: 10, z: 20}
                        parent: city
                    orphan:
                        type: global
                        parent: missing_parent
                    clash:
                        type: global
                """);
        write(b, """
                regions:
                  shop:
                    type: poly2d
                    min-y: 0
                    max-y: 50
                    points: [{x: 0, z: 0}, {x: 10, z: 0}, {x: 5, z: 10}]
                    parent: spawn
                    members: {players: [alice]}
                  __global__:
                    type: global
                    flags: {pvp: allow}
                """);

        Settings settings = Settings.parse(Map.of("worlds", Map.of("world", List.of("main"))), warning -> { });
        service = new RegionService(new PluginLog(Logger.getLogger("WGRegionListTest")), data,
                new WorldGuardBridge(Logger.getLogger("WGRegionListTest")));
        service.start(settings, 60);

        // ── 1. Загрузка
        ProtectedRegion spawn = world.getRegion("spawn");
        ProtectedRegion shop = world.getRegion("shop");
        ProtectedRegion house = world.getRegion("house");
        assertInstanceOf(AddonRegion.class, spawn);
        assertTrue(spawn.isTransient());
        assertEquals(5, spawn.getPriority());
        assertEquals(StateFlag.State.DENY, spawn.getFlag(Flags.PVP));
        assertEquals("Привет", spawn.getFlag(Flags.GREET_MESSAGE));
        assertNotNull(flags.get("custom-flag"), "неизвестный флаг зарегистрирован как UnknownFlag");
        assertSame(spawn, shop.getParent());
        assertTrue(shop.getMembers().getPlayers().contains("alice"));
        assertSame(city, house.getParent());
        assertNull(world.getRegion("orphan").getParent());
        assertInstanceOf(GlobalProtectedRegion.class, world.getRegion("__global__"));
        assertSame(clash, world.getRegion("clash"), "при совпадении ID побеждает WorldGuard");
        assertSame(spawn, kid.getParent(), "связь kid -> spawn восстановлена");
        assertTrue(world.getApplicableRegionsIDs(BlockVector3.at(1, 1, 1)).contains("spawn"), "регион работает в запросах WorldGuard");

        // WorldGuard сохраняет (полностью и по изменениям) только свои регионы
        world.save();
        world.saveChanges();
        assertNoAddonRegions(database(world).everSaved);
        assertEquals(Set.of("city", "kid", "clash"), ids(database(world).stored));

        // ── 2. Изменения в игре
        spawn.setFlag(Flags.PVP, StateFlag.State.ALLOW);
        spawn.setFlag(Flags.GREET_MESSAGE, null);
        shop.getMembers().addPlayer("bob");
        house.setPriority(7);
        world.saveChanges();
        assertNoAddonRegions(database(world).everSaved);
        assertEquals(2, service.saveNow().get());
        String aText = Files.readString(a);
        assertTrue(aText.contains("flags: {pvp: allow, custom-flag: hello}"), aText);
        assertTrue(aText.contains("# Файл спавна") && aText.contains("# главный регион"), aText);
        assertTrue(aText.contains("priority: 5 # приоритет"), aText);
        assertTrue(aText.contains("priority: 7"), aText);
        assertTrue(aText.contains("parent: missing_parent"), "ненайденный родитель не стёрт из файла");
        assertTrue(Files.readString(b).contains("players: [alice, bob]"), Files.readString(b));
        assertEquals(0, service.saveNow().get(), "без изменений файлы не переписываются");

        // WorldGuard сбрасывает dirty-флаги при своём сохранении — изменение не должно потеряться
        shop.getMembers().addPlayer("carol");
        world.save();
        service.saveNow().get();
        assertTrue(Files.readString(b).contains("carol"));

        // ── 3. /rg redefine (WorldGuard подменяет регион обычным)
        ProtectedRegion redefined = new ProtectedCuboidRegion("house", BlockVector3.at(100, 0, 100), BlockVector3.at(120, 30, 120));
        redefined.copyFrom(house);
        world.addRegion(redefined);
        world.saveChanges(); // WorldGuard успел сохранить копию у себя
        service.saveNow().get();
        ProtectedRegion house2 = world.getRegion("house");
        assertInstanceOf(AddonRegion.class, house2);
        assertSame(city, house2.getParent());
        assertEquals(7, house2.getPriority());
        assertTrue(Files.readString(a).contains("min: {x: 100, y: 0, z: 100}"));
        world.saveChanges();
        assertFalse(ids(database(world).stored).contains("house"), "копия убрана из хранилища WorldGuard");

        // ── 4. /rg remove
        world.removeRegion("orphan", RemovalStrategy.UNSET_PARENT_IN_CHILDREN);
        service.saveNow().get();
        assertFalse(Files.readString(a).contains("orphan:"));

        // ── 5. /rg load — WorldGuard перечитывает своё хранилище и заменяет индекс
        world.load();
        assertNull(world.getRegion("spawn"));
        service.requestCheck();
        service.status().get();
        assertInstanceOf(AddonRegion.class, world.getRegion("spawn"));
        assertSame(world.getRegion("spawn"), world.getRegion("shop").getParent());
        assertSame(world.getRegion("spawn"), world.getRegion("kid").getParent(), "связь kid -> spawn восстановлена снова");

        // ── 6. /wg reload — новый менеджер мира (регион clash из WorldGuard к этому времени удалён)
        RegionManager world2 = newManager("world", Set.of(
                new ProtectedCuboidRegion("city", BlockVector3.at(-500, 0, -500), BlockVector3.at(500, 255, 500)),
                new ProtectedCuboidRegion("kid", BlockVector3.at(0, 0, 0), BlockVector3.at(5, 5, 5))));
        loaded.set(0, world2);
        service.requestCheck();
        service.status().get();
        assertInstanceOf(AddonRegion.class, world2.getRegion("house"));
        assertSame(world2.getRegion("city"), world2.getRegion("house").getParent());
        assertSame(world2.getRegion("spawn"), world2.getRegion("kid").getParent());
        assertInstanceOf(AddonRegion.class, world2.getRegion("clash"), "без конфликта регион аддона загружается");

        // ── 7. Битый файл: регионы из него остаются в WorldGuard
        String good = Files.readString(b);
        write(b, "regions:\n  shop: {type: poly2d\n");
        RegionService.ReloadResult result = service.reload(settings).get();
        assertInstanceOf(AddonRegion.class, world2.getRegion("shop"));
        assertTrue(result.loaded().get(0).problems() > 0);
        write(b, good);

        // ── 8. Правка файла вручную + reload: обновление на месте
        ProtectedRegion spawnBefore = world2.getRegion("spawn");
        write(a, Files.readString(a).replace("priority: 5", "priority: 9"));
        service.reload(settings).get();
        assertSame(spawnBefore, world2.getRegion("spawn"));
        assertEquals(9, spawnBefore.getPriority());

        // ── 9. Регион удалён из файла + reload
        write(a, Files.readString(a).replaceAll("(?s)    clash:\\n        type: global\\n", ""));
        service.reload(settings).get();
        assertNull(world2.getRegion("clash"));

        // ── 10. Остановка сохраняет изменения, регионы остаются в WorldGuard
        world2.getRegion("spawn").setPriority(42);
        service.shutdown();
        service = null;
        assertTrue(Files.readString(a).contains("priority: 42 # приоритет"), Files.readString(a));
        assertNotNull(world2.getRegion("spawn"));
        world2.save();
        assertNoAddonRegions(database(world2).everSaved);
    }

    private static Set<String> ids(Set<ProtectedRegion> regions) {
        Set<String> ids = new HashSet<>();
        regions.forEach(region -> ids.add(region.getId()));
        return ids;
    }
}
