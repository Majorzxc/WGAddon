package me.majorzxc.wgregionlist.yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Регион, прочитанный из файла, но ещё не превращённый в регион WorldGuard.
 *
 * @param id       ID как он записан в файле
 * @param flags    «сырые» значения флагов — их разбирает реестр флагов WorldGuard
 * @param parentId ID родителя или null
 */
public record RegionDefinition(String id, Geometry geometry, int priority, Map<String, Object> flags,
                               DomainData owners, DomainData members, String parentId) {

    public RegionDefinition {
        flags = Collections.unmodifiableMap(new LinkedHashMap<>(flags));
    }

    public String normalizedId() {
        return Yamls.normalizeId(id);
    }
}
