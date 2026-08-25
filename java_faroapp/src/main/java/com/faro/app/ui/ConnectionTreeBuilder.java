package com.faro.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.faro.app.data.ConnectionRegistry;
import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;

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

    public static TreeItem<Object> buildRoot(ConnectionRegistry registry, CredentialStore credentials, ConnectionPoolManager pool) {
        return buildRoot(registry, "", credentials, pool);
    }

    /**
     * {@code filterText} vacío se comporta exactamente igual que
     * {@link #buildRoot(ConnectionRegistry, CredentialStore, ConnectionPoolManager)}.
     * Con texto, incluye una base si su alias lo contiene (sin distinguir
     * mayúsculas, comportamiento de siempre desde 2026-08-22) **o** si su
     * esquema YA CACHEADO (ver {@link SchemaIntrospector#cached}) tiene
     * algún nombre de tabla/vista/función/procedimiento/trigger que
     * calce — búsqueda dentro del esquema, agregada 2026-08-25 para la
     * Etapa C. <b>Límite real, a propósito:</b> solo encuentra bases que
     * ya se exploraron al menos una vez (con su esquema en caché) — no hay
     * fetch eager de todas las bases al escribir en el buscador, sería
     * costoso sin necesidad con muchas bases registradas (v0, mismo
     * criterio "cubre el caso común" que ya usa {@link SchemaIntrospector}
     * para el esquema por defecto de cada motor).
     */
    public static TreeItem<Object> buildRoot(
            ConnectionRegistry registry, String filterText, CredentialStore credentials, ConnectionPoolManager pool) {
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
                serverItem.getChildren().add(databaseItem(db, filter, credentials, pool));
            }
            root.getChildren().add(serverItem);
        }

        List<DatabaseEntry> matchingUngrouped =
                registry.ungroupedDatabases().stream().filter(db -> matches(db, filter)).toList();
        if (!matchingUngrouped.isEmpty()) {
            TreeItem<Object> ungroupedHeader = new TreeItem<>("Sin grupo");
            ungroupedHeader.setExpanded(true);
            for (DatabaseEntry db : matchingUngrouped) {
                ungroupedHeader.getChildren().add(databaseItem(db, filter, credentials, pool));
            }
            root.getChildren().add(ungroupedHeader);
        }

        return root;
    }

    private static boolean matches(DatabaseEntry db, String filter) {
        if (filter.isEmpty() || db.alias().toLowerCase(Locale.ROOT).contains(filter)) {
            return true;
        }
        return SchemaIntrospector.cached(db.id()).map(info -> SchemaTreeNode.matchesAnyName(info, filter)).orElse(false);
    }

    /**
     * {@code filter} solo se usa para decidir si esta base entró a la lista
     * por su esquema (no por su alias) — en ese caso su {@link DatabaseTreeItem}
     * arma sus hijos ya filtrados y auto-expandidos, ver
     * {@code DatabaseTreeItem#categoryItems}. Si el alias ya calzaba (o no
     * hay filtro), se le pasa {@code ""} — comportamiento normal, expandir
     * a mano para explorar el esquema completo. No hace falta
     * {@code CheckBoxTreeItem#setIndependent(true)} — el padre de cada uno
     * (serverItem/ungroupedHeader) es un {@code TreeItem<Object>} plano, NO
     * un {@code CheckBoxTreeItem}, así que no hay casilla de servidor a la
     * que propagar el estado en primer lugar.
     */
    private static TreeItem<Object> databaseItem(
            DatabaseEntry db, String filter, CredentialStore credentials, ConnectionPoolManager pool) {
        boolean aliasMatched = filter.isEmpty() || db.alias().toLowerCase(Locale.ROOT).contains(filter);
        String schemaFilter = aliasMatched ? "" : filter;
        return new DatabaseTreeItem(db, credentials, pool, schemaFilter);
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
