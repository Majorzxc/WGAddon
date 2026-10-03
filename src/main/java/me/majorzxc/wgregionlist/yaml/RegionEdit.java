package me.majorzxc.wgregionlist.yaml;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Набор точечных правок одного региона в файле. Меняются только перечисленные ключи —
 * всё остальное (комментарии, порядок, свои ключи пользователя) остаётся как было.
 */
public final class RegionEdit {

    private final String regionId;
    private final boolean delete;
    private final Map<String, Object> set = new LinkedHashMap<>();
    private final Set<String> remove = new LinkedHashSet<>();
    private final Map<String, Object> setFlags = new LinkedHashMap<>();
    private final Set<String> removeFlags = new LinkedHashSet<>();

    private RegionEdit(String regionId, boolean delete) {
        this.regionId = Yamls.normalizeId(regionId);
        this.delete = delete;
    }

    public static RegionEdit update(String regionId) {
        return new RegionEdit(regionId, false);
    }

    public static RegionEdit delete(String regionId) {
        return new RegionEdit(regionId, true);
    }

    public RegionEdit set(String key, Object value) {
        remove.remove(key);
        set.put(key, value);
        return this;
    }

    public RegionEdit remove(String key) {
        set.remove(key);
        remove.add(key);
        return this;
    }

    public RegionEdit setFlag(String name, Object value) {
        removeFlags.remove(name);
        setFlags.put(name, value);
        return this;
    }

    public RegionEdit removeFlag(String name) {
        setFlags.remove(name);
        removeFlags.add(name);
        return this;
    }

    public String regionId() {
        return regionId;
    }

    public boolean isDelete() {
        return delete;
    }

    public boolean isEmpty() {
        return !delete && set.isEmpty() && remove.isEmpty() && setFlags.isEmpty() && removeFlags.isEmpty();
    }

    Map<String, Object> keysToSet() {
        return Collections.unmodifiableMap(set);
    }

    Set<String> keysToRemove() {
        return Collections.unmodifiableSet(remove);
    }

    Map<String, Object> flagsToSet() {
        return Collections.unmodifiableMap(setFlags);
    }

    Set<String> flagsToRemove() {
        return Collections.unmodifiableSet(removeFlags);
    }

    @Override
    public String toString() {
        if (delete) {
            return "удаление " + regionId;
        }
        Set<String> changed = new LinkedHashSet<>(set.keySet());
        changed.addAll(remove);
        if (!setFlags.isEmpty() || !removeFlags.isEmpty()) {
            changed.add("flags");
        }
        return regionId + " " + changed;
    }
}
