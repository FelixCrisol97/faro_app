package com.faro.app.ui;

import java.util.List;
import java.util.Optional;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.ui.SchemaTreeNode.Kind;

import javafx.application.Platform;
import javafx.scene.control.TreeItem;

/**
 * Fila de categoría perezosa (Funciones/Procedimientos/Triggers/Tipos) bajo
 * una base ya expandida — esquema progresivo (2026-08-25, hallazgo en vivo
 * del usuario contra bases DEV reales de cliente: el árbol se quedaba
 * pegado en "Cargando esquema…" en bases con muchas tablas/columnas). A
 * diferencia de Tablas/Vistas (siempre eager, ver {@link DatabaseTreeItem}
 * — son las categorías rápidas y las que más se usan), estas 4 solo se
 * piden de verdad la primera vez que el usuario expande ESTA fila, no al
 * expandir la base entera.
 *
 * <p>Mismo patrón perezoso exacto que {@link DatabaseTreeItem} mismo —
 * {@link #isLeaf()} siempre {@code false} (la flecha de expandir se ve
 * siempre, sin saber de antemano si esta categoría tiene algo) y
 * {@link #getChildren()} sobrescrito para disparar la carga la primera vez
 * que se le pregunta, no en el constructor.
 */
final class CategoryTreeItem extends TreeItem<Object> {

    private final DatabaseEntry db;
    private final Kind kind;
    private final CredentialStore credentials;
    private final ConnectionPoolManager pool;
    private boolean childrenRequested;

    CategoryTreeItem(DatabaseEntry db, Kind kind, CredentialStore credentials, ConnectionPoolManager pool) {
        super(new SchemaTreeNode.Category(db, kind, SchemaTreeNode.UNKNOWN_COUNT));
        this.db = db;
        this.kind = kind;
        this.credentials = credentials;
        this.pool = pool;
    }

    @Override
    public boolean isLeaf() {
        return false;
    }

    @Override
    public javafx.collections.ObservableList<TreeItem<Object>> getChildren() {
        if (!childrenRequested) {
            childrenRequested = true;
            requestCategory();
        }
        return super.getChildren();
    }

    private void requestCategory() {
        Optional<List<String>> cached = SchemaIntrospector.cachedCategory(db.id(), kind);
        if (cached.isPresent()) {
            applyNames(cached.get());
            return;
        }
        super.getChildren().setAll(List.of(new TreeItem<>("Cargando…")));
        SchemaIntrospector.loadCategoryInBackground(db, credentials, pool, kind,
                names -> Platform.runLater(() -> applyNames(names)),
                // Mismo motivo que DatabaseTreeItem#requestSchema — sin esto la fila se
                // quedaba pegada en "Cargando…" para siempre si el fetch de esta categoría
                // fallaba, indistinguible de uno que de verdad seguía en curso.
                error -> Platform.runLater(() -> super.getChildren().setAll(
                        List.of(new TreeItem<>("Error al cargar: " + DatabaseTreeItem.shortCause(error))))));
    }

    /** {@code setValue} actualiza el conteo visible de esta fila (antes en {@link SchemaTreeNode#UNKNOWN_COUNT}) — dispara su propio refresh de celda, no hace falta tocar el árbol desde afuera. */
    private void applyNames(List<String> names) {
        setValue(new SchemaTreeNode.Category(db, kind, names.size()));
        java.util.function.Function<String, String> parentTableLookup = kind == Kind.TRIGGERS
                ? name -> SchemaIntrospector.cachedTriggerParentTable(db.id(), name).orElse(null)
                : name -> null;
        super.getChildren().setAll(DatabaseTreeItem.itemNodes(db, kind, names, parentTableLookup));
    }
}
