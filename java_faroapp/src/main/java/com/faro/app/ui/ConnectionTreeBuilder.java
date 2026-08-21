package com.faro.app.ui;

import java.util.ArrayList;
import java.util.List;

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
        TreeItem<Object> root = new TreeItem<>("root");
        root.setExpanded(true);

        for (Server server : registry.servers()) {
            TreeItem<Object> serverItem = new TreeItem<>(server);
            serverItem.setExpanded(true);
            for (DatabaseEntry db : server.databases()) {
                serverItem.getChildren().add(databaseItem(db));
            }
            root.getChildren().add(serverItem);
        }

        if (!registry.ungroupedDatabases().isEmpty()) {
            TreeItem<Object> ungroupedHeader = new TreeItem<>("Sin grupo");
            ungroupedHeader.setExpanded(true);
            for (DatabaseEntry db : registry.ungroupedDatabases()) {
                ungroupedHeader.getChildren().add(databaseItem(db));
            }
            root.getChildren().add(ungroupedHeader);
        }

        return root;
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
