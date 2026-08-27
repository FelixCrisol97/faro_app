package com.faro.app.ui;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;
import com.faro.app.ui.SchemaTreeNode.GenerateAction;
import com.faro.app.ui.SchemaTreeNode.Kind;

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
import javafx.scene.input.MouseButton;
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

    // -- Fila de categoría de esquema ("Tablas", "Vistas", etc.): construida una sola vez --
    private final Label schemaCategoryLabel = new Label();
    private final Label schemaCategoryCountLabel = new Label();
    private final HBox schemaCategoryRow;

    // -- Fila de objeto de esquema (una tabla/vista/función/procedimiento/trigger): construida una sola vez --
    private final SVGPath schemaItemIcon = new SVGPath();
    private final Label schemaItemLabel = new Label();
    private final HBox schemaItemRow;
    /** Vacío al construirse — {@code updateItem()} decide qué subconjunto de los 5 MenuItem de abajo le corresponde a cada fila vía {@link #menuItemsFor}, no todos aplican a todos los tipos. */
    private final ContextMenu schemaItemContextMenu = new ContextMenu();
    private final MenuItem generateSelectItem = new MenuItem("Generar SELECT");
    private final MenuItem generateInsertItem = new MenuItem("Generar INSERT");
    private final MenuItem generateUpdateItem = new MenuItem("Generar UPDATE");
    private final MenuItem generateDeleteItem = new MenuItem("Generar DELETE");
    private final MenuItem generateCreateItem = new MenuItem("Generar script CREATE");

    // -- Menú contextual (clic derecho) de una fila de base — construido una sola vez --
    private final ContextMenu databaseContextMenu;

    private BooleanProperty boundCheckProperty;
    private DatabaseEntry editTarget;
    /** Capturado junto con {@code editTarget} en cada {@code updateDatabaseRow} — solo lo usa "Recargar esquema", que necesita el {@code TreeItem} real (no solo el {@code DatabaseEntry}) para poder descartar y volver a pedir sus hijos. */
    private DatabaseTreeItem editTreeItem;
    private SchemaTreeNode.Item schemaItemTarget;

    public ConnectionTreeCell(
            Consumer<DatabaseEntry> onEditRequested,
            Consumer<DatabaseEntry> onNewQueryRequested,
            Consumer<DatabaseEntry> onDeleteRequested,
            Consumer<DatabaseEntry> onDiscoverRequested,
            BiConsumer<SchemaTreeNode.Item, GenerateAction> onGenerateRequested) {
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
        // "Recargar esquema" (2026-08-25, el usuario preguntó cómo recargar y no
        // existía ninguna forma real — el caché de SchemaIntrospector no tenía
        // invalidación, ni la app un control para pedirla) — descarta el caché de
        // ESTA base y vuelve a pedir Tablas/Vistas/Funciones/Procedimientos/
        // Triggers de verdad, sin importar si ya se habían cargado antes. No
        // depende de onXxxRequested (un Consumer<DatabaseEntry> hacia
        // MainController) porque necesita el TreeItem real, no solo el dato —
        // se resuelve directo acá con el mismo patrón que ya usa editTarget.
        MenuItem reloadSchemaItem = new MenuItem("Recargar esquema");
        reloadSchemaItem.setOnAction(event -> {
            if (editTreeItem != null) {
                editTreeItem.reloadSchema();
            }
        });
        databaseContextMenu = new ContextMenu(newQueryItem, discoverItem, reloadSchemaItem, deleteItem);

        sectionHeaderLabel.getStyleClass().add("tree-section-label");
        fixHeight(sectionHeaderLabel);

        // -- Fila de categoría de esquema ("Tablas 4", etc.) --
        schemaCategoryLabel.getStyleClass().add("tree-schema-category");
        HBox.setHgrow(schemaCategoryLabel, Priority.ALWAYS);
        schemaCategoryCountLabel.getStyleClass().add("tree-schema-count");
        schemaCategoryRow = new HBox(6, schemaCategoryLabel, schemaCategoryCountLabel);
        schemaCategoryRow.setAlignment(Pos.CENTER_LEFT);
        fixHeight(schemaCategoryRow);

        // -- Fila de objeto de esquema (tabla/vista/función/procedimiento/trigger) --
        schemaItemIcon.getStyleClass().add("tree-edit-icon");
        schemaItemIcon.setScaleX(0.55);
        schemaItemIcon.setScaleY(0.55);
        schemaItemLabel.getStyleClass().add("tree-schema-item");
        schemaItemRow = new HBox(6, schemaItemIcon, schemaItemLabel);
        schemaItemRow.setAlignment(Pos.CENTER_LEFT);
        fixHeight(schemaItemRow);
        // Menú "Generar…" — qué acciones aplican a cada fila depende de su tipo (ver
        // menuItemsFor(), llamado desde updateItem()): Tabla tiene las 5; Vista solo
        // SELECT+CREATE (no toda vista es escribible, "Generar UPDATE/INSERT/DELETE"
        // se queda fuera a propósito); Función/Procedimiento/Trigger solo CREATE — un
        // SELECT/CALL correcto ahí depende de la firma real (parámetros), fuera de
        // alcance a propósito (ver SchemaIntrospector). Doble clic en Tabla/Vista
        // dispara SELECT directo — atajo extra, nunca el único camino (mismo criterio
        // que el resto de esta celda).
        generateSelectItem.setOnAction(event -> fireGenerate(onGenerateRequested, GenerateAction.SELECT));
        generateInsertItem.setOnAction(event -> fireGenerate(onGenerateRequested, GenerateAction.INSERT));
        generateUpdateItem.setOnAction(event -> fireGenerate(onGenerateRequested, GenerateAction.UPDATE));
        generateDeleteItem.setOnAction(event -> fireGenerate(onGenerateRequested, GenerateAction.DELETE));
        // "Generar script CREATE" es un solo ítem para los 2 casos posibles (tabla vs.
        // el resto) — nunca conviven en la misma fila, así que basta decidir la acción
        // real al hacer clic, en vez de 2 MenuItem con el mismo texto.
        generateCreateItem.setOnAction(event -> {
            if (schemaItemTarget != null) {
                fireGenerate(onGenerateRequested,
                        schemaItemTarget.kind() == Kind.TABLES ? GenerateAction.CREATE_TABLE : GenerateAction.CREATE_SCRIPT);
            }
        });
        schemaItemRow.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && schemaItemTarget != null && isQueryable(schemaItemTarget.kind())) {
                onGenerateRequested.accept(schemaItemTarget, GenerateAction.SELECT);
            }
        });
    }

    private void fireGenerate(BiConsumer<SchemaTreeNode.Item, GenerateAction> onGenerateRequested, GenerateAction action) {
        if (schemaItemTarget != null) {
            onGenerateRequested.accept(schemaItemTarget, action);
        }
    }

    private static boolean isQueryable(Kind kind) {
        return kind == Kind.TABLES || kind == Kind.VIEWS;
    }

    /** Tabla: las 5. Vista: SELECT + CREATE (nunca INSERT/UPDATE/DELETE — no toda vista es escribible). Función/Procedimiento/Trigger/Tipo: solo CREATE (un SELECT/CALL o "instanciar" un tipo no tienen equivalente genérico seguro). */
    private List<MenuItem> menuItemsFor(Kind kind) {
        return switch (kind) {
            case TABLES -> List.of(generateSelectItem, generateInsertItem, generateUpdateItem, generateDeleteItem, generateCreateItem);
            case VIEWS -> List.of(generateSelectItem, generateCreateItem);
            case FUNCTIONS, PROCEDURES, TRIGGERS, TYPES -> List.of(generateCreateItem);
        };
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
            editTreeItem = null;
            schemaItemTarget = null;
            setContextMenu(null);
            setText(null);
            setGraphic(null);
            return;
        }

        if (item instanceof Server server) {
            unbindCheckbox();
            editTarget = null;
            editTreeItem = null;
            schemaItemTarget = null;
            setContextMenu(null);
            updateServerRow(server);
            setGraphic(serverRow);
        } else if (item instanceof DatabaseEntry db) {
            schemaItemTarget = null;
            editTreeItem = getTreeItem() instanceof DatabaseTreeItem dbTreeItem ? dbTreeItem : null;
            updateDatabaseRow(db);
            setContextMenu(databaseContextMenu);
            setGraphic(databaseRow);
        } else if (item instanceof SchemaTreeNode.Category category) {
            unbindCheckbox();
            editTarget = null;
            editTreeItem = null;
            schemaItemTarget = null;
            setContextMenu(null);
            schemaCategoryLabel.setText(category.kind().label());
            // Esquema progresivo (2026-08-25): Funciones/Procedimientos/Triggers/Tipos son
            // categorías perezosas (CategoryTreeItem) — mientras no se hayan expandido, su
            // conteo real todavía no se sabe (SchemaTreeNode.UNKNOWN_COUNT), así que se deja
            // en blanco en vez de imprimir "-1".
            schemaCategoryCountLabel.setText(
                    category.count() == SchemaTreeNode.UNKNOWN_COUNT ? "" : String.valueOf(category.count()));
            setGraphic(schemaCategoryRow);
        } else if (item instanceof SchemaTreeNode.Item schemaItem) {
            unbindCheckbox();
            editTarget = null;
            editTreeItem = null;
            schemaItemTarget = schemaItem;
            schemaItemIcon.setContent(isQueryable(schemaItem.kind()) ? Icons.TABLE : Icons.SETTINGS);
            schemaItemLabel.setText(schemaItem.name());
            schemaItemContextMenu.getItems().setAll(menuItemsFor(schemaItem.kind()));
            setContextMenu(schemaItemContextMenu);
            setGraphic(schemaItemRow);
        } else {
            unbindCheckbox();
            editTarget = null;
            editTreeItem = null;
            schemaItemTarget = null;
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
