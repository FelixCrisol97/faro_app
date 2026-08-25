package com.faro.app.query;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolManager.class);

    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();

    public Connection getConnection(DatabaseEntry db, CredentialStore.Credentials credentials) throws SQLException {
        HikariDataSource dataSource = pools.computeIfAbsent(db.id(), id -> buildDataSource(db, credentials));
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            log.warn("No se pudo obtener conexión del pool de '{}': {}", db.alias(), e.getMessage());
            throw e;
        }
    }

    /** Cierra y descarta el pool de una base — llamar después de editarla. */
    public void evict(String databaseId) {
        HikariDataSource removed = pools.remove(databaseId);
        if (removed != null) {
            log.info("Pool descartado para databaseId={} (base editada).", databaseId);
            removed.close();
        }
    }

    public void closeAll() {
        log.info("Cerrando todos los pools ({} abierto(s)).", pools.size());
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    /** {@code databaseCount} = bases con un pool abierto (uno por base, no por servidor — ver el javadoc de la clase); {@code activeConnections}/{@code totalConnections} sumados de todos esos pools. Para la barra de estado ("N conexiones · pool activo/total", igual que faro-java-prototipo.html). */
    public PoolSummary poolSummary() {
        int active = 0;
        int total = 0;
        for (HikariDataSource dataSource : pools.values()) {
            var bean = dataSource.getHikariPoolMXBean();
            if (bean != null) {
                active += bean.getActiveConnections();
                total += bean.getTotalConnections();
            }
        }
        return new PoolSummary(pools.size(), active, total);
    }

    public record PoolSummary(int databaseCount, int activeConnections, int totalConnections) {
    }

    private static HikariDataSource buildDataSource(DatabaseEntry db, CredentialStore.Credentials credentials) {
        log.info("Creando pool para '{}' — {} (usuario={}, poolSize={})",
                db.alias(), db.jdbcUrl(), credentials.user(), Math.max(1, db.poolSize()));
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.jdbcUrl());
        config.setUsername(credentials.user());
        config.setPassword(credentials.password());
        config.setMaximumPoolSize(Math.max(1, db.poolSize()));
        config.setPoolName("faro-" + db.alias());
        return new HikariDataSource(config);
    }
}
