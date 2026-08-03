package com.example.springbootapp.config;

import java.sql.Connection;
import java.sql.Statement;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSupport {

    private static final Logger logger = LoggerFactory.getLogger("lowops.database");

    private volatile boolean databaseAvailable;
    private volatile DataSource dataSource;

    public boolean isDatabaseAvailable() {
        return databaseAvailable;
    }

    public DataSource getDataSource() {
        return dataSource;
    }

    public boolean initDatabase() {
        PostgresSettings settings = buildPostgresSettings();
        if (settings == null) {
            databaseAvailable = false;
            dataSource = null;
            logger.warn(
                    "Database is not configured (POSTGRES_* env vars missing). "
                            + "Falling back to in-memory users store."
            );
            return false;
        }

        DriverManagerDataSource candidate = new DriverManagerDataSource();
        candidate.setDriverClassName("org.postgresql.Driver");
        candidate.setUrl(settings.jdbcUrl());
        candidate.setUsername(settings.user());
        candidate.setPassword(settings.password());

        try (Connection connection = candidate.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
            ensureSchema(connection);
            dataSource = candidate;
            databaseAvailable = true;
            logger.info(
                    "Database connection established ({}:{}/{})",
                    settings.host(),
                    settings.port(),
                    settings.database()
            );
            return true;
        } catch (Exception exc) {
            databaseAvailable = false;
            dataSource = null;
            logger.warn(
                    "Database connection failed. Falling back to in-memory users store. Reason: {}",
                    exc.toString()
            );
            return false;
        }
    }

    private void ensureSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS users (
                        id BIGSERIAL PRIMARY KEY,
                        name VARCHAR(150) NOT NULL,
                        email VARCHAR(254) NOT NULL UNIQUE,
                        avatar TEXT,
                        avatar_key VARCHAR(500),
                        created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
                        updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
                    )
                    """);
        }
    }

    private PostgresSettings buildPostgresSettings() {
        String user = env("POSTGRES_USER");
        String password = env("POSTGRES_PASSWORD");
        String host = env("POSTGRES_HOST");
        String port = env("POSTGRES_PORT");
        if (port.isBlank()) {
            port = "5432";
        }
        String database = env("POSTGRES_DATABASE");
        if (user.isBlank() || password.isBlank() || host.isBlank() || database.isBlank()) {
            return null;
        }
        return new PostgresSettings(user, password, host, port, database);
    }

    private static String env(String key) {
        String value = System.getenv(key);
        return value == null ? "" : value;
    }

    private record PostgresSettings(
            String user,
            String password,
            String host,
            String port,
            String database
    ) {
        String jdbcUrl() {
            return "jdbc:postgresql://" + host + ":" + port + "/" + database
                    + "?connectTimeout=5&socketTimeout=5&loginTimeout=5&tcpKeepAlive=true";
        }
    }
}
