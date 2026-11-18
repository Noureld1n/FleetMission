package com.fleetcontrol.backend.service;

import org.springframework.stereotype.Component;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-instance subscriber index, partitioned by trusted tenant ID and tile. */
@Component
public class MapTileRegistry {
    private final ConcurrentHashMap<Key, Set<UUID>> subscribers = new ConcurrentHashMap<>();
    public void replace(UUID connection, UUID tenant, Set<String> previous, Set<String> next) {
        for (String tile : previous) if (!next.contains(tile)) remove(connection,tenant,tile);
        for (String tile : next) if (!previous.contains(tile))
            subscribers.computeIfAbsent(new Key(tenant,tile),ignored->ConcurrentHashMap.newKeySet()).add(connection);
    }
    public boolean contains(UUID connection, UUID tenant, String tile) {
        Set<UUID> members=subscribers.get(new Key(tenant,tile));
        return members!=null&&members.contains(connection);
    }
    public int tileCount() { return subscribers.size(); }
    public void removeAll(UUID connection, UUID tenant) {
        subscribers.forEach((key,members)->{ if(key.tenant.equals(tenant)) remove(connection,tenant,key.tile); });
    }
    private void remove(UUID connection, UUID tenant, String tile) {
        Key key=new Key(tenant,tile); Set<UUID> members=subscribers.get(key);
        if(members!=null){ members.remove(connection); if(members.isEmpty())subscribers.remove(key,members); }
    }
    private record Key(UUID tenant,String tile) {}
}
