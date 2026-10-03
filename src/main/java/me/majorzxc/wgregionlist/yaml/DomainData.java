package me.majorzxc.wgregionlist.yaml;

import java.util.List;

/** Владельцы или участники региона, как они записаны в файле. */
public record DomainData(List<String> players, List<String> uniqueIds, List<String> groups) {

    public static final DomainData EMPTY = new DomainData(List.of(), List.of(), List.of());

    public DomainData {
        players = List.copyOf(players);
        uniqueIds = List.copyOf(uniqueIds);
        groups = List.copyOf(groups);
    }
}
