package me.majorzxc.wgregionlist.region;

import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.nio.file.Path;

/**
 * Служебные данные региона аддона: из какого файла он загружен и что было
 * записано в файл в последний раз (baseline) — от этого считаются изменения.
 */
public final class AddonRegionState {

    private volatile Path source;
    private volatile boolean changed;
    private volatile String unresolvedParentId;
    /** Состояние, совпадающее с файлом. Читается и пишется только рабочим потоком плагина. */
    private RegionSnapshot baseline;

    AddonRegionState(Path source) {
        this.source = source;
    }

    public Path source() {
        return source;
    }

    public void setSource(Path source) {
        this.source = source;
    }

    public RegionSnapshot baseline() {
        return baseline;
    }

    public void setBaseline(RegionSnapshot baseline) {
        this.baseline = baseline;
    }

    /**
     * ID родителя, который указан в файле, но сейчас не найден.
     * Нужен, чтобы при записи изменений не стереть ключ parent из файла.
     */
    public String unresolvedParentId() {
        return unresolvedParentId;
    }

    public void setUnresolvedParentId(String parentId) {
        this.unresolvedParentId = parentId;
    }

    void clearUnresolvedParent() {
        this.unresolvedParentId = null;
    }

    /**
     * Вызывается из переопределённого setDirty. WorldGuard не отслеживает transient-регионы
     * (их isDirty() всегда false), поэтому изменения фиксируем сами. Если WorldGuard
     * сбрасывает флаги (setDirty(false)), сначала запоминаем, не менялись ли владельцы/участники.
     */
    void onSetDirty(ProtectedRegion region, boolean dirty) {
        if (dirty || region.getOwners().isDirty() || region.getMembers().isDirty()) {
            changed = true;
        }
    }

    /** Были ли изменения с прошлой проверки; сбрасывает отметку. */
    public boolean consumeChanges(ProtectedRegion region) {
        DefaultDomain owners = region.getOwners();
        DefaultDomain members = region.getMembers();
        boolean result = changed || owners.isDirty() || members.isDirty();
        if (result) {
            // Сначала сбрасываем, потом вызывающий снимает состояние —
            // изменение, сделанное между этими шагами, не потеряется.
            changed = false;
            owners.setDirty(false);
            members.setDirty(false);
        }
        return result;
    }

    public void markChanged() {
        changed = true;
    }
}
