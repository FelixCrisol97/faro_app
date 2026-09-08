package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import javafx.collections.ObservableList;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.TreeItem;

/**
 * Protege el arreglo del hallazgo #1 de {@code AUDITORIA_BUGS_RENDIMIENTO.md}
 * — el recorrido del árbol NO debe pedir los hijos de una fila de base de
 * datos, porque en la app real esos hijos se cargan de forma perezosa con un
 * fetch JDBC adentro de {@code getChildren()} ({@link DatabaseTreeItem}/
 * {@link CategoryTreeItem}), y pedirlos equivale a abrir una conexión por cada
 * base registrada sin que el usuario lo haya pedido.
 *
 * <p>Se usa un doble de prueba (no {@code DatabaseTreeItem} real) a propósito:
 * lo que hay que verificar es el CONTRATO ("nadie toca getChildren() de una
 * fila de base"), no el fetch JDBC en sí — que necesitaría una base real. El
 * doble marca una bandera cuando le piden los hijos, que es exactamente la
 * señal que dispararía el fetch en producción.
 */
class ConnectionTreeBuilderTest {

    /** Mismo patrón perezoso que {@link DatabaseTreeItem}: {@code isLeaf()} falso y carga disparada dentro de {@code getChildren()}. */
    private static final class LazyDatabaseItem extends CheckBoxTreeItem<Object> {
        boolean childrenRequested;

        LazyDatabaseItem(String alias) {
            super(alias);
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public ObservableList<TreeItem<Object>> getChildren() {
            childrenRequested = true;
            return super.getChildren();
        }
    }

    private static TreeItem<Object> treeWith(LazyDatabaseItem... databases) {
        TreeItem<Object> root = new TreeItem<>("root");
        TreeItem<Object> group = new TreeItem<>("Grupo");
        group.getChildren().addAll(List.of(databases));
        root.getChildren().add(group);
        return root;
    }

    @Test
    void collectDatabaseItemsEncuentraLasBasesSinPedirSusHijos() {
        LazyDatabaseItem first = new LazyDatabaseItem("Bodega 1");
        LazyDatabaseItem second = new LazyDatabaseItem("Bodega 2");

        List<CheckBoxTreeItem<Object>> found = ConnectionTreeBuilder.collectDatabaseItems(treeWith(first, second));

        assertEquals(2, found.size(), "debe encontrar las 2 bases");
        assertFalse(first.childrenRequested, "no debe pedir los hijos de una base (dispararía el fetch de esquema)");
        assertFalse(second.childrenRequested, "no debe pedir los hijos de una base (dispararía el fetch de esquema)");
    }

    /**
     * Comportamiento real de JavaFX, verificado acá y no supuesto: un
     * {@code CheckBoxTreeItem} NO independiente propaga su estado hacia los
     * hijos al marcarse, y para eso los recorre — o sea que marcar una casilla
     * dispararía la carga perezosa igual que el recorrido del árbol.
     */
    @Test
    void marcarUnaCasillaNoIndependientePideLosHijos() {
        LazyDatabaseItem item = new LazyDatabaseItem("Bodega 1");

        item.setSelected(true);

        assertTrue(item.childrenRequested,
                "si esto falla, JavaFX cambió: un CheckBoxTreeItem no independiente ya no recorre a sus hijos");
    }

    /** Contraparte del anterior — {@code setIndependent(true)} es lo que evita ese recorrido, y es lo que {@link DatabaseTreeItem} usa. */
    @Test
    void marcarUnaCasillaIndependienteNoPideLosHijos() {
        LazyDatabaseItem item = new LazyDatabaseItem("Bodega 1");
        item.setIndependent(true);

        item.setSelected(true);

        assertFalse(item.childrenRequested, "una casilla independiente no debe tocar a sus hijos al marcarse");
    }
}
