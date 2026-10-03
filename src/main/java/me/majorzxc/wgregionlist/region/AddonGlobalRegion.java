package me.majorzxc.wgregionlist.region;

import com.sk89q.worldguard.protection.regions.GlobalProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.nio.file.Path;

/** Глобальный регион аддона (например, __global__). */
public final class AddonGlobalRegion extends GlobalProtectedRegion implements AddonRegion {

    private final AddonRegionState state;

    public AddonGlobalRegion(String id, Path source) {
        super(id, true);
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
