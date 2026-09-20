package com.faro.app.ui;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.ColumnMetadata;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.query.SqlScriptGenerator;

import javafx.concurrent.Task;

/**
 * Las seis acciones "Generar…" del explorador de esquema (2026-09-15, segundo paso del
 * hallazgo C1 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
 *
 * <p>El análisis lo señaló como el segundo candidato más limpio y tenía razón: ya estaba
 * casi aislado dentro de {@code MainController}. {@link #generateFromCacheOrFetch} es un
 * armazón genérico y los seis {@code onGenerateXxx} son adaptadores de una línea; su
 * única dependencia real hacia afuera era abrir una pestaña de consulta, que acá entra
 * como {@link BiConsumer}.
 *
 * <p><b>Qué queda del lado del controlador.</b> Dos funciones, las dos de UI: abrir la
 * pestaña con el script generado ({@code openQueryTab}) y escribir en la barra de estado
 * ({@code status}). Todo lo demás —el caché, el candado de duplicados, los {@code Task}
 * de fondo— vive acá.
 *
 * <p>Los métodos públicos se llaman <b>desde el hilo de JavaFX</b> (nacen en el menú
 * contextual del árbol) y las dos funciones inyectadas se invocan en ese mismo hilo.
 */
public final class ScriptGeneratorCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ScriptGeneratorCoordinator.class);

    /**
     * "acción:base:objeto" en vuelo ahora mismo — evita mandar dos fetches JDBC
     * idénticos si el usuario repite el mismo "Generar…" antes de que el primero
     * termine. Ver {@link #pendingKey} para por qué la llave se arma así.
     */
    private final Set<String> pendingGenerations = ConcurrentHashMap.newKeySet();

    private final CredentialStore credentials;
    private final ConnectionPoolManager pool;
    private final BiConsumer<String, Set<String>> openQueryTab;
    private final Consumer<String> status;

    /**
     * @param openQueryTab abre una pestaña nueva con el SQL generado y deja marcada solo
     *                     la base dueña del objeto; se invoca en el hilo de JavaFX
     * @param status       mensaje para la barra de estado; se invoca en el hilo de JavaFX
     */
    public ScriptGeneratorCoordinator(CredentialStore credentials, ConnectionPoolManager pool,
            BiConsumer<String, Set<String>> openQueryTab, Consumer<String> status) {
        this.credentials = credentials;
        this.pool = pool;
        this.openQueryTab = openQueryTab;
        this.status = status;
    }

    /**
     * Único punto de entrada desde {@link ConnectionTreeCell} — reparte según la acción
     * pedida, sin que agregar una acción nueva cambie la firma del constructor de la
     * celda.
     */
    public void generate(SchemaTreeNode.Item item, SchemaTreeNode.GenerateAction action) {
        switch (action) {
            case SELECT -> generateSelect(item);
            case INSERT -> generateInsert(item);
            case UPDATE -> generateUpdate(item);
            case DELETE -> generateDelete(item);
            case CREATE_TABLE -> generateCreateTable(item);
            case CREATE_SCRIPT -> generateCreateScript(item);
        }
    }

    // ------------------------------------------------------------------
    // Las seis acciones
    // ------------------------------------------------------------------

    /**
     * "Generar SELECT" — arma {@code SELECT col1, col2, … FROM tabla} con las columnas
     * reales.
     *
     * <p><b>Esquema progresivo (2026-08-25):</b> antes esto leía un caché masivo con las
     * columnas de TODAS las tablas de la base, cargado de un jalón al expandirla —
     * hallazgo en vivo del usuario contra bases DEV reales de cliente: ese fetch masivo
     * era lo que dejaba el árbol pegado en "Cargando esquema…" en bases grandes. Ahora
     * pasa por {@link #fromColumnDetails}, el mismo camino caché-primero-si-no-fetch que
     * ya usaban UPDATE/DELETE/CREATE TABLE.
     */
    private void generateSelect(SchemaTreeNode.Item item) {
        fromColumnDetails(item, "SELECT", columns -> {
            String columnList = columns.isEmpty() ? "*"
                    : columns.stream().map(ColumnMetadata::name).collect(Collectors.joining(", "));
            return "SELECT " + columnList + " FROM " + item.name();
        });
    }

    /** "Generar INSERT" (solo tablas) — solo los nombres de columna le importan a {@code SqlScriptGenerator}. */
    private void generateInsert(SchemaTreeNode.Item item) {
        fromColumnDetails(item, "INSERT", columns ->
                SqlScriptGenerator.generateInsertScript(item.name(), columns.stream().map(ColumnMetadata::name).toList()));
    }

    /** "Generar UPDATE" (solo tablas) — a diferencia de SELECT/INSERT necesita saber cuál columna es la PK (para el WHERE), así que sí puede implicar un viaje real a la base. */
    private void generateUpdate(SchemaTreeNode.Item item) {
        fromColumnDetails(item, "UPDATE", columns -> SqlScriptGenerator.generateUpdateScript(item.name(), columns));
    }

    /** "Generar DELETE" (solo tablas) — mismo motivo que UPDATE: necesita la PK real. */
    private void generateDelete(SchemaTreeNode.Item item) {
        fromColumnDetails(item, "DELETE", columns -> SqlScriptGenerator.generateDeleteScript(item.name(), columns));
    }

    /** "Generar script CREATE" de una tabla — mejor esfuerzo desde columnas (tipo/NOT NULL/PK). Ningún motor expone un DDL completo listo para tablas como sí tiene para rutinas, de ahí la diferencia con {@link #generateCreateScript}. */
    private void generateCreateTable(SchemaTreeNode.Item item) {
        fromColumnDetails(item, "CREATE TABLE", columns -> SqlScriptGenerator.generateCreateTableScript(item.name(), columns));
    }

    /**
     * "Generar script CREATE" de vista/función/procedimiento/trigger — acá sí hay un DDL
     * real que el motor da de un solo viaje ({@code pg_get_viewdef}/
     * {@code pg_get_functiondef}/{@code pg_get_triggerdef} en PostgreSQL,
     * {@code OBJECT_DEFINITION()} en SQL Server).
     */
    private void generateCreateScript(SchemaTreeNode.Item item) {
        generateFromCacheOrFetch(item, "script CREATE", "faro-script-definition",
                () -> SchemaIntrospector.cachedDefinition(item.database().id(), item.kind(), item.name()),
                () -> SchemaIntrospector.fetchDefinition(
                        item.database(), credentials, pool, item.kind(), item.name(), item.parentTable()),
                script -> script);
    }

    /**
     * Columnas con tipo/PK reales — instantáneo si ya se pidieron antes en esta sesión;
     * si no, un fetch real en segundo plano con feedback mientras corre. Camino
     * compartido por las 5 acciones de tabla desde el esquema progresivo (2026-08-25):
     * antes SELECT/INSERT tenían su propio atajo leyendo un caché masivo que se cargaba
     * completo al expandir la base, y ese caché era la causa real de que el árbol se
     * quedara pegado contra bases DEV grandes del cliente.
     */
    private void fromColumnDetails(SchemaTreeNode.Item item, String label,
            Function<List<ColumnMetadata>, String> scriptBuilder) {
        generateFromCacheOrFetch(item, label, "faro-script-columns",
                () -> SchemaIntrospector.cachedColumns(item.database().id(), item.name()),
                () -> SchemaIntrospector.fetchColumns(item.database(), credentials, pool, item.name()),
                scriptBuilder);
    }

    // ------------------------------------------------------------------
    // El armazón
    // ------------------------------------------------------------------

    /**
     * Único armazón real de "caché primero, si no fetch en segundo plano con feedback".
     * Las dos variantes de arriba eran dos copias casi idénticas de esto (hallazgo de
     * revisión de código, 2026-08-25): consulta al caché con retorno temprano, mensaje
     * "Generando…", construir el {@code Task}, éxito→abrir pestaña, falla→log + mensaje,
     * hilo demonio — diferían solo en de dónde sale el valor cacheado, el {@code Task} y
     * el nombre del hilo.
     */
    private <T> void generateFromCacheOrFetch(
            SchemaTreeNode.Item item, String label, String threadName,
            Supplier<Optional<T>> cacheLookup, Supplier<Task<T>> taskFactory, Function<T, String> scriptBuilder) {
        Optional<T> cached = cacheLookup.get();
        if (cached.isPresent()) {
            applyGeneratedScript(item, label, scriptBuilder.apply(cached.get()));
            return;
        }
        String pendingKey = pendingKey(label, item);
        if (!pendingGenerations.add(pendingKey)) {
            return;
        }
        status.accept("Generando " + label + " para " + item.name() + "…");
        Task<T> task = taskFactory.get();
        task.setOnSucceeded(e -> {
            pendingGenerations.remove(pendingKey);
            applyGeneratedScript(item, label, scriptBuilder.apply(task.getValue()));
        });
        task.setOnFailed(e -> {
            pendingGenerations.remove(pendingKey);
            log.warn("Generar {} falló para [{}] {}", label, item.database().alias(), item.name(), task.getException());
            status.accept("No se pudo generar " + label + " de " + item.name() + " — revisa Diagnóstico.");
        });
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Llave del candado de duplicados (hallazgo de revisión de código, 2026-08-25):
     * repetir el mismo "Generar…" antes de que el primer fetch termine —doble clic en la
     * fila más clic derecho, o el usuario impaciente repitiendo el clic— mandaba dos
     * consultas JDBC idénticas en paralelo, sin ningún candado.
     *
     * <p><b>Usa {@code label}, no el nombre del hilo, y la diferencia importa.</b>
     * SELECT/INSERT/UPDATE/DELETE/CREATE TABLE de una misma tabla comparten el hilo
     * {@code faro-script-columns}, pero cada una es una acción distinta que el usuario sí
     * quiere ver completada por separado, con su propia pestaña y su propio SQL. Dedupar
     * por nombre de hilo habría descartado en silencio —sin pestaña ni aviso— un SELECT
     * pedido justo después de un UPDATE sobre la misma tabla mientras el UPDATE seguía en
     * curso. Por {@code label} solo dedupa el caso real que importa: repetir la MISMA
     * acción sobre el MISMO objeto antes de que termine.
     *
     * <p>Es la única parte de esta clase que es lógica pura y no un adaptador, así que
     * es la única con tests: ver {@code ScriptGeneratorCoordinatorTest}.
     */
    static String pendingKey(String label, SchemaTreeNode.Item item) {
        return label + ":" + item.database().id() + ":" + item.name();
    }

    /** Visible para tests — cuántas generaciones hay en vuelo ahora mismo. */
    int pendingCount() {
        return pendingGenerations.size();
    }

    /** Último paso común de cualquier "Generar…": abrir una pestaña con el script, marcar SOLO la casilla de la base dueña, y avisar. */
    private void applyGeneratedScript(SchemaTreeNode.Item item, String label, String sql) {
        openQueryTab.accept(sql, Set.of(item.database().id()));
        status.accept(label + " generado para " + item.name() + " — su casilla ya quedó marcada.");
        log.info("Generar {}: [{}] {}", label, item.database().alias(), sql);
    }
}
