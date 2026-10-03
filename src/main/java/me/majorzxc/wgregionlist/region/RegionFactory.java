package me.majorzxc.wgregionlist.region;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.registry.FlagRegistry;
import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.majorzxc.wgregionlist.yaml.DomainData;
import me.majorzxc.wgregionlist.yaml.Geometry;
import me.majorzxc.wgregionlist.yaml.RegionDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/** Создаёт регионы WorldGuard из описаний в файлах. */
public final class RegionFactory {

    private final FlagRegistry flagRegistry;

    public RegionFactory(FlagRegistry flagRegistry) {
        this.flagRegistry = flagRegistry;
    }

    /**
     * Создаёт новый transient-регион (родитель назначается отдельно).
     *
     * @throws IllegalArgumentException если WorldGuard не принял ID или геометрию
     */
    public AddonRegion create(RegionDefinition definition, Path source) {
        String id = definition.id();
        AddonRegion region = switch (definition.geometry()) {
            case Geometry.Cuboid c -> new AddonCuboidRegion(id,
                    BlockVector3.at(c.minX(), c.minY(), c.minZ()),
                    BlockVector3.at(c.maxX(), c.maxY(), c.maxZ()), source);
            case Geometry.Polygon p -> {
                List<BlockVector2> points = new ArrayList<>(p.points().size());
                for (Geometry.Point point : p.points()) {
                    points.add(BlockVector2.at(point.x(), point.z()));
                }
                yield new AddonPolygonalRegion(id, points, p.minY(), p.maxY(), source);
            }
            case Geometry.Global g -> new AddonGlobalRegion(id, source);
        };
        applyData(region, definition);
        return region;
    }

    /** Переносит приоритет, флаги, владельцев и участников (на месте, без пересоздания региона). */
    public void applyData(AddonRegion target, RegionDefinition definition) {
        ProtectedRegion region = target.region();
        region.setPriority(definition.priority());
        // Флаги разбирает сам WorldGuard — так же, как при чтении своего regions.yml.
        // createUnknown = true: флаги ещё не загруженных плагинов сохраняются, а не теряются.
        region.setFlags(flagRegistry.unmarshal(new HashMap<>(definition.flags()), true));
        region.setOwners(toDomain(definition.owners()));
        region.setMembers(toDomain(definition.members()));
    }

    /**
     * Делает transient-копию региона, которым WorldGuard заменил регион аддона
     * (так работает /rg redefine). Возвращает null для неизвестных типов регионов.
     */
    public AddonRegion adopt(ProtectedRegion replacement, AddonRegion previous) {
        Path source = previous.addonState().source();
        String id = replacement.getId();
        AddonRegion adopted;
        if (replacement instanceof ProtectedCuboidRegion cuboid) {
            adopted = new AddonCuboidRegion(id, cuboid.getMinimumPoint(), cuboid.getMaximumPoint(), source);
        } else if (replacement instanceof ProtectedPolygonalRegion polygon) {
            adopted = new AddonPolygonalRegion(id, polygon.getPoints(),
                    polygon.getMinimumPoint().y(), polygon.getMaximumPoint().y(), source);
        } else if (replacement instanceof GlobalProtectedRegion) {
            adopted = new AddonGlobalRegion(id, source);
        } else {
            return null;
        }
        adopted.region().copyFrom(replacement);
        if (adopted.region().getParent() == null) {
            adopted.addonState().setUnresolvedParentId(previous.addonState().unresolvedParentId());
        }
        return adopted;
    }

    /** Геометрия региона WorldGuard в «чистом» виде; null для неизвестных типов. */
    public static Geometry geometryOf(ProtectedRegion region) {
        if (region instanceof ProtectedCuboidRegion) {
            BlockVector3 min = region.getMinimumPoint();
            BlockVector3 max = region.getMaximumPoint();
            return Geometry.Cuboid.of(min.x(), min.y(), min.z(), max.x(), max.y(), max.z());
        }
        if (region instanceof ProtectedPolygonalRegion polygon) {
            List<Geometry.Point> points = new ArrayList<>();
            for (BlockVector2 point : polygon.getPoints()) {
                points.add(new Geometry.Point(point.x(), point.z()));
            }
            return new Geometry.Polygon(points, polygon.getMinimumPoint().y(), polygon.getMaximumPoint().y());
        }
        if (region instanceof GlobalProtectedRegion) {
            return new Geometry.Global();
        }
        return null;
    }

    private static DefaultDomain toDomain(DomainData data) {
        DefaultDomain domain = new DefaultDomain();
        data.players().forEach(domain::addPlayer);
        for (String uuid : data.uniqueIds()) {
            domain.addPlayer(UUID.fromString(uuid));
        }
        data.groups().forEach(domain::addGroup);
        return domain;
    }
}
