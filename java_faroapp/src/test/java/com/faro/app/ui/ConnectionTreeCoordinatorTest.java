package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;

import javafx.collections.ObservableList;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.TreeItem;

/**
 * Los recorridos del árbol de conexiones de {@link ConnectionTreeCoordinator}
 * (2026-09-19).
 *
 * <p><b>Nada de esto tenía tests.</b> Vivía en métodos de instancia privados de
 * {@code MainController} que leían {@code connectionTree.getRoot()}, así que probarlos
 * exigía un {@code TreeView} y el toolkit de JavaFX. Ahora son estáticos sobre la raíz,
 * y la raíz se arma a mano con {@code TreeItem} — que son modelo, no nodos, y no
 * necesitan el toolkit (el mismo criterio de {@code ConnectionTreeBuilderTest}).
 *
 * <p><b>La invariante que más importa</b> es la del hallazgo #1 de
 * {@code AUDITORIA_BUGS_RENDIMIENTO.md}: recorrer el árbol nunca le pide los hijos a
 * una fila de base, porque las bases cargan su esquema —y abren una conexión— dentro de
 * {@code getChildren()}. {@link BaseFalsa} registra si alguien se los pidió.
 */
class ConnectionTreeCoordinatorTest {

    /**
     * Mismo patrón que {@code DatabaseTreeItem}: independiente (marcarla no se propaga a
     * los hijos, que dispararía la carga) y con {@code isLeaf()} falso. Anota si alguien
     * le pidió los hijos.
     */
    private static final class BaseFalsa extends CheckBoxTreeItem<Object> {
        boolean hijosPedidos;

        BaseFalsa(DatabaseEntry db) {
            super(db);
            setIndependent(true);
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        public ObservableList<TreeItem<Object>> getChildren() {
            hijosPedidos = true;
            return super.getChildren();
        }

        DatabaseEntry db() {
            return (DatabaseEntry) getValue();
        }
    }

    private static DatabaseEntry db(String alias) {
        return new DatabaseEntry(alias, "10.0.0.1", 5432, alias, DbEngine.POSTGRES, ServerMode.READ_ONLY);
    }

    private static BaseFalsa base(String alias) {
        return new BaseFalsa(db(alias));
    }

    private static TreeItem<Object> grupo(Object valor, BaseFalsa... bases) {
        TreeItem<Object> grupo = new TreeItem<>(valor);
        grupo.getChildren().addAll(List.of(bases));
        return grupo;
    }

    @SafeVarargs
    private static TreeItem<Object> raiz(TreeItem<Object>... grupos) {
        TreeItem<Object> raiz = new TreeItem<>("raiz");
        // Recorrido y no List.of(grupos): reenviar el arreglo varargs a otro método
        // varargs es justo lo que -Xlint marca como posible contaminación del heap.
        for (TreeItem<Object> grupo : grupos) {
            raiz.getChildren().add(grupo);
        }
        return raiz;
    }

    // ------------------------------------------------------------------
    // Selección
    // ------------------------------------------------------------------

    @Test
    void lasBasesMarcadasSalenEnElOrdenDelArbol() {
        BaseFalsa norte = base("norte");
        BaseFalsa centro = base("centro");
        BaseFalsa sur = base("sur");
        norte.setSelected(true);
        sur.setSelected(true);

        List<DatabaseEntry> marcadas = ConnectionTreeCoordinator.selectedDatabases(
                raiz(grupo(new Server("Bodegas"), norte, centro, sur)));

        assertEquals(List.of(norte.db(), sur.db()), marcadas);
    }

    /**
     * Al activar una pestaña, el árbol tiene que quedar EXACTAMENTE con su selección
     * guardada — incluido desmarcar lo que la pestaña anterior había marcado. Si solo
     * marcara, cambiar de pestaña iría acumulando bases y "Ejecutar" correría contra
     * bodegas que nadie eligió en esa pestaña.
     */
    @Test
    void seleccionarExactamenteDesmarcaLoQueNoEstaEnLaLista() {
        BaseFalsa a = base("a");
        BaseFalsa b = base("b");
        BaseFalsa c = base("c");
        a.setSelected(true);
        b.setSelected(true);

        ConnectionTreeCoordinator.selectExactly(raiz(grupo("g", a, b, c)), Set.of(c.db().id()));

        assertFalse(a.isSelected(), "quedó marcada una base que la pestaña no tenía");
        assertFalse(b.isSelected(), "quedó marcada una base que la pestaña no tenía");
        assertTrue(c.isSelected());
    }

    /**
     * {@code reselect} es la otra mitad y hace otra cosa a propósito: solo marca. Se usa
     * sobre un árbol recién armado, que nace sin nada marcado, así que no tiene nada que
     * desmarcar — y así era el código antes de separarlo. El test fija esa asimetría para
     * que nadie "unifique" las dos funciones sin darse cuenta de que no son iguales.
     */
    @Test
    void volverAMarcarSoloMarcaNuncaDesmarca() {
        BaseFalsa yaMarcada = base("ya-marcada");
        BaseFalsa aMarcar = base("a-marcar");
        yaMarcada.setSelected(true);

        ConnectionTreeCoordinator.reselect(raiz(grupo("g", yaMarcada, aMarcar)), Set.of(aMarcar.db().id()));

        assertTrue(yaMarcada.isSelected(), "reselect desmarcó algo — eso es selectExactly, no esto");
        assertTrue(aMarcar.isSelected());
    }

    // ------------------------------------------------------------------
    // Filas abiertas
    // ------------------------------------------------------------------

    /** Los grupos se identifican por su valor: un Server, o la cadena del encabezado "Sin grupo". */
    @Test
    void recuerdaQueGruposEstabanAbiertos() {
        Server bodegas = new Server("Bodegas");
        Server oficinas = new Server("Oficinas");
        TreeItem<Object> abierto = grupo(bodegas, base("a"));
        TreeItem<Object> cerrado = grupo(oficinas, base("b"));
        TreeItem<Object> sinGrupo = grupo(ConnectionTreeBuilder.UNGROUPED_HEADER, base("c"));
        abierto.setExpanded(true);
        sinGrupo.setExpanded(true);

        Set<Object> abiertos = ConnectionTreeCoordinator.expandedGroups(raiz(abierto, cerrado, sinGrupo));

        assertEquals(Set.of(bodegas, ConnectionTreeBuilder.UNGROUPED_HEADER), abiertos);
    }

    @Test
    void recuerdaQueBasesEstabanAbiertas() {
        BaseFalsa abierta = base("abierta");
        BaseFalsa cerrada = base("cerrada");
        abierta.setExpanded(true);

        Set<String> ids = ConnectionTreeCoordinator.expandedDatabaseIds(raiz(grupo("g", abierta, cerrada)));

        assertEquals(Set.of(abierta.db().id()), ids);
    }

    @Test
    void reabreSoloLasBasesQueEstabanAbiertas() {
        BaseFalsa reabrir = base("reabrir");
        BaseFalsa dejar = base("dejar");

        ConnectionTreeCoordinator.expandDatabases(raiz(grupo("g", reabrir, dejar)), Set.of(reabrir.db().id()));

        assertTrue(reabrir.isExpanded());
        assertFalse(dejar.isExpanded(), "abrió una base que no estaba abierta — eso dispara su carga de esquema");
    }

    // ------------------------------------------------------------------
    // La invariante del hallazgo #1
    // ------------------------------------------------------------------

    /**
     * Cada recorrido de esta clase, uno por uno, sobre bases que anotan si alguien les
     * pidió los hijos. Pedírselos es abrir una conexión contra esa base: con 50 bases
     * registradas y esto corriendo en cada reconstrucción del árbol, era la ráfaga de
     * conexiones que nadie pidió ("se llena de pool de conexiones si tengo muchas BD").
     */
    @Test
    void ningunRecorridoLePideLosHijosAUnaBase() {
        BaseFalsa a = base("a");
        BaseFalsa b = base("b");
        a.setSelected(true);
        a.setExpanded(true);
        TreeItem<Object> raiz = raiz(grupo(new Server("Bodegas"), a, b));

        ConnectionTreeCoordinator.selectedDatabases(raiz);
        ConnectionTreeCoordinator.selectedIds(raiz);
        ConnectionTreeCoordinator.selectExactly(raiz, Set.of(b.db().id()));
        ConnectionTreeCoordinator.reselect(raiz, Set.of(a.db().id()));
        ConnectionTreeCoordinator.expandedGroups(raiz);
        ConnectionTreeCoordinator.expandedDatabaseIds(raiz);
        ConnectionTreeCoordinator.findGroupItem(raiz, null);

        assertFalse(a.hijosPedidos, "un recorrido le pidió los hijos a una base — abriría una conexión");
        assertFalse(b.hijosPedidos, "un recorrido le pidió los hijos a una base — abriría una conexión");
    }

    // ------------------------------------------------------------------
    // Grupos
    // ------------------------------------------------------------------

    /** Por identidad y no por nombre: dos grupos pueden llamarse igual. */
    @Test
    void encuentraUnGrupoPorIdentidadNoPorNombre() {
        Server uno = new Server("Bodegas");
        Server otroIgual = new Server("Bodegas");
        TreeItem<Object> filaDeUno = grupo(uno);
        TreeItem<Object> filaDelOtro = grupo(otroIgual);

        TreeItem<Object> raiz = raiz(filaDeUno, filaDelOtro);

        assertSame(filaDelOtro, ConnectionTreeCoordinator.findGroupItem(raiz, otroIgual));
        assertSame(filaDeUno, ConnectionTreeCoordinator.findGroupItem(raiz, uno));
    }

    @Test
    void sinServidorBuscaElEncabezadoSinGrupo() {
        TreeItem<Object> sinGrupo = grupo(ConnectionTreeBuilder.UNGROUPED_HEADER);

        assertSame(sinGrupo,
                ConnectionTreeCoordinator.findGroupItem(raiz(grupo(new Server("Bodegas")), sinGrupo), null));
    }

    /** Un grupo filtrado por el buscador no está en el árbol: no se inventa una fila. */
    @Test
    void unGrupoQueNoEstaVisibleDaNulo() {
        assertNull(ConnectionTreeCoordinator.findGroupItem(raiz(grupo(new Server("Otro"))), new Server("Buscado")));
    }

    // ------------------------------------------------------------------
    // Antes del primer armado
    // ------------------------------------------------------------------

    /**
     * Al arrancar el árbol todavía no existe, y la primera reconstrucción lee el estado
     * del árbol "anterior" igual que todas las demás. Ninguno de estos puede tronar con
     * raíz nula.
     */
    @Test
    void sinArbolTodaviaNadaTruena() {
        assertTrue(ConnectionTreeCoordinator.expandedGroups(null).isEmpty());
        assertTrue(ConnectionTreeCoordinator.expandedDatabaseIds(null).isEmpty());
        ConnectionTreeCoordinator.expandDatabases(null, Set.of("x"));
        assertNull(ConnectionTreeCoordinator.findGroupItem(null, null));
    }
}
