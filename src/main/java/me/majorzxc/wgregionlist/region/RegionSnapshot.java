package me.majorzxc.wgregionlist.region;

import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import me.majorzxc.wgregionlist.yaml.Geometry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Снимок состояния региона. Два снимка сравниваются по значению, а разница
 * превращается в точечные правки файла ({@link RegionDiff}).
 *
 * @param parentId нормализованный ID родителя (или ненайденного родителя из файла), либо null
 */
public record RegionSnapshot(Geometry geometry, int priority, Map<Flag<?>, Object> flags,
                             DomainSnapshot owners, DomainSnapshot members, String parentId) {

    public static RegionSnapshot of(ProtectedRegion region) {
        String parentId;
        ProtectedRegion parent = region.getParent();
        if (parent != null) {
            parentId = parent.getId();
        } else if (region instanceof AddonRegion addon) {
            parentId = addon.addonState().unresolvedParentId();
        } else {
            parentId = null;
        }
        return new RegionSnapshot(
                RegionFactory.geometryOf(region),
                region.getPriority(),
                new HashMap<>(region.getFlags()),
                DomainSnapshot.of(region.getOwners()),
                DomainSnapshot.of(region.getMembers()),
                parentId);
    }

    public record DomainSnapshot(Set<String> players, Set<UUID> uniqueIds, Set<String> groups) {

        static DomainSnapshot of(DefaultDomain domain) {
            return new DomainSnapshot(Set.copyOf(domain.getPlayers()), Set.copyOf(domain.getUniqueIds()),
                    Set.copyOf(domain.getGroups()));
        }

        /** Формат WorldGuard: пустые списки не пишутся; сортировка — для стабильных диффов. */
        Map<String, Object> toYaml() {
            Map<String, Object> map = new LinkedHashMap<>();
            putSorted(map, "players", players);
            putSorted(map, "unique-ids", uniqueIds);
            putSorted(map, "groups", groups);
            return map;
        }

        private static void putSorted(Map<String, Object> map, String key, Set<?> values) {
            if (values.isEmpty()) {
                return;
            }
            List<String> list = new ArrayList<>(values.size());
            for (Object value : values) {
                list.add(String.valueOf(value));
            }
            list.sort(String.CASE_INSENSITIVE_ORDER);
            map.put(key, list);
        }
    }
}
