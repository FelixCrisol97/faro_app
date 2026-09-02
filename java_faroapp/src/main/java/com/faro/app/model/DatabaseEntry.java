package com.faro.app.model;

import java.util.Objects;
import java.util.UUID;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;

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

    /**
     * Piso duro de pool de conexiones — ver {@link #setPoolSize} para el
     * motivo real. Expuesto público para que la UI (diálogos de Agregar/
     * editar base de datos y Preferencias) pueda rechazar el guardado con
     * el mismo número, en vez de repetir el "2" mágico en varios sitios.
     */
    public static final int MIN_POOL_SIZE = 2;

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
    /**
     * {@code ObjectProperty}, no un campo plano (2026-08-28) — antes era
     * {@code volatile}, thread-safe para lectura/escritura cruda, pero
     * mutarlo no avisaba a nadie: el punto de color del árbol solo se
     * actualizaba si algo más disparaba {@code connectionTree.refresh()}
     * después. Como propiedad real, {@code ConnectionTreeCell} puede
     * ENLAZARSE a ella (ver {@code updateDatabaseRow}) y repintarse sola en
     * cuanto cualquier hilo la cambie — pedido explícito del usuario: "estos
     * círculos de conexión deberían estar sincronizados". Las propiedades de
     * JavaFX NO son thread-safe para escribir desde un hilo que no sea el de
     * la UI — cada {@code setConnectionStatus} desde un hilo de fondo (carga
     * de esquema, ejecución de consultas) tiene que pasar por
     * {@code Platform.runLater}, ya lo hacían los llamadores existentes por
     * otras razones.
     */
    private final ObjectProperty<ConnectionStatus> connectionStatus =
            new SimpleObjectProperty<>(ConnectionStatus.UNKNOWN);
    /**
     * {@code true} mientras esta base tiene una consulta corriendo de verdad
     * ahora mismo (2026-08-28, pedido explícito del usuario: "si se está
     * haciendo uso de esa BD... que pardee o se mueva el círculo... para que
     * se entienda mejor") — puramente de sesión, NUNCA se persiste (no tiene
     * sentido arrancar la app con una base marcada "en uso" de la corrida
     * anterior). {@code MainController#onRunQuery} la prende al arrancar una
     * corrida y la apaga cuando el estado de esa base deja de ser
     * {@code RUNNING}; {@code ConnectionTreeCell} la usa para animar
     * {@code statusDot}.
     */
    private final BooleanProperty inUse = new SimpleBooleanProperty(false);
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
        return connectionStatus.get();
    }

    /** Solo desde el hilo de JavaFX — ver el javadoc del campo {@link #connectionStatus}. */
    public void setConnectionStatus(ConnectionStatus connectionStatus) {
        this.connectionStatus.set(connectionStatus);
    }

    public ObjectProperty<ConnectionStatus> connectionStatusProperty() {
        return connectionStatus;
    }

    public boolean isInUse() {
        return inUse.get();
    }

    /** Solo desde el hilo de JavaFX — ver el javadoc del campo {@link #inUse}. */
    public void setInUse(boolean inUse) {
        this.inUse.set(inUse);
    }

    public BooleanProperty inUseProperty() {
        return inUse;
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
        this.poolSize = Math.max(MIN_POOL_SIZE, poolSize);
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
