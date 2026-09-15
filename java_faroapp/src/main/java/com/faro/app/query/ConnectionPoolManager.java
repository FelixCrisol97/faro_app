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

    /**
     * <b>El pool se construye FUERA del candado del mapa</b> (2026-09-10, hallazgo B7
     * de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
     *
     * <p>Antes esto era {@code pools.computeIfAbsent(db.id(), id -> buildDataSource(...))}.
     * {@code new HikariDataSource(config)} no es una construcción barata: inicializa el
     * pool de inmediato y abre una conexión real de arranque (TCP + TLS + login), o sea
     * cientos de milisegundos en red local y hasta el {@code connectionTimeout} entero
     * —30 s por defecto— si el servidor no responde. El javadoc de
     * {@code ConcurrentHashMap#computeIfAbsent} es explícito en que la función de mapeo
     * debe ser corta: mientras corre mantiene bloqueado el bin de la tabla, así que dos
     * bases DISTINTAS cuyos ids caigan en el mismo bin se bloqueaban entre sí. Con la
     * concurrencia por defecto (8 bases a la vez) arrancando sus pools en la primera
     * corrida de la sesión, eso serializaba algo que debería ser paralelo.
     *
     * <p>El patrón {@code get} → construir afuera → {@code putIfAbsent} puede crear un
     * pool de más si dos hilos corren contra la misma base a la vez; el perdedor se
     * cierra en el acto y nadie lo llega a usar. Es un desperdicio raro y acotado a
     * cambio de que ninguna base bloquee a otra.
     */
    public Connection getConnection(DatabaseEntry db, CredentialStore.Credentials credentials) throws SQLException {
        HikariDataSource dataSource = pools.get(db.id());
        if (dataSource == null) {
            HikariDataSource created = buildDataSource(db, credentials);
            HikariDataSource existing = pools.putIfAbsent(db.id(), created);
            if (existing != null) {
                log.debug("Carrera al crear el pool de '{}' — se descarta el duplicado.", db.alias());
                created.close();
                dataSource = existing;
            } else {
                dataSource = created;
            }
        }
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
            closeInBackground(removed, databaseId);
        }
    }

    /**
     * Descarta todos los pools sin bloquear el hilo que llama — para "Importar
     * configuración…", que corre en el hilo de JavaFX y reemplaza el registro entero.
     * Ver {@link #closeInBackground}.
     *
     * <p>Para el cierre de la app usar {@link #closeAllAndWait()} en su lugar.
     */
    public void closeAll() {
        log.info("Descartando todos los pools ({} abierto(s)) — cierre en segundo plano.", pools.size());
        for (Map.Entry<String, HikariDataSource> entry : pools.entrySet()) {
            closeInBackground(entry.getValue(), entry.getKey());
        }
        pools.clear();
    }

    /**
     * Como {@link #closeAll()} pero <b>espera</b> a que cada pool termine de cerrarse —
     * para {@code MainController#shutdown()}.
     *
     * <p>Acá el bloqueo es lo correcto, no un descuido: justo después la JVM sale, y
     * los hilos de {@link #closeInBackground} son demonio, así que se los llevaría por
     * delante a mitad del cierre. Cerrar en serie y en el hilo que llama garantiza que
     * cada pool alcance a devolver sus conexiones al servidor en vez de dejarlas
     * colgadas del lado de la base hasta que expire su propio timeout.
     */
    public void closeAllAndWait() {
        log.info("Cerrando todos los pools ({} abierto(s)) y esperando.", pools.size());
        pools.values().forEach(HikariDataSource::close);
        pools.clear();
    }

    /**
     * Cierra un {@link HikariDataSource} en un hilo aparte (2026-09-10, hallazgo A7).
     *
     * <p>{@code close()} no es instantáneo: desaloja las conexiones ociosas, <b>espera
     * a que vuelvan las que estén en uso</b> y apaga los hilos internos del pool. Con
     * una consulta larga corriendo contra esa base eso se va a segundos. Los tres
     * llamadores reales —editar una base, eliminarla, e importar configuración— corren
     * en el hilo de JavaFX, y el peor es importar: {@code closeAll()} recorre TODOS los
     * pools y los cerraba uno tras otro, congelando la ventana hasta el último. Es la
     * misma clase de bug que el hallazgo #2 de {@code AUDITORIA_BUGS_RENDIMIENTO.md}
     * ("Probar conexión congela la ventana"), en otro camino.
     *
     * <p>Sacarlo del mapa ya garantiza que nadie nuevo lo va a usar —eso pasa antes de
     * llamar acá, y es lo único que tiene que ser inmediato—; el {@code close()} real es
     * limpieza de fondo. Hilo demonio: si la app se está cerrando, no hay que esperarlo.
     */
    private static void closeInBackground(HikariDataSource dataSource, String databaseId) {
        Thread thread = new Thread(() -> {
            try {
                dataSource.close();
                log.debug("Pool de databaseId={} cerrado.", databaseId);
            } catch (RuntimeException e) {
                log.warn("Error cerrando el pool de databaseId={}: {}", databaseId, e.getMessage());
            }
        }, "faro-pool-close");
        thread.setDaemon(true);
        thread.start();
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
        // Red de seguridad, no la validación principal — DatabaseEntry#setPoolSize ya no deja
        // guardar menos de MIN_POOL_SIZE (el respaldo de cancelación necesita una segunda
        // conexión libre); esto solo cubre un DatabaseEntry armado sin pasar por ese setter.
        int poolSize = Math.max(DatabaseEntry.MIN_POOL_SIZE, db.poolSize());
        log.info("Creando pool para '{}' — {} (usuario={}, poolSize={})",
                db.alias(), db.jdbcUrl(), credentials.user(), poolSize);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(db.jdbcUrl());
        config.setUsername(credentials.user());
        config.setPassword(credentials.password());
        config.setMaximumPoolSize(poolSize);
        config.setPoolName("faro-" + db.alias());
        return new HikariDataSource(config);
    }
}
