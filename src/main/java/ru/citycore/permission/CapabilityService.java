package ru.citycore.permission;

import org.bukkit.entity.Player;
import ru.citycore.city.CityRole;
import ru.citycore.city.CityService;

public final class CapabilityService {
    public boolean allows(Player player, CityService.CityView city, Capability capability) {
        return switch (capability) {
            case MAYOR_WORKSPACE -> city != null && city.role() == CityRole.MAYOR;
            case GOVERNMENT_WORKSPACE -> city != null
                    && (city.role() == CityRole.MAYOR || city.role() == CityRole.OFFICIAL);
            case ADMIN_WORKSPACE -> player.hasPermission("citycore.admin");
            case REVIEW_CITY_FOUNDATIONS -> player.hasPermission("citycore.admin.cities");
            case ISSUE_CURRENCY -> player.hasPermission("citycore.admin.emission");
            case MANAGE_INDUSTRY -> city != null
                    && (city.role() == CityRole.MAYOR || city.role() == CityRole.OFFICIAL);
            case ADMIN_INDUSTRY -> player.hasPermission("citycore.admin.industry");
            case MANAGE_EVENTS -> player.hasPermission("citycore.admin.events");
        };
    }
}
