package com.faro.app.ui;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.query.SchemaIntrospector.SchemaInfo;
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
        Optional<SchemaInfo> cached = SchemaIntrospector.cached(db.id());
        if (cached.isPresent()) {
            super.getChildren().setAll(categoryItems(cached.get()));
            return;
        }
        super.getChildren().setAll(List.of(new TreeItem<>("Cargando esquema…")));
        SchemaIntrospector.loadInBackground(db, credentials, pool,
                info -> Platform.runLater(() -> super.getChildren().setAll(categoryItems(info))));
    }

    private List<TreeItem<Object>> categoryItems(SchemaInfo info) {
        Map<Kind, List<String>> byKind = schemaFilter.isEmpty()
                ? SchemaTreeNode.namesByKind(info)
                : SchemaTreeNode.filterSchema(info, schemaFilter);
        List<TreeItem<Object>> categories = new java.util.ArrayList<>();
        for (Kind kind : Kind.values()) {
            List<String> names = byKind.getOrDefault(kind, List.of());
            if (!schemaFilter.isEmpty() && names.isEmpty()) {
                // Filtrando por esquema: una categoría sin ningún nombre que calce no
                // aporta nada — se omite en vez de mostrarla vacía (distinto del
                // recorrido normal sin filtro, donde SÍ se listan las 5 aunque una
                // esté en 0, para que el conteo real quede a la vista).
                continue;
            }
            TreeItem<Object> categoryItem = new TreeItem<>(new SchemaTreeNode.Category(db, kind, names.size()));
            for (String name : names) {
                categoryItem.getChildren().add(new TreeItem<>(new SchemaTreeNode.Item(db, kind, name)));
            }
            if (!schemaFilter.isEmpty()) {
                categoryItem.setExpanded(true);
            }
            categories.add(categoryItem);
        }
        return categories;
    }
}
