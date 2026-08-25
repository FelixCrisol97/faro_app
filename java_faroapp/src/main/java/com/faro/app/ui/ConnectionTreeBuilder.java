package com.faro.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.faro.app.data.ConnectionRegistry;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;

import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.TreeItem;

/**
 * Arma el árbol de {@code TreeItem<Object>} a partir de un
 * {@link ConnectionRegistry} — separado de {@link ConnectionTreeCell} (cómo
 * se pinta cada fila) y de {@code MainController} (que solo pide el árbol
 * ya armado y lo conecta al {@code TreeView}), para que ninguno de los tres
 * crezca hasta volverse un archivo que hace de todo — la lección ya
 * documentada varias veces en la versión Flutter sobre archivos
 * "god-file".
 */
public final class ConnectionTreeBuilder {

    private ConnectionTreeBuilder() {
    }

    public static TreeItem<Object> buildRoot(ConnectionRegistry registry) {
        return buildRoot(registry, "");
    }

    /**
     * {@code filterText} vacío se comporta exactamente igual que
     * {@link #buildRoot(ConnectionRegistry)} (retrocompatible con los ~15
     * sitios que ya llaman a ese método sin filtro). Con texto, solo
     * incluye bases cuyo alias lo contenga (sin distinguir mayúsculas) —
     * un servidor/grupo sin ninguna base que calce no aparece en absoluto,
     * en vez de mostrarse vacío. Buscador del árbol de conexiones,
     * pedido 2026-08-22 ("puedo llegar a tener cientos de BDs").
     */
    public static TreeItem<Object> buildRoot(ConnectionRegistry registry, String filterText) {
        String filter = filterText == null ? "" : filterText.trim().toLowerCase(Locale.ROOT);

        TreeItem<Object> root = new TreeItem<>("root");
        root.setExpanded(true);

        for (Server server : registry.servers()) {
            List<DatabaseEntry> matching = server.databases().stream().filter(db -> matches(db, filter)).toList();
            if (matching.isEmpty()) {
                continue;
            }
            TreeItem<Object> serverItem = new TreeItem<>(server);
            serverItem.setExpanded(true);
            for (DatabaseEntry db : matching) {
                serverItem.getChildren().add(databaseItem(db));
            }
            root.getChildren().add(serverItem);
        }

        List<DatabaseEntry> matchingUngrouped =
                registry.ungroupedDatabases().stream().filter(db -> matches(db, filter)).toList();
        if (!matchingUngrouped.isEmpty()) {
            TreeItem<Object> ungroupedHeader = new TreeItem<>("Sin grupo");
            ungroupedHeader.setExpanded(true);
            for (DatabaseEntry db : matchingUngrouped) {
                ungroupedHeader.getChildren().add(databaseItem(db));
            }
            root.getChildren().add(ungroupedHeader);
        }

        return root;
    }

    private static boolean matches(DatabaseEntry db, String filter) {
        return filter.isEmpty() || db.alias().toLowerCase(Locale.ROOT).contains(filter);
    }

    private static TreeItem<Object> databaseItem(DatabaseEntry db) {
        // No hace falta CheckBoxTreeItem#setIndependent(true) — el padre de
        // cada uno (serverItem/ungroupedHeader) es un TreeItem<Object> plano,
        // NO un CheckBoxTreeItem, así que no hay checkbox de servidor al que
        // propagar el estado en primer lugar (ver ConnectionTreeCell: los
        // servidores no llevan casilla).
        return new CheckBoxTreeItem<>(db);
    }

    /**
     * Todos los {@code CheckBoxTreeItem} de bases de datos del árbol, sin
     * importar si su fila está actualmente renderizada — la propiedad
     * {@code selectedProperty()} vive en el {@code TreeItem}, no en la
     * celda virtualizada, así que esto sirve para observar cuántas bases
     * están marcadas (ej. el contador "N bases seleccionadas" de la barra
     * de herramientas) sin depender del scroll.
     */
    public static List<CheckBoxTreeItem<Object>> collectDatabaseItems(TreeItem<Object> root) {
        List<CheckBoxTreeItem<Object>> result = new ArrayList<>();
        collectDatabaseItems(root, result);
        return result;
    }

    private static void collectDatabaseItems(TreeItem<Object> item, List<CheckBoxTreeItem<Object>> out) {
        if (item instanceof CheckBoxTreeItem<Object> checkItem) {
            out.add(checkItem);
        }
        for (TreeItem<Object> child : item.getChildren()) {
            collectDatabaseItems(child, out);
        }
    }
}
