package me.majorzxc.wgregionlist.region;

import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.FlagUtil;
import me.majorzxc.wgregionlist.yaml.Geometry;
import me.majorzxc.wgregionlist.yaml.RegionEdit;

import java.util.Map;
import java.util.Objects;

/** Превращает разницу двух снимков региона в минимальный набор правок файла. */
public final class RegionDiff {

    private RegionDiff() {
    }

    /** @return правка или null, если отличий нет */
    public static RegionEdit between(String regionId, RegionSnapshot before, RegionSnapshot after) {
        RegionEdit edit = RegionEdit.update(regionId);

        if (!Objects.equals(before.geometry(), after.geometry()) && after.geometry() != null) {
            Map<String, Object> keys = after.geometry().toYaml();
            for (String key : Geometry.KEYS) {
                if (keys.containsKey(key)) {
                    edit.set(key, keys.get(key));
                } else {
                    edit.remove(key);
                }
            }
        }
        if (before.priority() != after.priority()) {
            edit.set("priority", after.priority());
        }

        // Флаги правим по одному, чтобы не трогать остальные ключи в разделе flags
        for (Map.Entry<Flag<?>, Object> entry : after.flags().entrySet()) {
            Flag<?> flag = entry.getKey();
            if (!Objects.equals(before.flags().get(flag), entry.getValue())) {
                Object marshalled = FlagUtil.marshal(Map.<Flag<?>, Object>of(flag, entry.getValue()))
                        .get(flag.getName());
                if (marshalled != null) {
                    edit.setFlag(flag.getName(), marshalled);
                }
            }
        }
        for (Flag<?> flag : before.flags().keySet()) {
            if (!after.flags().containsKey(flag)) {
                edit.removeFlag(flag.getName());
            }
        }

        if (!before.owners().equals(after.owners())) {
            edit.set("owners", after.owners().toYaml());
        }
        if (!before.members().equals(after.members())) {
            edit.set("members", after.members().toYaml());
        }
        if (!Objects.equals(before.parentId(), after.parentId())) {
            if (after.parentId() == null) {
                edit.remove("parent");
            } else {
                edit.set("parent", after.parentId());
            }
        }
        return edit.isEmpty() ? null : edit;
    }
}
