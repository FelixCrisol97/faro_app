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
import javafx.scene.control.Label;
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
    private final SVGPath modeIcon = new SVGPath();
    private final Tooltip modeTooltip = new Tooltip();
    private final Label engineBadge = new Label();
    private final SVGPath editIcon = new SVGPath();
    private final StackPane editButton;
    private final HBox databaseRow;

    // -- Encabezado de sección ("Sin grupo"): construido una sola vez --
    private final Label sectionHeaderLabel = new Label();

    private BooleanProperty boundCheckProperty;
    private DatabaseEntry editTarget;

    public ConnectionTreeCell(Consumer<DatabaseEntry> onEditRequested) {
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
        modeIcon.setScaleX(0.5);
        modeIcon.setScaleY(0.5);
        Tooltip.install(modeIcon, modeTooltip);

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

        databaseRow = new HBox(6, checkBox, statusDot, aliasLabel, modeIcon, engineBadge, editButton);
        databaseRow.setAlignment(Pos.CENTER_LEFT);
        databaseRow.setPadding(new Insets(1, 0, 1, 0));
        fixHeight(databaseRow);

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
            setText(null);
            setGraphic(null);
            return;
        }

        if (item instanceof Server server) {
            unbindCheckbox();
            editTarget = null;
            updateServerRow(server);
            setGraphic(serverRow);
        } else if (item instanceof DatabaseEntry db) {
            updateDatabaseRow(db);
            setGraphic(databaseRow);
        } else {
            unbindCheckbox();
            editTarget = null;
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

        boolean readOnly = db.mode() == ServerMode.READ_ONLY;
        modeIcon.setContent(readOnly ? Icons.LOCK : Icons.LOCK_OPEN);
        String modeStyleClass = readOnly ? "tree-mode-icon-locked" : "tree-mode-icon-unlocked";
        if (!modeIcon.getStyleClass().contains(modeStyleClass)) {
            modeIcon.getStyleClass().removeIf(c -> c.startsWith("tree-mode-icon-"));
            modeIcon.getStyleClass().add(modeStyleClass);
        }
        modeTooltip.setText(readOnly ? "Solo lectura" : "Sin restricciones");

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
