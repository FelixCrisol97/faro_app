package com.faro.app.model;

import java.util.List;
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
    /**
     * Solo aplica a SQL Server (2026-09-07, hallazgo #12 de
     * {@code AUDITORIA_BUGS_RENDIMIENTO.md}) — ver {@link #jdbcUrl()}.
     *
     * <p>{@code true} (el default, y lo que hacían TODAS las conexiones antes de
     * que este campo existiera) significa aceptar el certificado que presente el
     * servidor sin verificarlo: el tráfico va cifrado igual, pero se apaga la
     * comprobación de que el servidor es realmente quien dice ser — alguien
     * ubicado en la red entre Faro y el servidor podría hacerse pasar por él y
     * leer usuario/contraseña/resultados. {@code false} exige un certificado que
     * la máquina reconozca como válido; con un certificado autofirmado (lo más
     * común en servidores internos) la conexión simplemente falla.
     *
     * <p>Por eso el default es {@code true} y no {@code false}: cambiarlo de golpe
     * rompería las conexiones existentes. Se apaga por base, a mano, en las que sí
     * tengan un certificado bueno.
     */
    private volatile boolean trustServerCertificate = true;

    /**
     * Codificación que se le pide al servidor PostgreSQL para esta conexión
     * ({@code client_encoding}) — vacío (el default) significa "no tocar nada": el
     * driver usa UTF8, que es lo que hacían TODAS las conexiones antes de que este
     * campo existiera. Solo aplica a PostgreSQL, ver {@link #jdbcUrl()}.
     *
     * <p><b>Para qué sirve</b> (2026-09-10, error real reportado por el usuario al
     * expandir el esquema de una base: {@code ERROR: invalid byte sequence for
     * encoding "UTF8": 0xe9 0x73 0x20}). Ese error lo tira el SERVIDOR, no Faro:
     * pasa cuando la base guarda texto que no es UTF-8 válido —lo típico es una base
     * creada con codificación {@code SQL_ASCII}, que PostgreSQL acepta sin validar
     * nada, con contenido en LATIN1/Windows-1252 adentro (el {@code 0xe9} del error
     * es una 'é' en LATIN1)— y el cliente pide UTF8. El servidor no puede convertir
     * esos bytes y falla la consulta entera, incluidas las del catálogo que usa el
     * explorador de esquema.
     *
     * <p>Poniendo acá la codificación REAL de los datos (LATIN1, WIN1252,
     * SQL_ASCII), el servidor deja de intentar una conversión imposible y pgJDBC
     * decodifica con esa misma codificación, así que los acentos salen bien en vez
     * de romper la consulta.
     *
     * <p><b>Sin verificar contra un servidor con este problema</b> — implementado
     * contra el comportamiento documentado de pgJDBC ({@code allowEncodingChanges}
     * es su escotilla oficial para esto: sin ella el driver ABORTA la conexión si
     * {@code client_encoding} no es UTF8). Queda pendiente probarlo contra la base
     * real que dio el error.
     */
    private volatile String clientEncoding = "";

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

    /** Ver el javadoc del campo {@link #trustServerCertificate} — solo tiene efecto en SQL Server. */
    public boolean trustServerCertificate() {
        return trustServerCertificate;
    }

    public void setTrustServerCertificate(boolean trustServerCertificate) {
        this.trustServerCertificate = trustServerCertificate;
    }

    /** Ver el javadoc del campo {@link #clientEncoding} — vacío = automática (UTF8), el comportamiento de siempre. Solo tiene efecto en PostgreSQL. */
    public String clientEncoding() {
        return clientEncoding;
    }

    public void setClientEncoding(String clientEncoding) {
        this.clientEncoding = clientEncoding == null ? "" : clientEncoding.trim();
    }

    /** Las codificaciones que ofrece el diálogo de Agregar/editar — la vacía es "Automática (UTF-8)". Nombres tal cual los entiende PostgreSQL en {@code client_encoding}. */
    public static final List<String> CLIENT_ENCODINGS =
            List.of("", "LATIN1", "WIN1252", "SQL_ASCII", "LATIN9", "UTF8");

    /**
     * {@code jdbc:postgresql://host:port/db} /
     * {@code jdbc:sqlserver://host:port;databaseName=db}.
     *
     * <p>{@code encrypt=true} siempre en SQL Server (el tráfico va cifrado sin
     * excepción); {@code trustServerCertificate} sale de
     * {@link #trustServerCertificate()}, configurable por base desde el diálogo
     * de Agregar/editar — antes estaba fijo en {@code true} para todas, sin forma
     * de apretarlo ni siquiera contra un servidor con certificado válido. En
     * PostgreSQL el campo no se usa: la URL no negocia TLS por su cuenta (pgJDBC
     * tiene su propio {@code sslmode}, que este proyecto no expone todavía).
     */
    public String jdbcUrl() {
        return switch (engine) {
            case POSTGRES -> "jdbc:postgresql://" + host + ":" + port + "/" + databaseName + postgresEncodingParams();
            case SQL_SERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + databaseName
                    + ";encrypt=true;trustServerCertificate=" + trustServerCertificate;
        };
    }

    /**
     * Parámetros de codificación de la URL de PostgreSQL — cadena vacía cuando
     * {@link #clientEncoding} no está puesto, o sea que una base sin configurar
     * produce EXACTAMENTE la misma URL que antes de que este campo existiera.
     *
     * <p>Los dos parámetros van juntos y ninguno sirve solo:
     * <ul>
     *   <li>{@code options=-c client_encoding=XXX} es lo que le pide al servidor que
     *       hable en esa codificación (la forma estándar de pasarle parámetros de
     *       sesión a PostgreSQL desde la cadena de conexión).</li>
     *   <li>{@code allowEncodingChanges=true} es obligatorio: pgJDBC fija
     *       {@code client_encoding=UTF8} por su cuenta y <b>corta la conexión</b> si
     *       detecta que cambió, salvo con esta bandera. Con ella puesta, además,
     *       el driver reconfigura su propio decodificador con la codificación nueva
     *       — que es justo lo que hace que el texto salga bien y no como mojibake.</li>
     * </ul>
     *
     * <p>{@code %20} y {@code %3D} porque el valor viaja dentro de la cadena de
     * consulta de la URL: un espacio o un {@code =} sin escapar cortarían el
     * parámetro a la mitad. pgJDBC decodifica estos valores al parsear la URL.
     */
    private String postgresEncodingParams() {
        String encoding = clientEncoding;
        if (encoding == null || encoding.isBlank()) {
            return "";
        }
        return "?options=-c%20client_encoding%3D" + encoding.trim() + "&allowEncodingChanges=true";
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
