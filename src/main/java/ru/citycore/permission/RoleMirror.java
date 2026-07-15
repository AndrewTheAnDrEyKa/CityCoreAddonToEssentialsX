package ru.citycore.permission;

import ru.citycore.city.CityRole;

import java.util.UUID;

/** Decorative role projection. CityCore remains the authority for gameplay capabilities. */
public interface RoleMirror {
    void sync(UUID playerId, CityRole role);
    boolean available();
}
