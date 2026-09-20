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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Lo que es igual para TODAS las bases de una corrida — se calcula una sola vez en
     * {@link #execute} y se le pasa a cada {@link #runOne} (2026-09-10, hallazgo B4).
     *
     * <p>Existe además para no seguir engordando la firma de {@code runOne}, que ya
     * iba por 10 parámetros posicionales: agrupar los invariantes de la corrida en un
     * solo objeto la baja a 9 y deja explícito cuáles varían por base y cuáles no.
     *
     * <p>{@code allStatementsReadOnly} se calcula acá aunque solo lo miren las bases
     * en modo {@code READ_ONLY}: es una propiedad del SCRIPT, no de la base, y con
     * varias bodegas protegidas marcadas se recalculaba idéntico una vez por cada una.
     */
    record RunPlan(List<String> statements, boolean allStatementsReadOnly, int fetchSize, int maxDisplayRows) {

        RunPlan(List<String> statements, int fetchSize, int maxDisplayRows) {
            this(statements, statements.stream().allMatch(QueryExecutionService::isReadOnlyStatement),
                    fetchSize, maxDisplayRows);
        }
    }

    /**
     * Cuántas filas más caben antes del tope de {@link RunPlan#maxDisplayRows}, dado
     * cuántas se llevan leídas entre TODAS las bases.
     *
     * <p>El tope es del resultado <b>combinado</b>, no por base: lo que puede tumbar la
     * app es lo que termina junto en el grid. Por eso el contador se comparte entre los
     * hilos y esta cuenta se hace contra ese total, no contra el avance de cada base.
     *
     * <p>Devuelve 0 cuando ya no cabe ninguna — es la señal de cortar la lectura del
     * {@code ResultSet}, que además evita que el resto de las filas viajen por la red.
     */
    static int remainingCapacity(int alreadyCollected, int maxDisplayRows) {
        return Math.max(0, maxDisplayRows - alreadyCollected);
    }

    public static Task<QueryResult> execute(
            List<DatabaseEntry> databases, CredentialStore credentials, ConnectionPoolManager pool,
            Map<String, ExecutionStatus> statusByDatabaseId, String sql, int maxConcurrentDatabases,
            int fetchSize, int maxDisplayRows, Map<DbEngine, String> engineVersions) {
        return new Task<>() {
            @Override
            protected QueryResult call() throws InterruptedException {
                long startedAt = System.currentTimeMillis();
                AtomicReference<List<String>> columnsRef = new AtomicReference<>();
                List<Object[]> rows = Collections.synchronizedList(new ArrayList<>());
                List<String> errors = Collections.synchronizedList(new ArrayList<>());
                // Compartidos entre las bases: el tope es del resultado COMBINADO, que es
                // lo que termina junto en el grid. Ver remainingCapacity().
                AtomicInteger collectedRows = new AtomicInteger();
                AtomicBoolean truncated = new AtomicBoolean();

                // UNA sola vez por corrida, no una por base (2026-09-10, hallazgo B4 de
                // ANALISIS_OPTIMIZACION_ESTRUCTURA.md). Partir el script es un escaneo
                // carácter por carácter de todo el texto más un substring por sentencia, y
                // el resultado es IDÉNTICO para todas las bases —es el mismo `sql`—, pero
                // se calculaba dentro de runOne, o sea N veces en paralelo, cada una con su
                // propia copia del script partido viva a la vez. Lo mismo con la validación
                // de solo lectura: es una función del script, no de la base; de la base solo
                // depende SI aplica.
                RunPlan plan = new RunPlan(
                        SqlStatementSplitter.split(sql),
                        fetchSize, maxDisplayRows);
                int poolSize = Math.min(Math.max(1, databases.size()), Math.max(1, maxConcurrentDatabases));
                log.info("Ejecutando consulta contra {} base(s), concurrencia={}, fetchSize={}, sql.length={}, sentencias={}",
                        databases.size(), poolSize, fetchSize, sql.length(), plan.statements().size());
                ExecutorService executor = Executors.newFixedThreadPool(poolSize);
                try {
                    List<Callable<Void>> jobs = databases.stream()
                        .<Callable<Void>>map(db -> () -> {
                            runOne(db, credentials, pool, plan, statusByDatabaseId.get(db.id()), columnsRef, rows, errors,
                                    collectedRows, truncated,
                                    engineVersions);
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
                // new ArrayList<>(rows) copia solo la lista de NIVEL SUPERIOR (un array de
                // referencias) para salir de Collections.synchronizedList — cada fila real
                // (Object[]) NO se copia, sigue siendo la misma instancia. Ver QueryResult.
                return new QueryResult(columns == null ? List.of() : columns, new ArrayList<>(rows),
                        new ArrayList<>(errors), truncated.get());
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
                List<Object[]> rows = new ArrayList<>();
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
            ResultSet rs, String databaseAlias, List<String> columns, List<Object[]> rows) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        if (columns.isEmpty()) {
            columns.add("Base de datos");
            for (int i = 1; i <= columnCount; i++) {
                columns.add(meta.getColumnLabel(i));
            }
        }
        while (rs.next()) {
            Object[] row = new Object[columnCount + 1];
            row[0] = databaseAlias;
            for (int i = 1; i <= columnCount; i++) {
                row[i] = rs.getObject(i);
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
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, RunPlan plan,
            ExecutionStatus status, AtomicReference<List<String>> columnsRef,
            List<Object[]> rows, List<String> errors,
            AtomicInteger collectedRows, AtomicBoolean truncated,
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

        List<String> statements = plan.statements();
        if (db.mode() == ServerMode.READ_ONLY && !plan.allStatementsReadOnly()) {
            String message = "Base de solo lectura — la consulta debe empezar con "
                    + "SELECT/WITH/SHOW/EXPLAIN/DESCRIBE";
            log.warn("[{}] Ejecución rechazada — {}", db.alias(), message);
            errors.add(db.alias() + ": " + message);
            reportFailure(status, message, startedAt);
            return;
        }

        int rowCount = 0;
        // Fuera del try para poder restaurar autoCommit en el finally y, sobre todo, para
        // que el catch sepa si hay una transacción abierta que revertir.
        boolean useCursor = false;
        Connection openConnection = null;
        try (Connection conn = pool.getConnection(db, creds.get());
             Statement jdbcStatement = conn.createStatement()) {
            openConnection = conn;
            // Conexión real, exitosa, de verdad — mismo punto de sincronización real
            // que la carga de esquema de DatabaseTreeItem (2026-08-28, pedido
            // explícito del usuario: "estos círculos de conexión deberían estar
            // sincronizados"). Platform.runLater — DatabaseEntry#connectionStatus es
            // una propiedad de JavaFX, este método corre en un hilo de fondo (uno por
            // base, ver QueryExecutionService#execute).
            Platform.runLater(() -> db.setConnectionStatus(DatabaseEntry.ConnectionStatus.CONNECTED));
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
            jdbcStatement.setFetchSize(plan.fetchSize());
            // Cursor real en PostgreSQL — ver shouldUseCursor(). Tiene que ir DESPUÉS de
            // setFetchSize (el driver mira las dos cosas juntas) y antes de ejecutar nada.
            useCursor = shouldUseCursor(db, plan);
            if (useCursor) {
                conn.setAutoCommit(false);
            }

            List<String> lastColumnNames = null;
            List<Object[]> lastRows = null;
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

                    // Se para al llegar al tope COMBINADO — ver remainingCapacity(). Cortar
                    // acá no solo acota la memoria: deja de pedirle filas al servidor, así
                    // que el resto del resultado ni siquiera viaja por la red.
                    List<Object[]> theseRows = new ArrayList<>();
                    while (remainingCapacity(collectedRows.get(), plan.maxDisplayRows()) > 0 && rs.next()) {
                        Object[] row = new Object[columnCount + 1];
                        row[0] = db.alias();
                        for (int i = 1; i <= columnCount; i++) {
                            row[i] = rs.getObject(i);
                        }
                        theseRows.add(row);
                        collectedRows.incrementAndGet();
                    }
                    // Quedó algo sin leer: el tope se alcanzó y todavía hay filas.
                    if (remainingCapacity(collectedRows.get(), plan.maxDisplayRows()) == 0 && rs.next()) {
                        truncated.set(true);
                        log.info("[{}] Lectura cortada en el tope de {} fila(s) en memoria — hay más.",
                                db.alias(), plan.maxDisplayRows());
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
            if (useCursor) {
                // Cierra la transacción de lectura. No hay nada que persistir —
                // shouldUseCursor solo deja entrar scripts de solo lectura— pero dejarla
                // abierta mantendría un snapshot vivo en el servidor hasta que la conexión
                // se reciclara.
                conn.commit();
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
            //
            // ESTE catch específico (no el de SQLException de arriba) es el punto real
            // donde pool.getConnection(...) falló en sí — pedido explícito del usuario
            // (2026-08-28): "si por alguna razón la contraseña cambió, que se marque en
            // rojo... que indique que no ha podido hacerse una conexión exitosa". A
            // propósito NO se toca connectionStatus en el catch de SQLException de
            // arriba — ese cubre errores de SQL (sintaxis, permisos sobre una tabla,
            // etc.) con la conexión YA abierta con éxito, marcar FAILED ahí volvería
            // rojo un punto que en realidad sí conecta bien, solo que el script tiene
            // un error.
            Platform.runLater(() -> db.setConnectionStatus(DatabaseEntry.ConnectionStatus.FAILED));
            log.error("[{}] Excepción no esperada tras {} ms.", db.alias(), System.currentTimeMillis() - startedAt, e);
            errors.add(db.alias() + ": " + e.getMessage());
            reportFailure(status, String.valueOf(e.getMessage()), startedAt);
        } finally {
            if (useCursor && openConnection != null) {
                endCursorTransaction(db, openConnection);
            }
            if (status != null) {
                status.attachStatement(null);
            }
        }
    }

    /**
     * Cierra la transacción abierta por el modo cursor y restaura el {@code autoCommit}
     * antes de que la conexión vuelva al pool.
     *
     * <p><b>El orden importa y no es intercambiable:</b> según el contrato de JDBC,
     * cambiar {@code autoCommit} con una transacción abierta la <b>confirma
     * implícitamente</b>. Por eso primero se revierte lo que quede vivo (en el camino
     * de éxito ya se hizo {@code commit}, así que esto no encuentra nada; en el de
     * error sí) y recién después se restaura la bandera.
     *
     * <p>Se restaura a mano aunque HikariCP también lo haría al reciclar la conexión
     * ({@code DIRTY_BIT_AUTOCOMMIT} → {@code resetConnectionState}, verificado en su
     * código): que la transacción termine de forma deliberada no debe depender del
     * comportamiento interno del pool.
     *
     * <p>Mejor esfuerzo — si el servidor ya cerró la sesión (una cancelación con
     * {@code pg_cancel_backend} de por medio) estas llamadas fallan, y eso no es un
     * error que reportarle al usuario: la consulta ya terminó de la forma que
     * corresponda y la conexión se va a descartar igual.
     */
    private static void endCursorTransaction(DatabaseEntry db, Connection conn) {
        try {
            if (!conn.getAutoCommit()) {
                conn.rollback();
            }
        } catch (SQLException e) {
            log.debug("[{}] rollback al cerrar el modo cursor falló (la sesión ya pudo haber terminado): {}",
                    db.alias(), e.getMessage());
        }
        try {
            conn.setAutoCommit(true);
        } catch (SQLException e) {
            log.debug("[{}] no se pudo restaurar autoCommit: {}", db.alias(), e.getMessage());
        }
    }

    /**
     * Si esta corrida debe traer las filas con un cursor del servidor en vez de
     * cargarlas todas en el driver (2026-09-14).
     *
     * <p><b>El problema.</b> {@code Statement#setFetchSize} —la preferencia "fetch" de
     * Preferencias → Rendimiento— <b>no hacía absolutamente nada en PostgreSQL</b>. La
     * documentación de pgJDBC exige cuatro condiciones para usar cursor, y tres ya se
     * cumplían (protocolo V3, {@code TYPE_FORWARD_ONLY} por defecto de
     * {@code createStatement()}, y una sentencia a la vez porque
     * {@link SqlStatementSplitter} ya las separa). Faltaba la cuarta, textual:
     * <i>"The Connection must not be in autocommit mode. The backend closes cursors at
     * the end of transactions, so in autocommit mode the backend will have closed the
     * cursor before anything can be fetched from it."</i>
     *
     * <p>Sin cursor, el driver <b>materializa el resultado completo</b> antes de que
     * {@code execute} retorne; recién entonces el bucle de {@link #runOne} lo copia a
     * los {@code Object[]}. Al terminar el bucle, el resultado vive <b>dos veces</b>:
     * el buffer del driver y las filas de la app. Es la causa de memoria que quedaba
     * en pie después de haber quitado las otras dos copias (ver
     * {@code OPTIMIZACION_RENDIMIENTO.md}), y el escenario del {@code OutOfMemoryError}
     * reportado con 6 bodegas × 500,000 filas.
     *
     * <p><b>Por qué SOLO con scripts de solo lectura.</b> Desactivar el autocommit
     * convierte el script entero en una transacción: un fallo en la sentencia 3 de 5
     * desharía las dos primeras, cuando hoy cada una se confirma sola. Eso es un cambio
     * de comportamiento que nadie pidió. Limitándolo a scripts donde no hay nada que
     * confirmar, el cambio es <b>invisible</b>: mismo resultado, mismo manejo de
     * errores, solo que las filas llegan de a {@code fetchSize} en vez de todas juntas.
     * La condición ya estaba calculada en {@link RunPlan#allStatementsReadOnly()}, que
     * hasta ahora solo servía para el modo Solo lectura del árbol.
     *
     * <p>SQL Server no entra: su driver respeta {@code setFetchSize} sin tocar el
     * autocommit, así que ahí no hay nada que arreglar y cambiarlo solo agregaría
     * semántica transaccional sin ganancia.
     */
    static boolean shouldUseCursor(DatabaseEntry db, RunPlan plan) {
        return db.engine() == DbEngine.POSTGRES && plan.allStatementsReadOnly();
    }

    /** Cuántos caracteres de una sentencia llegan al archivo de log — un INSERT con miles de literales no debe volar el log. */
    private static final int LOG_STATEMENT_LIMIT = 500;

    /**
     * Recorta el texto de una sentencia para el log.
     *
     * <p><b>Recorta ANTES de limpiar, no después</b> (2026-09-12): la versión anterior
     * hacía {@code statement.replace('\n',' ').replace('\r',' ').strip()} sobre el
     * texto COMPLETO y recién entonces cortaba a 500 caracteres. Cada {@code replace}
     * copia la cadena entera, así que una sentencia de 2 MB producía ~4 MB de basura
     * — y esto corre por cada sentencia, por cada base, en cada corrida: con 20
     * bodegas y un {@code INSERT} generado grande (algo que esta misma app produce)
     * son decenas de MB de copias temporales solo para escribir 500 caracteres.
     *
     * <p>El argumento además se evalúa SIEMPRE, aunque DEBUG estuviera apagado: el
     * formato diferido de SLF4J difiere el {@code toString} de los argumentos, no la
     * llamada al método que los produce. Recortando primero, el costo pasa a ser fijo
     * (500 caracteres) en vez de proporcional al tamaño del script.
     */
    private static String truncateForLog(String statement) {
        boolean truncated = statement.length() > LOG_STATEMENT_LIMIT;
        String head = truncated ? statement.substring(0, LOG_STATEMENT_LIMIT) : statement;
        String oneLine = head.replace('\n', ' ').replace('\r', ' ').strip();
        return truncated
                ? oneLine + "… (truncado, " + statement.length() + " caracteres)"
                : oneLine;
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
    static String enrichErrorMessage(DbEngine engine, String rawMessage) {
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
    static boolean isReadOnlyStatement(String sql) {
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
