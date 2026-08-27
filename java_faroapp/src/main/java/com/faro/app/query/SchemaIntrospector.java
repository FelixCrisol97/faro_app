package com.faro.app.query;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.ColumnMetadata;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.ui.SchemaTreeNode;

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
 * igual, con los mismos dos objetos de prueba). Por eso {@link #fetchCategory}
 * usa los métodos JDBC estándar solo para PostgreSQL, y para SQL Server
 * consulta {@code sys.objects} directo, separando por {@code type} real
 * ({@code 'P'} = procedimiento, {@code 'FN'/'IF'/'TF'} = función) — la
 * única forma verificada de obtener la lista correcta en ese motor.
 */
public final class SchemaIntrospector {

    private static final Logger log = LoggerFactory.getLogger(SchemaIntrospector.class);

    /**
     * Solo tablas/vistas — lo único que se pide DE UNA al expandir una base
     * (2026-08-25, esquema progresivo: hallazgo en vivo del usuario contra
     * bases DEV reales de cliente, con muchas más tablas/columnas que los
     * contenedores {@code bodegas-test}). Antes esto era {@code SchemaInfo},
     * un solo record con las 6 categorías Y todas las columnas de todas las
     * tablas juntas — un fetch tan grande que el árbol se quedaba pegado en
     * "Cargando esquema…" en bases grandes. Funciones/Procedimientos/
     * Triggers/Tipos ahora se piden por separado, solo al expandir esa
     * categoría (ver {@link #loadCategoryInBackground}/{@code
     * CategoryTreeItem}); columnas de una tabla, solo al pedir SELECT/
     * INSERT/UPDATE/DELETE/CREATE TABLE de ESA tabla (ya existía ese camino,
     * {@link #fetchColumns} — antes solo lo usaban UPDATE/DELETE/CREATE
     * TABLE, ahora lo usan las 5).
     */
    public record SchemaStructure(List<String> tableNames, List<String> viewNames) {

        /** Tablas + vistas juntas — lo único que le importa al autocompletado (no distingue las dos para sugerir un nombre después de FROM). */
        public List<String> queryableNames() {
            List<String> combined = new ArrayList<>(tableNames.size() + viewNames.size());
            combined.addAll(tableNames);
            combined.addAll(viewNames);
            return combined;
        }
    }

    /** Caché en memoria por id de base, compartido entre autocompletado y el árbol — vive mientras la app esté abierta, sin invalidación si el esquema cambia en caliente del lado del servidor (límite conocido, v0; alcanza con marcar/desmarcar la base o reiniciar la app). */
    private static final Map<String, SchemaStructure> cache = new ConcurrentHashMap<>();
    /** Bases con una carga de estructura en curso — evita pedir el mismo esquema dos veces si autocompletado y el árbol lo piden casi a la vez. */
    private static final java.util.Set<String> loading = ConcurrentHashMap.newKeySet();
    /** Igual que {@link #loading} pero por base+categoría — Funciones/Procedimientos/Triggers/Tipos se piden cada una por separado (ver {@link #loadCategoryInBackground}), llave {@code "dbId:KIND"}. */
    private static final java.util.Set<String> categoryLoading = ConcurrentHashMap.newKeySet();
    /** Caché de columnas con tipo/PK, por base y por tabla — {@link #fetchColumns}, usado por "Generar SELECT/INSERT/UPDATE/DELETE/script CREATE" (tabla). Separado de {@code cache} porque es más caro de pedir (tipo/PK reales, no solo nombres) y solo hace falta bajo demanda, no al expandir la base. */
    private static final Map<String, Map<String, List<ColumnMetadata>>> columnDetailsCache = new ConcurrentHashMap<>();
    /** Caché de scripts CREATE, por base y por "{@code KIND:nombre}" — {@link #fetchDefinition}, usado por "Generar script CREATE" (vistas/funciones/procedimientos/triggers). */
    private static final Map<String, Map<String, String>> definitionCache = new ConcurrentHashMap<>();
    /** Caché de Funciones/Procedimientos/Tipos, por base y por {@link SchemaTreeNode.Kind} — {@link #fetchCategory}, solo se llena cuando el usuario expande esa categoría en el árbol. TRIGGERS no vive acá — ver {@link #triggerCache}, necesita tabla dueña además del nombre. */
    private static final Map<String, Map<SchemaTreeNode.Kind, List<String>>> categoryCache = new ConcurrentHashMap<>();
    /** Caché de Triggers, por base — nombre (o "tabla.nombre" si hace falta, ver {@link #disambiguateByTable}) → tabla dueña, igual forma que la vieja {@code SchemaInfo#triggerParentTable}. Aparte de {@link #categoryCache} porque un trigger necesita su tabla dueña, no solo el nombre. */
    private static final Map<String, Map<String, String>> triggerCache = new ConcurrentHashMap<>();

    /**
     * Tope real de cuántos esquemas se leen a la vez (2026-08-25, hallazgo
     * en vivo del usuario contra bases reales de cliente) — antes
     * {@link #loadInBackground} armaba un {@code Thread} nuevo por cada
     * base, sin límite: expandir/buscar sobre muchas bases grandes a la vez
     * (o el auto-expandido del buscador por nombre de esquema, ver
     * {@code ConnectionTreeBuilder}) podía disparar decenas de fetches JDBC
     * simultáneos, saturando red, los pools de {@link ConnectionPoolManager}
     * y el hilo de JavaFX de golpe. Un {@code ExecutorService} fijo pone un
     * techo real sin tocar el candado por-base que ya existía
     * ({@link #loading}) — esa dedup sigue evitando pedir el esquema de la
     * MISMA base dos veces; el pool nuevo solo limita cuántas bases
     * DISTINTAS se leen en paralelo. Hilos demonio explícitos (el
     * {@code ExecutorService} de fábrica no los marca así) para que no
     * bloqueen el cierre de la app con un fetch a medias.
     */
    private static final int SCHEMA_LOAD_PARALLELISM = 3;
    private static final ExecutorService schemaExecutor = Executors.newFixedThreadPool(SCHEMA_LOAD_PARALLELISM, runnable -> {
        Thread thread = new Thread(runnable, "faro-schema-fetch");
        thread.setDaemon(true);
        return thread;
    });

    private SchemaIntrospector() {
    }

    public static Optional<SchemaStructure> cached(String databaseId) {
        return Optional.ofNullable(cache.get(databaseId));
    }

    public static Optional<List<ColumnMetadata>> cachedColumns(String databaseId, String tableName) {
        Map<String, List<ColumnMetadata>> byTable = columnDetailsCache.get(databaseId);
        return byTable == null ? Optional.empty() : Optional.ofNullable(byTable.get(tableName));
    }

    /** Unión de nombres de columna de todas las tablas cuyas columnas ya se pidieron esta sesión (por cualquier motivo — SELECT/INSERT/UPDATE/DELETE/CREATE TABLE, o autocompletado repetido sobre esa tabla). Usada por {@code SqlAutocomplete}: reemplaza a la vieja {@code SchemaInfo#allColumnNames()}, que traía TODAS las columnas de la base de una — ver el javadoc de {@link SchemaStructure}. */
    public static List<String> cachedColumnNames(String databaseId) {
        Map<String, List<ColumnMetadata>> byTable = columnDetailsCache.get(databaseId);
        if (byTable == null) {
            return List.of();
        }
        return byTable.values().stream().flatMap(List::stream).map(ColumnMetadata::name).distinct().toList();
    }

    public static Optional<String> cachedDefinition(String databaseId, SchemaTreeNode.Kind kind, String objectName) {
        Map<String, String> byKey = definitionCache.get(databaseId);
        return byKey == null ? Optional.empty() : Optional.ofNullable(byKey.get(definitionCacheKey(kind, objectName)));
    }

    private static String definitionCacheKey(SchemaTreeNode.Kind kind, String objectName) {
        return kind.name() + ":" + objectName;
    }

    /**
     * Nombres cacheados de una categoría (FUNCTIONS/PROCEDURES/TRIGGERS/
     * TYPES) — vacío si esa categoría todavía no se expandió en el árbol
     * para esta base (ver {@link #loadCategoryInBackground}/{@code
     * CategoryTreeItem}). TRIGGERS lee de {@link #triggerCache} (mismas
     * llaves, calificadas "tabla.trigger" cuando hace falta — ver
     * {@link #disambiguateByTable}); las otras 3, de {@link #categoryCache}.
     */
    public static Optional<List<String>> cachedCategory(String databaseId, SchemaTreeNode.Kind kind) {
        if (kind == SchemaTreeNode.Kind.TRIGGERS) {
            Map<String, String> byName = triggerCache.get(databaseId);
            return byName == null ? Optional.empty() : Optional.of(new ArrayList<>(byName.keySet()));
        }
        Map<SchemaTreeNode.Kind, List<String>> byKind = categoryCache.get(databaseId);
        return byKind == null ? Optional.empty() : Optional.ofNullable(byKind.get(kind));
    }

    /** Tabla dueña de un trigger ya cacheado — necesaria para armar {@code SchemaTreeNode.Item#parentTable} al construir los hijos de la categoría TRIGGERS ({@code CategoryTreeItem}) y para {@link #fetchDefinition}. */
    public static Optional<String> cachedTriggerParentTable(String databaseId, String triggerName) {
        Map<String, String> byName = triggerCache.get(databaseId);
        return byName == null ? Optional.empty() : Optional.ofNullable(byName.get(triggerName));
    }

    /**
     * Arma el {@code Map<Kind, List<String>>} de las 6 categorías con lo que
     * HAYA en caché en este momento para esa base — estructura (Tablas/
     * Vistas) siempre que la base ya se haya expandido una vez, las otras 4
     * categorías solo si además ya se expandieron ellas mismas (lista vacía
     * si no). Reemplaza a la vieja {@code SchemaTreeNode.namesByKind
     * (SchemaInfo)}, que asumía las 6 categorías siempre juntas en un solo
     * objeto — con carga perezosa por categoría eso ya no es cierto, así que
     * armar este mapa necesita leer las cachés reales, no puede ser una
     * función pura de un solo record. Usado por {@code ConnectionTreeBuilder}
     * para la búsqueda por nombre de esquema — mismo límite de siempre
     * ("solo busca en lo que ya está en caché, nunca fuerza un fetch"),
     * ahora también implícito por categoría, no solo por base.
     */
    public static Map<SchemaTreeNode.Kind, List<String>> cachedNamesByKind(String databaseId) {
        Map<SchemaTreeNode.Kind, List<String>> byKind = new java.util.EnumMap<>(SchemaTreeNode.Kind.class);
        Optional<SchemaStructure> structure = cached(databaseId);
        byKind.put(SchemaTreeNode.Kind.TABLES, structure.map(SchemaStructure::tableNames).orElse(List.of()));
        byKind.put(SchemaTreeNode.Kind.VIEWS, structure.map(SchemaStructure::viewNames).orElse(List.of()));
        for (SchemaTreeNode.Kind kind : List.of(SchemaTreeNode.Kind.FUNCTIONS, SchemaTreeNode.Kind.PROCEDURES,
                SchemaTreeNode.Kind.TRIGGERS, SchemaTreeNode.Kind.TYPES)) {
            byKind.put(kind, cachedCategory(databaseId, kind).orElse(List.of()));
        }
        return byKind;
    }

    /**
     * Contador de generación por base (2026-08-26, cierra el límite conocido
     * documentado abajo desde Etapa C) — {@link #invalidate} lo sube; cada
     * fetch captura la generación vigente ANTES de arrancar (justo antes de
     * crear su {@code Task}, el punto más temprano posible) y la vuelve a
     * comparar justo antes de escribir en cualquier caché compartida. Si
     * cambió mientras tanto (alguien pidió "Recargar esquema" a mitad del
     * fetch), la escritura se salta — nunca repobla una caché recién
     * limpiada con datos de antes del recargo. El resultado en sí (lo que
     * ve quien pidió ESE fetch puntual, ej. un "Generar UPDATE") no se
     * descarta — solo se salta guardarlo en la caché compartida para que
     * otro consumidor futuro no lo reciba como si fuera dato fresco.
     */
    private static final Map<String, Long> generation = new ConcurrentHashMap<>();

    private static long currentGeneration(String databaseId) {
        return generation.getOrDefault(databaseId, 0L);
    }

    /** Package-private a propósito — {@code SchemaIntrospectorTest} la ejercita directo, sin necesitar JDBC ni JavaFX. */
    static long testGeneration(String databaseId) {
        return currentGeneration(databaseId);
    }

    /**
     * Descarta TODO lo cacheado de una base (estructura, columnas,
     * definiciones, categorías perezosas, triggers) — "Recargar esquema" del
     * menú contextual del árbol (2026-08-25, el usuario preguntó cómo
     * recargar y no había forma real). La próxima expansión vuelve a pedir
     * cada cosa de cero — estructura de inmediato, las 4 categorías
     * perezosas otra vez solo al volver a expandirlas (mismo comportamiento
     * perezoso que la primera vez).
     */
    public static void invalidate(String databaseId) {
        generation.merge(databaseId, 1L, Long::sum);
        cache.remove(databaseId);
        columnDetailsCache.remove(databaseId);
        definitionCache.remove(databaseId);
        categoryCache.remove(databaseId);
        triggerCache.remove(databaseId);
    }

    /**
     * Igual que {@link #loadInBackground(DatabaseEntry, CredentialStore,
     * ConnectionPoolManager, Consumer)} pero sin ningún consumidor real de la
     * falla — usada por {@code SqlAutocomplete}, a quien le basta con el
     * warning que este método ya deja en el log (dispara la carga "por si
     * acaso" sin nada que actualizar en la UI si falla).
     */
    public static void loadInBackground(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, Consumer<SchemaStructure> onLoaded) {
        loadInBackground(db, credentials, pool, onLoaded, error -> { });
    }

    /**
     * Dispara {@link #fetchStructure} en {@link #schemaExecutor} y deja el
     * resultado en {@link #cache} — {@code onLoaded}/{@code onError} corren
     * en el hilo de JavaFX (la propia máquina de estados de {@link Task} ya
     * invoca sus listeners vía {@code Platform.runLater}), seguro tocar
     * nodos de UI directo desde ahí. No hace nada si ya hay una carga en
     * curso para esa base (evita mandar la misma consulta dos veces).
     *
     * <p><b>{@code onError} (2026-08-25, hallazgo en vivo del usuario contra
     * bases reales de cliente):</b> antes, una falla real (credenciales
     * vencidas, red caída, permiso insuficiente) solo se registraba en el
     * log — la fila del árbol se quedaba en "Cargando esquema…" para
     * siempre, indistinguible en la UI de un fetch que de verdad seguía en
     * curso. {@code onError} le da a cada consumidor la falla real para que
     * pueda mostrar algo distinto de un bloqueo silencioso (ver
     * {@code DatabaseTreeItem#requestSchema}).
     */
    public static void loadInBackground(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool,
            Consumer<SchemaStructure> onLoaded, Consumer<Throwable> onError) {
        if (!loading.add(db.id())) {
            return;
        }
        long startGeneration = currentGeneration(db.id());
        Task<SchemaStructure> task = fetchStructure(db, credentials, pool);
        task.setOnSucceeded(e -> {
            SchemaStructure structure = task.getValue();
            loading.remove(db.id());
            if (currentGeneration(db.id()) != startGeneration) {
                log.debug("[{}] Estructura descartada — la base se recargó mientras el fetch seguía en curso.", db.alias());
                return;
            }
            cache.put(db.id(), structure);
            Platform.runLater(() -> onLoaded.accept(structure));
        });
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            log.warn("[{}] No se pudo cargar la estructura del esquema", db.alias(), error);
            loading.remove(db.id());
            onError.accept(error);
        });
        schemaExecutor.submit(task);
    }

    /**
     * Solo tablas/vistas (2 llamadas a {@link DatabaseMetaData#getTables}) —
     * SIN columnas, a diferencia de la vieja versión de este método (que se
     * llamaba {@code fetch} y traía las 6 categorías completas más todas las
     * columnas de todas las tablas de un jalón). Esto es lo que hace que el
     * árbol deje de quedarse pegado en "Cargando esquema…" contra bases
     * grandes — lo primero que se pide siempre es barato.
     */
    public static Task<SchemaStructure> fetchStructure(DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool) {
        return new Task<>() {
            @Override
            protected SchemaStructure call() throws SQLException {
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
                    log.warn("[{}] SchemaIntrospector abortado — sin credenciales guardadas.", db.alias());
                    throw new IllegalStateException("Sin usuario/contraseña guardados para " + db.alias());
                }
                String schema = db.engine().defaultSchema();
                log.debug("[{}] Leyendo estructura (tablas/vistas) de '{}'…", db.alias(), schema);

                List<String> tableNames;
                List<String> viewNames;
                try (Connection conn = pool.getConnection(db, creds.get())) {
                    DatabaseMetaData meta = conn.getMetaData();
                    String catalog = conn.getCatalog();
                    tableNames = fetchNames(meta.getTables(catalog, schema, "%", new String[] {"TABLE"}), "TABLE_NAME");
                    viewNames = fetchNames(meta.getTables(catalog, schema, "%", new String[] {"VIEW"}), "TABLE_NAME");
                }
                log.info("[{}] Estructura leída — {} tabla(s), {} vista(s).", db.alias(), tableNames.size(), viewNames.size());
                return new SchemaStructure(tableNames, viewNames);
            }
        };
    }

    /**
     * Dispara la carga perezosa de UNA categoría (Funciones/Procedimientos/
     * Triggers/Tipos) de UNA base en {@link #schemaExecutor} — usada por
     * {@code CategoryTreeItem} al expandirse la primera vez, mismo patrón
     * exacto de {@code onLoaded}/{@code onError} que {@link #loadInBackground}
     * (ver su javadoc para el porqué de {@code onError}). Candado por
     * base+categoría ({@link #categoryLoading}, llave {@code "dbId:KIND"}) en
     * vez de solo por base — a diferencia de la estructura, cada categoría se
     * pide y cachea por separado, así que el dedup también tiene que serlo.
     */
    public static void loadCategoryInBackground(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, SchemaTreeNode.Kind kind,
            Consumer<List<String>> onLoaded, Consumer<Throwable> onError) {
        String loadKey = db.id() + ":" + kind;
        if (!categoryLoading.add(loadKey)) {
            return;
        }
        long startGeneration = currentGeneration(db.id());
        Task<List<String>> task = kind == SchemaTreeNode.Kind.TRIGGERS
                ? fetchTriggerCategory(db, credentials, pool, startGeneration)
                : fetchCategory(db, credentials, pool, kind, startGeneration);
        task.setOnSucceeded(e -> {
            List<String> names = task.getValue();
            categoryLoading.remove(loadKey);
            if (currentGeneration(db.id()) != startGeneration) {
                log.debug("[{}] {} descartado — la base se recargó mientras el fetch seguía en curso.", db.alias(), kind.label());
                return;
            }
            Platform.runLater(() -> onLoaded.accept(names));
        });
        task.setOnFailed(e -> {
            Throwable error = task.getException();
            log.warn("[{}] No se pudo cargar '{}'", db.alias(), kind.label(), error);
            categoryLoading.remove(loadKey);
            onError.accept(error);
        });
        schemaExecutor.submit(task);
    }

    /**
     * Funciones/Procedimientos/Tipos — cachea el resultado en
     * {@link #categoryCache} al terminar (solo si {@code startGeneration}
     * sigue vigente, ver {@link #generation}). TRIGGERS no pasa por acá, ver
     * {@link #fetchTriggerCategory} (necesita tabla dueña, no solo el
     * nombre).
     *
     * <p><b>PostgreSQL, FUNCTIONS/PROCEDURES — ya no usa
     * {@code DatabaseMetaData#getFunctions}/{@code #getProcedures}</b> (el
     * camino JDBC estándar que sí seguía usando esta rama hasta el
     * 2026-08-26): pgjdbc distingue bien función de procedimiento (ver el
     * javadoc de la clase), pero no expone la FIRMA de cada rutina, y sin
     * ella no hay forma de desambiguar sobrecarga real (PostgreSQL permite
     * dos funciones con el mismo nombre, distintos tipos de parámetro — ver
     * {@link #fetchPostgresRoutineNames}). SQL Server no tiene este riesgo
     * (no permite sobrecargar funciones/procedimientos por firma), así que
     * {@link #fetchSqlServerRoutines} se queda igual.
     */
    private static Task<List<String>> fetchCategory(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, SchemaTreeNode.Kind kind, long startGeneration) {
        return new Task<>() {
            @Override
            protected List<String> call() throws SQLException {
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
                    throw new IllegalStateException("Sin usuario/contraseña guardados para " + db.alias());
                }
                String schema = db.engine().defaultSchema();
                List<String> names;
                try (Connection conn = pool.getConnection(db, creds.get())) {
                    names = switch (kind) {
                        case FUNCTIONS -> db.engine() == DbEngine.SQL_SERVER
                                // Ver el javadoc de la clase — getFunctions() de mssql-jdbc no
                                // distingue función de procedimiento, verificado contra un
                                // contenedor real con ambos objetos creados de prueba.
                                ? fetchSqlServerRoutines(conn, schema, "'FN','IF','TF'")
                                : fetchPostgresRoutineNames(conn, schema, 'f');
                        case PROCEDURES -> db.engine() == DbEngine.SQL_SERVER
                                ? fetchSqlServerRoutines(conn, schema, "'P'")
                                : fetchPostgresRoutineNames(conn, schema, 'p');
                        case TYPES -> fetchTypes(conn, db.engine(), schema);
                        case TABLES, VIEWS, TRIGGERS ->
                            throw new IllegalArgumentException(kind + " no pasa por fetchCategory.");
                    };
                }
                if (currentGeneration(db.id()) == startGeneration) {
                    // ConcurrentHashMap, no EnumMap (hallazgo real de revisión de código,
                    // 2026-08-25) — con schemaExecutor de 3 hilos, dos categorías de la MISMA
                    // base (ej. Funciones y Procedimientos) pueden estar cargándose de verdad en
                    // paralelo, en 2 hilos del pool distintos; ambas harían .put() sobre el MISMO
                    // Map interno una vez que computeIfAbsent lo entrega — EnumMap no es
                    // thread-safe para escrituras concurrentes (a diferencia de la propiedad
                    // atómica de computeIfAbsent en sí, que solo cubre la creación del mapa, no
                    // los put() posteriores sobre él).
                    categoryCache.computeIfAbsent(db.id(), k -> new ConcurrentHashMap<>()).put(kind, names);
                } else {
                    log.debug("[{}] {} no se cachea — la base se recargó mientras el fetch estaba en curso.", db.alias(), kind.label());
                }
                log.info("[{}] {} leído(s) — {} nombre(s).", db.alias(), kind.label(), names.size());
                return names;
            }
        };
    }

    /** Triggers — cachea el resultado (nombre→tabla dueña) en {@link #triggerCache} al terminar (solo si {@code startGeneration} sigue vigente), devuelve solo los nombres (mismo tipo de retorno que {@link #fetchCategory}, para que {@link #loadCategoryInBackground} pueda tratar las 4 categorías igual desde el punto de vista de {@code CategoryTreeItem}). */
    private static Task<List<String>> fetchTriggerCategory(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, long startGeneration) {
        return new Task<>() {
            @Override
            protected List<String> call() throws SQLException {
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
                    throw new IllegalStateException("Sin usuario/contraseña guardados para " + db.alias());
                }
                String schema = db.engine().defaultSchema();
                LinkedHashMap<String, String> triggerParentTable;
                try (Connection conn = pool.getConnection(db, creds.get())) {
                    triggerParentTable = fetchTriggers(conn, db.engine(), schema);
                }
                if (currentGeneration(db.id()) == startGeneration) {
                    triggerCache.put(db.id(), triggerParentTable);
                } else {
                    log.debug("[{}] Triggers no se cachean — la base se recargó mientras el fetch estaba en curso.", db.alias());
                }
                log.info("[{}] Triggers leídos — {} trigger(s).", db.alias(), triggerParentTable.size());
                return new ArrayList<>(triggerParentTable.keySet());
            }
        };
    }

    /**
     * Columnas reales (tipo/nulabilidad/PK) de una tabla — a diferencia de
     * {@link #fetchStructure}, no se pide al expandir la base, solo bajo
     * demanda desde "Generar SELECT/INSERT/UPDATE/DELETE/script CREATE"
     * (tabla — ver {@code MainController#generateFromColumnDetails}), que
     * sin esto no pueden armar las columnas/{@code SET}/{@code WHERE}/
     * definición de columna reales. Cachea el resultado en
     * {@link #columnDetailsCache} al terminar.
     */
    public static Task<List<ColumnMetadata>> fetchColumns(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, String tableName) {
        long startGeneration = currentGeneration(db.id());
        return new Task<>() {
            @Override
            protected List<ColumnMetadata> call() throws SQLException {
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
                    throw new IllegalStateException("Sin usuario/contraseña guardados para " + db.alias());
                }
                String schema = db.engine().defaultSchema();
                String query = switch (db.engine()) {
                    case POSTGRES -> POSTGRES_COLUMNS_QUERY;
                    case SQL_SERVER -> SQL_SERVER_COLUMNS_QUERY;
                };
                List<ColumnMetadata> columns = new ArrayList<>();
                try (Connection conn = pool.getConnection(db, creds.get());
                        PreparedStatement statement = conn.prepareStatement(query)) {
                    statement.setString(1, schema);
                    statement.setString(2, tableName);
                    statement.setString(3, schema);
                    statement.setString(4, tableName);
                    try (ResultSet rs = statement.executeQuery()) {
                        while (rs.next()) {
                            columns.add(new ColumnMetadata(
                                    rs.getString("column_name"),
                                    rs.getString("data_type"),
                                    "YES".equalsIgnoreCase(rs.getString("is_nullable")),
                                    rs.getBoolean("is_primary_key"),
                                    rs.getObject("char_len", Integer.class),
                                    rs.getObject("num_precision", Integer.class),
                                    rs.getObject("num_scale", Integer.class)));
                        }
                    }
                }
                if (currentGeneration(db.id()) == startGeneration) {
                    columnDetailsCache.computeIfAbsent(db.id(), k -> new ConcurrentHashMap<>()).put(tableName, columns);
                } else {
                    log.debug("[{}] Columnas de '{}' no se cachean — la base se recargó mientras el fetch estaba en curso.", db.alias(), tableName);
                }
                log.info("[{}] Columnas con tipo/PK leídas para '{}' — {} columna(s).", db.alias(), tableName, columns.size());
                return columns;
            }
        };
    }

    private static final String POSTGRES_COLUMNS_QUERY = """
            SELECT c.column_name AS column_name, c.data_type AS data_type, c.is_nullable AS is_nullable,
                   c.character_maximum_length AS char_len, c.numeric_precision AS num_precision, c.numeric_scale AS num_scale,
                   (pk.column_name IS NOT NULL) AS is_primary_key
            FROM information_schema.columns c
            LEFT JOIN (
              SELECT kcu.column_name
              FROM information_schema.table_constraints tc
              JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema
              WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_schema = ? AND tc.table_name = ?
            ) pk ON pk.column_name = c.column_name
            WHERE c.table_schema = ? AND c.table_name = ?
            ORDER BY c.ordinal_position
            """;

    private static final String SQL_SERVER_COLUMNS_QUERY = """
            SELECT c.COLUMN_NAME AS column_name, c.DATA_TYPE AS data_type, c.IS_NULLABLE AS is_nullable,
                   c.CHARACTER_MAXIMUM_LENGTH AS char_len, c.NUMERIC_PRECISION AS num_precision, c.NUMERIC_SCALE AS num_scale,
                   CASE WHEN pk.COLUMN_NAME IS NOT NULL THEN 1 ELSE 0 END AS is_primary_key
            FROM INFORMATION_SCHEMA.COLUMNS c
            LEFT JOIN (
              SELECT kcu.COLUMN_NAME
              FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
              JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA
              WHERE tc.CONSTRAINT_TYPE = 'PRIMARY KEY' AND tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ?
            ) pk ON pk.COLUMN_NAME = c.COLUMN_NAME
            WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ?
            ORDER BY c.ORDINAL_POSITION
            """;

    /**
     * Script CREATE real de una vista/función/procedimiento/trigger — a
     * diferencia de una tabla (sin equivalente simple en ningún motor, ver
     * {@code SqlScriptGenerator#generateCreateTableScript}), ambos motores
     * sí exponen el DDL real de una rutina/vista/trigger de un solo viaje:
     * {@code pg_get_viewdef}/{@code pg_get_functiondef}/
     * {@code pg_get_triggerdef} en PostgreSQL, {@code OBJECT_DEFINITION()}
     * de forma uniforme en SQL Server. Cachea el resultado en
     * {@link #definitionCache} al terminar.
     */
    public static Task<String> fetchDefinition(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool,
            SchemaTreeNode.Kind kind, String objectName, String parentTable) {
        long startGeneration = currentGeneration(db.id());
        return new Task<>() {
            @Override
            protected String call() throws SQLException {
                Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                if (creds.isEmpty()) {
                    throw new IllegalStateException("Sin usuario/contraseña guardados para " + db.alias());
                }
                String schema = db.engine().defaultSchema();
                String raw;
                try (Connection conn = pool.getConnection(db, creds.get())) {
                    // TYPES nunca pasa por OBJECT_DEFINITION()/pg_get_*def — ninguno de los
                    // 2 motores trata un tipo como un objeto "programable" con texto fuente
                    // guardado; hay que reconstruir el CREATE TYPE a mano por catálogo (ver
                    // fetchTypeDefinition).
                    raw = kind == SchemaTreeNode.Kind.TYPES
                            ? fetchTypeDefinition(conn, db.engine(), schema, objectName)
                            : db.engine() == DbEngine.SQL_SERVER
                                    ? fetchSqlServerDefinition(conn, schema, objectName)
                                    : fetchPostgresDefinition(conn, schema, objectName, kind, parentTable);
                }
                // Bug real de revisión de código (2026-08-25): sin este chequeo, un objeto
                // que ya no existe (renombrado/borrado entre listar el árbol y pedir el
                // script) o — específico de SQL Server — OBJECT_DEFINITION() devolviendo NULL
                // porque el login no tiene permiso VIEW DEFINITION (la app soporta
                // explícitamente credenciales de solo lectura, un caso realista) terminaban en
                // un script vacío/nulo que se guardaba como si hubiera sido un éxito, sin
                // ningún error visible para el usuario.
                if (raw == null || raw.isBlank()) {
                    throw new SQLException("No se encontró la definición de '" + objectName
                            + "' — puede que ya no exista, o que la conexión no tenga permiso para verla.");
                }
                // pg_get_viewdef solo trae el SELECT, sin el encabezado CREATE VIEW — los
                // demás casos (funciones/procedimientos/triggers en Postgres, todo en SQL
                // Server vía OBJECT_DEFINITION) ya regresan un script completo listo para
                // correr.
                String script = (db.engine() == DbEngine.POSTGRES && kind == SchemaTreeNode.Kind.VIEWS)
                        ? "CREATE OR REPLACE VIEW " + schema + "." + objectName + " AS\n" + raw
                        : raw;
                if (currentGeneration(db.id()) == startGeneration) {
                    definitionCache.computeIfAbsent(db.id(), k -> new ConcurrentHashMap<>())
                            .put(definitionCacheKey(kind, objectName), script);
                } else {
                    log.debug("[{}] Definición de '{}' no se cachea — la base se recargó mientras el fetch estaba en curso.", db.alias(), objectName);
                }
                log.info("[{}] Definición CREATE leída para '{}' ({}).", db.alias(), objectName, kind);
                return script;
            }
        };
    }

    private static String fetchSqlServerDefinition(Connection conn, String schema, String objectName) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement("SELECT OBJECT_DEFINITION(OBJECT_ID(?))")) {
            statement.setString(1, schema + "." + objectName);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    /**
     * {@code parentTable} solo se usa (y solo se necesita) para
     * {@code TRIGGERS} — un nombre de trigger es único por tabla en
     * PostgreSQL, no global.
     *
     * <p><b>FUNCTIONS/PROCEDURES — sobrecarga real desambiguada (cerrado
     * 2026-08-26, mismo tamaño de cambio que ya se hizo para triggers con
     * {@code parentTable}):</b> PostgreSQL permite dos funciones con el
     * mismo nombre y distintos tipos de parámetro — {@code objectName}
     * puede venir calificado como {@code "nombre(firma)"} cuando
     * {@link #fetchPostgresRoutineNames} detectó una colisión real (ver
     * {@link #disambiguateBySignature}). {@link #routineSignature} extrae
     * esa firma si está presente y la suma como filtro exacto vía
     * {@code pg_get_function_identity_arguments(p.oid) = ?} — sin firma
     * (nombre sin colisión), se filtra solo por nombre, igual que antes.
     */
    private static String fetchPostgresDefinition(
            Connection conn, String schema, String objectName, SchemaTreeNode.Kind kind, String parentTable) throws SQLException {
        Optional<String> signature = (kind == SchemaTreeNode.Kind.FUNCTIONS || kind == SchemaTreeNode.Kind.PROCEDURES)
                ? routineSignature(objectName)
                : Optional.empty();
        String query = switch (kind) {
            case VIEWS -> "SELECT pg_get_viewdef(('\"' || ? || '\".\"' || ? || '\"')::regclass, true)";
            case FUNCTIONS, PROCEDURES -> signature.isPresent()
                    ? "SELECT pg_get_functiondef(p.oid) FROM pg_proc p "
                            + "JOIN pg_namespace n ON n.oid = p.pronamespace "
                            + "WHERE n.nspname = ? AND p.proname = ? AND pg_get_function_identity_arguments(p.oid) = ?"
                    : "SELECT pg_get_functiondef(p.oid) FROM pg_proc p "
                            + "JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = ? AND p.proname = ?";
            case TRIGGERS -> "SELECT pg_get_triggerdef(t.oid, true) FROM pg_trigger t "
                    + "JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = ? AND c.relname = ? AND t.tgname = ? AND NOT t.tgisinternal";
            case TABLES -> throw new IllegalArgumentException("Las tablas no usan fetchDefinition — ver SqlScriptGenerator.generateCreateTableScript.");
            case TYPES -> throw new IllegalArgumentException("Los tipos no pasan por acá — ver fetchTypeDefinition.");
        };
        try (PreparedStatement statement = conn.prepareStatement(query)) {
            if (kind == SchemaTreeNode.Kind.TRIGGERS) {
                statement.setString(1, schema);
                statement.setString(2, parentTable);
                // objectName puede venir calificado "tabla.trigger" (ver disambiguateByTable) —
                // pg_trigger.tgname espera solo el nombre real, sin la tabla.
                statement.setString(3, bareTriggerName(objectName, parentTable));
            } else if (signature.isPresent()) {
                statement.setString(1, schema);
                statement.setString(2, bareRoutineName(objectName));
                statement.setString(3, signature.get());
            } else {
                statement.setString(1, schema);
                statement.setString(2, objectName);
            }
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getString(1) : "";
            }
        }
    }

    private static String fetchTypeDefinition(Connection conn, DbEngine engine, String schema, String typeName) throws SQLException {
        return engine == DbEngine.SQL_SERVER
                ? fetchSqlServerTypeDefinition(conn, schema, typeName)
                : fetchPostgresTypeDefinition(conn, schema, typeName);
    }

    /**
     * A diferencia de vistas/funciones/procedimientos/triggers, PostgreSQL
     * no tiene un {@code pg_get_typedef} — hay que mirar {@code pg_type
     * .typtype} primero y reconstruir por rama: enum ({@code 'e'}, todos
     * sus {@code pg_enum.enumlabel} en orden), dominio ({@code 'd'}, tipo
     * base + NOT NULL + DEFAULT — sin reconstruir CHECK, mismo criterio de
     * "mejor esfuerzo" que {@code SqlScriptGenerator#
     * generateCreateTableScript}), o compuesto ({@code 'c'}, columnas vía
     * {@code pg_attribute}, igual que una tabla pero sin PK).
     */
    private static String fetchPostgresTypeDefinition(Connection conn, String schema, String typeName) throws SQLException {
        String qualifiedName = schema + "." + typeName;
        String typtype;
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT t.typtype FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = ? AND t.typname = ?")) {
            statement.setString(1, schema);
            statement.setString(2, typeName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                typtype = rs.getString(1);
            }
        }
        // "c" explícito, no un default abierto — fetchTypes() ya solo deja pasar
        // e/d/c a la categoría "Tipos" (ver su javadoc), pero si typtype cambiara
        // entre que se listó el tipo y que se le pidió el script (o si algún día
        // se afloja ese filtro), tratar cualquier otra cosa como compuesto por
        // default consultaría pg_attribute con un typrelid inválido y regresaría
        // un "CREATE TYPE x AS ( );" sin sentido en vez de un error claro
        // (hallazgo real de revisión de código, 2026-08-25).
        return switch (typtype) {
            case "e" -> fetchPostgresEnumDefinition(conn, schema, typeName, qualifiedName);
            case "d" -> fetchPostgresDomainDefinition(conn, schema, typeName, qualifiedName);
            case "c" -> fetchPostgresCompositeDefinition(conn, schema, typeName, qualifiedName);
            default -> "-- No se pudo generar: '" + typeName + "' no es un enum/dominio/tipo compuesto reconocido (typtype='" + typtype + "').";
        };
    }

    private static String fetchPostgresEnumDefinition(Connection conn, String schema, String typeName, String qualifiedName) throws SQLException {
        List<String> labels = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT e.enumlabel FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                        + "JOIN pg_enum e ON e.enumtypid = t.oid WHERE n.nspname = ? AND t.typname = ? ORDER BY e.enumsortorder")) {
            statement.setString(1, schema);
            statement.setString(2, typeName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    labels.add(rs.getString(1));
                }
            }
        }
        String values = labels.stream().map(l -> "'" + l.replace("'", "''") + "'").collect(java.util.stream.Collectors.joining(", "));
        return "CREATE TYPE " + qualifiedName + " AS ENUM (" + values + ");";
    }

    private static String fetchPostgresDomainDefinition(Connection conn, String schema, String typeName, String qualifiedName) throws SQLException {
        // format_type ya arma el tipo base con longitud/precisión real (ej.
        // "character varying(50)") — mismo helper que usa Postgres internamente para \d.
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT format_type(t.typbasetype, t.typtypmod) AS base_type, t.typnotnull, t.typdefault "
                        + "FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace WHERE n.nspname = ? AND t.typname = ?")) {
            statement.setString(1, schema);
            statement.setString(2, typeName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                StringBuilder sql = new StringBuilder("CREATE DOMAIN ").append(qualifiedName).append(" AS ").append(rs.getString("base_type"));
                if (rs.getBoolean("typnotnull")) {
                    sql.append(" NOT NULL");
                }
                String defaultExpr = rs.getString("typdefault");
                if (defaultExpr != null) {
                    sql.append(" DEFAULT ").append(defaultExpr);
                }
                // Mejor esfuerzo — no reconstruye CHECK constraints del dominio (viven en
                // pg_constraint, pero requieren reconstruir la expresión de condición).
                return sql.append(';').toString();
            }
        }
    }

    private static String fetchPostgresCompositeDefinition(Connection conn, String schema, String typeName, String qualifiedName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT a.attname, format_type(a.atttypid, a.atttypmod) AS col_type "
                        + "FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                        + "JOIN pg_attribute a ON a.attrelid = t.typrelid "
                        + "WHERE n.nspname = ? AND t.typname = ? AND a.attnum > 0 AND NOT a.attisdropped ORDER BY a.attnum")) {
            statement.setString(1, schema);
            statement.setString(2, typeName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(SqlScriptGenerator.columnDefinitionLine(rs.getString("attname"), rs.getString("col_type")));
                }
            }
        }
        return "CREATE TYPE " + qualifiedName + " AS (\n" + String.join(",\n", columns) + "\n);";
    }

    /**
     * SQL Server distingue tipo alias ({@code CREATE TYPE x FROM int}, la
     * mayoría) de tipo de tabla ({@code CREATE TYPE x AS TABLE (...)}, para
     * parámetros con valores de tabla) vía {@code sys.types.is_table_type}
     * — cada uno necesita una reconstrucción distinta.
     */
    private static String fetchSqlServerTypeDefinition(Connection conn, String schema, String typeName) throws SQLException {
        String qualifiedName = schema + "." + typeName;
        boolean isTableType;
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT t.is_table_type FROM sys.types t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? AND t.name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, typeName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                isTableType = rs.getBoolean(1);
            }
        }
        return isTableType
                ? fetchSqlServerTableTypeDefinition(conn, schema, typeName, qualifiedName)
                : fetchSqlServerAliasTypeDefinition(conn, schema, typeName, qualifiedName);
    }

    private static String fetchSqlServerAliasTypeDefinition(Connection conn, String schema, String typeName, String qualifiedName) throws SQLException {
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT TYPE_NAME(t.system_type_id) AS base_type, t.max_length, t.precision, t.scale, t.is_nullable "
                        + "FROM sys.types t JOIN sys.schemas s ON t.schema_id = s.schema_id WHERE s.name = ? AND t.name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, typeName);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    return "";
                }
                String typeWithLength = sqlServerTypeWithLength(
                        rs.getString("base_type"), rs.getInt("max_length"), rs.getInt("precision"), rs.getInt("scale"));
                boolean nullable = rs.getBoolean("is_nullable");
                return "CREATE TYPE " + qualifiedName + " FROM " + typeWithLength + (nullable ? " NULL" : " NOT NULL") + ";";
            }
        }
    }

    /** Mejor esfuerzo — no reconstruye PRIMARY KEY/índices del tipo de tabla, mismo criterio que {@code SqlScriptGenerator#generateCreateTableScript}. */
    private static String fetchSqlServerTableTypeDefinition(Connection conn, String schema, String typeName, String qualifiedName) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT c.name, TYPE_NAME(c.user_type_id) AS col_type, c.max_length, c.precision, c.scale, c.is_nullable "
                        + "FROM sys.table_types tt JOIN sys.columns c ON c.object_id = tt.type_table_object_id "
                        + "JOIN sys.schemas s ON tt.schema_id = s.schema_id WHERE s.name = ? AND tt.name = ? ORDER BY c.column_id")) {
            statement.setString(1, schema);
            statement.setString(2, typeName);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    String colType = sqlServerTypeWithLength(
                            rs.getString("col_type"), rs.getInt("max_length"), rs.getInt("precision"), rs.getInt("scale"));
                    boolean nullable = rs.getBoolean("is_nullable");
                    columns.add(SqlScriptGenerator.columnDefinitionLine(rs.getString("name"), colType, !nullable));
                }
            }
        }
        return "CREATE TYPE " + qualifiedName + " AS TABLE (\n" + String.join(",\n", columns) + "\n);";
    }

    /**
     * {@code sys.types}/{@code sys.columns} no arman la longitud legible
     * (ej. {@code nvarchar(50)}) sola como sí hace {@code format_type} en
     * Postgres — {@code max_length} viene en BYTES para tipos {@code n*}
     * (2 bytes/carácter), y {@code -1} significa {@code MAX} en cualquier
     * tipo de longitud variable.
     *
     * <p>Package-private (no {@code private}) a propósito —
     * {@code SchemaIntrospectorTest} la ejercita directo, sin necesitar JDBC.
     */
    static String sqlServerTypeWithLength(String baseType, int maxLength, int precision, int scale) {
        String lower = baseType.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("decimal") || lower.equals("numeric")) {
            return baseType + "(" + precision + "," + scale + ")";
        }
        if (lower.equals("nchar") || lower.equals("nvarchar")) {
            return baseType + "(" + (maxLength == -1 ? "MAX" : String.valueOf(maxLength / 2)) + ")";
        }
        if (lower.equals("char") || lower.equals("varchar") || lower.equals("binary") || lower.equals("varbinary")) {
            return baseType + "(" + (maxLength == -1 ? "MAX" : String.valueOf(maxLength)) + ")";
        }
        return baseType;
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

    /**
     * {@code typesInClause} ya viene formateado listo para pegar en el
     * {@code IN (...)}, ej. {@code "'P'"} — siempre valores fijos nuestros,
     * nunca texto de usuario, no necesita parámetro. {@code schema} sí se
     * parametriza con {@code ?} (encontrado por revisión de código,
     * 2026-08-25: era el único sitio de este archivo que seguía armando
     * SQL por concatenación después de que {@code fetchTriggers}/
     * {@code fetchTypes}/etc. ya se hubieran pasado a {@code PreparedStatement}
     * en esta misma sesión — sin riesgo real hoy, {@code schema} siempre es
     * "public"/"dbo", pero inconsistente dejarlo así).
     */
    private static List<String> fetchSqlServerRoutines(Connection conn, String schema, String typesInClause) throws SQLException {
        String query = "SELECT o.name FROM sys.objects o JOIN sys.schemas s ON o.schema_id = s.schema_id "
                + "WHERE o.type IN (" + typesInClause + ") AND s.name = ?";
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(query)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
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
     *
     * <p>Regresa nombre (o "tabla.nombre" si hace falta, ver
     * {@link #disambiguateByTable}) → tabla dueña — hace falta para
     * "Generar script CREATE" de un trigger en PostgreSQL, donde
     * {@code pg_get_triggerdef} necesita la tabla dueña porque el nombre
     * del trigger no es único a nivel de esquema, solo por tabla.
     *
     * <p><b>Bug real corregido por revisión de código (2026-08-25):</b> la
     * primera versión de este método armaba un {@code LinkedHashMap} con el
     * nombre de trigger crudo como llave — si dos tablas del mismo esquema
     * tenían un trigger con el mismo nombre (patrón real: un trigger de
     * auditoría/"updated_at" copiado a varias tablas), el segundo
     * {@code put()} pisaba al primero en silencio: el árbol mostraba un solo
     * trigger en vez de dos, y "Generar script CREATE" podía traer la
     * definición de la tabla equivocada sin ningún aviso. {@code SQL Server}
     * no tiene este riesgo (ahí un nombre de trigger SÍ es único por
     * esquema, restricción real del motor), así que solo la rama PostgreSQL
     * pasa por {@link #disambiguateByTable}.
     */
    private static LinkedHashMap<String, String> fetchTriggers(Connection conn, DbEngine engine, String schema) throws SQLException {
        String query = switch (engine) {
            case POSTGRES -> "SELECT DISTINCT trigger_name, event_object_table FROM information_schema.triggers WHERE trigger_schema = ?";
            case SQL_SERVER -> "SELECT t.name, tbl.name FROM sys.triggers t "
                    + "JOIN sys.tables tbl ON t.parent_id = tbl.object_id "
                    + "JOIN sys.schemas s ON tbl.schema_id = s.schema_id "
                    + "WHERE s.name = ? AND t.parent_class = 1";
        };
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(query)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        if (engine == DbEngine.SQL_SERVER) {
            LinkedHashMap<String, String> byName = new LinkedHashMap<>();
            for (String[] row : rows) {
                byName.put(row[0], row[1]);
            }
            return byName;
        }
        return disambiguateByTable(rows);
    }

    /**
     * Cuando un nombre de trigger se repite entre tablas del mismo esquema
     * (posible en PostgreSQL, no en SQL Server — ver {@link #fetchTriggers}),
     * la llave que identifica esa fila en el árbol se califica con la tabla
     * ({@code "tabla.trigger"}) para que ambas queden visibles y cada una
     * resuelva a su propia definición real; cuando el nombre NO se repite,
     * se deja tal cual, sin cambio de comportamiento para el caso común. Ver
     * {@link #bareTriggerName} para el lado inverso (recuperar el nombre
     * real que espera {@code pg_trigger.tgname} a partir de esta llave).
     */
    static LinkedHashMap<String, String> disambiguateByTable(List<String[]> rows) {
        Map<String, Long> occurrences = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(row -> row[0], java.util.stream.Collectors.counting()));
        LinkedHashMap<String, String> byKey = new LinkedHashMap<>();
        for (String[] row : rows) {
            String name = row[0];
            String table = row[1];
            String key = occurrences.get(name) > 1 ? table + "." + name : name;
            byKey.put(key, table);
        }
        return byKey;
    }

    /**
     * Deshace {@link #disambiguateByTable}: si {@code objectName} viene
     * calificado como {@code "tabla.trigger"} (el caso de colisión), regresa
     * solo la parte del nombre real — {@code pg_trigger.tgname} no incluye
     * la tabla. Confiable porque el prefijo se arma y se deshace en el mismo
     * archivo, con el mismo separador, nunca a partir de texto de usuario.
     */
    static String bareTriggerName(String objectName, String parentTable) {
        String prefix = parentTable + ".";
        return objectName.startsWith(prefix) ? objectName.substring(prefix.length()) : objectName;
    }

    /**
     * Nombres de Funciones/Procedimientos en PostgreSQL, con su firma real
     * (2026-08-26, cierra el límite conocido de sobrecarga sin desambiguar
     * — ver el javadoc de {@link #fetchCategory}). {@code prokind}
     * distingue función ({@code 'f'}) de procedimiento ({@code 'p'}) de
     * forma nativa y confiable en el propio catálogo de Postgres — no hace
     * falta el rodeo por {@code sys.objects} que sí necesita SQL Server
     * (ver {@link #fetchSqlServerRoutines}).
     */
    private static List<String> fetchPostgresRoutineNames(Connection conn, String schema, char prokind) throws SQLException {
        List<String[]> rows = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(
                "SELECT p.proname, pg_get_function_identity_arguments(p.oid) FROM pg_proc p "
                        + "JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = ? AND p.prokind = ? ORDER BY p.proname")) {
            statement.setString(1, schema);
            statement.setString(2, String.valueOf(prokind));
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[] {rs.getString(1), rs.getString(2)});
                }
            }
        }
        return new ArrayList<>(disambiguateBySignature(rows).keySet());
    }

    /**
     * Cuando un nombre de función/procedimiento se repite en el mismo
     * esquema (sobrecarga real de PostgreSQL — mismo nombre, distintos
     * tipos de parámetro), la llave que identifica esa fila en el árbol se
     * califica con la firma ({@code "nombre(firma)"}, ej.
     * {@code "calcular(integer, text)"}) para que las dos queden visibles y
     * cada una resuelva a su propia definición real; cuando el nombre NO se
     * repite, se deja tal cual, sin cambio de comportamiento para el caso
     * común (el 99% de las bases de aplicación reales). Mismo criterio
     * exacto que {@link #disambiguateByTable} para triggers — formato de
     * calificación distinto ({@code "nombre(firma)"}, no
     * {@code "tabla.nombre"}) porque acá lo que desambigua es la firma, no
     * una tabla dueña. Ver {@link #bareRoutineName}/{@link #routineSignature}
     * para el lado inverso.
     */
    static LinkedHashMap<String, String> disambiguateBySignature(List<String[]> rows) {
        Map<String, Long> occurrences = rows.stream()
                .collect(java.util.stream.Collectors.groupingBy(row -> row[0], java.util.stream.Collectors.counting()));
        LinkedHashMap<String, String> byKey = new LinkedHashMap<>();
        for (String[] row : rows) {
            String name = row[0];
            String signature = row[1];
            String key = occurrences.get(name) > 1 ? name + "(" + signature + ")" : name;
            byKey.put(key, signature);
        }
        return byKey;
    }

    /**
     * Deshace {@link #disambiguateBySignature}: si {@code objectName} viene
     * calificado como {@code "nombre(firma)"} (el caso de colisión),
     * regresa solo el nombre real — {@code pg_proc.proname} no incluye la
     * firma. Confiable porque el sufijo se arma y se deshace en el mismo
     * archivo, nunca a partir de texto de usuario.
     */
    static String bareRoutineName(String objectName) {
        int parenIndex = objectName.indexOf('(');
        return parenIndex == -1 ? objectName : objectName.substring(0, parenIndex);
    }

    /**
     * Extrae la firma de un {@code objectName} calificado como
     * {@code "nombre(firma)"} — vacío si no está calificado (nombre sin
     * colisión, ver {@link #disambiguateBySignature}). Usada por
     * {@link #fetchPostgresDefinition} para filtrar por
     * {@code pg_get_function_identity_arguments(p.oid) = ?} y así traer la
     * definición de la sobrecarga correcta, no la primera que
     * {@code pg_proc} devuelva.
     */
    static Optional<String> routineSignature(String objectName) {
        int parenIndex = objectName.indexOf('(');
        if (parenIndex == -1 || !objectName.endsWith(")")) {
            return Optional.empty();
        }
        return Optional.of(objectName.substring(parenIndex + 1, objectName.length() - 1));
    }

    /**
     * Tipos personalizados (2026-08-25, pedido explícito del usuario tras
     * ver el árbol de esquema: "se me ocurren types, jobs, etc." — types sí
     * son objetos de esquema reales, jobs no, ver la entrada de esa fecha
     * en {@code CONTEXTO_SESIONES.md}). {@code DatabaseMetaData} no tiene
     * ningún método para esto tampoco.
     *
     * <p>PostgreSQL: {@code pg_type.typtype} distingue enum ({@code 'e'}),
     * dominio ({@code 'd'}) y compuesto ({@code 'c'}) — pero un tipo
     * compuesto {@code 'c'} TAMBIÉN es como Postgres representa la fila de
     * cada tabla/vista internamente, así que sin el filtro extra
     * ({@code pg_class.relkind = 'c'}, el marcador real de "esto es un
     * {@code CREATE TYPE ... AS (...)} de verdad, no una tabla") el árbol
     * mostraría CADA tabla/vista otra vez bajo "Tipos", duplicado.
     * SQL Server: {@code sys.types.is_user_defined = 1} cubre tanto tipos
     * alias ({@code CREATE TYPE x FROM int}) como tipos de tabla
     * ({@code CREATE TYPE x AS TABLE (...)}) de un jalón.
     */
    private static List<String> fetchTypes(Connection conn, DbEngine engine, String schema) throws SQLException {
        String query = switch (engine) {
            case POSTGRES -> "SELECT t.typname FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace "
                    + "WHERE n.nspname = ? AND (t.typtype IN ('e', 'd') "
                    + "OR (t.typtype = 'c' AND EXISTS (SELECT 1 FROM pg_class c WHERE c.oid = t.typrelid AND c.relkind = 'c'))) "
                    + "ORDER BY t.typname";
            case SQL_SERVER -> "SELECT t.name FROM sys.types t JOIN sys.schemas s ON t.schema_id = s.schema_id "
                    + "WHERE s.name = ? AND t.is_user_defined = 1 ORDER BY t.name";
        };
        List<String> names = new ArrayList<>();
        try (PreparedStatement statement = conn.prepareStatement(query)) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        }
        return names;
    }
}
