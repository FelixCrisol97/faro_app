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

    private static final Set<String> READ_ONLY_LEADING_KEYWORDS =
            Set.of("SELECT", "WITH", "SHOW", "EXPLAIN", "DESCRIBE", "DESC");
    private static final Pattern LEADING_NOISE =
            Pattern.compile("\\A(?:\\s+|--[^\\n]*\\n|/\\*.*?\\*/)*", Pattern.DOTALL);

    private QueryExecutionService() {
    }

    public static Task<QueryResult> execute(
            List<DatabaseEntry> databases, CredentialStore credentials, ConnectionPoolManager pool,
            Map<String, ExecutionStatus> statusByDatabaseId, String sql, int maxConcurrentDatabases) {
        return new Task<>() {
            @Override
            protected QueryResult call() throws InterruptedException {
                AtomicReference<List<String>> columnsRef = new AtomicReference<>();
                List<List<Object>> rows = Collections.synchronizedList(new ArrayList<>());
                List<String> errors = Collections.synchronizedList(new ArrayList<>());

                int poolSize = Math.min(Math.max(1, databases.size()), Math.max(1, maxConcurrentDatabases));
                ExecutorService executor = Executors.newFixedThreadPool(poolSize);
                try {
                    List<Callable<Void>> jobs = databases.stream()
                        .<Callable<Void>>map(db -> () -> {
                            runOne(db, credentials, pool, sql, statusByDatabaseId.get(db.id()), columnsRef, rows, errors);
                            return null;
                        })
                        .toList();
                    executor.invokeAll(jobs);
                } finally {
                    executor.shutdown();
                }

                List<String> columns = columnsRef.get();
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
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
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
                }
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

    private static void runOne(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, String sql,
            ExecutionStatus status, AtomicReference<List<String>> columnsRef,
            List<List<Object>> rows, List<String> errors) {
        long startedAt = System.currentTimeMillis();

        Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
        if (creds.isEmpty()) {
            String message = "Sin usuario/contraseña guardados";
            errors.add(db.alias() + ": " + message
                    + " (edítala y guarda unas propias, o define credenciales por defecto)");
            reportFailure(status, message, startedAt);
            return;
        }

        if (db.mode() == ServerMode.READ_ONLY && !isReadOnlyStatement(sql)) {
            String message = "Base de solo lectura — la consulta debe empezar con "
                    + "SELECT/WITH/SHOW/EXPLAIN/DESCRIBE";
            errors.add(db.alias() + ": " + message);
            reportFailure(status, message, startedAt);
            return;
        }

        int rowCount = 0;
        try (Connection conn = pool.getConnection(db, creds.get());
             Statement statement = conn.createStatement()) {
            if (status != null) {
                status.attachStatement(statement);
                attachKillFallback(status, db, creds.get(), pool, conn);
            }
            statement.setQueryTimeout(db.queryTimeoutSeconds());

            try (ResultSet rs = statement.executeQuery(sql)) {
                ResultSetMetaData meta = rs.getMetaData();
                int columnCount = meta.getColumnCount();
                List<String> columnNames = new ArrayList<>();
                columnNames.add("Base de datos");
                for (int i = 1; i <= columnCount; i++) {
                    columnNames.add(meta.getColumnLabel(i));
                }
                columnsRef.compareAndSet(null, columnNames);

                while (rs.next()) {
                    List<Object> row = new ArrayList<>(columnCount + 1);
                    row.add(db.alias());
                    for (int i = 1; i <= columnCount; i++) {
                        row.add(rs.getObject(i));
                    }
                    rows.add(row);
                    rowCount++;
                }
            }
            reportSuccess(status, rowCount, startedAt);
        } catch (SQLException e) {
            if (status != null && status.wasCancelRequested()) {
                reportCancelled(status, startedAt);
            } else {
                errors.add(db.alias() + ": " + e.getMessage());
                reportFailure(status, e.getMessage(), startedAt);
            }
        } catch (RuntimeException e) {
            // Ej. HikariPool.PoolInitializationException (no checked) al armar el pool con
            // una URL/config inválida — sin este catch, executor.invokeAll() se traga la
            // excepción en su Future descartado y esa fila se quedaba en "Ejecutando…" para
            // siempre, sin ningún error visible (hallazgo real de /code-review).
            errors.add(db.alias() + ": " + e.getMessage());
            reportFailure(status, String.valueOf(e.getMessage()), startedAt);
        } finally {
            if (status != null) {
                status.attachStatement(null);
            }
        }
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
            status.attachKillFallback(() -> killBackend(db, creds, pool, backendId));
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
        try (Connection killConn = pool.getConnection(db, creds);
             Statement s = killConn.createStatement()) {
            s.execute(command);
        } catch (SQLException e) {
            // No es un error para el usuario — Statement.cancel() ya es el camino
            // principal, esto es solo el respaldo. La sesión ya pudo haber
            // terminado sola antes de que este comando llegara.
            System.err.println("Respaldo KILL/pg_cancel_backend falló para " + db.alias() + ": " + e.getMessage());
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
