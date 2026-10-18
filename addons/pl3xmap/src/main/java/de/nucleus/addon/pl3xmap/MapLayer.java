package de.nucleus.addon.pl3xmap;

import net.pl3x.map.core.markers.layer.WorldLayer;
import net.pl3x.map.core.markers.marker.Marker;
import net.pl3x.map.core.world.World;

import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

/**
 * Eine Kartenebene, deren Marker auf dem Server-Hauptthread berechnet und hier nur abgelegt werden.
 * Pl3xMap fragt {@link #getMarkers()} aus eigenen Threads ab und bekommt immer eine fertige, unveränderliche Liste.
 */
final class MapLayer extends WorldLayer {

    private volatile List<Marker<?>> markers = List.of();

    MapLayer(String key, World world, Supplier<String> label, int updateSeconds, boolean defaultHidden, int priority) {
        super(key, world, label);
        setUpdateInterval(updateSeconds);
        setLiveUpdate(true);
        setShowControls(true);
        setDefaultHidden(defaultHidden);
        setPriority(priority);
        setZIndex(priority);
    }

    void update(List<Marker<?>> next) {
        markers = List.copyOf(next);
    }

    @Override
    public Collection<Marker<?>> getMarkers() {
        return markers;
    }
}
