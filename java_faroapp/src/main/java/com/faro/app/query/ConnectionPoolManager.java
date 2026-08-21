package com.faro.app.query;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Un {@link HikariDataSource} por base de datos (no por servidor — un
 * "servidor" en este árbol es solo un agrupador libre, la conexión real
 * vive en {@link DatabaseEntry}), creado la primera vez que se pide y
 * reusado después — reemplaza el {@code DriverManager.getConnection} por
 * ejecución que usaba la primera versión de {@link QueryExecutionService}.
 *
 * <p><b>Límite conocido, deliberado:</b> el pool se arma una sola vez con
 * las credenciales/host/puerto de ese momento. Si el usuario edita esa
 * base después (host, puerto, motor, usuario, contraseña), el pool viejo
 * queda con datos obsoletos — por eso {@code MainController} llama a
 * {@link #evict} cada vez que el diálogo de editar se cierra con cambios
 * guardados. No hay invalidación automática por cambio de credenciales
 * más fina que esa (ej. detectar que solo cambió la contraseña) — v0.
 */
public final class ConnectionPoolManager {

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public Connection getConnection(DatabaseEntry db, CredentialStore.Credentials credentials) throws SQLException {
        HikariDataSource dataSource = pools.computeIfAbsent(db.id(), id -> buildDataSource(db, credentials));
        return dataSource.getConnection();
    }

    /** Cierra y descarta el pool de una base — llamar después de editarla. */
    public void evict(String databaseId) {
        HikariDataSource removed = pools.remove(databaseId);
        if (removed != null) {
            removed.close();
        }
    }

    public void closeAll() {
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    private static HikariDataSource buildDataSource(DatabaseEntry db, CredentialStore.Credentials credentials) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.jdbcUrl());
        config.setUsername(credentials.user());
        config.setPassword(credentials.password());
        config.setMaximumPoolSize(Math.max(1, db.poolSize()));
        config.setPoolName("faro-" + db.alias());
        return new HikariDataSource(config);
    }
}
