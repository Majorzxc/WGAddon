package me.majorzxc.wgregionlist.sync;

/** Итог загрузки регионов мира — для логов и ответа на /wgrl reload. */
public record LoadSummary(String world, int files, int regions, int problems, int conflicts, int removed,
                          int restoredParents) {
}
