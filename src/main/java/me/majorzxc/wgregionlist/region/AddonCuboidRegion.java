package me.majorzxc.wgregionlist.region;

import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.nio.file.Path;

/** Кубоидный регион аддона. Логика отслеживания изменений — в {@link AddonRegionState}. */
public final class AddonCuboidRegion extends ProtectedCuboidRegion implements AddonRegion {

    // null, пока работает конструктор родителя (он уже вызывает setDirty)
    private final AddonRegionState state;

    public AddonCuboidRegion(String id, BlockVector3 min, BlockVector3 max, Path source) {
        super(id, true, min, max);
        this.state = new AddonRegionState(source);
    }

    @Override
    public AddonRegionState addonState() {
        return state;
    }

    @Override
    public void setDirty(boolean dirty) {
        if (state != null) {
            state.onSetDirty(this, dirty);
        }
        super.setDirty(dirty);
    }

    @Override
    public void setParent(ProtectedRegion parent) throws CircularInheritanceException {
        super.setParent(parent);
        if (state != null) {
            state.clearUnresolvedParent();
        }
    }

    @Override
    public void clearParent() {
        super.clearParent();
        if (state != null) {
            state.clearUnresolvedParent();
        }
    }
}
