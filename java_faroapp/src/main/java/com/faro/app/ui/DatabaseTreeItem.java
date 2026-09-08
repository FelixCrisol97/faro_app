package com.faro.app.ui;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.ui.SchemaTreeNode.Kind;

import javafx.application.Platform;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.TreeItem;

/**
 * Fila de base de datos del árbol de conexiones — a diferencia de un
 * {@code CheckBoxTreeItem<>(db)} plano (lo que armaba
 * {@code ConnectionTreeBuilder} antes de esto), esta sabe expandirse a su
 * propio explorador de esquema (Tablas/Vistas/Funciones/Procedimientos/
 * Triggers), cargado de verdad vía JDBC la PRIMERA vez que se expande, no
 * antes — patrón estándar de árbol perezoso de JavaFX: {@link #isLeaf()}
 * siempre {@code false} (para que la flecha de expandir se vea SIEMPRE,
 * sin tener que saber de antemano si esta base tiene algo que mostrar) y
 * {@link #getChildren()} sobrescrito para disparar la carga la primera vez
 * que se le pregunta, no en el constructor — así construir el árbol entero
 * (que pasa seguido, ver {@code MainController#refreshTree}, cada tecla del
 * buscador lo reconstruye completo) nunca dispara un fetch JDBC por cada
 * base que tenga, solo las que el usuario de verdad expande.
 *
 * <p><b>Eso último es cierto desde el 2026-09-07, no antes</b> (hallazgo #1 de
 * {@code AUDITORIA_BUGS_RENDIMIENTO.md}): había DOS caminos que pedían
 * {@code getChildren()} sin que nadie expandiera nada — el recorrido de
 * {@code ConnectionTreeBuilder#collectDatabaseItems} (que se llama en cada
 * tecla del buscador, cada cambio de pestaña y cada ejecución) y la
 * propagación hacia abajo de {@code CheckBoxTreeItem} al marcar la casilla.
 * Los dos están cerrados ahora (ver ese método y {@code setIndependent(true)}
 * en el constructor de acá); el efecto real era abrir un pool de HikariCP por
 * cada base registrada apenas arrancaba la app, cosa que el usuario reportó en
 * vivo ("se llena de pool de conexiones si tengo muchas BD ya en la lista").
 *
 * <p>El fetch en sí (y su caché, compartido con el autocompletado) vive en
 * {@link SchemaIntrospector} — esta clase solo decide CUÁNDO pedirlo y
 * cómo convertir el resultado en {@code TreeItem}s reales.
 */
final class DatabaseTreeItem extends CheckBoxTreeItem<Object> {

    private final DatabaseEntry db;
    private final CredentialStore credentials;
    private final ConnectionPoolManager pool;
    /** No vacío = esta base entró a la lista por un nombre de esquema que calzó (no por su alias) — ver ConnectionTreeBuilder#matches. Arma sus hijos ya filtrados/auto-expandidos en vez de esperar un clic. */
    private final String schemaFilter;
    private boolean childrenRequested;

    DatabaseTreeItem(DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool) {
        this(db, credentials, pool, "");
    }

    DatabaseTreeItem(DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool, String schemaFilter) {
        super(db);
        this.db = db;
        this.credentials = credentials;
        this.pool = pool;
        // setIndependent(true) — 2026-09-07, segunda mitad del arreglo del hallazgo #1
        // (ver AUDITORIA_BUGS_RENDIMIENTO.md y ConnectionTreeBuilder#collectDatabaseItems).
        // Un CheckBoxTreeItem NO independiente propaga su estado HACIA LOS HIJOS al
        // marcarse, y para eso los recorre — o sea que marcar una casilla disparaba la
        // carga perezosa de esquema de esa base (fetch JDBC + pool nuevo) igual que el
        // recorrido del árbol. Verificado de verdad, no supuesto: ver
        // ConnectionTreeBuilderTest#marcarUnaCasillaNoIndependientePideLosHijos.
        // Independiente no cambia nada visible acá — los hijos de esta fila son nodos de
        // esquema sin casilla (no hay estado que propagarles), y su padre es un TreeItem
        // plano (no hay casilla de grupo que se pinte "a medias").
        setIndependent(true);
        this.schemaFilter = schemaFilter == null ? "" : schemaFilter;
        if (!this.schemaFilter.isEmpty()) {
            setExpanded(true);
        }
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    @Override
    public javafx.collections.ObservableList<TreeItem<Object>> getChildren() {
        if (!childrenRequested) {
            childrenRequested = true;
            requestSchema();
        }
        return super.getChildren();
    }

    /** "Recargar esquema" del menú contextual — descarta el caché de esta base y pide el esquema de nuevo, sin importar si ya se había cargado antes. A diferencia de {@link #getChildren()}, siempre dispara la carga (no solo la primera vez). */
    void reloadSchema() {
        SchemaIntrospector.invalidate(db.id());
        childrenRequested = true;
        requestSchema();
    }

    /**
     * <b>Expandir una base ya NO consulta el esquema</b> (2026-09-07, pedido
     * explícito del usuario: "que cargue las tablas solo cuando le de a
     * desplegar, también para los procedimientos, triggers, etc"). Antes esto
     * disparaba {@code fetchStructure} —tablas y vistas de un jalón— apenas se
     * abría la fila; ahora las 6 categorías se dibujan al instante y cada una
     * pide lo suyo cuando el usuario la expande a ella (ver
     * {@link CategoryTreeItem}).
     *
     * <p>Lo único que sí se hace acá es <b>abrir una conexión de verdad</b> para
     * confirmar el punto de estado de esta base — el usuario lo pidió con esas
     * palabras ("hasta que yo le de clic a la flecha de desplegar la BD me haga
     * la conexión"), y es lo que mantiene vivo el verde/rojo del árbol ahora que
     * ya no hay ninguna carga automática al arrancar la app. Es UNA conexión, de
     * la base que se acaba de abrir, no de todas.
     */
    private void requestSchema() {
        super.getChildren().setAll(categoryItems());
        probeConnection();
    }

    /**
     * Abre y cierra una conexión del pool solo para saber si esta base responde
     * — ver {@link #requestSchema()}. En un hilo demonio propio: es JDBC, nunca
     * puede correr en el hilo de JavaFX (mismo criterio que el resto de la app),
     * y el resultado vuelve por {@code Platform.runLater} porque
     * {@code connectionStatus} es una propiedad de JavaFX.
     *
     * <p>No muestra ningún error en el árbol si falla: el punto rojo YA es la
     * señal, y las categorías siguen ahí para que el usuario reintente
     * expandiendo cualquiera (que sí mostrará el error real de ese fetch).
     */
    private void probeConnection() {
        Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
        if (creds.isEmpty()) {
            Platform.runLater(() -> db.setConnectionStatus(DatabaseEntry.ConnectionStatus.FAILED));
            return;
        }
        Thread thread = new Thread(() -> {
            try (var connection = pool.getConnection(db, creds.get())) {
                Platform.runLater(() -> db.setConnectionStatus(DatabaseEntry.ConnectionStatus.CONNECTED));
            } catch (SQLException | RuntimeException e) {
                Platform.runLater(() -> db.setConnectionStatus(DatabaseEntry.ConnectionStatus.FAILED));
            }
        }, "faro-connection-probe");
        thread.setDaemon(true);
        thread.start();
    }

    /** Primera línea del mensaje real (los de JDBC pueden traer varias) — o el nombre de la clase si no hay mensaje. Package-private a propósito — {@link CategoryTreeItem} la reusa para su propio estado de error. */
    static String shortCause(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.lines().findFirst().orElse(message);
    }

    /**
     * Las 6 categorías, TODAS perezosas — no consulta nada, solo dibuja las
     * filas; cada {@link CategoryTreeItem} pide lo suyo cuando el usuario la
     * expande a ella.
     *
     * <p><b>2026-09-07:</b> Tablas y Vistas eran las 2 excepciones — se armaban
     * "eager" con los nombres que {@code fetchStructure} ya había traído al
     * expandir la base. Ahora son {@link CategoryTreeItem} como las otras 4
     * (pedido explícito del usuario), así que este método ya no lee ninguna
     * caché ni necesita que haya un fetch previo. Si esa estructura YA está en
     * caché (porque el usuario expandió Tablas o Vistas antes), expandir
     * cualquiera de las dos sigue siendo instantáneo — ver
     * {@code CategoryTreeItem#requestStructureCategory}.
     *
     * <p>En modo búsqueda por esquema ({@link #schemaFilter} no vacío) se sigue
     * exactamente el comportamiento de siempre, ver
     * {@link #filteredCategoryItems}.
     */
    private List<TreeItem<Object>> categoryItems() {
        if (!schemaFilter.isEmpty()) {
            return filteredCategoryItems();
        }
        List<TreeItem<Object>> categories = new java.util.ArrayList<>();
        for (Kind kind : Kind.values()) {
            categories.add(new CategoryTreeItem(db, kind, credentials, pool));
        }
        return categories;
    }

    private TreeItem<Object> eagerCategory(Kind kind, List<String> names, java.util.function.Function<String, String> parentTableLookup) {
        TreeItem<Object> categoryItem = new TreeItem<>(new SchemaTreeNode.Category(db, kind, names.size()));
        categoryItem.getChildren().setAll(itemNodes(db, kind, names, parentTableLookup));
        return categoryItem;
    }

    /**
     * Modo búsqueda por nombre de esquema ({@link #schemaFilter} no vacío) —
     * arma las 6 categorías desde lo que YA esté en caché ahora mismo
     * ({@link SchemaIntrospector#cachedNamesByKind}), sin carga perezosa: si
     * esta base entró a la lista fue justo porque algo YA cacheado calzó el
     * filtro (ver {@code ConnectionTreeBuilder#matches}) — pedir más no
     * cambiaría el resultado del filtro que ya se usó para decidir mostrar
     * esta fila. Mismo comportamiento de siempre (previo al esquema
     * progresivo): categorías reales, auto-expandidas, sin lazy-load.
     */
    private List<TreeItem<Object>> filteredCategoryItems() {
        Map<Kind, List<String>> byKind = SchemaTreeNode.filterSchema(SchemaIntrospector.cachedNamesByKind(db.id()), schemaFilter);
        List<TreeItem<Object>> categories = new java.util.ArrayList<>();
        for (Kind kind : Kind.values()) {
            List<String> names = byKind.getOrDefault(kind, List.of());
            if (names.isEmpty()) {
                // Filtrando por esquema: una categoría sin ningún nombre que calce no
                // aporta nada — se omite en vez de mostrarla vacía (distinto del
                // recorrido normal sin filtro, donde SÍ se listan las 6 aunque una
                // esté en 0, para que el conteo real quede a la vista).
                continue;
            }
            java.util.function.Function<String, String> parentTableLookup = kind == Kind.TRIGGERS
                    ? name -> SchemaIntrospector.cachedTriggerParentTable(db.id(), name).orElse(null)
                    : name -> null;
            TreeItem<Object> categoryItem = eagerCategory(kind, names, parentTableLookup);
            categoryItem.setExpanded(true);
            categories.add(categoryItem);
        }
        return categories;
    }

    /** Convierte nombres reales en hijos {@code TreeItem<SchemaTreeNode.Item>} — reusado por {@link #eagerCategory} y por {@link CategoryTreeItem} al terminar su propia carga perezosa. {@code parentTableLookup} solo se consulta para {@code Kind#TRIGGERS} (ver {@code SchemaTreeNode.Item#parentTable}). */
    static List<TreeItem<Object>> itemNodes(
            DatabaseEntry db, Kind kind, List<String> names, java.util.function.Function<String, String> parentTableLookup) {
        List<TreeItem<Object>> items = new java.util.ArrayList<>();
        for (String name : names) {
            String parentTable = kind == Kind.TRIGGERS ? parentTableLookup.apply(name) : null;
            items.add(new TreeItem<>(new SchemaTreeNode.Item(db, kind, name, parentTable)));
        }
        return items;
    }
}
