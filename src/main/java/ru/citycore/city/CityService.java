package ru.citycore.city;

import ru.citycore.db.Database;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public final class CityService {
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{2,23}");
    private final Database database;
    public CityService(Database database) { this.database = database; }

    public CreatedCity create(UUID founder, String rawSlug, String rawName) {
        String slug = rawSlug.toLowerCase(Locale.ROOT).trim(); String name = rawName.trim();
        if (!SLUG.matcher(slug).matches()) throw new IllegalArgumentException("ID города: 3–24 символа, a-z, 0-9, _ или -");
        if (name.length() < 3 || name.length() > 32) throw new IllegalArgumentException("Название города: от 3 до 32 символов");
        return database.transaction(connection -> {
            try (var profile = connection.prepareStatement("SELECT 1 FROM player_profile WHERE uuid=?")) {
                profile.setString(1, founder.toString());
                try (var rs = profile.executeQuery()) { if (!rs.next()) throw new IllegalStateException("Профиль игрока ещё не создан"); }
            }
            try (var citizenship = connection.prepareStatement("SELECT 1 FROM citizenship WHERE player_uuid=?")) {
                citizenship.setString(1, founder.toString());
                try (var rs = citizenship.executeQuery()) { if (rs.next()) throw new IllegalStateException("Игрок уже состоит в городе"); }
            }
            String id = UUID.randomUUID().toString(); String now = Instant.now().toString();
            try (var city = connection.prepareStatement("INSERT INTO city(id,slug,name,founder_uuid,status,created_at) VALUES(?,?,?,?,?,?)")) {
                city.setString(1, id); city.setString(2, slug); city.setString(3, name);
                city.setString(4, founder.toString()); city.setString(5, "ACTIVE"); city.setString(6, now); city.executeUpdate();
            }
            try (var citizen = connection.prepareStatement("INSERT INTO citizenship(player_uuid,city_id,role,joined_at) VALUES(?,?,?,?)")) {
                citizen.setString(1, founder.toString()); citizen.setString(2, id);
                citizen.setString(3, CityRole.MAYOR.name()); citizen.setString(4, now); citizen.executeUpdate();
            }
            String treasury = UUID.randomUUID().toString();
            try (var account = connection.prepareStatement("INSERT INTO account(id,owner_type,owner_id,currency,balance_minor,version) VALUES(?,?,?,?,0,0)")) {
                account.setString(1, treasury); account.setString(2, "CITY"); account.setString(3, id); account.setString(4, "ESSENTIALS"); account.executeUpdate();
            }
            return new CreatedCity(id, slug, name, treasury);
        });
    }

    public record CreatedCity(String id, String slug, String name, String treasuryAccountId) {}
}
