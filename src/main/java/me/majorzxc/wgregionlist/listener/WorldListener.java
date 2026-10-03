package me.majorzxc.wgregionlist.listener;

import me.majorzxc.wgregionlist.sync.RegionService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/**
 * Ускоряет реакцию на загрузку/выгрузку миров. Приоритет MONITOR — к этому моменту
 * WorldGuard уже создал (или убрал) менеджер регионов мира. Даже без этих событий
 * плагин заметит изменения при ежесекундной проверке.
 */
public final class WorldListener implements Listener {

    private final RegionService service;

    public WorldListener(RegionService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldLoad(WorldLoadEvent event) {
        service.requestCheck();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        service.requestCheck();
    }
}
