package com.faro.app.query;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.concurrent.Task;

/**
 * Corre un texto SQL contra una o más bases de datos, todas a la vez —
 * cada base en su propio hilo (acotado a {@code maxConcurrentDatabases}
 * a la vez — ver {@code AppPreferences#maxConcurrentDatabases}, editable
 * desde Preferencias → Rendimiento — para no abrir cientos de conexiones
 * si se marca todo el árbol), usando el pool de {@link ConnectionPoolManager}. Si una base
 * falla (sin credenciales, SQL inválido, host caído) se registra el error
 * y se sigue con las demás — no se aborta toda la ejecución por una base.
 * Se corre siempre en un {@link Task} — nunca JDBC en el hilo de la UI,
 * ver README.
 *
 * <p>El estado en vivo de cada base (pestaña "Ejecución") se reporta a
 * través de {@code statusByDatabaseId} — un {@link ExecutionStatus} por
 * base, creado por el llamador ANTES de arrancar la ejecución (así la
 * tabla ya muestra "Ejecutando…" para todas apenas se presiona Ejecutar,
 * no solo al terminar). Sus propiedades solo se mutan dentro de
 * {@code Platform.runLater} — nunca directo desde el hilo de la base que
 * se está consultando, JavaFX no lo permite.
 *
 * <p><b>Simplificación deliberada:</b> el orden de las filas del resultado
 * combinado ya no es predecible entre bases (antes, secuencial, salían en
 * el mismo orden que el árbol) — es el costo normal de correr en paralelo.
 *
 * <p><b>{@code ServerMode.READ_ONLY} se aplica de verdad</b> (hallazgo de
 * `/code-review`: antes el candado del árbol no protegía nada). Ver
 * {@link #isReadOnlyStatement}: es una heurística por primera palabra
 * clave (SELECT/WITH/SHOW/EXPLAIN/DESCRIBE), no un parser SQL completo —
 * cubre el caso real que importa (evitar ejecutar por accidente un
 * DELETE/UPDATE/DROP contra una base marcada como protegida), no
 * pretende ser a prueba de un usuario que deliberadamente intente
 * evadirla con SQL adversarial.
 */
public final class QueryExecutionService {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutionService.class);

    private static final Set<String> READ_ONLY_LEADING_KEYWORDS =
            Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC");
    private static final Pattern LEADING_NOISE =
            Pattern.compile("\\A(?:\\s+|--[^\\n]*\\n|/\\*.*?\\*/)*", Pattern.DOTALL);

    /**
     * Palabras que de verdad arrancan una sentencia nueva — usado solo
     * para reconocer el error real de abajo, un set aparte de
     * {@link SqlFormatter#KEYWORDS} (que también trae FROM/WHERE/JOIN/etc,
     * palabras que NO empiezan una sentencia y darían falsos positivos).
     */
    private static final Set<String> STATEMENT_START_KEYWORDS = Set.of(
            "SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC",
            "INSERT", "UPDATE", "DELETE", "CREATE", "DROP", "ALTER", "TRUNCATE", "MERGE",
            "GRANT", "REVOKE", "BEGIN", "COMMIT", "ROLLBACK", "CALL", "EXEC", "EXECUTE", "DECLARE");
    /** PostgreSQL reporta justo la primera palabra de la sentencia siguiente cuando falta el `;` que las separa — ver {@link #enrichErrorMessage}. */
    private static final Pattern PG_SYNTAX_ERROR_NEAR_WORD =
            Pattern.compile("syntax error at or near \"(\\w+)\"", Pattern.CASE_INSENSITIVE);

    private QueryExecutionService() {
    }

    public static Task<QueryResult> execute(
            List<DatabaseEntry> databases, CredentialStore credentials, ConnectionPoolManager pool,
            Map<String, ExecutionStatus> statusByDatabaseId, String sql, int maxConcurrentDatabases,
            int fetchSize, Map<DbEngine, String> engineVersions) {
        return new Task<>() {
            @Override
            protected QueryResult call() throws InterruptedException {
                long startedAt = System.currentTimeMillis();
                AtomicReference<List<String>> columnsRef = new AtomicReference<>();
                List<List<Object>> rows = Collections.synchronizedList(new ArrayList<>());
                List<String> errors = Collections.synchronizedList(new ArrayList<>());

                int poolSize = Math.min(Math.max(1, databases.size()), Math.max(1, maxConcurrentDatabases));
                log.info("Ejecutando consulta contra {} base(s), concurrencia={}, fetchSize={}, sql.length={}",
                        databases.size(), poolSize, fetchSize, sql.length());
                ExecutorService executor = Executors.newFixedThreadPool(poolSize);
                try {
                    List<Callable<Void>> jobs = databases.stream()
                        .<Callable<Void>>map(db -> () -> {
                            runOne(db, credentials, pool, sql, statusByDatabaseId.get(db.id()), columnsRef, rows, errors,
                                    fetchSize, engineVersions);
                            return null;
                        })
                        .toList();
                    executor.invokeAll(jobs);
                } finally {
                    executor.shutdown();
                }

                List<String> columns = columnsRef.get();
                long elapsed = System.currentTimeMillis() - startedAt;
                log.info("Corrida completa en {} ms — {} fila(s) combinadas, {} error(es) — {}/{} bases con error",
                        elapsed, rows.size(), errors.size(), errors.size(), databases.size());
                return new QueryResult(columns == null ? List.of() : columns, new ArrayList<>(rows), new ArrayList<>(errors));
            }
        };
    }

    /**
     * Consulta → Explicar plan de ejecución — corre {@code EXPLAIN} (Postgres)
     * o activa {@code SHOWPLAN_ALL} (SQL Server, la consulta se manda igual
     * pero el motor devuelve el plan en vez de ejecutarla de verdad) contra
     * UNA sola base — a diferencia de {@link #execute}, que corre en
     * paralelo contra todas las marcadas. Un plan es por naturaleza "por
     * base" (cada motor/instancia puede elegir un plan distinto para la
     * misma consulta), mezclar planes de varias bases en una sola tabla no
     * tendría sentido. <b>No pasa por la validación de
     * {@code ServerMode.READ_ONLY}</b> — {@code EXPLAIN} solo (sin
     * {@code ANALYZE}) nunca ejecuta la sentencia de verdad en ningún
     * motor, así que no hay nada que proteger ahí. <b>Sin verificar contra
     * un servidor real todavía</b> — implementado con el comportamiento
     * documentado de cada driver, no probado en vivo (ver README).
     */
    public static Task<QueryResult> explain(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, String sql) {
        return new Task<>() {
            @Override
            protected QueryResult call() throws SQLException {
                log.info("Explicando plan de ejecución para '{}' ({})", db.alias(), db.engine());
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
                    log.warn("EXPLAIN abortado para '{}' — sin credenciales guardadas.", db.alias());
                    throw new IllegalStateException("Sin usuario/contraseña guardados para " + db.alias());
                }

                List<String> columns = new ArrayList<>();
                List<List<Object>> rows = new ArrayList<>();
                try (Connection conn = pool.getConnection(db, creds.get());
                     Statement statement = conn.createStatement()) {
                    switch (db.engine()) {
                        case POSTGRES -> {
                            try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
                                collectPlanRows(rs, db.alias(), columns, rows);
                            }
                        }
                        case SQL_SERVER -> {
                            statement.execute("SET SHOWPLAN_ALL ON");
                            try (ResultSet rs = statement.executeQuery(sql)) {
                                collectPlanRows(rs, db.alias(), columns, rows);
                            } finally {
                                statement.execute("SET SHOWPLAN_ALL OFF");
                            }
                        }
                    }
                } catch (SQLException e) {
                    log.warn("EXPLAIN falló para '{}': {}", db.alias(), e.getMessage());
                    throw e;
                }
                log.info("EXPLAIN de '{}' completo — {} fila(s) de plan.", db.alias(), rows.size());
                return new QueryResult(columns, rows, List.of());
            }
        };
    }

    private static void collectPlanRows(
            ResultSet rs, String databaseAlias, List<String> columns, List<List<Object>> rows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        if (columns.isEmpty()) {
            columns.add("Base de datos");
            for (int i = 1; i <= columnCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }
        }
        while (rs.next()) {
            List<Object> row = new ArrayList<>(columnCount + 1);
            row.add(databaseAlias);
            for (int i = 1; i <= columnCount; i++) {
                row.add(rs.getObject(i));
            }
            rows.add(row);
        }
    }

    /**
     * <b>Corre el script completo, no una sola sentencia</b> — hallazgo
     * real del usuario (2026-08-22): antes esto mandaba TODO el texto del
     * editor tal cual a una sola llamada {@code executeQuery(sql)}, que
     * truena en PostgreSQL apenas el script tiene más de una sentencia
     * ("Multiple ResultSets were returned by the query.") y en SQL Server
     * corre algo sin avisar cuál sentencia quedó reflejada. Ahora
     * {@link SqlStatementSplitter#split} parte el script primero (respeta
     * comentarios/strings/bloques {@code $$…$$}), y cada sentencia corre
     * por separado con {@code Statement.execute(...)} (no
     * {@code executeQuery}, que además truena sola con cualquier sentencia
     * que no devuelva {@code ResultSet} — un script de solo
     * {@code UPDATE}/{@code INSERT} ya fallaba por esto mismo antes de
     * hoy). Se detiene en la primera sentencia que falle (mismo criterio
     * de diseño ya usado en la versión Flutter anterior de este proyecto:
     * por base, en orden, para en el primer error). Solo la ÚLTIMA
     * sentencia que sí devolvió un {@code ResultSet} queda visible en
     * Resultados — mismo criterio que pgAdmin/SSMS corriendo un script
     * completo (un {@code UPDATE} de limpieza después de un
     * {@code SELECT} no borra los resultados que sí importan).
     */
    private static void runOne(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, String sql,
            ExecutionStatus status, AtomicReference<List<String>> columnsRef,
            List<List<Object>> rows, List<String> errors, int fetchSize,
            Map<DbEngine, String> engineVersions) {
        long startedAt = System.currentTimeMillis();
        log.debug("[{}] Iniciando ejecución ({})", db.alias(), db.engine());

        Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
        if (creds.isEmpty()) {
            String message = "Sin usuario/contraseña guardados";
            log.warn("[{}] Ejecución abortada — {}", db.alias(), message);
            errors.add(db.alias() + ": " + message
                    + " (edítala y guarda unas propias, o define credenciales por defecto)");
            reportFailure(status, message, startedAt);
            return;
        }

        List<String> statements = SqlStatementSplitter.split(sql);
        log.debug("[{}] Script partido en {} sentencia(s).", db.alias(), statements.size());
        if (db.mode() == ServerMode.READ_ONLY) {
            for (String statement : statements) {
                if (!isReadOnlyStatement(statement)) {
                    String message = "Base de solo lectura — la consulta debe empezar con "
                            + "SELECT/WITH/SHOW/EXPLAIN/DESCRIBE";
                    log.warn("[{}] Ejecución rechazada — {}", db.alias(), message);
                    errors.add(db.alias() + ": " + message);
                    reportFailure(status, message, startedAt);
                    return;
                }
            }
        }

        int rowCount = 0;
        try (Connection conn = pool.getConnection(db, creds.get());
             Statement jdbcStatement = conn.createStatement()) {
            // Versión real del motor para la barra de estado de abajo
            // ("PostgreSQL 15.4 · SQL Server 2019", igual que
            // faro-java-prototipo.html) — getDatabaseProductVersion() es
            // información que el driver ya trae del handshake de conexión,
            // no un viaje de red aparte, así que es seguro pedirlo en cada
            // ejecución sin costo real; el chequeo de abajo solo evita
            // escribir en el mapa compartido de más.
            if (!engineVersions.containsKey(db.engine())) {
                try {
                    engineVersions.put(db.engine(), conn.getMetaData().getDatabaseProductVersion());
                } catch (SQLException ignored) {
                    // Mejor esfuerzo — sin versión real, la barra de estado
                    // simplemente no muestra nada para este motor todavía.
                }
            }

            if (status != null) {
                status.attachStatement(jdbcStatement);
                attachKillFallback(status, db, creds.get(), pool, conn);
            }
            jdbcStatement.setQueryTimeout(db.queryTimeoutSeconds());
            jdbcStatement.setFetchSize(fetchSize);

            List<String> lastColumnNames = null;
            List<List<Object>> lastRows = null;
            int statementIndex = 0;
            for (String statement : statements) {
                statementIndex++;
                log.debug("[{}] Sentencia {}/{}: {}", db.alias(), statementIndex, statements.size(), truncateForLog(statement));
                boolean hasResultSet = jdbcStatement.execute(statement);
                if (!hasResultSet) {
                    continue;
                }
                try (ResultSet rs = jdbcStatement.getResultSet()) {
                    ResultSetMetaData meta = rs.getMetaData();
                    int columnCount = meta.getColumnCount();
                    List<String> columnNames = new ArrayList<>();
                    columnNames.add("Base de datos");
                    for (int i = 1; i <= columnCount; i++) {
                        columnNames.add(meta.getColumnLabel(i));
                    }

                    List<List<Object>> theseRows = new ArrayList<>();
                    while (rs.next()) {
                        List<Object> row = new ArrayList<>(columnCount + 1);
                        row.add(db.alias());
                        for (int i = 1; i <= columnCount; i++) {
                            row.add(rs.getObject(i));
                        }
                        theseRows.add(row);
                    }
                    lastColumnNames = columnNames;
                    lastRows = theseRows;
                }
            }
            if (lastColumnNames != null) {
                columnsRef.compareAndSet(null, lastColumnNames);
                rows.addAll(lastRows);
                rowCount = lastRows.size();
            }
            long elapsed = System.currentTimeMillis() - startedAt;
            log.info("[{}] OK — {} fila(s) en {} ms.", db.alias(), rowCount, elapsed);
            reportSuccess(status, rowCount, startedAt);
        } catch (SQLException e) {
            if (status != null && status.wasCancelRequested()) {
                log.info("[{}] Cancelado por el usuario tras {} ms.", db.alias(), System.currentTimeMillis() - startedAt);
                reportCancelled(status, startedAt);
            } else {
                String message = enrichErrorMessage(db.engine(), e.getMessage());
                log.warn("[{}] Falló tras {} ms: {}", db.alias(), System.currentTimeMillis() - startedAt, message);
                errors.add(db.alias() + ": " + message);
                reportFailure(status, message, startedAt);
            }
        } catch (RuntimeException e) {
            // Ej. HikariPool.PoolInitializationException (no checked) al armar el pool con
            // una URL/config inválida — sin este catch, executor.invokeAll() se traga la
            // excepción en su Future descartado y esa fila se quedaba en "Ejecutando…" para
            // siempre, sin ningún error visible (hallazgo real de /code-review).
            log.error("[{}] Excepción no esperada tras {} ms.", db.alias(), System.currentTimeMillis() - startedAt, e);
            errors.add(db.alias() + ": " + e.getMessage());
            reportFailure(status, String.valueOf(e.getMessage()), startedAt);
        } finally {
            if (status != null) {
                status.attachStatement(null);
            }
        }
    }

    /** Recorta el texto de una sentencia para el log — un INSERT con miles de literales no debe volar el archivo de log. */
    private static String truncateForLog(String statement) {
        String oneLine = statement.replace('\n', ' ').replace('\r', ' ').strip();
        return oneLine.length() > 500 ? oneLine.substring(0, 500) + "… (truncado, " + oneLine.length() + " caracteres)" : oneLine;
    }

    /**
     * Aclara el mensaje crudo de PostgreSQL cuando el error real es un
     * script con varias sentencias SIN {@code ;} entre ellas — hallazgo
     * real del usuario (2026-08-22): "en SQL Server sí corre, en Postgres
     * no". No es un bug de Faro — a diferencia de T-SQL (donde el `;` es
     * opcional entre sentencias, cada una se reconoce por su propia
     * palabra clave inicial), el estándar SQL/PostgreSQL de verdad
     * requiere `;` para separar sentencias dentro de un mismo bloque; sin
     * eso, el parser de Postgres ve "DELETE ... SELECT ..." como una sola
     * sentencia inválida y truena justo en la palabra que empieza la
     * "siguiente" sentencia que el usuario tenía en mente.
     *
     * <p><b>Deliberadamente NO se intenta partir el script solo</b> —
     * adivinar dónde va el `;` que falta, buscando palabras como
     * {@code SELECT} a la mitad del texto, rompería sentencias legítimas
     * con subconsultas (ej. {@code INSERT INTO x SELECT * FROM y} también
     * "empieza" con `SELECT` a la mitad, sin ser dos sentencias). Más
     * seguro explicar el error real que adivinar mal una corrección.
     */
    private static String enrichErrorMessage(DbEngine engine, String rawMessage) {
        if (engine != DbEngine.POSTGRES || rawMessage == null) {
            return rawMessage;
        }
        Matcher matcher = PG_SYNTAX_ERROR_NEAR_WORD.matcher(rawMessage);
        if (matcher.find() && STATEMENT_START_KEYWORDS.contains(matcher.group(1).toUpperCase(Locale.ROOT))) {
            return rawMessage + " — ¿Falta un punto y coma (;) antes de esto? PostgreSQL necesita ';' "
                    + "entre cada sentencia de un script (SQL Server es más permisivo con esto).";
        }
        return rawMessage;
    }

    /**
     * Arma el respaldo real de cancelación ({@code KILL <spid>}/
     * {@code pg_cancel_backend(pid)}) — lee el pid/spid del backend que va
     * a correr la consulta (una consulta corta extra sobre la MISMA
     * conexión, antes de la consulta real) y lo deja listo en
     * {@code status} para que {@link ExecutionStatus#cancelQuery} lo
     * dispare si hace falta. Si no se pudo leer el pid (motor no
     * soportado, la consulta corta falla) no pasa nada — la cancelación
     * sigue funcionando igual, solo sin este respaldo para esa base.
     */
    private static void attachKillFallback(
            ExecutionStatus status, DatabaseEntry db, CredentialStore.Credentials creds,
            ConnectionPoolManager pool, Connection conn) {
        Integer backendId = fetchBackendId(db.engine(), conn);
        if (backendId != null) {
            log.debug("[{}] Respaldo de cancelación listo — backend id={}.", db.alias(), backendId);
            status.attachKillFallback(() -> killBackend(db, creds, pool, backendId));
        } else {
            log.debug("[{}] No se pudo leer el pid/spid del backend — sin respaldo de cancelación para esta base.", db.alias());
        }
    }

    private static Integer fetchBackendId(DbEngine engine, Connection conn) {
        String query = switch (engine) {
            case POSTGRES -> "SELECT pg_backend_pid()";
            case SQL_SERVER -> "SELECT @@SPID";
        };
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(query)) {
            return rs.next() ? rs.getInt(1) : null;
        } catch (SQLException e) {
            log.debug("No se pudo leer el pid/spid del backend: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Corre en su propio hilo (ver {@code ExecutionStatus#cancelQuery}) —
     * abre una conexión NUEVA del mismo pool (la que corre la consulta
     * larga está ocupada) para mandar el comando administrativo.
     * {@code backendId} viene de {@link #fetchBackendId}, siempre un
     * entero leído de la propia base, nunca texto de usuario — no hace
     * falta escaparlo. <b>Límite conocido:</b> si el pool está saturado
     * (ej. {@code poolSize=1} y esa única conexión es justo la que está
     * corriendo la consulta que se quiere matar), conseguir esta conexión
     * nueva puede tardar hasta el {@code connectionTimeout} de HikariCP o
     * no conseguirla nunca — recomendado dejar {@code poolSize >= 2} si se
     * depende de este respaldo.
     */
    private static void killBackend(DatabaseEntry db, CredentialStore.Credentials creds, ConnectionPoolManager pool, int backendId) {
        String command = switch (db.engine()) {
            case POSTGRES -> "SELECT pg_cancel_backend(" + backendId + ")";
            case SQL_SERVER -> "KILL " + backendId;
        };
        log.info("[{}] Disparando respaldo de cancelación: {}", db.alias(), command);
        try (Connection killConn = pool.getConnection(db, creds);
             Statement s = killConn.createStatement()) {
            s.execute(command);
            log.info("[{}] Respaldo de cancelación enviado correctamente (backend id={}).", db.alias(), backendId);
        } catch (SQLException e) {
            // No es un error para el usuario — Statement.cancel() ya es el camino
            // principal, esto es solo el respaldo. La sesión ya pudo haber
            // terminado sola antes de que este comando llegara.
            log.warn("[{}] Respaldo KILL/pg_cancel_backend falló (backend id={}): {}", db.alias(), backendId, e.getMessage());
        }
    }

    /** Ver el javadoc de la clase — heurística por primera palabra clave, no un parser SQL completo. */
    private static boolean isReadOnlyStatement(String sql) {
        Matcher noise = LEADING_NOISE.matcher(sql);
        String rest = (noise.lookingAt() ? sql.substring(noise.end()) : sql).stripLeading();

        int end = 0;
        while (end < rest.length() && Character.isLetter(rest.charAt(end))) {
            end++;
        }
        String firstWord = rest.substring(0, end).toUpperCase(Locale.ROOT);
        return READ_ONLY_LEADING_KEYWORDS.contains(firstWord);
    }

    private static void reportSuccess(ExecutionStatus status, int rowCount, long startedAt) {
        if (status == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        Platform.runLater(() -> {
            status.stateProperty().set(ExecutionStatus.State.SUCCEEDED);
            status.rowCountProperty().set(rowCount);
            status.elapsedMillisProperty().set(elapsed);
        });
    }

    private static void reportFailure(ExecutionStatus status, String message, long startedAt) {
        if (status == null) {
            return;
        }
        long elapsed = System.currentTimeMillis() - startedAt;
        Platform.runLater(() -> {
            status.stateProperty().set(ExecutionStatus.State.FAILED);
            status.elapsedMillisProperty().set(elapsed);
            status.messageProperty().set(message);
        });
    }

    private static void reportCancelled(ExecutionStatus status, long startedAt) {
        long elapsed = System.currentTimeMillis() - startedAt;
        Platform.runLater(() -> {
            status.stateProperty().set(ExecutionStatus.State.CANCELLED);
            status.elapsedMillisProperty().set(elapsed);
            status.messageProperty().set("Cancelado por el usuario");
        });
    }
}
