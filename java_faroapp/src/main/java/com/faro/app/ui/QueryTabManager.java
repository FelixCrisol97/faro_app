package com.faro.app.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.ConnectionRegistry;
import com.faro.app.data.SavedQueryTab;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.SqlFormatter;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/**
 * Las pestañas de consulta: crearlas, su encabezado de dos líneas, el estado de cada
 * una, guardarlas, buscar y formatear dentro de ellas (2026-09-18, tercer paso del
 * hallazgo C1 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
 *
 * <p>Es el paso que más le quita a {@code MainController} y el primero que es UI de
 * verdad: los dos anteriores ({@code SessionPersistence},
 * {@code ScriptGeneratorCoordinator}) casi no tocaban nodos. Por eso casi nada de esta
 * clase tiene test — lo que sí es lógica pura ({@link #indexOfIgnoreCase},
 * {@link #describeSelection}, {@link #describeSelectionByIds}) está expuesto a nivel
 * de paquete y cubierto en {@code QueryTabManagerTest}.
 *
 * <h2>Lo que se queda en el controlador, y por qué</h2>
 *
 * <ul>
 *   <li><b>Los manejadores {@code @FXML}</b> ({@code onNewQueryTab},
 *       {@code onSaveFile}, {@code onFindNext}…): el FXML los enlaza por nombre al
 *       controlador, así que quedan allá como delegaciones de una línea.</li>
 *   <li><b>La barra de búsqueda</b> (sus tres nodos viven en el FXML del controlador):
 *       acá está la búsqueda en sí, que devuelve un {@link FindOutcome}; el controlador
 *       decide qué pintar en la barra.</li>
 *   <li><b>El árbol de conexiones.</b> Cada pestaña recuerda qué bases tenía marcadas,
 *       pero las casillas viven en el árbol, que es el paso 4 del plan. Esta clase las
 *       lee y las escribe a través de {@link Host}.</li>
 * </ul>
 *
 * <h2>Por qué {@link Host} es una interfaz y no una lista de lambdas</h2>
 *
 * Esta clase necesita nueve cosas del controlador. Pasarlas como parámetros
 * posicionales del constructor repetiría el riesgo que el análisis dejó anotado para
 * {@link ConnectionTreeActions}: varios serían del mismo tipo funcional
 * ({@code Consumer<String>} para la barra de estado y para el log de Diagnóstico), y
 * cruzarlos <b>compila</b> y falla en vivo. Con métodos con nombre no se pueden cruzar.
 */
public final class QueryTabManager {

    private static final Logger log = LoggerFactory.getLogger(QueryTabManager.class);

    /**
     * Lo que esta clase le pide al controlador. Todo se invoca en el hilo de JavaFX.
     */
    public interface Host {
        /** Dueña de los diálogos (confirmar cambios sin guardar, elegir dónde guardar). */
        Window window();

        /** Bases marcadas AHORA MISMO en el árbol, como objetos — lo que la pestaña activa "ve". */
        List<DatabaseEntry> selectedDatabases();

        /** Lo mismo, como ids — para guardar la selección de una pestaña que se deja. */
        Set<String> capturedSelectedDatabaseIds();

        /** Marca en el árbol exactamente estas bases — al activar una pestaña. */
        void applySelectedDatabaseIds(Set<String> ids);

        /**
         * Si el árbol ya existe. Al arrancar hay un momento en que todavía no, y la
         * segunda línea de la pestaña activa cae entonces a la selección guardada.
         */
        boolean treeReady();

        /** El registro vigente — para traducir un id guardado a su alias. */
        ConnectionRegistry registry();

        /** Aplica el tema claro/oscuro a un diálogo propio. */
        void applyTheme(Dialog<?> dialog);

        /** Mensaje de una línea en la barra de estado de abajo. */
        void status(String message);

        /** Línea informativa en la pestaña Diagnóstico. */
        void diagnostic(String message);
    }

    /** Resultado de una búsqueda — el controlador decide qué mostrar en la barra de búsqueda. */
    public enum FindOutcome {
        /** No había pestaña activa o el texto a buscar estaba vacío: no se hizo nada. */
        NOTHING_TO_SEARCH,
        NOT_FOUND,
        FOUND
    }

    /**
     * Estado de una pestaña de consulta — su editor, el archivo asociado (si ya se
     * guardó o se abrió), si tiene cambios sin guardar, y qué bases tiene marcadas.
     * Vive como {@code userData} del {@link Tab}.
     *
     * <p>Desde fuera del paquete solo se lee, por los accesores: quien corre una consulta
     * necesita el editor y el nombre, pero cambiar el archivo, el punto de "sin guardar"
     * o la selección es cosa de esta clase.
     */
    public static final class TabState {
        final CodeArea codeArea;
        File file;
        boolean dirty;
        /**
         * Bases marcadas en el árbol para ESTA pestaña (2026-08-28) — hallazgo real del
         * usuario: antes, cuál base estaba marcada era un solo estado global compartido
         * por TODAS las pestañas a la vez. Abrir una pestaña nueva para una base, y
         * después otra para una base distinta, dejaba la primera apuntando a la base
         * equivocada al volver a ella — "de nada me sirve cambiar entre ventanas si no
         * mantienen la BD que yo abrí en una ventana aparte". Ahora cada pestaña guarda
         * sus propios ids; al cambiar de pestaña se guarda el estado de la que se deja y
         * se aplica el de la que se activa sobre las casillas reales del árbol (ver
         * {@link #syncTreeOnTabSwitch}). La activa sigue siendo la única fuente visual
         * —hay un solo árbol— pero ya no se pisan entre sí.
         */
        Set<String> selectedDatabaseIds = new LinkedHashSet<>();
        /**
         * Nombre SIN el punto de "cambios sin guardar" — "Consulta N", o el nombre del
         * archivo si se guardó o abrió uno.
         *
         * <p>Antes el nombre vivía en {@code tab.getText()} y el punto se concatenaba
         * encima, lo que obligaba a deshacerlo con {@code replace("● ", "")} en dos
         * sitios — frágil (un archivo que empezara con "● " se habría roto) e imposible
         * de combinar con un encabezado de dos líneas. Ahora el nombre es un dato y el
         * encabezado se pinta a partir de él.
         */
        String baseName = "";
        /** Primera línea del encabezado — nombre más el punto de cambios sin guardar. */
        Label nameLabel;
        /** Segunda línea — qué base(s) va a usar "Ejecutar" en esta pestaña. */
        Label databaseLabel;

        TabState(CodeArea codeArea) {
            this.codeArea = codeArea;
        }

        public CodeArea codeArea() {
            return codeArea;
        }

        /** Nombre visible sin el punto de "sin guardar". */
        public String baseName() {
            return baseName;
        }
    }

    private final TabPane tabPane;
    private final AppPreferences preferences;
    private final Host host;
    private int queryTabCounter;

    /**
     * Instala además la sincronización del árbol al cambiar de pestaña — ver
     * {@link #syncTreeOnTabSwitch}.
     *
     * <p><b>Tiene que construirse antes de la primera reconstrucción del árbol.</b> Esa
     * reconstrucción ya repinta el encabezado de la pestaña activa (que al arrancar no
     * existe todavía, y entonces no hace nada) — pero para no hacer nada necesita que
     * esta instancia exista.
     */
    public QueryTabManager(TabPane tabPane, AppPreferences preferences, Host host) {
        this.tabPane = tabPane;
        this.preferences = preferences;
        this.host = host;
        tabPane.getSelectionModel().selectedItemProperty().addListener(
                (obs, oldTab, newTab) -> syncTreeOnTabSwitch(oldTab, newTab));
    }

    // ------------------------------------------------------------------
    // Crear pestañas
    // ------------------------------------------------------------------

    /**
     * Crea una pestaña de consulta nueva con su propio {@code CodeArea} independiente y
     * la selecciona. No se puede cerrar la última que quede — siempre tiene que haber al
     * menos un editor abierto. Cerrar una con cambios sin guardar pide confirmación
     * primero (antes se perdían en silencio).
     *
     * <p>{@code initialSelectedDatabaseIds}: {@code null} hereda una COPIA de lo que la
     * pestaña saliente tenga marcado ahora mismo en el árbol (lo de siempre para
     * Ctrl+T/"+" — abrir una pestaña nueva no debería dejar el árbol en blanco de la
     * nada); un conjunto explícito fuerza esa selección exacta — lo usan "Nueva consulta
     * para esta base" y los "Generar…" para asociar la pestaña nueva a UNA base sin tocar
     * las casillas de la que se está dejando. Si mutaran el árbol ANTES de crear la
     * pestaña, como hacía el código viejo, el cambio de pestaña guardaría esa mutación
     * como si fuera la selección real de la saliente — bug real que este orden evita.
     */
    public Tab addQueryTab(String initialText, File file, Set<String> initialSelectedDatabaseIds) {
        CodeArea codeArea = SqlEditorFactory.create();
        if (initialText != null && !initialText.isEmpty()) {
            codeArea.replaceText(initialText);
        }
        codeArea.setStyle("-fx-font-size: " + preferences.editorFontSize() + "px;");
        installEditorZoom(codeArea);

        TabState state = new TabState(codeArea);
        state.file = file;
        state.selectedDatabaseIds = initialSelectedDatabaseIds != null
                ? new LinkedHashSet<>(initialSelectedDatabaseIds)
                : host.capturedSelectedDatabaseIds();

        Tab tab = new Tab();
        state.baseName = file != null ? file.getName() : "Consulta " + (++queryTabCounter);
        tab.setContent(new VirtualizedScrollPane<>(codeArea));
        tab.setUserData(state);
        // Encabezado de 2 líneas (2026-09-10, pedido del usuario: la pestaña no decía
        // contra qué base iba a correr, y el único aviso —"su casilla ya quedó
        // marcada"— vivía en la barra de estado de ABAJO, donde nadie está mirando
        // mientras escribe SQL arriba).
        tab.setGraphic(buildTabHeader(state));
        refreshTabHeader(tab);

        // Agregado DESPUÉS de replaceText() de arriba — si no, cargar el texto inicial
        // (un archivo abierto, por ejemplo) marcaría la pestaña como "con cambios sin
        // guardar" apenas se crea, lo cual sería falso.
        //
        // plainTextChanges(), NO textProperty() (2026-09-07, hallazgo #5 de
        // AUDITORIA_BUGS_RENDIMIENTO.md): en RichTextFX el texto no es un campo, es un
        // valor derivado del documento — un listener en textProperty() obliga a
        // materializar el documento COMPLETO como un String nuevo en cada tecla (y dos
        // veces, porque también recibe el valor anterior). Con un script grande pegado
        // eso son megabytes de basura por pulsación, y se siente como latencia al
        // escribir. La propia documentación de RichTextFX lo advierte. La suscripción vive
        // lo que viva el CodeArea (muere con la pestaña, sin fuga) y en cuanto la pestaña
        // ya está sucia no hace nada más.
        codeArea.plainTextChanges().subscribe(change -> {
            if (!state.dirty) {
                state.dirty = true;
                refreshTabHeader(tab);
            }
        });

        tab.setOnCloseRequest(event -> {
            if (tabPane.getTabs().size() <= 1) {
                event.consume();
                return;
            }
            if (state.dirty && !confirmSaveOrDiscard(tab, state)) {
                event.consume();
            }
        });

        tabPane.getTabs().add(tab);
        tabPane.getSelectionModel().select(tab);
        return tab;
    }

    /**
     * Ctrl+Más / Ctrl+Menos / Ctrl+0 y Ctrl+rueda — SOLO el tamaño de fuente del editor
     * (todas las pestañas comparten un único valor), a propósito distinto del "zoom
     * global" que se probó tres veces y se quitó por completo el 2026-08-22 (ese
     * escalaba TODA la interfaz). Filtros puestos en el {@code CodeArea}, no un
     * acelerador de menú global, para que solo disparen con el editor enfocado.
     *
     * <p><b>La rueda</b> (pedido del usuario, 2026-08-26): sin Ctrl el evento nunca se
     * toca y el scroll normal de RichTextFX sigue igual; con Ctrl se consume SIEMPRE,
     * incluso con el tamaño ya en su límite (10–24 px) — si no, un Ctrl+rueda en el
     * límite dejaría pasar el evento y RichTextFX movería el scroll A LA VEZ que "no"
     * hace zoom, justo el efecto doble que se quería evitar. Un píxel por evento, igual
     * que el teclado: promediar los muchos eventos chicos de un trackpad sería más suave,
     * pero es complejidad que no se puede probar sin la ventana real.
     */
    private void installEditorZoom(CodeArea codeArea) {
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isControlDown()) {
                return;
            }
            KeyCode code = event.getCode();
            if (code == KeyCode.PLUS || code == KeyCode.EQUALS || code == KeyCode.ADD) {
                preferences.setEditorFontSize(preferences.editorFontSize() + 1);
                applyEditorFontSize();
                event.consume();
            } else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT) {
                preferences.setEditorFontSize(preferences.editorFontSize() - 1);
                applyEditorFontSize();
                event.consume();
            } else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) {
                preferences.setEditorFontSize(14);
                applyEditorFontSize();
                event.consume();
            }
        });
        codeArea.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown()) {
                return;
            }
            preferences.setEditorFontSize(preferences.editorFontSize() + (event.getDeltaY() > 0 ? 1 : -1));
            applyEditorFontSize();
            event.consume();
        });
    }

    /**
     * Reaplica el tamaño de fuente del editor a TODAS las pestañas abiertas — es una sola
     * preferencia compartida, no una por pestaña. {@link #addQueryTab} ya lo pone en la
     * suya al crearla; esto cubre las que YA estaban abiertas. Lo llaman el cambio de
     * tema, Preferencias → Apariencia y los atajos del propio editor.
     */
    public void applyEditorFontSize() {
        String style = "-fx-font-size: " + preferences.editorFontSize() + "px;";
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() instanceof TabState state) {
                state.codeArea.setStyle(style);
            }
        }
    }

    // ------------------------------------------------------------------
    // Pestaña activa y selección de bases
    // ------------------------------------------------------------------

    /** El estado de la pestaña activa, o {@code null} si no hay ninguna. */
    public TabState current() {
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        return tab == null ? null : (TabState) tab.getUserData();
    }

    /**
     * Al cambiar de pestaña: guarda la selección de la que se deja y aplica al árbol la de
     * la que se activa, ANTES de que el usuario pueda tocar "Ejecutar" contra la base
     * equivocada. Ver el javadoc de {@link TabState#selectedDatabaseIds}.
     */
    private void syncTreeOnTabSwitch(Tab oldTab, Tab newTab) {
        if (oldTab != null && oldTab.getUserData() instanceof TabState oldState) {
            oldState.selectedDatabaseIds = host.capturedSelectedDatabaseIds();
        }
        if (newTab != null && newTab.getUserData() instanceof TabState newState) {
            host.applySelectedDatabaseIds(newState.selectedDatabaseIds);
        }
        // Cambian DOS encabezados a la vez (la que se deja pasa a mostrar su selección
        // guardada, la que se activa pasa a seguir el árbol en vivo), así que se repintan
        // todos en vez de rastrear cuáles.
        refreshAllTabHeaders();
    }

    /**
     * Captura todas las pestañas para guardarlas en {@code connections.json}.
     *
     * <p>Antes de capturar, refresca la selección de la activa desde el árbol: su
     * {@code selectedDatabaseIds} solo se actualiza al CAMBIAR de pestaña, así que sin
     * este paso quedaría desactualizado si el usuario tocó casillas sin cambiar de
     * pestaña antes de cerrar la app.
     *
     * <p>Lee el texto de cada {@code CodeArea}: solo es seguro en el hilo de JavaFX.
     */
    public List<SavedQueryTab> captureForSave() {
        TabState activeState = current();
        if (activeState != null) {
            activeState.selectedDatabaseIds = host.capturedSelectedDatabaseIds();
        }
        List<SavedQueryTab> saved = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            if (tab.getUserData() instanceof TabState state) {
                saved.add(new SavedQueryTab(
                        state.codeArea.getText(),
                        state.file != null ? state.file.getAbsolutePath() : null,
                        List.copyOf(state.selectedDatabaseIds)));
            }
        }
        return saved;
    }

    // ------------------------------------------------------------------
    // Encabezado de dos líneas
    // ------------------------------------------------------------------

    /**
     * Encabezado de 2 líneas: el nombre arriba y, debajo y en chico, contra qué base(s)
     * va a correr "Ejecutar".
     *
     * <p>Se eligió 2 líneas y no "Consulta 1 · bodega" en una sola para que las pestañas
     * no se alarguen: con varias abiertas, un nombre de base largo (los reales del
     * usuario son del tipo {@code bodegamuebles.30001}) empujaría la barra hasta necesitar
     * sus flechas de scroll. La barra queda más alta, no más ancha.
     */
    private Node buildTabHeader(TabState state) {
        Label name = new Label();
        name.getStyleClass().add("query-tab-name");
        Label database = new Label();
        database.getStyleClass().add("query-tab-database");
        state.nameLabel = name;
        state.databaseLabel = database;
        VBox header = new VBox(0, name, database);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    /**
     * Repinta las 2 líneas del encabezado de {@code tab}.
     *
     * <p>Para la pestaña ACTIVA la segunda línea sale de las casillas reales del árbol,
     * no de {@code state.selectedDatabaseIds} — ese campo solo se actualiza al CAMBIAR de
     * pestaña, así que usarlo acá dejaría la línea desactualizada justo en el caso que
     * importa: el usuario marcando y desmarcando bases para la consulta que está
     * escribiendo ahora.
     */
    private void refreshTabHeader(Tab tab) {
        if (!(tab.getUserData() instanceof TabState state) || state.nameLabel == null) {
            return;
        }
        state.nameLabel.setText((state.dirty ? "● " : "") + state.baseName);
        boolean isActive = tabPane.getSelectionModel().getSelectedItem() == tab;
        if (isActive && host.treeReady()) {
            // Directo desde el árbol, sin pasar por ids (2026-09-12): antes esto recorría
            // el árbol para sacar ids y después buscaba el alias recorriendo TODAS las
            // bases del registro. Dos recorridos y dos colecciones por cada casilla que se
            // marca o desmarca; con "Todas" sobre decenas de bases eso se multiplica. Acá
            // el alias ya viene en el objeto.
            state.databaseLabel.setText(describeSelection(host.selectedDatabases()));
        } else {
            state.databaseLabel.setText(
                    describeSelectionByIds(state.selectedDatabaseIds, host.registry().allDatabases()));
        }
    }

    /** Repinta el encabezado de TODAS las pestañas — al cambiar de pestaña cambian dos, y recorrerlas es más simple y barato que rastrear cuáles. */
    private void refreshAllTabHeaders() {
        for (Tab tab : tabPane.getTabs()) {
            refreshTabHeader(tab);
        }
    }

    /** Solo el de la activa — lo que se llama al marcar o desmarcar una casilla del árbol, donde las demás pestañas no cambian. */
    public void refreshActiveTabHeader() {
        Tab active = tabPane.getSelectionModel().getSelectedItem();
        if (active != null) {
            refreshTabHeader(active);
        }
    }

    /**
     * Segunda línea del encabezado: el alias cuando hay exactamente una base marcada, el
     * conteo cuando hay varias, y un aviso explícito cuando no hay ninguna — ese último
     * caso es el que antes solo se descubría al presionar "Ejecutar" y recibir
     * "Selecciona al menos una base de datos" abajo.
     */
    static String describeSelection(List<DatabaseEntry> selected) {
        if (selected.isEmpty()) {
            return "sin base seleccionada";
        }
        return selected.size() > 1 ? selected.size() + " bases" : selected.get(0).alias();
    }

    /**
     * Igual que {@link #describeSelection}, pero partiendo de ids guardados — es lo único
     * que tiene una pestaña que NO está activa: su selección vive como ids desde la última
     * vez que se la dejó, no como objetos del árbol de ahora.
     *
     * @param allDatabases todas las bases del registro, para traducir el id a su alias
     */
    static String describeSelectionByIds(Set<String> ids, List<DatabaseEntry> allDatabases) {
        if (ids.isEmpty()) {
            return "sin base seleccionada";
        }
        if (ids.size() > 1) {
            return ids.size() + " bases";
        }
        String id = ids.iterator().next();
        return allDatabases.stream()
                .filter(db -> db.id().equals(id))
                .map(DatabaseEntry::alias)
                .findFirst()
                // Una base que ya no existe (se borró mientras la pestaña la tenía
                // guardada) — no se inventa un nombre ni se deja la línea en blanco.
                .orElse("base no encontrada");
    }

    // ------------------------------------------------------------------
    // Guardar y cerrar
    // ------------------------------------------------------------------

    /** Archivo → Guardar: sobre el archivo de la pestaña activa, o pide uno si todavía no tiene. */
    public void saveCurrent() {
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        TabState state = current();
        if (tab == null || state == null) {
            return;
        }
        if (state.file == null) {
            saveCurrentAs();
            return;
        }
        writeScriptTo(tab, state, state.file);
    }

    /** Archivo → Guardar como…: siempre pide el archivo. */
    public void saveCurrentAs() {
        Tab tab = tabPane.getSelectionModel().getSelectedItem();
        TabState state = current();
        if (tab == null || state == null) {
            return;
        }
        File file = chooseSaveFile();
        if (file == null) {
            return;
        }
        state.file = file;
        writeScriptTo(tab, state, file);
    }

    private File chooseSaveFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Guardar script SQL");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL", "*.sql"));
        return chooser.showSaveDialog(host.window());
    }

    private void writeScriptTo(Tab tab, TabState state, File file) {
        try {
            Files.writeString(file.toPath(), state.codeArea.getText());
            state.baseName = file.getName();
            state.dirty = false;
            refreshTabHeader(tab);
            host.status("Guardado: " + file.getName());
            host.diagnostic("Script guardado en " + file.getName());
        } catch (IOException e) {
            log.warn("No se pudo guardar el script en {}", file.getAbsolutePath(), e);
            host.status("Error al guardar: " + e.getMessage());
        }
    }

    /**
     * Pregunta qué hacer al cerrar una pestaña con cambios sin guardar — Guardar /
     * Descartar / Cancelar. Devuelve {@code true} si se puede seguir cerrando (se guardó,
     * o el usuario decidió descartar) y {@code false} si hay que vetar el cierre (canceló,
     * o el guardado en sí falló o se canceló a mitad de camino).
     *
     * <p>Ninguno de los tres botones es "peligroso" por defecto: el de Guardar es el
     * primario, y Descartar no se dispara con un Enter de más.
     */
    private boolean confirmSaveOrDiscard(Tab tab, TabState state) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Cambios sin guardar");
        alert.setHeaderText("La pestaña \"" + state.baseName + "\" tiene cambios sin guardar.");
        alert.setContentText("¿Qué quieres hacer antes de cerrarla?");
        ButtonType saveType = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        ButtonType discardType = new ButtonType("Descartar cambios");
        ButtonType cancelType = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(saveType, discardType, cancelType);
        alert.initOwner(host.window());
        host.applyTheme(alert);

        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == cancelType) {
            return false;
        }
        if (choice.get() == discardType) {
            return true;
        }

        File file = state.file;
        if (file == null) {
            file = chooseSaveFile();
            if (file == null) {
                return false;
            }
            state.file = file;
        }
        writeScriptTo(tab, state, file);
        return !state.dirty;
    }

    /**
     * Al cerrar la ventana entera (la X del sistema, Alt+F4 o Archivo → Salir): la misma
     * confirmación de {@link #confirmSaveOrDiscard}, una vez por cada pestaña con cambios
     * pendientes. Devuelve {@code false} en cuanto una se cancela — las que quedaban por
     * preguntar ya no se tocan.
     */
    public boolean confirmCloseAllTabs() {
        for (Tab tab : List.copyOf(tabPane.getTabs())) {
            TabState state = (TabState) tab.getUserData();
            if (state.dirty && !confirmSaveOrDiscard(tab, state)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Buscar y formatear
    // ------------------------------------------------------------------

    /** Devuelve el foco al editor de la pestaña activa — al cerrar la barra de búsqueda. */
    public void focusCurrentEditor() {
        TabState state = current();
        if (state != null) {
            state.codeArea.requestFocus();
        }
    }

    /**
     * Busca la siguiente/anterior aparición de {@code needle} en el editor de la pestaña
     * activa, sin distinguir mayúsculas, y la selecciona. Si no hay más ocurrencias en
     * esa dirección desde el cursor, da la vuelta al principio/final del texto (búsqueda
     * circular), en vez de decir "sin resultados" apenas se pasa del final.
     */
    public FindOutcome find(String needle, boolean forward) {
        TabState state = current();
        if (state == null || needle.isEmpty()) {
            return FindOutcome.NOTHING_TO_SEARCH;
        }
        CodeArea codeArea = state.codeArea;
        // Sin copia en minúsculas del documento entero (2026-09-07, hallazgo #9 de
        // AUDITORIA_BUGS_RENDIMIENTO.md) — antes cada "buscar siguiente" hacía
        // getText().toLowerCase(), o sea DOS copias completas del script por cada F3.
        // Sobre un script grande eso eran decenas de MB de basura solo para encontrar la
        // siguiente coincidencia. indexOfIgnoreCase compara en el lugar.
        String haystack = codeArea.getText();

        int caret = codeArea.getCaretPosition();
        int index;
        if (forward) {
            index = indexOfIgnoreCase(haystack, needle, caret, true);
            if (index < 0) {
                index = indexOfIgnoreCase(haystack, needle, 0, true);
            }
        } else {
            int searchFrom = caret - needle.length() - 1;
            index = searchFrom >= 0 ? indexOfIgnoreCase(haystack, needle, searchFrom, false) : -1;
            if (index < 0) {
                index = indexOfIgnoreCase(haystack, needle, haystack.length() - needle.length(), false);
            }
        }

        if (index < 0) {
            return FindOutcome.NOT_FOUND;
        }
        codeArea.selectRange(index, index + needle.length());
        codeArea.requestFollowCaret();
        return FindOutcome.FOUND;
    }

    /**
     * Equivalente sin distinguir mayúsculas de {@code indexOf}/{@code lastIndexOf} SIN
     * copiar el texto — {@code String#regionMatches(true, ...)} compara en el lugar,
     * carácter por carácter, sobre el documento original. {@code forward=false} busca
     * hacia atrás desde {@code from} inclusive, igual que {@code lastIndexOf}. Devuelve
     * -1 si no hay ninguna coincidencia en esa dirección.
     */
    static int indexOfIgnoreCase(String haystack, String needle, int from, boolean forward) {
        int lastPossibleStart = haystack.length() - needle.length();
        if (needle.isEmpty() || lastPossibleStart < 0) {
            return -1;
        }
        if (forward) {
            for (int i = Math.max(0, from); i <= lastPossibleStart; i++) {
                if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                    return i;
                }
            }
        } else {
            for (int i = Math.min(from, lastPossibleStart); i >= 0; i--) {
                if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Editar → Formatear SQL (Ctrl+L) — reescribe el texto de la pestaña activa con
     * {@link SqlFormatter#format}. La marca como "con cambios sin guardar" igual que
     * cualquier otra edición, porque sí cambia el texto real.
     *
     * @return {@code false} si no había pestaña activa y no se hizo nada
     */
    public boolean formatCurrent() {
        TabState state = current();
        if (state == null) {
            return false;
        }
        state.codeArea.replaceText(SqlFormatter.format(state.codeArea.getText()));
        return true;
    }
}
