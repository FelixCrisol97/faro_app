package com.faro.app.query;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.concurrent.Task;

/**
 * Lee tabla/columnas reales de una base ya conectada, vía
 * {@link DatabaseMetaData} — la API JDBC estándar para esto, funciona igual
 * para PostgreSQL y SQL Server sin tener que escribir SQL propio contra
 * {@code information_schema}/{@code sys.columns} por motor. Respalda el
 * autocompletado real de tablas/columnas (ver {@code SqlAutocomplete}) —
 * antes solo sugería palabras clave SQL.
 *
 * <p><b>Dos consultas en total, no una por tabla</b> — {@link
 * DatabaseMetaData#getColumns} acepta {@code "%"} como patrón de nombre de
 * tabla, así que trae las columnas de TODAS las tablas de una base en una
 * sola llamada (evita el patrón N+1 de pedir columnas tabla por tabla, que
 * sería lentísimo en una base con cientos de tablas).
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
 */
public final class SchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntrospector.class);

    public record SchemaInfo(Map<String, List<String>> columnsByTable) {
        public List<String> tableNames() {
            return List.copyOf(columnsByTable.keySet());
        }

        public List<String> allColumnNames() {
            return columnsByTable.values().stream().flatMap(List::stream).distinct().toList();
        }
    }

    private SchemaIntrospector() {
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
                log.debug("[{}] Leyendo esquema '{}' para autocompletado…", db.alias(), schema);

                Map<String, List<String>> columnsByTable = new LinkedHashMap<>();
                try (Connection conn = pool.getConnection(db, creds.get())) {
                    DatabaseMetaData meta = conn.getMetaData();
                    String catalog = conn.getCatalog();

                    try (ResultSet rs = meta.getTables(catalog, schema, "%", new String[] {"TABLE", "VIEW"})) {
                        while (rs.next()) {
                            columnsByTable.put(rs.getString("TABLE_NAME"), new java.util.ArrayList<>());
                        }
                    }
                    try (ResultSet rs = meta.getColumns(catalog, schema, "%", "%")) {
                        while (rs.next()) {
                            List<String> columns = columnsByTable.get(rs.getString("TABLE_NAME"));
                            if (columns != null) {
                                columns.add(rs.getString("COLUMN_NAME"));
                            }
                        }
                    }
                }
                log.info("[{}] Esquema leído — {} tabla(s)/vista(s), {} columna(s) en total.", db.alias(),
                        columnsByTable.size(), columnsByTable.values().stream().mapToInt(List::size).sum());
                return new SchemaInfo(columnsByTable);
            }
        };
    }
}
