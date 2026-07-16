package ru.citycore.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Creates one stable pre-Alpha-18 snapshot before schema migration opens the database. */
public final class DatabaseBackup {
    private DatabaseBackup() {}

    public static Path beforeAlpha18(Path databaseFile, Path backupDirectory) {
        if (!Files.isRegularFile(databaseFile)) return null;
        try {
            Files.createDirectories(backupDirectory);
            Path backup = backupDirectory.resolve("citycore-before-alpha18.db");
            if (!Files.exists(backup)) {
                Files.copy(databaseFile, backup, StandardCopyOption.COPY_ATTRIBUTES);
                copySidecarIfPresent(databaseFile.resolveSibling(databaseFile.getFileName() + "-wal"),
                        backup.resolveSibling(backup.getFileName() + "-wal"));
                copySidecarIfPresent(databaseFile.resolveSibling(databaseFile.getFileName() + "-shm"),
                        backup.resolveSibling(backup.getFileName() + "-shm"));
            }
            return backup;
        } catch (IOException error) {
            throw new IllegalStateException("Не удалось создать резервную копию базы перед Alpha 18", error);
        }
    }

    private static void copySidecarIfPresent(Path source, Path target) throws IOException {
        if (Files.isRegularFile(source)) Files.copy(source, target, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
