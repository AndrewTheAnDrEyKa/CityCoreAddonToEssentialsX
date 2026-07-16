package ru.citycore.audit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

public final class AuditLog {
    private AuditLog() {}

    public static void append(Connection connection, String type, UUID actor, String subjectType, String subjectId, String payload) throws Exception {
        String previous = null;
        try (var query = connection.createStatement(); var rs = query.executeQuery("SELECT event_hash FROM audit_event ORDER BY rowid DESC LIMIT 1")) {
            if (rs.next()) previous = rs.getString(1);
        }
        String id = UUID.randomUUID().toString(); String created = Instant.now().toString();
        String canonical = String.join("|", id, type, actor == null ? "" : actor.toString(), subjectType, subjectId,
                payload, created, previous == null ? "" : previous);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        try (var insert = connection.prepareStatement("INSERT INTO audit_event(id,event_type,actor_uuid,subject_type,subject_id,payload,created_at,previous_hash,event_hash) VALUES(?,?,?,?,?,?,?,?,?)")) {
            insert.setString(1, id); insert.setString(2, type); insert.setString(3, actor == null ? null : actor.toString());
            insert.setString(4, subjectType); insert.setString(5, subjectId); insert.setString(6, payload);
            insert.setString(7, created); insert.setString(8, previous); insert.setString(9, hash); insert.executeUpdate();
        }
    }
}
