package com.faro.app.ui;

import java.util.List;
import java.util.Locale;
import java.util.function.IntSupplier;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;
import com.faro.app.ui.SchemaTreeNode.GenerateAction;
import com.faro.app.ui.SchemaTreeNode.Kind;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

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

    // El alto de fila TIENE que coincidir con el fixedCellSize del TreeView. Si no, el
    // contenido mide distinto de lo que JavaFX asume por el layout virtualizado y el
    // árbol recalcula en cada clic — se veía como toda la lista parpadeando. Por eso ya
    // no está fijo en main-view.fxml: lo calcula rowHeight() y MainController se lo pone
    // al TreeView, para que no puedan desincronizarse (2026-09-14, hallazgo A9).
    //
    // Historia del valor, que explica la calibración de VERTICAL_PADDING_PX: 28→36→44
    // (2026-08-28). La fila de base pasó de 1 línea (alias) a 2 (alias + IP:puerto
    // apilados, ver aliasAndHostBox); 36px ya evitaba el corte, pero el usuario lo vio
    // "muy junto" y se subió a 44. Como fixedCellSize es del TreeView completo y no por
    // tipo de fila, TODAS las filas —servidor, categoría, objeto de esquema— usan el
    // mismo alto aunque tengan una sola línea: quedan con más aire, no rotas.

    /**
     * Tamaños reales de las dos líneas de una fila de base en {@code app.css}
     * ({@code .tree-db-name} y {@code .tree-db-host}) — base para {@link #rowHeight}.
     * Si esos literales cambian en la hoja, hay que cambiarlos acá.
     */
    private static final double NAME_FONT_SIZE_PX = 12.5;
    private static final double HOST_FONT_SIZE_PX = 9.5;
    /** Mismo piso que {@code Theme#scaledFontSize} — nunca calcular una fila para un tamaño ilegible. */
    private static final double MIN_FONT_SIZE_PX = 8;
    /**
     * Padding vertical de {@code databaseRow} (4+4) más el aire que el usuario pidió
     * explícitamente entre filas. Calibrado para que en el valor POR DEFECTO del slider
     * ({@code fontScaleDelta == -1}) la fórmula dé exactamente 44 px — el alto que ya se
     * ajustó a mano tres veces (28→36→44, la última porque se veía "muy junto") y que no
     * debe cambiar solo porque ahora se calcule en vez de estar fijo.
     */
    private static final double VERTICAL_PADDING_PX = 20;

    /**
     * Alto de fila para el tamaño de fuente EFECTIVO de la interfaz (2026-09-14,
     * hallazgo A9 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
     *
     * <p>Antes esto era la constante fija {@code 44}, duplicada además como
     * {@code fixedCellSize="44"} en el FXML. La fila de base tiene DOS líneas de texto
     * apiladas (alias + {@code host:puerto}) y las dos crecen con el slider de tamaño de
     * interfaz, pero el alto no: con el slider arriba del todo el contenido dejaba de
     * caber. Es el mismo modo de falla que ya se había corregido para el grid de
     * resultados el 2026-08-26 (texto de filas contiguas superpuesto), con la misma
     * fórmula — a esta fila simplemente nunca se le aplicó.
     *
     * <p>El interlineado 1.2 y la estructura del cálculo son los mismos que
     * {@code ResultsTableFactory#rowHeight}, a propósito: dos formas distintas de
     * calcular el alto de una fila en la misma app serían una invitación a que se
     * desincronicen.
     */
    public static double rowHeight(int fontScaleDelta) {
        double name = Math.max(MIN_FONT_SIZE_PX, NAME_FONT_SIZE_PX + fontScaleDelta);
        double host = Math.max(MIN_FONT_SIZE_PX, HOST_FONT_SIZE_PX + fontScaleDelta);
        return (name + host) * 1.2 + VERTICAL_PADDING_PX;
    }

    // -- Fila de servidor: construida una sola vez --
    private final Label serverNameLabel = new Label();
    private final Label serverCountLabel = new Label();
    private final HBox serverRow;

    // -- Fila de base de datos: construida una sola vez --
    private final CheckBox checkBox = new CheckBox();
    /** 3.5 → 5 (2026-08-28, pedido explícito del usuario, con captura: el punto se veía "muy chico" contra la casilla de 18px). */
    private final Circle statusDot = new Circle(5);
    private final Tooltip statusTooltip = new Tooltip();
    private final Label aliasLabel = new Label();
    private final Label hostLabel = new Label();
    /** Alias + IP:puerto apiladas (2026-08-28, a pedido del usuario — "creo se vería mejor que esté abajo o arriba del nombre de la BD"). Es la razón de que la fila necesite 2 líneas de alto — ver {@link #rowHeight}. */
    private final VBox aliasAndHostBox;
    /** Candado del modo (2026-08-28) — reemplaza el texto "SIN RESTRICCIONES"/"SOLO LECTURA" de antes, pedido explícito del usuario ("se me hace muy [pesado], hay forma de usar iconos"). Cerrado = solo lectura, abierto = sin restricciones — mismo lenguaje visual que Lucide `lock`/`lock-open` (ver Icons.java), con tooltip para quien de verdad necesite el texto exacto. */
    private final SVGPath modeIcon = new SVGPath();
    private final Tooltip modeTooltip = new Tooltip();
    private final Label engineBadge = new Label();
    private final SVGPath editIcon = new SVGPath();
    private final StackPane editButton;
    private final SVGPath deleteIcon = new SVGPath();
    private final StackPane deleteButton;
    /**
     * checkBox + statusDot agrupados aparte (2026-08-28, pedido explícito del
     * usuario, con captura: "el círculo... debe estar alineado" — antes ambos
     * eran hijos DIRECTOS de databaseRow con TOP_LEFT, así que un Circle de 10px
     * (statusDot) y una casilla de 18px (checkBox) alineaban por el borde
     * SUPERIOR, no por el centro — el punto quedaba "flotando" arriba en vez de
     * centrado contra la casilla). CENTER_LEFT adentro de este sub-HBox los
     * centra entre sí (su alto lo define el más alto de los dos, checkBox); el
     * databaseRow de afuera sigue en TOP_LEFT, así que este grupo completo se
     * alinea contra la PRIMERA línea de aliasAndHostBox (el alias), no contra el
     * bloque de 2 líneas entero.
     */
    private final HBox leadingIconsBox;
    /** Misma razón que {@link #leadingIconsBox}, para el candado/badge/lápiz/basura del lado derecho. */
    private final HBox trailingIconsBox;
    private final HBox databaseRow;

    // -- Encabezado de sección ("Sin grupo"): construido una sola vez --
    private final Label sectionHeaderLabel = new Label();

    // -- Fila de categoría de esquema ("Tablas", "Vistas", etc.): construida una sola vez --
    private final Label schemaCategoryLabel = new Label();
    private final Label schemaCategoryCountLabel = new Label();
    private final HBox schemaCategoryRow;

    // -- Fila de "cargando…" / error de esquema: construida una sola vez --
    /**
     * Animación real de carga (2026-09-07, pedido explícito del usuario) —
     * {@code ProgressIndicator} indeterminado, que gira solo mientras esté
     * visible; antes esta fila era texto pelado ("Cargando esquema…") y un fetch
     * lento se veía igual que un árbol trabado. Se construye una sola vez y se
     * reusa, como todo el resto de esta celda.
     */
    private final ProgressIndicator loadingSpinner = new ProgressIndicator();
    private final Label loadingLabel = new Label();
    private final HBox loadingRow;
    private final Label schemaErrorLabel = new Label();
    /** Creado e instalado UNA vez, como {@code statusTooltip}/{@code modeTooltip} — ver el comentario en la rama de {@code SchemaTreeNode.Error} de {@link #updateItem}. */
    private final Tooltip schemaErrorTooltip = new Tooltip();

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
    /** Comparar este objeto entre todas las bases marcadas — ver {@code SchemaComparisonService}. */
    private final MenuItem compareItem = new MenuItem("Comparar en las bases marcadas…");

    // -- Menú contextual (clic derecho) de una fila de base — construido una sola vez --
    private final ContextMenu databaseContextMenu;
    /** Menú contextual de una fila de GRUPO (2026-09-11) — ver el constructor. */
    private final ContextMenu serverContextMenu;
    /** Menú contextual del encabezado "Sin grupo" — un subconjunto del de grupo, ver el constructor. */
    private final ContextMenu sectionHeaderContextMenu;

    private BooleanProperty boundCheckProperty;
    private DatabaseEntry editTarget;
    /** Capturado junto con {@code editTarget} en cada {@code updateDatabaseRow} — solo lo usa "Recargar esquema", que necesita el {@code TreeItem} real (no solo el {@code DatabaseEntry}) para poder descartar y volver a pedir sus hijos. */
    private DatabaseTreeItem editTreeItem;
    /** El {@link Server} que muestra esta celda ahora mismo, o null — lo leen los ítems de {@code serverContextMenu}, igual que {@code editTarget} para los de la fila de base. */
    private Server serverTarget;
    private SchemaTreeNode.Item schemaItemTarget;

    /**
     * Ver el javadoc de {@link DatabaseEntry#connectionStatusProperty()} — a
     * diferencia del resto de esta celda (que solo LEE datos frescos en cada
     * {@code updateItem()}), el punto de color necesita reaccionar SOLO
     * cuando cambia, sin esperar a que la celda se repinte por otro motivo
     * (2026-08-28, pedido explícito del usuario: "estos círculos de conexión
     * deberían estar sincronizados"). {@code statusListenerTarget} rastrea a
     * cuál {@code DatabaseEntry} están enganchados los 2 listeners de abajo
     * ahora mismo, para desengancharlos del anterior antes de reusar esta
     * celda con una base distinta — mismo motivo por el que
     * {@code boundCheckProperty} existe para la casilla.
     */
    private DatabaseEntry statusListenerTarget;
    private final ChangeListener<DatabaseEntry.ConnectionStatus> connectionStatusListener =
            (obs, oldStatus, newStatus) -> refreshStatusDot(statusListenerTarget);
    private final ChangeListener<Boolean> inUseListener =
            (obs, wasInUse, isInUse) -> refreshInUseAnimation(statusListenerTarget);
    /** Pulso de opacidad mientras {@link DatabaseEntry#isInUse()} — ver {@link #refreshInUseAnimation}. */
    private final FadeTransition inUsePulse = new FadeTransition(Duration.millis(600), statusDot);

    /** Ver {@link ConnectionTreeActions} — agrupa todo lo que esta celda le pide a MainController. */
    private final ConnectionTreeActions actions;

    /**
     * Tamaño de fuente de la interfaz vigente ahora mismo
     * ({@code AppPreferences#fontScaleDelta}) — un {@link IntSupplier}, no un
     * {@code int}, a propósito: la celda se construye una sola vez por fábrica y
     * tiene que leer el valor ACTUAL en cada repintado, no el que había cuando
     * se creó. Ver {@link #applyDisclosureScale()} y {@link #fixHeight}.
     */
    private final IntSupplier fontScaleDelta;

    public ConnectionTreeCell(ConnectionTreeActions actions, IntSupplier fontScaleDelta) {
        this.actions = actions;
        this.fontScaleDelta = fontScaleDelta;
        compareItem.setOnAction(event -> {
            if (schemaItemTarget != null) {
                actions.onCompare().accept(schemaItemTarget);
            }
        });
        serverNameLabel.getStyleClass().add("tree-server-name");
        HBox.setHgrow(serverNameLabel, Priority.ALWAYS);
        serverCountLabel.getStyleClass().add("tree-count");
        serverRow = new HBox(6, serverNameLabel, serverCountLabel);
        serverRow.setAlignment(Pos.CENTER_LEFT);
        fixHeight(serverRow);

        // Preventivo (2026-08-28, mismo bug que aliasLabel/modeIcon/schemaItemRow,
        // no reportado todavía para la casilla en sí pero mismo riesgo real: un
        // MOUSE_CLICKED que llega hasta acá sin consumir sube hasta
        // MainController#onTreeClicked y abre "Editar base" en cualquier doble
        // clic). CheckBox ya alterna su propio estado por su cuenta (Skin interno,
        // no manejado a mano acá) — este handler solo CONSUME, no toca
        // selectedProperty, para no interferir con eso.
        checkBox.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });

        aliasLabel.getStyleClass().add("tree-db-name");
        hostLabel.getStyleClass().add("tree-db-host");
        // Alias arriba, IP:puerto abajo — apiladas, no lado a lado (probado en la
        // misma línea primero, el usuario prefirió esto). El hgrow vive en esta
        // VBox, no en ninguno de los 2 Label — así el hueco de espacio libre queda
        // DESPUÉS de las 2 líneas (mismo lugar de siempre, antes de los badges/
        // íconos de la derecha), no separando visualmente el alias de su IP.
        aliasAndHostBox = new VBox(0, aliasLabel, hostLabel);
        HBox.setHgrow(aliasAndHostBox, Priority.ALWAYS);
        aliasLabel.setCursor(Cursor.HAND);
        // Clic sencillo en el nombre marca/desmarca la casilla de esa base ("como yo
        // le hice clic al nombre de la BD debería marcarme la casilla", pedido
        // explícito del usuario) — doble clic abre Editar base. Los dos SOLO acá, en
        // el texto del alias, no en ningún nivel más alto de la fila/el árbol
        // (2026-08-28, pedido explícito del usuario tras encontrar que un manejador
        // global de doble clic en `MainController` reaccionaba a CUALQUIER parte de
        // la fila — candado, filas de esquema, etc. — no solo al texto: "no hay
        // forma de quitar el evento global... que solo se active... cuando esté en
        // el texto solamente"). `event.consume()` corre siempre que el clic aterriza
        // acá (se actúe o no sobre él) para que nunca se filtre hacia el árbol
        // entero — mismo criterio en {@code modeIcon}/{@code checkBox}/
        // {@code schemaItemRow} más abajo. Solo botón primario — el derecho abre el
        // menú contextual, no debe tocar la casilla ni abrir Editar.
        //
        // MOUSE_PRESSED/RELEASED (2026-08-28, mismo bug real que ya apareció en
        // modeIcon, ahora confirmado acá también: "el doble clic sobre la BD
        // despliega el esquema pero también abre la ventana de editar BD al mismo
        // tiempo") — expandir/colapsar la fila es comportamiento NATIVO de
        // TreeCell, actúa en MOUSE_PRESSED, antes de que MOUSE_CLICKED exista.
        // Consumir solo en setOnMouseClicked (más abajo) llega tarde. Ver el
        // comentario largo en modeIcon para el detalle completo de por qué esto
        // funciona sin romper el propio MOUSE_CLICKED de este mismo nodo.
        aliasLabel.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });
        aliasLabel.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });
        aliasLabel.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            event.consume();
            if (event.getClickCount() == 1) {
                checkBox.setSelected(!checkBox.isSelected());
            } else if (event.getClickCount() == 2 && editTarget != null) {
                actions.onEdit().accept(editTarget);
            }
        });
        engineBadge.getStyleClass().add("tree-engine-badge");

        Tooltip.install(statusDot, statusTooltip);
        // Pulso de opacidad (2026-08-28, pedido explícito del usuario) mientras una
        // base tiene una consulta corriendo — ver DatabaseEntry#inUse y
        // refreshInUseAnimation. 0.35 de piso (no 0, para que el punto nunca
        // desaparezca del todo — sigue siendo el indicador de a qué estado de
        // conexión real va a volver cuando termine), 600ms por medio ciclo, vaivén
        // indefinido mientras dure la consulta.
        inUsePulse.setFromValue(1.0);
        inUsePulse.setToValue(0.35);
        inUsePulse.setCycleCount(Animation.INDEFINITE);
        inUsePulse.setAutoReverse(true);
        modeIcon.getStyleClass().add("tree-mode-icon");
        modeIcon.setScaleX(0.55);
        modeIcon.setScaleY(0.55);
        // Sin esto (2026-08-28, bug real: "el botón del candado no siempre
        // reacciona") — un SVGPath con relleno transparente (trazo nada más, ver
        // .tree-mode-icon en app.css) solo es "clickeable" en el trazo mismo por
        // defecto, no en toda su caja — clics que caen en el hueco de adentro del
        // candado (la mayoría, el trazo es angosto) no tocaban ningún nodo real y
        // el evento se perdía sin llegar a ningún handler. editIcon/deleteIcon no
        // sufren esto porque su clic vive en el StackPane que los envuelve
        // (editButton/deleteButton, con área rectangular normal), no en el SVGPath
        // directo como acá. pickOnBounds hace que TODA la caja del ícono (no solo
        // el trazo) sea clickeable.
        modeIcon.setPickOnBounds(true);
        Tooltip.install(modeIcon, modeTooltip);
        // Clic en el candado alterna el modo directo, sin abrir el diálogo de editar
        // (2026-08-28, pedido explícito del usuario). Mismo chequeo de botón primario
        // Y de clickCount que aliasLabel arriba, MISMA razón para consumir siempre
        // que aterrice acá (evita que "abre y cierra la BD al mismo tiempo" se
        // convierta también en "abre la ventana de Editar" — ver el comentario
        // largo en aliasLabel más arriba, mismo bug, mismo arreglo).
        modeIcon.setCursor(Cursor.HAND);
        // Sin esto (2026-08-28, bug real: "doy doble clic [en el candado] y se
        // despliega la BD") — el doble clic para expandir/colapsar una fila del
        // árbol es comportamiento NATIVO de JavaFX (TreeCell), no algo de esta
        // app, y actúa en MOUSE_PRESSED — antes de que MOUSE_CLICKED siquiera se
        // sintetice. Consumir el clic en setOnMouseClicked (más abajo) llega
        // tarde: el expandir/colapsar ya pasó. Consumiendo PRESSED/RELEASED acá
        // (antes de que suban al TreeCell) evita que la fila reaccione, sin
        // afectar el propio MOUSE_CLICKED de este nodo (JavaFX lo sintetiza
        // igual, aunque PRESSED/RELEASED se hayan consumido). Solo botón
        // primario, para no interferir con el clic derecho que abre el menú
        // contextual.
        modeIcon.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });
        modeIcon.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });
        modeIcon.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            event.consume();
            if (event.getClickCount() == 1 && editTarget != null) {
                actions.onToggleMode().accept(editTarget);
            }
        });

        editIcon.setContent(Icons.PENCIL);
        editIcon.getStyleClass().add("tree-edit-icon");
        editIcon.setScaleX(0.55);
        editIcon.setScaleY(0.55);
        editButton = new StackPane(editIcon);
        editButton.getStyleClass().add("tree-edit-button");
        editButton.setOnMouseClicked(event -> {
            event.consume();
            if (editTarget != null) {
                actions.onEdit().accept(editTarget);
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
                actions.onDelete().accept(editTarget);
            }
        });

        leadingIconsBox = new HBox(6, checkBox, statusDot);
        leadingIconsBox.setAlignment(Pos.CENTER_LEFT);
        trailingIconsBox = new HBox(6, modeIcon, engineBadge, editButton, deleteButton);
        trailingIconsBox.setAlignment(Pos.CENTER_LEFT);

        databaseRow = new HBox(6, leadingIconsBox, aliasAndHostBox, trailingIconsBox);
        // TOP_LEFT, no CENTER_LEFT (2026-08-28, pedido explícito del usuario, con
        // captura: "el checkbox está abajo de la BD, debería estar al mismo nivel
        // que el nombre") — la fila tiene 2 líneas de texto apiladas (alias + IP,
        // ver aliasAndHostBox) desde que se agregó la IP; CENTER_LEFT centraba la
        // casilla/candado/badges/íconos contra el ALTO TOTAL de la fila (44px), que
        // cae justo en el hueco ENTRE las 2 líneas, no contra el alias. TOP_LEFT los
        // alinea con la primera línea (el alias), que es el nivel que se espera —
        // leadingIconsBox/trailingIconsBox (ver su javadoc) resuelven el resto:
        // que checkBox/statusDot (y candado/badge/lápiz/basura) se centren ENTRE
        // SÍ, no solo contra el alias.
        databaseRow.setAlignment(Pos.TOP_LEFT);
        databaseRow.setPadding(new Insets(4, 0, 4, 0));
        // Red de seguridad a nivel de TODA la fila (2026-08-28) — el mismo bug de
        // expandir/colapsar nativo en MOUSE_PRESSED seguía filtrándose incluso
        // después de arreglarlo en aliasLabel: la fila ahora tiene 2 líneas (alias +
        // IP, ver aliasAndHostBox) con más alto que antes, y hay zonas reales sin
        // ningún manejador propio (el hueco vertical entre las 2 líneas, el propio
        // texto de la IP, el margen extra del padding) que dejaban pasar el clic
        // directo hacia TreeCell. En vez de seguir agregando el mismo parche nodo
        // por nodo cada vez que aparece un hueco nuevo, esto consume CUALQUIER
        // clic primario que llegue hasta acá sin haber sido ya consumido por un
        // hijo más específico (checkBox/aliasLabel/modeIcon/editButton/
        // deleteButton, que siguen actuando igual — bubbling ya los dejó actuar
        // antes de llegar hasta acá).
        databaseRow.setOnMousePressed(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });
        databaseRow.setOnMouseReleased(event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
            }
        });
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
                actions.onNewQuery().accept(editTarget);
            }
        });
        MenuItem discoverItem = new MenuItem("Descubrir bases en esta IP…");
        discoverItem.setOnAction(event -> {
            if (editTarget != null) {
                actions.onDiscover().accept(editTarget);
            }
        });
        MenuItem deleteItem = new MenuItem("Eliminar esta base");
        deleteItem.setOnAction(event -> {
            if (editTarget != null) {
                actions.onDelete().accept(editTarget);
            }
        });
        // "Mover a grupo…" (2026-08-28, pedido explícito del usuario, con imagen de
        // referencia: "no veo el tema de poder agrupar las BD") — antes NO existía
        // ninguna forma de mover una base a un servidor/grupo desde la UI (los
        // servidores solo se podían crear en el JSON a mano); ver también
        // MainController#onNewGroup para crear el grupo en sí.
        MenuItem moveToGroupItem = new MenuItem("Mover a grupo…");
        moveToGroupItem.setOnAction(event -> {
            if (editTarget != null) {
                actions.onMoveToGroup().accept(editTarget);
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
        // Subir/Bajar de una base (2026-09-11, pedido del usuario: "tampoco puedo mover
        // las bd y grupos de acuerdo al orden que yo quiera"). Mueve dentro de SU lista
        // —su grupo, o las sueltas— sin sacarla de ahí; cambiar de grupo sigue siendo
        // "Mover a grupo…", que es otra cosa. Ver ConnectionRegistry#moveDatabase.
        MenuItem moveDatabaseUpItem = new MenuItem("Subir");
        moveDatabaseUpItem.setOnAction(event -> {
            if (editTarget != null) {
                actions.onMoveDatabase().accept(editTarget, -1);
            }
        });
        MenuItem moveDatabaseDownItem = new MenuItem("Bajar");
        moveDatabaseDownItem.setOnAction(event -> {
            if (editTarget != null) {
                actions.onMoveDatabase().accept(editTarget, 1);
            }
        });
        databaseContextMenu = new ContextMenu(newQueryItem, discoverItem, reloadSchemaItem,
                new SeparatorMenuItem(), moveDatabaseUpItem, moveDatabaseDownItem, moveToGroupItem,
                new SeparatorMenuItem(), deleteItem);

        // -- Menú contextual de una fila de GRUPO (2026-09-11) --
        // Antes las filas de grupo no tenían menú (setContextMenu(null)): no había forma
        // de renombrar un grupo, de marcar/desmarcar solo sus bases —el botón "Todas" de
        // arriba es global— ni de cambiarlas de orden.
        serverContextMenu = new ContextMenu(
                groupItem("Marcar todas las de este grupo", () -> actions.onSetGroupSelection().accept(serverTarget, true)),
                groupItem("Desmarcar todas las de este grupo", () -> actions.onSetGroupSelection().accept(serverTarget, false)),
                new SeparatorMenuItem(),
                groupItem("Renombrar grupo…", () -> actions.onRenameGroup().accept(serverTarget)),
                new SeparatorMenuItem(),
                groupItem("Subir", () -> actions.onMoveGroup().accept(serverTarget, -1)),
                groupItem("Bajar", () -> actions.onMoveGroup().accept(serverTarget, 1)),
                groupItem("Ordenar sus bases A-Z", () -> actions.onSortGroup().accept(serverTarget)));

        // El encabezado "Sin grupo" NO es un Server (es la ausencia de grupo, ver el
        // javadoc de Server), así que no se puede renombrar ni mover — pero marcar sus
        // bases y ordenarlas sí tiene el mismo sentido. serverTarget queda en null ahí,
        // que es justo lo que ConnectionRegistry#sortDatabasesByAlias entiende como
        // "las sueltas".
        sectionHeaderContextMenu = new ContextMenu(
                groupItem("Marcar todas las de aquí", () -> actions.onSetGroupSelection().accept(null, true)),
                groupItem("Desmarcar todas las de aquí", () -> actions.onSetGroupSelection().accept(null, false)),
                new SeparatorMenuItem(),
                groupItem("Ordenar A-Z", () -> actions.onSortGroup().accept(null)));

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

        // Spinner de carga real (2026-09-07) — tamaño explícito y chico: un
        // ProgressIndicator sin tamaño fijo toma el default de Modena (bastante más
        // grande que una fila del árbol) y desbordaría los 44px de alto.
        loadingSpinner.setPrefSize(14, 14);
        loadingSpinner.setMinSize(14, 14);
        loadingSpinner.setMaxSize(14, 14);
        loadingSpinner.getStyleClass().add("tree-loading-spinner");
        loadingLabel.getStyleClass().add("tree-loading-label");
        loadingRow = new HBox(7, loadingSpinner, loadingLabel);
        loadingRow.setAlignment(Pos.CENTER_LEFT);
        fixHeight(loadingRow);

        schemaErrorLabel.getStyleClass().add("tree-schema-error");
        schemaErrorLabel.setWrapText(false);
        Tooltip.install(schemaErrorLabel, schemaErrorTooltip);
        // Menú "Generar…" — qué acciones aplican a cada fila depende de su tipo (ver
        // menuItemsFor(), llamado desde updateItem()): Tabla tiene las 5; Vista solo
        // SELECT+CREATE (no toda vista es escribible, "Generar UPDATE/INSERT/DELETE"
        // se queda fuera a propósito); Función/Procedimiento/Trigger solo CREATE — un
        // SELECT/CALL correcto ahí depende de la firma real (parámetros), fuera de
        // alcance a propósito (ver SchemaIntrospector). Doble clic en Tabla/Vista
        // dispara SELECT directo — atajo extra, nunca el único camino (mismo criterio
        // que el resto de esta celda).
        generateSelectItem.setOnAction(event -> fireGenerate(GenerateAction.SELECT));
        generateInsertItem.setOnAction(event -> fireGenerate(GenerateAction.INSERT));
        generateUpdateItem.setOnAction(event -> fireGenerate(GenerateAction.UPDATE));
        generateDeleteItem.setOnAction(event -> fireGenerate(GenerateAction.DELETE));
        // "Generar script CREATE" es un solo ítem para los 2 casos posibles (tabla vs.
        // el resto) — nunca conviven en la misma fila, así que basta decidir la acción
        // real al hacer clic, en vez de 2 MenuItem con el mismo texto.
        generateCreateItem.setOnAction(event -> {
            if (schemaItemTarget != null) {
                fireGenerate(schemaItemTarget.kind() == Kind.TABLES
                        ? GenerateAction.CREATE_TABLE : GenerateAction.CREATE_SCRIPT);
            }
        });
        // NUNCA consumía nada (2026-08-28, mismo bug real que aliasLabel/modeIcon,
        // reportado por el usuario: "al desplegar... tablas, vistas, etc, cualquiera
        // de esos componentes al hacer doble clic sobre ellos también abren la
        // ventana de editar BD") — un doble clic sin consumir acá subía hasta
        // `MainController#onTreeClicked` (nivel de todo el árbol), que abre "Editar
        // base" en cualquier doble clic mientras una base siga con el resaltado de
        // fila del TreeView (que no cambia solo por expandir/clickear su esquema).
        // `event.consume()` siempre que el clic sea primario, se dispare o no
        // "Generar SELECT" — mismo criterio que el resto de esta celda.
        schemaItemRow.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            event.consume();
            if (event.getClickCount() == 2 && schemaItemTarget != null && isQueryable(schemaItemTarget.kind())) {
                actions.onGenerate().accept(schemaItemTarget, GenerateAction.SELECT);
            }
        });
    }

    /**
     * Un {@code MenuItem} de los menús de grupo — todos siguen el mismo patrón (texto
     * fijo, acción que lee {@code serverTarget} al dispararse, no al construirse), así
     * que escribirlos uno por uno sería repetir cuatro líneas ocho veces.
     */
    private static MenuItem groupItem(String text, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(event -> action.run());
        return item;
    }

    private void fireGenerate(GenerateAction action) {
        if (schemaItemTarget != null) {
            actions.onGenerate().accept(schemaItemTarget, action);
        }
    }

    private static boolean isQueryable(Kind kind) {
        return kind == Kind.TABLES || kind == Kind.VIEWS;
    }

    /*
     * Tabla: las 5. Vista: SELECT + CREATE (nunca INSERT/UPDATE/DELETE — no toda vista
     * es escribible). Función/Procedimiento/Trigger/Tipo: solo CREATE (un SELECT/CALL o
     * "instanciar" un tipo no tienen equivalente genérico seguro).
     */
    /**
     * {@link #compareItem} va en TODOS los tipos (2026-09-07) — a diferencia de
     * "Generar…", comparar tiene sentido para cualquier objeto de esquema: el
     * usuario quiere verificar que la misma función/tabla/trigger sea idéntica en
     * todas las bodegas, sin importar de qué tipo sea.
     */
    private List<MenuItem> menuItemsFor(Kind kind) {
        return switch (kind) {
            case TABLES -> List.of(generateSelectItem, generateInsertItem, generateUpdateItem, generateDeleteItem,
                    generateCreateItem, compareItem);
            case VIEWS -> List.of(generateSelectItem, generateCreateItem, compareItem);
            case FUNCTIONS, PROCEDURES, TRIGGERS, TYPES -> List.of(generateCreateItem, compareItem);
        };
    }

    /**
     * Tamaño base de la flecha de expandir, como múltiplo de la que trae Modena
     * (2026-09-07, pedido del usuario: "se ven muy pequeñas"). La anterior estaba
     * en 0.85 — más chica que la de fábrica — para que no compitiera con los
     * íconos de la fila; 1.25 la deja claramente visible sin desbalancear la
     * fila, que mide 44px.
     */
    private static final double DISCLOSURE_BASE_SCALE = 1.25;

    /**
     * Tamaño de fuente base de la interfaz, el que corresponde a
     * {@code fontScaleDelta == 0} — es el mismo 13px que usa
     * {@code ResultsTableFactory#BASE_FONT_SIZE_PX} para calcular la altura de
     * fila del grid. La flecha crece/decrece en la MISMA proporción que el texto:
     * con el slider en +5 el texto pasa de 13 a 18px (×1.38) y la flecha también.
     */
    private static final double BASE_FONT_SIZE_PX = 13;

    /**
     * Escala la flecha de expandir con el tamaño de fuente de la interfaz
     * (2026-09-07, pedido del usuario: "capaz y habría que ajustar su tamaño
     * dinámicamente así como lo hacemos con el tamaño de fuente de la app").
     *
     * <p>Va en Java y no en CSS porque el mecanismo de escalado de esta app
     * reescribe literales de {@code -fx-font-size} en una copia de {@code app.css}
     * (ver {@code Theme#scaledAppCssUri}) — un {@code -fx-scale-*} no es uno de
     * ellos, y {@code -fx-font-size} no admite variables en JavaFX, así que no
     * había forma de expresar esto en la hoja. El nodo de la flecha lo crea el
     * {@code Skin} del {@code TreeCell}, puede no existir todavía en los primeros
     * repintados — de ahí el chequeo de nulo, no es defensivo de más.
     */
    private void applyDisclosureScale() {
        javafx.scene.Node disclosure = getDisclosureNode();
        if (disclosure == null) {
            return;
        }
        double scale = DISCLOSURE_BASE_SCALE
                * Math.max(0.5, (BASE_FONT_SIZE_PX + fontScaleDelta.getAsInt()) / BASE_FONT_SIZE_PX);
        if (disclosure.getScaleX() != scale) {
            disclosure.setScaleX(scale);
            disclosure.setScaleY(scale);
        }
    }

    /**
     * Fija el alto de una fila al calculado para el tamaño de fuente vigente.
     *
     * <p>Ya no es estático ni constante: lee {@link #fontScaleDelta}, el mismo
     * {@code IntSupplier} que usa {@link #applyDisclosureScale()}. Se vuelve a aplicar
     * en cada {@code updateItem} porque el usuario puede mover el slider con el árbol
     * ya dibujado, y las celdas se reciclan sin reconstruirse.
     */
    private void fixHeight(Region node) {
        double height = rowHeight(fontScaleDelta.getAsInt());
        if (node.getPrefHeight() == height) {
            return;
        }
        node.setPrefHeight(height);
        node.setMinHeight(height);
        node.setMaxHeight(height);
    }

    /** Reaplica el alto a las filas ya construidas — ver {@link #fixHeight}. */
    private void applyRowHeight() {
        fixHeight(serverRow);
        fixHeight(databaseRow);
        fixHeight(sectionHeaderLabel);
        fixHeight(schemaCategoryRow);
        fixHeight(schemaItemRow);
        fixHeight(loadingRow);
    }

    @Override
    protected void updateItem(Object item, boolean empty) {
        super.updateItem(item, empty);
        applyDisclosureScale();
        applyRowHeight();

        if (empty || item == null) {
            detachRowState();
            setText(null);
            setGraphic(null);
            return;
        }

        if (item instanceof Server server) {
            detachRowState();
            serverTarget = server;
            setContextMenu(serverContextMenu);
            updateServerRow(server);
            setGraphic(serverRow);
        } else if (item instanceof DatabaseEntry db) {
            schemaItemTarget = null;
            // serverTarget también (2026-09-12) — esta rama no pasa por detachRowState()
            // a propósito (updateDatabaseRow reengancha los listeners de forma
            // condicional, ver su comentario), así que hay que limpiar a mano lo que
            // aquella limpiaría. Hoy no es alcanzable —el menú que se pone acá es el de
            // base, y sus ítems leen editTarget— pero dejar apuntando a un Server de una
            // fila anterior es exactamente la clase de referencia obsoleta que causó A2.
            serverTarget = null;
            editTreeItem = getTreeItem() instanceof DatabaseTreeItem dbTreeItem ? dbTreeItem : null;
            updateDatabaseRow(db);
            setContextMenu(databaseContextMenu);
            setGraphic(databaseRow);
        } else if (item instanceof SchemaTreeNode.Category category) {
            detachRowState();
            schemaCategoryLabel.setText(category.kind().label());
            // Esquema progresivo (2026-08-25): Funciones/Procedimientos/Triggers/Tipos son
            // categorías perezosas (CategoryTreeItem) — mientras no se hayan expandido, su
            // conteo real todavía no se sabe (SchemaTreeNode.UNKNOWN_COUNT), así que se deja
            // en blanco en vez de imprimir "-1".
            schemaCategoryCountLabel.setText(
                    category.count() == SchemaTreeNode.UNKNOWN_COUNT ? "" : String.valueOf(category.count()));
            setGraphic(schemaCategoryRow);
        } else if (item instanceof SchemaTreeNode.Loading loading) {
            detachRowState();
            loadingLabel.setText(loading.label());
            setGraphic(loadingRow);
        } else if (item instanceof SchemaTreeNode.Error error) {
            detachRowState();
            schemaErrorLabel.setText(error.message());
            // Tooltip aparte porque el mensaje real de JDBC suele ser bastante más
            // largo que el ancho del panel — la fila lo corta con "…", el tooltip
            // deja leerlo completo (mismo criterio que alias/host en Ejecución).
            //
            // setText sobre un Tooltip creado UNA vez, no `Tooltip.install(label, new
            // Tooltip(...))` en cada repintado (2026-09-08, hallazgo A5): eso creaba un
            // Tooltip nuevo y reinstalaba sus manejadores de mouse sobre el mismo Label
            // en cada pasada de layout, contra el criterio de toda esta clase (ver su
            // javadoc: los nodos se construyen una sola vez y updateItem solo actualiza
            // su contenido — statusTooltip/modeTooltip ya lo hacían así).
            schemaErrorTooltip.setText(error.message());
            setGraphic(schemaErrorLabel);
        } else if (item instanceof SchemaTreeNode.Item schemaItem) {
            detachRowState();
            schemaItemTarget = schemaItem;
            schemaItemIcon.setContent(isQueryable(schemaItem.kind()) ? Icons.TABLE : Icons.SETTINGS);
            schemaItemLabel.setText(schemaItem.name());
            schemaItemContextMenu.getItems().setAll(menuItemsFor(schemaItem.kind()));
            setContextMenu(schemaItemContextMenu);
            setGraphic(schemaItemRow);
        } else {
            detachRowState();
            // El encabezado "Sin grupo" — serverTarget queda en null a propósito, que es
            // lo que sus acciones interpretan como "las bases sueltas".
            setContextMenu(sectionHeaderContextMenu);
            sectionHeaderLabel.setText(String.valueOf(item).toUpperCase(Locale.ROOT));
            setGraphic(sectionHeaderLabel);
        }
        setText(null);
    }

    /**
     * Deja la celda sin ningún vínculo con la fila que mostraba antes — casilla
     * desatada, listeners de estado desenganchados, pulso apagado, referencias de
     * menú/edición limpias. Lo llaman las 6 ramas de {@link #updateItem} que NO son
     * una fila de base; la de base no, porque {@link #updateDatabaseRow} ya hace lo
     * suyo de forma condicional (solo re-engancha si de verdad cambió de base, ver
     * su comentario sobre el parpadeo del árbol).
     *
     * <p><b>Antes esto eran 5 líneas copiadas 6 veces</b> (2026-09-08, hallazgos A2
     * y C4 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}) y a ninguna de las 6
     * copias se le había agregado el desenganche de los listeners de
     * {@code connectionStatus}/{@code inUse} — solo {@link #updateDatabaseRow} los
     * cambiaba, y únicamente al pasar a otra base. O sea que una celda reciclada a
     * una fila de esquema (o vacía) seguía escuchando a la base anterior: cuando esa
     * base pasaba a "en uso", {@link #refreshInUseAnimation} arrancaba un
     * {@code FadeTransition} INDEFINITE sobre un {@code statusDot} que ya no se
     * muestra, y {@link #refreshStatusDot} reescribía clases de estilo en una fila
     * que estaba pintando otra cosa. Es el mismo bug que el hallazgo #10 de
     * {@code AUDITORIA_BUGS_RENDIMIENTO.md} intentó cerrar: aquel apagó la animación
     * al quedar la celda vacía, pero dejó vivo el listener que la volvía a prender.
     */
    private void detachRowState() {
        unbindCheckbox();
        // Primero desenganchar, después apagar — al revés, un evento que llegara
        // entre las dos líneas volvería a prender el pulso que se acaba de apagar.
        detachStatusListeners();
        refreshInUseAnimation(null);
        editTarget = null;
        editTreeItem = null;
        schemaItemTarget = null;
        serverTarget = null;
        setContextMenu(null);
    }

    /** Ver {@link #detachRowState()} y el javadoc de {@code statusListenerTarget}. */
    private void detachStatusListeners() {
        if (statusListenerTarget != null) {
            statusListenerTarget.connectionStatusProperty().removeListener(connectionStatusListener);
            statusListenerTarget.inUseProperty().removeListener(inUseListener);
            statusListenerTarget = null;
        }
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

        // Ver el javadoc de statusListenerTarget — enganchar/desenganchar los 2
        // listeners de connectionStatus/inUse solo cuando esta celda pasa a
        // representar una base DISTINTA (mismo criterio de "no mutar si no
        // cambió" que ya rige boundCheckProperty arriba).
        if (statusListenerTarget != db) {
            if (statusListenerTarget != null) {
                statusListenerTarget.connectionStatusProperty().removeListener(connectionStatusListener);
                statusListenerTarget.inUseProperty().removeListener(inUseListener);
            }
            statusListenerTarget = db;
            db.connectionStatusProperty().addListener(connectionStatusListener);
            db.inUseProperty().addListener(inUseListener);
        }
        refreshStatusDot(db);
        refreshInUseAnimation(db);

        aliasLabel.setText(db.alias());
        hostLabel.setText(db.host() + ":" + db.port());
        engineBadge.setText(db.engine().badge());

        // Candado del modo — SIEMPRE visible (a diferencia del texto "SIN
        // RESTRICCIONES" que reemplaza, que solo aparecía en el caso no-lectura):
        // un ícono cerrado/abierto se lee de un vistazo en los dos estados, no hace
        // falta esconder el "normal" para no distraer, como sí hacía falta con un
        // bloque de texto.
        //
        // Corrección del comentario (2026-09-14, hallazgo C7): acá decía que TODAS las
        // mutaciones de abajo eran condicionales. No es así — solo lo es la de la clase
        // de estilo. `setContent` y `setText` se llaman siempre, y está bien que así sea:
        // las propiedades de JavaFX descartan por su cuenta un `set` con un valor igual
        // (comparan por equals), así que no invalidan nada ni disparan CSS de más. Lo que
        // SÍ hay que hacer a mano es lo de la clase de estilo, porque mutar la lista de
        // clases fuerza una repasada de CSS aunque el resultado final sea idéntico — ese
        // es el caso que causaba el parpadeo del árbol y el que el javadoc de la clase
        // describe.
        boolean unrestricted = db.mode() != ServerMode.READ_ONLY;
        modeIcon.setContent(unrestricted ? Icons.LOCK_OPEN : Icons.LOCK);
        modeTooltip.setText(db.mode().label());
        boolean hasUnrestrictedStyle = modeIcon.getStyleClass().contains("tree-mode-icon-unrestricted");
        if (unrestricted != hasUnrestrictedStyle) {
            if (unrestricted) {
                modeIcon.getStyleClass().add("tree-mode-icon-unrestricted");
            } else {
                modeIcon.getStyleClass().remove("tree-mode-icon-unrestricted");
            }
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

    /**
     * Repinta {@code statusDot} contra el estado REAL de {@code db} — llamado
     * tanto desde {@code updateDatabaseRow} (pintura inicial/reciclado de
     * celda) como desde {@link #connectionStatusListener} (cambio en vivo,
     * ver el javadoc de {@code statusListenerTarget}) — los dos casos usan
     * exactamente la misma lógica, no hay ninguna diferencia entre "recién
     * pintado" y "cambió mientras ya estaba visible".
     */
    private void refreshStatusDot(DatabaseEntry db) {
        if (db == null) {
            return;
        }
        String statusStyleClass = "tree-status-dot-" + statusStyleSuffix(db);
        if (!statusDot.getStyleClass().contains(statusStyleClass)) {
            statusDot.getStyleClass().removeIf(c -> c.startsWith("tree-status-dot-"));
            statusDot.getStyleClass().add(statusStyleClass);
        }
        statusTooltip.setText(statusTooltipText(db));
    }

    /**
     * Prende/apaga el pulso de {@link #inUsePulse} contra
     * {@link DatabaseEntry#isInUse()} — 2026-08-28, pedido explícito del
     * usuario: "si se está haciendo uso de esa BD... que pardee o se mueva
     * ese círculo azul indicando que está en uso". {@code playFromStart()}
     * solo si no estaba ya corriendo (evita reiniciar el pulso a mitad de un
     * ciclo cada vez que {@code updateDatabaseRow} repinta la celda sin que
     * {@code inUse} haya cambiado de verdad); {@code stop()} + opacidad 1.0
     * al apagarse, para no dejar el punto a medio desvanecer.
     */
    private void refreshInUseAnimation(DatabaseEntry db) {
        if (db != null && db.isInUse()) {
            if (inUsePulse.getStatus() != Animation.Status.RUNNING) {
                statusDot.setOpacity(1.0);
                inUsePulse.playFromStart();
            }
        } else {
            inUsePulse.stop();
            statusDot.setOpacity(1.0);
        }
    }
}
