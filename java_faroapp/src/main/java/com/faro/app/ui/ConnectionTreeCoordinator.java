package com.faro.app.ui;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.faro.app.data.ConnectionRegistry;
import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;
import com.faro.app.query.ConnectionPoolManager;

import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.IndexedCell;
import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.control.skin.VirtualFlow;

/**
 * El estado del árbol de conexiones que sobrevive a cada reconstrucción: qué bases
 * están marcadas, qué filas están abiertas, dónde estaba el scroll, y el texto del
 * buscador (2026-09-19, cuarto y último paso del hallazgo C1 de
 * {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
 *
 * <p><b>Por qué existe este estado.</b> El árbol no se actualiza por partes: cada cambio
 * —agregar, editar, borrar o mover una base, descubrir, importar, cada pausa del
 * buscador— tira todos los {@code TreeItem} y los vuelve a armar desde el registro. Sin
 * esta clase, cada reconstrucción perdía la selección del usuario a medio armar una
 * consulta masiva, reabría todos los grupos que había cerrado, y lo mandaba al tope de
 * una lista de 50 bases (los tres fueron reportes reales, 2026-09-10).
 *
 * <p><b>Qué NO hace.</b> No decide qué cambia en el registro ni abre diálogos: agregar,
 * editar, borrar, mover y renombrar siguen en el controlador, que después le pide a esta
 * clase {@link #refresh()}, {@link #revealDatabase} o {@link #revealGroup}.
 *
 * <h2>Lo testeable, y la invariante que protege</h2>
 *
 * Todo lo que recorre el árbol está en métodos estáticos que reciben la raíz
 * ({@link #selectedDatabases(TreeItem)}, {@link #expandedGroups},
 * {@link #expandedDatabaseIds}, {@link #expandDatabases}, {@link #selectExactly},
 * {@link #findGroupItem}), así que se prueban con {@code TreeItem} armados a mano, sin
 * arrancar JavaFX — ver {@code ConnectionTreeCoordinatorTest}.
 *
 * <p>La invariante que esos tests fijan es la del hallazgo #1 de
 * {@code AUDITORIA_BUGS_RENDIMIENTO.md}: <b>recorrer el árbol nunca le pide los hijos a
 * una fila de base</b>. Las bases cargan su esquema de forma perezosa dentro de
 * {@code getChildren()}, así que un recorrido descuidado abre una conexión contra cada
 * base registrada — "se llena de pool de conexiones si tengo muchas BD". La única
 * excepción deliberada es {@link #expandDatabases}: abrir una fila que el usuario tenía
 * abierta SÍ tiene que cargarla.
 */
public final class ConnectionTreeCoordinator {

    private final TreeView<Object> tree;
    private final Supplier<ConnectionRegistry> registry;
    private final CredentialStore credentials;
    private final ConnectionPoolManager pool;
    private final Label selectedCountLabel;
    private final Button selectAllButton;
    private final Runnable onSelectionChanged;

    /** Texto actual del buscador de bases — ver {@link #setFilterText}. */
    private String filterText = "";

    /**
     * @param registry           el registro vigente — {@link Supplier} y no referencia
     *                           porque "Importar configuración…" lo REEMPLAZA por otro
     *                           objeto (mismo motivo que en {@code SessionPersistence})
     * @param selectedCountLabel "N bases seleccionadas", atada a las casillas
     * @param selectAllButton    "Todas"/"Ninguna", su texto atado a las casillas
     * @param onSelectionChanged se invoca al marcar o desmarcar cualquier base — hoy,
     *                           repintar el encabezado de la pestaña activa
     */
    public ConnectionTreeCoordinator(TreeView<Object> tree, Supplier<ConnectionRegistry> registry,
            CredentialStore credentials, ConnectionPoolManager pool,
            Label selectedCountLabel, Button selectAllButton, Runnable onSelectionChanged) {
        this.tree = tree;
        this.registry = registry;
        this.credentials = credentials;
        this.pool = pool;
        this.selectedCountLabel = selectedCountLabel;
        this.selectAllButton = selectAllButton;
        this.onSelectionChanged = onSelectionChanged;
    }

    // ------------------------------------------------------------------
    // Buscador
    // ------------------------------------------------------------------

    /**
     * Guarda el texto del buscador. <b>No reconstruye</b>: el controlador lo hace después
     * de una pausa en el tecleo (debounce, hallazgo B2) llamando a
     * {@link #refreshFromFilter()} — reconstruir en cada tecla eran 12 reconstrucciones
     * completas para escribir "bodega norte".
     */
    public void setFilterText(String text) {
        this.filterText = text;
    }

    /**
     * Si hay un filtro puesto. Reordenar con el buscador activo movería filas que el
     * usuario no está viendo (hallazgo D2), así que el controlador lo bloquea con esto.
     */
    public boolean isFilterActive() {
        return filterText != null && !filterText.isBlank();
    }

    // ------------------------------------------------------------------
    // Reconstrucción
    // ------------------------------------------------------------------

    /**
     * Reconstruye el árbol desde cero (siempre — no hay actualización incremental)
     * aplicando el filtro actual. Antes de tirar el árbol viejo guarda qué bases estaban
     * marcadas y las vuelve a marcar en el nuevo: sin esto, cada tecla del buscador
     * habría borrado la selección del usuario a medio armar una consulta masiva.
     */
    public void refresh() {
        rebuild(true);
    }

    /**
     * La reconstrucción que dispara el buscador — igual que {@link #refresh()} salvo que
     * NO reabre las filas de base que estaban expandidas. Ver {@link #rebuild} para el
     * motivo.
     */
    public void refreshFromFilter() {
        rebuild(false);
    }

    /**
     * {@code restoreExpandedDatabases} — si además de los grupos hay que volver a abrir
     * las filas de BASE que estaban expandidas.
     *
     * <p>Es {@code false} solo para el buscador, y por una razón concreta: expandir una
     * fila de base dispara su carga perezosa de esquema y una conexión de prueba.
     * Restaurarlas en cada pausa del buscador sería exactamente el hallazgo #1 de
     * {@code AUDITORIA_BUGS_RENDIMIENTO.md} otra vez. Para todos los demás caminos
     * (agregar/editar/borrar una base, moverla de grupo, descubrir, importar) sí se
     * restauran: son acciones puntuales del usuario, no una ráfaga por tecla.
     */
    private void rebuild(boolean restoreExpandedDatabases) {
        TreeItem<Object> oldRoot = tree.getRoot();
        Set<String> selectedIds = oldRoot == null ? Set.of() : Set.copyOf(selectedIds(oldRoot));
        Set<Object> expandedGroups = expandedGroups(oldRoot);
        Set<String> expandedDatabaseIds = restoreExpandedDatabases ? expandedDatabaseIds(oldRoot) : Set.of();
        int firstRow = firstVisibleRow();

        // oldRoot == null ⇒ primer armado (arranque): se le pasa null para que abra todos
        // los grupos, el comportamiento de siempre. De ahí en adelante manda lo que el
        // usuario haya dejado abierto o cerrado a mano.
        tree.setRoot(ConnectionTreeBuilder.buildRoot(
                registry.get(), filterText, credentials, pool, oldRoot == null ? null : expandedGroups));
        bindSelectionDependentUi();

        reselect(tree.getRoot(), selectedIds);
        expandDatabases(tree.getRoot(), expandedDatabaseIds);
        restoreScrollTo(firstRow);
    }

    /**
     * Todo lo que depende de "qué bases están marcadas", con UN SOLO recorrido del árbol:
     * el contador "N bases seleccionadas", el texto del botón Todas/Ninguna, y la segunda
     * línea del encabezado de la pestaña activa.
     *
     * <p>Antes eran dos métodos gemelos, cada uno con su propio recorrido completo y su
     * propio {@code Observable[]} — dos recorridos donde alcanza uno, en algo que corre en
     * cada reconstrucción, incluida cada pausa del buscador (hallazgos B2 y C4).
     *
     * <p>El texto de "Todas" reactivo viene de un hallazgo del usuario ("el texto no
     * cambia entre todas y ninguna") y reacciona tanto al clic del botón como a marcar o
     * desmarcar bases a mano.
     */
    private void bindSelectionDependentUi() {
        List<CheckBoxTreeItem<Object>> databaseItems = ConnectionTreeBuilder.collectDatabaseItems(tree.getRoot());
        Observable[] selectedProperties = databaseItems.stream()
                .map(CheckBoxTreeItem::selectedProperty)
                .toArray(Observable[]::new);

        selectedCountLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            long selected = databaseItems.stream().filter(CheckBoxTreeItem::isSelected).count();
            return selected == 1 ? "1 base seleccionada" : selected + " bases seleccionadas";
        }, selectedProperties));

        selectAllButton.textProperty().bind(Bindings.createStringBinding(() -> {
            boolean allSelected = !databaseItems.isEmpty() && databaseItems.stream().allMatch(CheckBoxTreeItem::isSelected);
            return allSelected ? "Ninguna" : "Todas";
        }, selectedProperties));

        // Listener suelto y no un binding: el encabezado no es una propiedad de texto que
        // se pueda atar (son dos Label dentro de un VBox, y además el valor depende de
        // CUÁL pestaña está activa). Los listeners mueren con estos TreeItem, que se tiran
        // enteros en la próxima reconstrucción — no se acumulan.
        for (Observable selectedProperty : selectedProperties) {
            selectedProperty.addListener(observable -> onSelectionChanged.run());
        }
        onSelectionChanged.run();
    }

    // ------------------------------------------------------------------
    // Selección
    // ------------------------------------------------------------------

    /**
     * Las bases marcadas ahora mismo en el árbol, en el orden en que aparecen. Es lo que
     * usan "Ejecutar", "Explicar plan", "Comparar" y el autocompletado.
     */
    public List<DatabaseEntry> selectedDatabases() {
        return selectedDatabases(tree.getRoot());
    }

    /** Ids de las bases marcadas ahora mismo — lo que la pestaña activa "ve". */
    public Set<String> capturedSelectedDatabaseIds() {
        return selectedIds(tree.getRoot());
    }

    /** Marca EXACTAMENTE estas bases y desmarca cualquier otra — al activar una pestaña, para que el árbol refleje su selección guardada. */
    public void applySelectedDatabaseIds(Set<String> ids) {
        selectExactly(tree.getRoot(), ids);
    }

    /** Si el árbol ya existe — al arrancar hay un momento en que todavía no. */
    public boolean isBuilt() {
        return tree.getRoot() != null;
    }

    // ------------------------------------------------------------------
    // Llevar la vista a algo que acaba de cambiar
    // ------------------------------------------------------------------

    /**
     * Deja la fila de {@code db} visible y seleccionada — se llama después de AGREGAR una
     * base (a mano o por descubrimiento). Sin esto, el usuario acaba de crear una base y
     * el árbol la deja fuera de pantalla, sin ninguna señal de dónde quedó: hay que ir a
     * buscarla entre las 50 que ya había. Expande el grupo que la contiene aunque
     * estuviera cerrado — acaba de meter algo ahí, esconderlo no ayudaría.
     */
    public void revealDatabase(DatabaseEntry db) {
        TreeItem<Object> root = tree.getRoot();
        if (root == null) {
            return;
        }
        for (TreeItem<Object> group : root.getChildren()) {
            for (TreeItem<Object> dbItem : group.getChildren()) {
                if (dbItem.getValue() == db) {
                    group.setExpanded(true);
                    int row = tree.getRow(dbItem);
                    if (row >= 0) {
                        tree.getSelectionModel().select(row);
                        Platform.runLater(() -> tree.scrollTo(row));
                    }
                    return;
                }
            }
        }
    }

    /** Deja visible la fila de un grupo tras moverlo — mismo criterio que {@link #revealDatabase}: después de mover algo, verlo donde quedó. */
    public void revealGroup(Server server) {
        TreeItem<Object> groupItem = findGroupItem(tree.getRoot(), server);
        if (groupItem == null) {
            return;
        }
        int row = tree.getRow(groupItem);
        if (row >= 0) {
            tree.getSelectionModel().select(row);
            Platform.runLater(() -> tree.scrollTo(row));
        }
    }

    /** La fila de {@code server} en el árbol actual, o {@code null} si no está visible — ver {@link #findGroupItem(TreeItem, Server)}. */
    public TreeItem<Object> findGroupItem(Server server) {
        return findGroupItem(tree.getRoot(), server);
    }

    /**
     * Índice de la primera fila visible, para dejar el scroll donde estaba después de
     * reconstruir — {@code TreeView} no lo expone, hay que preguntarle al
     * {@code VirtualFlow} de su skin. -1 si todavía no hay skin (antes del primer layout)
     * o si el árbol está vacío; entonces {@link #restoreScrollTo} no hace nada, que es el
     * comportamiento de siempre.
     */
    private int firstVisibleRow() {
        if (tree.lookup(".virtual-flow") instanceof VirtualFlow<?> flow) {
            IndexedCell<?> firstCell = flow.getFirstVisibleCell();
            if (firstCell != null) {
                return firstCell.getIndex();
            }
        }
        return -1;
    }

    /**
     * Devuelve el scroll a donde estaba (2026-09-10, reporte del usuario: "estoy probando
     * las conexiones de una nueva bd y le doy a ok, me lleva hasta el inicio de la primera
     * BD"). {@code setRoot} deja siempre el árbol arriba del todo, y con ~50 bases eso es
     * perder el lugar en cada cambio.
     *
     * <p>{@code Platform.runLater} porque {@code scrollTo} necesita que el árbol nuevo ya
     * haya pasado por un layout — en el mismo pulso no tiene efecto.
     */
    private void restoreScrollTo(int row) {
        if (row <= 0) {
            return;
        }
        Platform.runLater(() -> tree.scrollTo(row));
    }

    // ------------------------------------------------------------------
    // Recorridos puros sobre la raíz — testeables sin JavaFX
    // ------------------------------------------------------------------

    /**
     * Las bases marcadas bajo {@code root}.
     *
     * <p>El cast a {@link DatabaseEntry} es una suposición del diseño que documenta el
     * javadoc de {@code SchemaTreeNode}: los nodos de esquema NUNCA son
     * {@code CheckBoxTreeItem}. Existía copiada en cuatro sitios (hallazgo C4.2); vive acá
     * para que haya un solo lugar que tocar si cambiara.
     */
    static List<DatabaseEntry> selectedDatabases(TreeItem<Object> root) {
        return ConnectionTreeBuilder.collectDatabaseItems(root).stream()
                .filter(CheckBoxTreeItem::isSelected)
                .map(item -> (DatabaseEntry) item.getValue())
                .toList();
    }

    /** Ids de las bases marcadas, en orden de aparición. */
    static Set<String> selectedIds(TreeItem<Object> root) {
        return selectedDatabases(root).stream()
                .map(DatabaseEntry::id)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Marca exactamente las bases de {@code ids} y desmarca todas las demás. */
    static void selectExactly(TreeItem<Object> root, Set<String> ids) {
        for (CheckBoxTreeItem<Object> item : ConnectionTreeBuilder.collectDatabaseItems(root)) {
            item.setSelected(ids.contains(((DatabaseEntry) item.getValue()).id()));
        }
    }

    /**
     * Vuelve a marcar las bases de {@code ids} en un árbol recién armado. A diferencia de
     * {@link #selectExactly}, <b>solo marca, nunca desmarca</b> — así era el código antes
     * de separarlo, y sobre un árbol nuevo (que nace sin nada marcado) da lo mismo.
     */
    static void reselect(TreeItem<Object> root, Set<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        for (CheckBoxTreeItem<Object> item : ConnectionTreeBuilder.collectDatabaseItems(root)) {
            if (ids.contains(((DatabaseEntry) item.getValue()).id())) {
                item.setSelected(true);
            }
        }
    }

    /**
     * Los valores de las filas de AGRUPACIÓN abiertas — un {@link Server}, o la cadena del
     * encabezado "Sin grupo" ({@link ConnectionTreeBuilder#UNGROUPED_HEADER}). Solo se
     * recorre el primer nivel: pedirle los hijos a la raíz es gratis, sus hijos son los
     * grupos y son {@code TreeItem} planos, no perezosos.
     */
    static Set<Object> expandedGroups(TreeItem<Object> root) {
        Set<Object> expanded = new HashSet<>();
        if (root == null) {
            return expanded;
        }
        for (TreeItem<Object> group : root.getChildren()) {
            if (group.isExpanded()) {
                expanded.add(group.getValue());
            }
        }
        return expanded;
    }

    /**
     * Ids de las filas de BASE abiertas. Solo se LEE {@code isExpanded()} sobre ellas,
     * nunca {@code getChildren()} — eso dispararía su carga de esquema.
     */
    static Set<String> expandedDatabaseIds(TreeItem<Object> root) {
        Set<String> ids = new LinkedHashSet<>();
        if (root == null) {
            return ids;
        }
        for (TreeItem<Object> group : root.getChildren()) {
            for (TreeItem<Object> dbItem : group.getChildren()) {
                if (dbItem.isExpanded() && dbItem.getValue() instanceof DatabaseEntry db) {
                    ids.add(db.id());
                }
            }
        }
        return ids;
    }

    /**
     * Contraparte de {@link #expandedDatabaseIds} — {@code setExpanded(true)} acá SÍ
     * dispara la carga perezosa de esa base, que es justo lo que se quiere: el usuario la
     * tenía abierta.
     */
    static void expandDatabases(TreeItem<Object> root, Set<String> ids) {
        if (ids.isEmpty() || root == null) {
            return;
        }
        for (TreeItem<Object> group : root.getChildren()) {
            for (TreeItem<Object> dbItem : group.getChildren()) {
                if (dbItem.getValue() instanceof DatabaseEntry db && ids.contains(db.id())) {
                    dbItem.setExpanded(true);
                }
            }
        }
    }

    /**
     * La fila que representa a {@code server}, o la de "Sin grupo" si es {@code null}.
     * Devuelve {@code null} si ese grupo no está visible ahora mismo (por ejemplo,
     * filtrado por el buscador).
     */
    static TreeItem<Object> findGroupItem(TreeItem<Object> root, Server server) {
        if (root == null) {
            return null;
        }
        for (TreeItem<Object> group : root.getChildren()) {
            Object value = group.getValue();
            if (server == null
                    ? ConnectionTreeBuilder.UNGROUPED_HEADER.equals(value)
                    : value == server) {
                return group;
            }
        }
        return null;
    }
}
