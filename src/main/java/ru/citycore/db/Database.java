package ru.citycore.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

public final class Database implements AutoCloseable {
    private final HikariDataSource dataSource;

    public Database(Path file, int poolSize) {
        Objects.requireNonNull(file, "file");
        HikariConfig config = new HikariConfig();
        config.setPoolName("CityCore-SQLite");
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.toAbsolutePath());
        config.setMaximumPoolSize(poolSize);
        config.addDataSourceProperty("foreign_keys", "true");
        config.addDataSourceProperty("busy_timeout", "5000");
        config.addDataSourceProperty("journal_mode", "WAL");
        config.setAutoCommit(true);
        dataSource = new HikariDataSource(config);
    }

    public Connection connection() throws SQLException { return dataSource.getConnection(); }

    public <T> T transaction(SqlFunction<Connection, T> operation) {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try {
                T result = operation.apply(connection);
                connection.commit();
                return result;
            } catch (Exception exception) {
                connection.rollback();
                throw new DatabaseException("Транзакция CityCore отменена", exception);
            }
        } catch (SQLException exception) {
            throw new DatabaseException("Ошибка подключения к CityCore DB", exception);
        }
    }

    @Override public void close() { dataSource.close(); }

    @FunctionalInterface public interface SqlFunction<T, R> { R apply(T value) throws Exception; }
}
