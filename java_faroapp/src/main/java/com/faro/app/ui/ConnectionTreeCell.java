package com.faro.app.ui;

import java.util.function.Consumer;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;

import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;

/**
 * Celda del árbol de conexiones — renderiza tres tipos de fila distintos
 * ({@link Server}, {@link DatabaseEntry}, o el encabezado de texto plano
 * "Sin grupo") dentro del mismo {@code TreeView<Object>}, porque JavaFX no
 * exige que un árbol sea homogéneo: cada {@code TreeItem} lleva su propio
 * valor y esta celda decide cómo pintarlo según su tipo real. Solo las filas
 * de {@link DatabaseEntry} llevan casilla — un servidor es solo un
 * agrupador, seleccionar bases es lo único que afecta a una consulta (mismo
 * criterio que {@code database_check_row.dart} en la versión Flutter).
 *
 * <p><b>Los nodos de cada fila se construyen UNA sola vez, en el
 * constructor, y {@code updateItem} solo actualiza su contenido</b> — nunca
 * se crean {@code HBox}/{@code Button}/{@code CheckBox} nuevos en cada
 * llamada. Antes esta celda sí los recreaba en cada {@code updateItem}, y
 * eso causaba que la flecha de expandir/colapsar, el resaltado de
 * selección y el botón de editar necesitaran dos clics: si la celda se
 * reconstruye a mitad de un gesto de clic, el nodo que recibe
 * {@code MOUSE_PRESSED} deja de ser el mismo que recibe
 * {@code MOUSE_RELEASED} (fue reemplazado por uno nuevo), y JavaFX solo
 * sintetiza {@code MOUSE_CLICKED} cuando ambos ocurren sobre el mismo nodo
 * — por eso el primer clic "no hacía nada" y el segundo sí. Reusar los
 * mismos nodos y solo actualizar su texto/estilo/binding evita el problema
 * de raíz.
 */
public class ConnectionTreeCell extends TreeCell<Object> {

    /**
     * Debe coincidir exactamente con {@code fixedCellSize} del
     * {@code TreeView} en main-view.fxml. Sin esto, el contenido de cada
     * fila mide una altura distinta a la que JavaFX asume por el layout
     * virtualizado, y el árbol recalcula el layout en cada clic — se veía
     * como toda la lista parpadeando.
     */
    private static final double ROW_HEIGHT = 28;

    // -- Fila de servidor: construida una sola vez --
    private final Label serverNameLabel = new Label();
    private final Label serverCountLabel = new Label();
    private final HBox serverRow;

    // -- Fila de base de datos: construida una sola vez --
    private final CheckBox checkBox = new CheckBox();
    private final Circle statusDot = new Circle(3.5);
    private final Tooltip statusTooltip = new Tooltip();
    private final Label aliasLabel = new Label();
    private final Label unrestrictedBadge = new Label("SIN RESTRICCIONES");
    private final Label engineBadge = new Label();
    private final SVGPath editIcon = new SVGPath();
    private final StackPane editButton;
    private final SVGPath deleteIcon = new SVGPath();
    private final StackPane deleteButton;
    private final HBox databaseRow;

    // -- Encabezado de sección ("Sin grupo"): construido una sola vez --
    private final Label sectionHeaderLabel = new Label();

    // -- Menú contextual (clic derecho) de una fila de base — construido una sola vez --
    private final ContextMenu databaseContextMenu;

    private BooleanProperty boundCheckProperty;
    private DatabaseEntry editTarget;

    public ConnectionTreeCell(
            Consumer<DatabaseEntry> onEditRequested,
            Consumer<DatabaseEntry> onNewQueryRequested,
            Consumer<DatabaseEntry> onDeleteRequested,
            Consumer<DatabaseEntry> onDiscoverRequested) {
        serverNameLabel.getStyleClass().add("tree-server-name");
        HBox.setHgrow(serverNameLabel, Priority.ALWAYS);
        serverCountLabel.getStyleClass().add("tree-count");
        serverRow = new HBox(6, serverNameLabel, serverCountLabel);
        serverRow.setAlignment(Pos.CENTER_LEFT);
        fixHeight(serverRow);

        aliasLabel.getStyleClass().add("tree-db-name");
        HBox.setHgrow(aliasLabel, Priority.ALWAYS);
        aliasLabel.setCursor(Cursor.HAND);
        // Clic en el nombre marca/desmarca la casilla de esa base — el
        // usuario esperaba esto ("como yo le hice clic al nombre de la BD
        // debería marcarme la casilla"). Solo en aliasLabel (hermano de
        // checkBox, no ancestro) para no interferir con el propio manejo
        // de clic de la casilla — un handler a nivel de fila causaría un
        // doble-toggle que se cancela al hacer clic directo en la casilla.
        aliasLabel.setOnMouseClicked(event -> {
            event.consume();
            checkBox.setSelected(!checkBox.isSelected());
        });
        engineBadge.getStyleClass().add("tree-engine-badge");

        Tooltip.install(statusDot, statusTooltip);
        unrestrictedBadge.getStyleClass().add("tree-unrestricted-badge");
        unrestrictedBadge.setManaged(false);
        unrestrictedBadge.setVisible(false);

        editIcon.setContent(Icons.PENCIL);
        editIcon.getStyleClass().add("tree-edit-icon");
        editIcon.setScaleX(0.55);
        editIcon.setScaleY(0.55);
        editButton = new StackPane(editIcon);
        editButton.getStyleClass().add("tree-edit-button");
        editButton.setOnMouseClicked(event -> {
            event.consume();
            if (editTarget != null) {
                onEditRequested.accept(editTarget);
            }
        });

        // Bote de basura visible en la fila, junto al lápiz — antes no
        // existía NINGUNA forma de quitar una base ya agregada, ni acá ni
        // en el diálogo de editar (hallazgo real del usuario: "no veo la
        // opción de borrar una BD"). Mismo criterio de "sin gestos
        // escondidos" que ya rige el resto de esta celda — un ícono
        // siempre visible, no solo el ítem del menú contextual de abajo
        // (que se deja además, como atajo extra, igual que el doble clic
        // ya es atajo extra del lápiz).
        deleteIcon.setContent(Icons.TRASH);
        deleteIcon.getStyleClass().add("tree-edit-icon");
        deleteIcon.setScaleX(0.55);
        deleteIcon.setScaleY(0.55);
        deleteButton = new StackPane(deleteIcon);
        deleteButton.getStyleClass().add("tree-edit-button");
        deleteButton.setOnMouseClicked(event -> {
            event.consume();
            if (editTarget != null) {
                onDeleteRequested.accept(editTarget);
            }
        });

        databaseRow = new HBox(6, checkBox, statusDot, aliasLabel, unrestrictedBadge, engineBadge, editButton, deleteButton);
        databaseRow.setAlignment(Pos.CENTER_LEFT);
        databaseRow.setPadding(new Insets(1, 0, 1, 0));
        fixHeight(databaseRow);

        // Clic derecho en una fila de base — mismo patrón para las 3
        // acciones: "Nueva consulta para esta base" marca SOLO esa casilla
        // (desmarca las demás) y abre una pestaña de consulta nueva, para
        // no tener que adivinar cuál base marcar antes de escribir el SQL
        // (pedido explícito del usuario tras encontrar poco intuitivo el
        // botón "+" genérico). "Eliminar esta base" es un atajo extra al
        // bote de basura de la fila, no el único camino — ver el
        // comentario de deleteButton arriba. "Descubrir bases en esta
        // IP…" reusa el mismo diálogo del menú Conexiones de arriba, pero
        // precargado con el host de esta fila — antes solo existía la
        // versión genérica de arriba, sin partir de una base ya conocida.
        MenuItem newQueryItem = new MenuItem("Nueva consulta para esta base");
        newQueryItem.setOnAction(event -> {
            if (editTarget != null) {
                onNewQueryRequested.accept(editTarget);
            }
        });
        MenuItem discoverItem = new MenuItem("Descubrir bases en esta IP…");
        discoverItem.setOnAction(event -> {
            if (editTarget != null) {
                onDiscoverRequested.accept(editTarget);
            }
        });
        MenuItem deleteItem = new MenuItem("Eliminar esta base");
        deleteItem.setOnAction(event -> {
            if (editTarget != null) {
                onDeleteRequested.accept(editTarget);
            }
        });
        databaseContextMenu = new ContextMenu(newQueryItem, discoverItem, deleteItem);

        sectionHeaderLabel.getStyleClass().add("tree-section-label");
        fixHeight(sectionHeaderLabel);
    }

    /** {@code prefHeight == minHeight == maxHeight == ROW_HEIGHT}, para que no pueda haber mismatch con {@code fixedCellSize}. */
    private static void fixHeight(Region node) {
        node.setPrefHeight(ROW_HEIGHT);
        node.setMinHeight(ROW_HEIGHT);
        node.setMaxHeight(ROW_HEIGHT);
    }

    @Override
    protected void updateItem(Object item, boolean empty) {
        super.updateItem(item, empty);

        if (empty || item == null) {
            unbindCheckbox();
            editTarget = null;
            setContextMenu(null);
            setText(null);
            setGraphic(null);
            return;
        }

        if (item instanceof Server server) {
            unbindCheckbox();
            editTarget = null;
            setContextMenu(null);
            updateServerRow(server);
            setGraphic(serverRow);
        } else if (item instanceof DatabaseEntry db) {
            updateDatabaseRow(db);
            setContextMenu(databaseContextMenu);
            setGraphic(databaseRow);
        } else {
            unbindCheckbox();
            editTarget = null;
            setContextMenu(null);
            sectionHeaderLabel.setText(String.valueOf(item).toUpperCase());
            setGraphic(sectionHeaderLabel);
        }
        setText(null);
    }

    private void updateServerRow(Server server) {
        serverNameLabel.setText(server.name());
        serverCountLabel.setText(String.valueOf(server.databases().size()));
    }

    private void updateDatabaseRow(DatabaseEntry db) {
        // JavaFX puede llamar updateItem() para la MISMA fila varias veces
        // seguidas sin que su dato haya cambiado en absoluto (pasadas de
        // layout del VirtualFlow, no solo cambios reales de datos — está
        // documentado, no es un supuesto). Antes, este método desataba y
        // reataba el CheckBox y reescribía las listas de estilo de
        // statusDot/modeIcon SIEMPRE, sin condición — mutar un
        // ObservableList de estilos (aunque el resultado final sea
        // idéntico) fuerza una repasada de CSS en ese nodo. Si eso pasa a
        // la vez en varias filas visibles, se ve como el parpadeo de toda
        // la lista (candidato nuevo, distinto a la teoría de altura de
        // fila ya descartada — ver README). Ahora cada mutación solo
        // ocurre si el valor deseado es distinto del que ya está puesto.
        BooleanProperty newBoundProperty = getTreeItem() instanceof CheckBoxTreeItem<Object> checkItem
                ? checkItem.selectedProperty() : null;
        if (newBoundProperty != boundCheckProperty) {
            unbindCheckbox();
            if (newBoundProperty != null) {
                boundCheckProperty = newBoundProperty;
                checkBox.selectedProperty().bindBidirectional(boundCheckProperty);
            }
        }

        String statusStyleClass = "tree-status-dot-" + statusStyleSuffix(db);
        if (!statusDot.getStyleClass().contains(statusStyleClass)) {
            statusDot.getStyleClass().removeIf(c -> c.startsWith("tree-status-dot-"));
            statusDot.getStyleClass().add(statusStyleClass);
        }
        statusTooltip.setText(statusTooltipText(db));

        aliasLabel.setText(db.alias());
        engineBadge.setText(db.engine().badge());

        // Etiqueta "SIN RESTRICCIONES" — solo para bases que NO son de
        // solo lectura (igual que faro-java-prototipo.html: una base de
        // solo lectura no lleva ninguna marca extra, es el caso normal).
        boolean unrestricted = db.mode() != ServerMode.READ_ONLY;
        if (unrestricted != unrestrictedBadge.isVisible()) {
            unrestrictedBadge.setVisible(unrestricted);
            unrestrictedBadge.setManaged(unrestricted);
        }

        editTarget = db;
    }

    private void unbindCheckbox() {
        if (boundCheckProperty != null) {
            checkBox.selectedProperty().unbindBidirectional(boundCheckProperty);
            boundCheckProperty = null;
        }
    }

    private String statusStyleSuffix(DatabaseEntry db) {
        return switch (db.connectionStatus()) {
            case CONNECTED -> "connected";
            case FAILED -> "failed";
            case TESTING -> "testing";
            case UNKNOWN -> "unknown";
        };
    }

    private String statusTooltipText(DatabaseEntry db) {
        return switch (db.connectionStatus()) {
            case CONNECTED -> "Conexión: exitosa";
            case FAILED -> "Conexión: falló";
            case TESTING -> "Probando conexión…";
            case UNKNOWN -> "Conexión: nunca probada";
        };
    }
}
