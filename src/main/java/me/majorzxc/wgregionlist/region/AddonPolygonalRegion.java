package me.majorzxc.wgregionlist.region;

import com.sk89q.worldedit.math.BlockVector2;
import com.sk89q.worldguard.protection.regions.ProtectedPolygonalRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.nio.file.Path;
import java.util.List;

/** Полигональный (poly2d) регион аддона. */
public final class AddonPolygonalRegion extends ProtectedPolygonalRegion implements AddonRegion {

    private final AddonRegionState state;

    public AddonPolygonalRegion(String id, List<BlockVector2> points, int minY, int maxY, Path source) {
        super(id, true, points, minY, maxY);
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
