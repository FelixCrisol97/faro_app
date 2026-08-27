package com.faro.app.ui;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.query.SchemaIntrospector.SchemaStructure;
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

    private void requestSchema() {
        Optional<SchemaStructure> cached = SchemaIntrospector.cached(db.id());
        if (cached.isPresent()) {
            super.getChildren().setAll(categoryItems());
            return;
        }
        super.getChildren().setAll(List.of(new TreeItem<>("Cargando esquema…")));
        SchemaIntrospector.loadInBackground(db, credentials, pool,
                structure -> Platform.runLater(() -> super.getChildren().setAll(categoryItems())),
                // Hallazgo en vivo del usuario (2026-08-25, bases reales de cliente): sin esto
                // la fila se quedaba en "Cargando esquema…" para siempre si el fetch fallaba
                // (credenciales vencidas, red, permisos) — parecía un bloqueo, el único rastro
                // real quedaba en el log. childrenRequested ya sigue en true, así que expandir
                // de nuevo esta fila no reintenta solo; "Recargar esquema" del menú contextual
                // (ya existente) sí vuelve a pedirlo.
                error -> Platform.runLater(() -> super.getChildren().setAll(
                        List.of(new TreeItem<>("Error al cargar esquema: " + shortCause(error))))));
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
     * Las 6 categorías — se llama solo después de que la estructura
     * (Tablas/Vistas) YA está en {@link SchemaIntrospector#cached}, así que
     * leerla acá nunca dispara un fetch por sí sola.
     *
     * <p><b>Esquema progresivo (2026-08-25):</b> en el recorrido normal (sin
     * {@link #schemaFilter}), Tablas/Vistas se arman de inmediato (ya están
     * cargadas); Funciones/Procedimientos/Triggers/Tipos se arman como
     * {@link CategoryTreeItem} — perezoso, cada una pide su propio fetch
     * solo cuando el usuario la expande, no antes. En modo búsqueda por
     * esquema ({@link #schemaFilter} no vacío) se sigue exactamente el
     * comportamiento de siempre, ver {@link #filteredCategoryItems}.
     */
    private List<TreeItem<Object>> categoryItems() {
        if (!schemaFilter.isEmpty()) {
            return filteredCategoryItems();
        }
        SchemaStructure structure = SchemaIntrospector.cached(db.id()).orElseThrow();
        List<TreeItem<Object>> categories = new java.util.ArrayList<>();
        categories.add(eagerCategory(Kind.TABLES, structure.tableNames(), name -> null));
        categories.add(eagerCategory(Kind.VIEWS, structure.viewNames(), name -> null));
        categories.add(new CategoryTreeItem(db, Kind.FUNCTIONS, credentials, pool));
        categories.add(new CategoryTreeItem(db, Kind.PROCEDURES, credentials, pool));
        categories.add(new CategoryTreeItem(db, Kind.TRIGGERS, credentials, pool));
        categories.add(new CategoryTreeItem(db, Kind.TYPES, credentials, pool));
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
