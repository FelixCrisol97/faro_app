package com.faro.app.query;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.application.Platform;
import javafx.concurrent.Task;

/**
 * Lee tablas/vistas/funciones/procedimientos/triggers reales de una base ya
 * conectada — vía {@link DatabaseMetaData} donde alcanza (API JDBC estándar,
 * funciona igual para PostgreSQL y SQL Server), y con SQL propio por motor
 * donde no ({@link #fetchTriggers} — JDBC no tiene ningún método para
 * triggers; {@link #fetchSqlServerRoutines} — ver el hallazgo real de abajo).
 * Respalda dos consumidores que comparten el mismo caché ({@link #cached}/
 * {@link #loadInBackground}): el autocompletado real de tablas/columnas
 * ({@code SqlAutocomplete}) y el explorador de esquema del árbol de
 * conexiones ({@code DatabaseTreeItem}) — un solo fetch por base sirve a
 * los dos, expandir una base en el árbol también acelera su autocompletado.
 *
 * <p><b>Filtrado al esquema por defecto de cada motor</b>
 * ({@code public} en PostgreSQL, {@code dbo} en SQL Server) — sin esto,
 * {@code getTables}/{@code getColumns} con {@code schemaPattern=null}
 * también trae las tablas internas del motor ({@code pg_catalog},
 * {@code information_schema}, etc. en Postgres), inundando el
 * autocompletado con cientos de nombres irrelevantes que nadie va a usar
 * en una consulta real. Límite conocido: una base con tablas de usuario
 * repartidas en varios esquemas custom (no el default) no las va a ver
 * sugeridas — v0, cubre el caso común.
 *
 * <p><b>Hallazgo real (2026-08-25, verificado contra los contenedores reales
 * de {@code bodegas-test}, no adivinado):</b> en SQL Server,
 * {@code DatabaseMetaData#getFunctions}/{@code #getProcedures} del driver
 * mssql-jdbc NO distinguen función de procedimiento — con una función
 * {@code fn_test} y un procedimiento {@code sp_test} reales creados de
 * prueba, los dos métodos devolvieron AMBOS nombres, mezclados. En
 * PostgreSQL (pgjdbc) los mismos dos métodos sí distinguen bien (verificado
 * igual, con los mismos dos objetos de prueba). Por eso {@link #fetch}
 * usa los métodos JDBC estándar solo para PostgreSQL, y para SQL Server
 * consulta {@code sys.objects} directo, separando por {@code type} real
 * ({@code 'P'} = procedimiento, {@code 'FN'/'IF'/'TF'} = función) — la
 * única forma verificada de obtener la lista correcta en ese motor.
 */
public final class SchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntrospector.class);

    public record SchemaInfo(
            List<String> tableNames,
            List<String> viewNames,
            List<String> functionNames,
            List<String> procedureNames,
            List<String> triggerNames,
            Map<String, List<String>> columnsByTable) {

        /** Tablas + vistas juntas — lo único que le importa al autocompletado (no distingue las dos para sugerir un nombre después de FROM). */
        public List<String> queryableNames() {
            List<String> combined = new ArrayList<>(tableNames.size() + viewNames.size());
            combined.addAll(tableNames);
            combined.addAll(viewNames);
            return combined;
        }

        public List<String> allColumnNames() {
            return columnsByTable.values().stream().flatMap(List::stream).distinct().toList();
        }
    }

    /** Caché en memoria por id de base, compartido entre autocompletado y el árbol — vive mientras la app esté abierta, sin invalidación si el esquema cambia en caliente del lado del servidor (límite conocido, v0; alcanza con marcar/desmarcar la base o reiniciar la app). */
    private static final Map<String, SchemaInfo> cache = new ConcurrentHashMap<>();
    /** Bases con una carga en curso — evita pedir el mismo esquema dos veces si autocompletado y el árbol lo piden casi a la vez. */
    private static final java.util.Set<String> loading = ConcurrentHashMap.newKeySet();

    private SchemaIntrospector() {
    }

    public static Optional<SchemaInfo> cached(String databaseId) {
        return Optional.ofNullable(cache.get(databaseId));
    }

    /** Descarta el esquema en caché de una base — "Recargar esquema" del menú contextual del árbol (2026-08-25, el usuario preguntó cómo recargar y no había forma real). La próxima llamada a {@link #loadInBackground} vuelve a pedirlo de verdad. */
    public static void invalidate(String databaseId) {
        cache.remove(databaseId);
    }

    /**
     * Dispara {@link #fetch} en un hilo aparte y deja el resultado en
     * {@link #cache} — {@code onLoaded} corre en el hilo de JavaFX
     * ({@code Platform.runLater}), seguro de tocar nodos de UI directo desde
     * ahí. No hace nada si ya hay una carga en curso para esa base (evita
     * mandar la misma consulta dos veces).
     */
    public static void loadInBackground(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, Consumer<SchemaInfo> onLoaded) {
        if (!loading.add(db.id())) {
            return;
        }
        Task<SchemaInfo> task = fetch(db, credentials, pool);
        task.setOnSucceeded(e -> {
            SchemaInfo info = task.getValue();
            cache.put(db.id(), info);
            loading.remove(db.id());
            Platform.runLater(() -> onLoaded.accept(info));
        });
        task.setOnFailed(e -> {
            log.warn("[{}] No se pudo cargar el esquema", db.alias(), task.getException());
            loading.remove(db.id());
        });
        Thread thread = new Thread(task, "faro-schema-fetch");
        thread.setDaemon(true);
        thread.start();
    }

    public static Task<SchemaInfo> fetch(DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool) {
        return new Task<>() {
            @Override
            protected SchemaInfo call() throws SQLException {
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
                    log.warn("[{}] SchemaIntrospector abortado — sin credenciales guardadas.", db.alias());
                    throw new IllegalStateException("Sin usuario/contraseña guardados para " + db.alias());
                }
                String schema = switch (db.engine()) {
                    case POSTGRES -> "public";
                    case SQL_SERVER -> "dbo";
                };
                log.debug("[{}] Leyendo esquema '{}'…", db.alias(), schema);

                List<String> tableNames;
                List<String> viewNames;
                List<String> functionNames;
                List<String> procedureNames;
                List<String> triggerNames;
                Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
                try (Connection conn = pool.getConnection(db, creds.get())) {
                    DatabaseMetaData meta = conn.getMetaData();
                    String catalog = conn.getCatalog();

                    tableNames = fetchNames(meta.getTables(catalog, schema, "%", new String[] {"TABLE"}), "TABLE_NAME");
                    viewNames = fetchNames(meta.getTables(catalog, schema, "%", new String[] {"VIEW"}), "TABLE_NAME");
                    for (String name : tableNames) {
                        columnsByTable.put(name, new ArrayList<>());
                    }
                    for (String name : viewNames) {
                        columnsByTable.put(name, new ArrayList<>());
                    }
                    try (ResultSet rs = meta.getColumns(catalog, schema, "%", "%")) {
                        while (rs.next()) {
                            List<String> columns = columnsByTable.get(rs.getString("TABLE_NAME"));
                            if (columns != null) {
                                columns.add(rs.getString("COLUMN_NAME"));
                            }
                        }
                    }

                    if (db.engine() == com.faro.app.model.DbEngine.SQL_SERVER) {
                        // Ver el javadoc de la clase — getFunctions()/getProcedures() de
                        // mssql-jdbc no distinguen una cosa de la otra, verificado contra un
                        // contenedor real con ambos objetos creados de prueba.
                        functionNames = fetchSqlServerRoutines(conn, schema, "'FN','IF','TF'");
                        procedureNames = fetchSqlServerRoutines(conn, schema, "'P'");
                    } else {
                        functionNames = fetchNames(meta.getFunctions(catalog, schema, "%"), "FUNCTION_NAME");
                        procedureNames = fetchNames(meta.getProcedures(catalog, schema, "%"), "PROCEDURE_NAME");
                    }
                    triggerNames = fetchTriggers(conn, db.engine(), schema);
                }
                log.info("[{}] Esquema leído — {} tabla(s), {} vista(s), {} función(es), {} procedimiento(s), {} trigger(s), {} columna(s) en total.",
                        db.alias(), tableNames.size(), viewNames.size(), functionNames.size(), procedureNames.size(),
                        triggerNames.size(), columnsByTable.values().stream().mapToInt(List::size).sum());
                return new SchemaInfo(tableNames, viewNames, functionNames, procedureNames, triggerNames, columnsByTable);
            }
        };
    }

    private static List<String> fetchNames(ResultSet rs, String column) throws SQLException {
        List<String> names = new ArrayList<>();
        try (rs) {
            while (rs.next()) {
                names.add(rs.getString(column));
            }
        }
        return names;
    }

    /** {@code typesInClause} ya viene formateado listo para pegar en el IN (...), ej. {@code "'P'"} — siempre valores fijos nuestros, nunca texto de usuario, no hace falta parametrizarlo. */
    private static List<String> fetchSqlServerRoutines(Connection conn, String schema, String typesInClause) throws SQLException {
        String query = "SELECT o.name FROM sys.objects o JOIN sys.schemas s ON o.schema_id = s.schema_id "
                + "WHERE o.type IN (" + typesInClause + ") AND s.name = '" + schema + "'";
        List<String> names = new ArrayList<>();
        try (Statement statement = conn.createStatement(); ResultSet rs = statement.executeQuery(query)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }

    /**
     * {@code DatabaseMetaData} no tiene ningún método para triggers — SQL
     * propio por motor, verificado contra los contenedores reales de
     * {@code bodegas-test} con un trigger de prueba creado a propósito
     * (2026-08-25, no adivinado): PostgreSQL vía
     * {@code information_schema.triggers} ({@code DISTINCT} porque un
     * trigger con varios eventos — INSERT+UPDATE, por ejemplo — aparece en
     * una fila por evento); SQL Server vía {@code sys.triggers} unido a
     * {@code sys.tables}/{@code sys.schemas}, {@code parent_class=1} para
     * quedarse solo con triggers de tabla/vista (no los de base de datos
     * completa, {@code parent_class=0}).
     */
    private static List<String> fetchTriggers(Connection conn, com.faro.app.model.DbEngine engine, String schema) throws SQLException {
        String query = switch (engine) {
            case POSTGRES -> "SELECT DISTINCT trigger_name FROM information_schema.triggers WHERE trigger_schema = '" + schema + "'";
            case SQL_SERVER -> "SELECT t.name FROM sys.triggers t "
                    + "JOIN sys.tables tbl ON t.parent_id = tbl.object_id "
                    + "JOIN sys.schemas s ON tbl.schema_id = s.schema_id "
                    + "WHERE s.name = '" + schema + "' AND t.parent_class = 1";
        };
        List<String> names = new ArrayList<>();
        try (Statement statement = conn.createStatement(); ResultSet rs = statement.executeQuery(query)) {
            while (rs.next()) {
                names.add(rs.getString(1));
            }
        }
        return names;
    }
}
