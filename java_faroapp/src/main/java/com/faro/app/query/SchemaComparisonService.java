package com.faro.app.query;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.ColumnMetadata;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.ui.SchemaTreeNode;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.concurrent.Task;

/**
 * Compara el MISMO objeto de esquema (una función, una tabla, un trigger…) entre
 * varias bases a la vez y dice si todas tienen la misma versión — pedido
 * explícito del usuario (2026-09-07): "si yo quiero sacar una función que está
 * en todas las BD… tener la opción de extraer el script de esa función, su md5,
 * y compararla con todas las demás versiones de otras bodegas, la idea es sobre
 * todo para verificar que la función es la misma en todas las bodegas y que no
 * están usando versiones diferentes".
 *
 * <p><b>Por qué existe como servicio aparte y no como una consulta más:</b> el
 * dato que interesa acá no sale de ninguna tabla — es el DDL del propio objeto,
 * que cada motor expone por su cuenta ({@code pg_get_functiondef},
 * {@code OBJECT_DEFINITION()}, etc., ver {@link SchemaIntrospector}). No hay
 * ningún SQL que el usuario pueda escribir una sola vez y correr igual en
 * PostgreSQL y en SQL Server para obtener esto.
 *
 * <p><b>Devuelve un {@link QueryResult}</b>, exactamente el mismo tipo que una
 * ejecución masiva normal — así el resultado cae en la pestaña Resultados de
 * siempre y "Exportar CSV" funciona sin ningún camino nuevo (el usuario lo pidió
 * así: "que me la exporte en csv como ya lo hacen las consultas masivas").
 *
 * <p><b>Nunca usa el caché de {@link SchemaIntrospector}</b> — comparar versiones
 * entre bodegas solo tiene sentido leyendo lo que hay AHORA en cada servidor. Un
 * script cacheado hace media hora podría estar desactualizado justo en la base
 * que cambió, que es el caso que esta función existe para detectar.
 */
public final class SchemaComparisonService {

    private static final Logger log = LoggerFactory.getLogger(SchemaComparisonService.class);

    /** Marca de la fila cuyo hash coincide con el de la mayoría de las bases. */
    private static final String MATCHES_MAJORITY = "Sí";
    /** Marca de la fila que difiere de la mayoría — lo que el usuario está buscando encontrar. */
    private static final String DIFFERS = "NO — difiere";
    /** Cuando no hay una mayoría clara (empate, o todas distintas) no se puede señalar a nadie como "la correcta". */
    private static final String NO_MAJORITY = "sin mayoría";
    /** Una sola base de ese motor en la corrida — no hay contra qué compararla, y decir "Sí" sería mentir (coincide consigo misma). */
    private static final String NOTHING_TO_COMPARE = "única de su motor";

    private SchemaComparisonService() {
    }

    /**
     * Columnas del resultado — el orden importa para leerlo de un vistazo:
     * primero de qué base es la fila, después el veredicto (¿coincide?), y recién
     * al final el script completo, que es lo más largo.
     *
     * <p>"Motor" está a la vista a propósito: la comparación se hace POR MOTOR
     * (ver {@link #majorityByEngine}), así que saber de cuál es cada fila es
     * parte de leer el resultado, no un dato de adorno.
     */
    private static final List<String> COLUMNS = List.of(
            "Base de datos", "Motor", "Objeto", "Tipo", "Estado", "Coincide", "MD5", "Caracteres", "Definición");

    public static Task<QueryResult> compare(
            SchemaTreeNode.Kind kind, String objectName, String parentTable,
            List<DatabaseEntry> databases, CredentialStore credentials, ConnectionPoolManager pool,
            int maxConcurrentDatabases) {
        return new Task<>() {
            @Override
            protected QueryResult call() throws InterruptedException {
                long startedAt = System.currentTimeMillis();
                log.info("Comparando {} '{}' contra {} base(s).", kind, objectName, databases.size());

                List<Comparison> comparisons = Collections.synchronizedList(new ArrayList<>());
                List<String> errors = Collections.synchronizedList(new ArrayList<>());

                int poolSize = Math.min(Math.max(1, databases.size()), Math.max(1, maxConcurrentDatabases));
                ExecutorService executor = Executors.newFixedThreadPool(poolSize);
                try {
                    List<Callable<Void>> jobs = databases.stream()
                            .<Callable<Void>>map(db -> () -> {
                                comparisons.add(compareOne(db, credentials, pool, kind, objectName, parentTable, errors));
                                return null;
                            })
                            .toList();
                    executor.invokeAll(jobs);
                } finally {
                    executor.shutdown();
                }

                // El orden de llegada es el de terminación de cada hilo — se reordena por
                // alias para que el resultado sea estable entre corridas y fácil de leer.
                List<Comparison> ordered = new ArrayList<>(comparisons);
                ordered.sort((a, b) -> a.alias().compareToIgnoreCase(b.alias()));

                Map<DbEngine, String> majorityByEngine = majorityByEngine(ordered);
                List<Object[]> rows = new ArrayList<>(ordered.size());
                for (Comparison c : ordered) {
                    rows.add(new Object[] {
                            c.alias(),
                            c.engine().label(),
                            objectName,
                            kind.label(),
                            c.status(),
                            verdict(c, majorityByEngine, ordered),
                            c.hash() == null ? "" : c.hash(),
                            c.definition() == null ? "" : String.valueOf(c.definition().length()),
                            c.definition() == null ? "" : c.definition(),
                    });
                }
                log.info("Comparación de '{}' completa en {} ms — {} base(s), {} distinta(s) de la mayoría de su motor.",
                        objectName, System.currentTimeMillis() - startedAt, ordered.size(),
                        rows.stream().filter(r -> DIFFERS.equals(r[5])).count());
                return new QueryResult(COLUMNS, rows, new ArrayList<>(errors));
            }
        };
    }

    private static Comparison compareOne(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool,
            SchemaTreeNode.Kind kind, String objectName, String parentTable, List<String> errors) {
        Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
        if (creds.isEmpty()) {
            errors.add(db.alias() + ": sin usuario/contraseña guardados");
            return Comparison.failed(db, "Sin credenciales");
        }
        try (Connection conn = pool.getConnection(db, creds.get())) {
            String definition = kind == SchemaTreeNode.Kind.TABLES
                    // Una tabla no tiene DDL que el motor devuelva listo — se reconstruye
                    // desde sus columnas reales (mismo "mejor esfuerzo" que "Generar script
                    // CREATE" de una tabla, ver SqlScriptGenerator). Comparar ESO sigue
                    // detectando lo que importa acá: que una bodega tenga una columna de
                    // más, de menos, o con otro tipo.
                    ? createTableScript(conn, db, objectName)
                    : SchemaIntrospector.definitionFrom(conn, db, kind, objectName, parentTable);
            return Comparison.of(db, definition);
        } catch (SQLException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().lines().findFirst().orElse("");
            log.warn("[{}] No se pudo leer '{}': {}", db.alias(), objectName, message);
            errors.add(db.alias() + ": " + message);
            return Comparison.failed(db, message);
        }
    }

    private static String createTableScript(Connection conn, DatabaseEntry db, String tableName) throws SQLException {
        List<ColumnMetadata> columns = SchemaIntrospector.columnsFrom(conn, db, tableName);
        if (columns.isEmpty()) {
            throw new SQLException("La tabla '" + tableName + "' no existe en esta base (o no hay permiso para verla).");
        }
        return SqlScriptGenerator.generateCreateTableScript(tableName, columns);
    }

    /**
     * La versión mayoritaria <b>de cada motor</b>, no una sola global.
     *
     * <p><b>Por qué por motor (hallazgo real, 2026-09-07, corriendo esto contra
     * las 6 bases de prueba):</b> la primera versión calculaba una única mayoría
     * entre todas las bases y el resultado fue inservible — las 3 de PostgreSQL
     * daban un hash y las 3 de SQL Server otro, empate 3-3, "sin mayoría" para
     * todas. Y no porque las tablas fueran distintas: el DDL difiere porque cada
     * motor nombra sus tipos distinto ({@code character varying} contra
     * {@code nvarchar}, etc.). Comparar entre motores SIEMPRE iba a dar falsa
     * alarma, que es exactamente lo contrario de lo que esta función existe para
     * hacer. Agrupando por motor, cada bodega se compara solo contra las que de
     * verdad son comparables.
     *
     * <p>Dentro de cada motor, si no hay mayoría estricta (empate, o todas
     * distintas) el valor queda {@code null} y ninguna fila de ese motor se marca
     * como correcta: señalar a una como "la buena" sin que lo sea sería peor que
     * no decir nada.
     */
    private static Map<DbEngine, String> majorityByEngine(List<Comparison> comparisons) {
        Map<DbEngine, Map<String, Integer>> countsByEngine = new HashMap<>();
        for (Comparison c : comparisons) {
            if (c.hash() != null) {
                countsByEngine.computeIfAbsent(c.engine(), k -> new HashMap<>()).merge(c.hash(), 1, Integer::sum);
            }
        }
        Map<DbEngine, String> majority = new HashMap<>();
        countsByEngine.forEach((engine, counts) -> {
            String best = null;
            int bestCount = 0;
            boolean tied = false;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                    tied = false;
                } else if (entry.getValue() == bestCount) {
                    tied = true;
                }
            }
            if (!tied && best != null) {
                majority.put(engine, best);
            }
        });
        return majority;
    }

    private static String verdict(Comparison c, Map<DbEngine, String> majorityByEngine, List<Comparison> all) {
        if (c.hash() == null) {
            return "";
        }
        // Una sola base de ese motor: no hay con qué compararla. Decir "Sí" sería
        // engañoso (coincidiría consigo misma), y "difiere" sería falso.
        if (all.stream().filter(other -> other.engine() == c.engine() && other.hash() != null).count() < 2) {
            return NOTHING_TO_COMPARE;
        }
        String majority = majorityByEngine.get(c.engine());
        if (majority == null) {
            return NO_MAJORITY;
        }
        return c.hash().equals(majority) ? MATCHES_MAJORITY : DIFFERS;
    }

    /**
     * MD5 del script <b>normalizado</b>, no del texto crudo: se unifican los
     * saltos de línea ({@code \r\n} y {@code \r} a {@code \n}) y se recorta el
     * espacio del principio y del final. Sin eso, el mismo objeto creado desde un
     * cliente Windows y desde uno Linux daría hashes distintos por los finales de
     * línea — ruido puro que taparía las diferencias reales, que es justo lo que
     * esta función existe para encontrar. Todo lo demás (mayúsculas, sangría,
     * comentarios internos) SÍ cuenta como diferencia, a propósito: son cambios
     * reales en el objeto guardado en el servidor.
     */
    static String md5(String definition) {
        String normalized = definition.replace("\r\n", "\n").replace('\r', '\n').strip();
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // MD5 es obligatorio en toda JVM (java.security.MessageDigest lo garantiza),
            // así que esto no puede pasar de verdad — pero dejarlo silenciado sería peor.
            throw new IllegalStateException("Esta JVM no tiene MD5 disponible", e);
        }
    }

    /** Una fila del resultado, antes de convertirse en {@code Object[]} — {@code hash}/{@code definition} nulos si esa base falló. {@code engine} se guarda porque la mayoría se calcula por motor, ver {@link #majorityByEngine}. */
    private record Comparison(String alias, DbEngine engine, String status, String hash, String definition) {

        static Comparison of(DatabaseEntry db, String definition) {
            return new Comparison(db.alias(), db.engine(), "OK", md5(definition), definition);
        }

        static Comparison failed(DatabaseEntry db, String message) {
            return new Comparison(db.alias(), db.engine(), "Error: " + message, null, null);
        }
    }
}
