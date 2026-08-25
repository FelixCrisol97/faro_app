package com.faro.app.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Una base de datos individual — la unidad real de conexión. {@link #engine}
 * y {@link #mode} viven aquí, no en {@link Server}: un servidor es solo un
 * agrupador libre y opcional (ver la clase {@code Server}), así que dos
 * bases del mismo grupo pueden tener motores y modos distintos entre sí.
 * Mismo modelo que {@code DatabaseEntry} en la versión Flutter — ver
 * {@code project_faro_architecture.md} en la memoria del proyecto si hace
 * falta más contexto de por qué está separado así.
 *
 * <p>Mutable a propósito (no un record) — alias/host/motor/modo se editan
 * en vivo desde la UI, igual que en la versión Flutter.
 */
public class DatabaseEntry {

    // volatile: el diálogo de editar muta estos campos en el hilo de JavaFX
    // mientras una ejecución en curso (hilo de fondo) los puede estar
    // leyendo al mismo tiempo vía jdbcUrl()/alias()/etc. — sin volatile, el
    // hilo de ejecución podía no ver el cambio en absoluto (hallazgo real
    // de /code-review). volatile garantiza que cada lectura vea la última
    // escritura de CADA campo individual; no garantiza atomicidad entre
    // varios campos a la vez (ej. leer host nuevo con puerto viejo si se
    // edita justo a mitad de una ejecución) — ese caso, más angosto, en el
    // peor de los casos falla la conexión de esa corrida, no corrompe
    // datos, y se autocorrige en la siguiente ejecución.
    private final String id;
    private volatile String alias;
    private volatile String host;
    private volatile int port;
    private volatile String databaseName;
    private volatile DbEngine engine;
    private volatile ServerMode mode;
    private volatile ConnectionStatus connectionStatus;
    private volatile int poolSize = 4;
    private volatile int queryTimeoutSeconds = 30;

    public DatabaseEntry(String alias, String host, int port, String databaseName,
                          DbEngine engine, ServerMode mode) {
        this(UUID.randomUUID().toString(), alias, host, port, databaseName, engine, mode);
    }

    public DatabaseEntry(String id, String alias, String host, int port, String databaseName,
                          DbEngine engine, ServerMode mode) {
        this.id = Objects.requireNonNull(id);
        this.alias = Objects.requireNonNull(alias);
        this.host = Objects.requireNonNull(host);
        this.port = port;
        this.databaseName = Objects.requireNonNull(databaseName);
        this.engine = Objects.requireNonNull(engine);
        this.mode = Objects.requireNonNull(mode);
        this.connectionStatus = ConnectionStatus.UNKNOWN;
    }

    public String id() {
        return id;
    }

    public String alias() {
        return alias;
    }

    public void setAlias(String alias) {
        this.alias = alias;
    }

    public String host() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int port() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String databaseName() {
        return databaseName;
    }

    public void setDatabaseName(String databaseName) {
        this.databaseName = databaseName;
    }

    public DbEngine engine() {
        return engine;
    }

    public void setEngine(DbEngine engine) {
        this.engine = engine;
    }

    public ServerMode mode() {
        return mode;
    }

    public void setMode(ServerMode mode) {
        this.mode = mode;
    }

    public ConnectionStatus connectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(ConnectionStatus connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    /** Tamaño del pool de conexiones (HikariCP) — un pool por servidor, ver README. */
    public int poolSize() {
        return poolSize;
    }

    /**
     * Piso duro de 2, no solo recomendado — con {@code poolSize=1}, el
     * respaldo de cancelación ({@code KILL}/{@code pg_cancel_backend}, ver
     * {@code QueryExecutionService#killBackend}) necesita abrir una
     * conexión NUEVA mientras la única que hay está ocupada corriendo la
     * consulta que se quiere matar — se queda esperando hasta el
     * {@code connectionTimeout} de HikariCP (30s por defecto) o nunca la
     * consigue, justo el escenario que ese respaldo debería resolver.
     * Pedido explícito del usuario (2026-08-25): que la UI no deje bajar de
     * ahí, no solo que quede documentado en un comentario.
     */
    public void setPoolSize(int poolSize) {
        this.poolSize = Math.max(2, poolSize);
    }

    public int queryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(int queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    /** {@code jdbc:postgresql://host:port/db} / {@code jdbc:sqlserver://host:port;databaseName=db}. */
    public String jdbcUrl() {
        return switch (engine) {
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + port + "/" + databaseName;
            case SQL_SERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + databaseName
                    + ";encrypt=true;trustServerCertificate=true";
        };
    }

    @Override
    public String toString() {
        return alias;
    }

    /** Estado de la última prueba de conexión — el punto de color en el árbol. */
    public enum ConnectionStatus {
        UNKNOWN, TESTING, CONNECTED, FAILED
    }
}
