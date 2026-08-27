package com.faro.app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.ConnectionRegistry;
import com.faro.app.data.ConnectionRegistryStore;
import com.faro.app.data.CredentialStore;
import com.faro.app.data.CredentialVaultStore;
import com.faro.app.data.Favorite;
import com.faro.app.data.FavoritesStore;
import com.faro.app.model.ColumnMetadata;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.CsvFileNamer;
import com.faro.app.query.ExecutionStatus;
import com.faro.app.query.QueryExecutionService;
import com.faro.app.query.QueryResult;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.query.SqlFormatter;
import com.faro.app.query.SqlScriptGenerator;
import com.faro.app.ui.AddDatabaseDialog;
import com.faro.app.ui.ConnectionTreeBuilder;
import com.faro.app.ui.ConnectionTreeCell;
import com.faro.app.ui.CredentialsDialog;
import com.faro.app.ui.CsvImportDialog;
import com.faro.app.ui.DiscoverDialog;
import com.faro.app.ui.ExecutionTableFactory;
import com.faro.app.ui.Icons;
import com.faro.app.ui.PreferencesDialog;
import com.faro.app.ui.ResultsTableFactory;
import com.faro.app.ui.SchemaTreeNode;
import com.faro.app.ui.SqlAutocomplete;
import com.faro.app.ui.SqlEditorFactory;
import com.faro.app.ui.Theme;

import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.util.Duration;

/**
 * Controlador de la ventana principal. Árbol de conexiones, editor SQL
 * (con pestañas de múltiples consultas, aviso de cambios sin guardar,
 * buscar y formatear), tabla de resultados, ejecución real de consultas
 * (concurrente, con pool de HikariCP, cancelable con respaldo KILL/
 * pg_cancel_backend), los 5 diálogos, tema claro/oscuro, y buena parte
 * del `MenuBar` ya son reales — conexiones/preferencias/credenciales
 * persisten en disco (ver {@link ConnectionRegistryStore},
 * {@code CredentialVaultStore}). Ver el README, sección "Estado actual",
 * para el detalle exacto de qué sigue faltando (Autocompletado,
 * favoritos, plan de ejecución, panel Ver acoplable, importar/exportar
 * configuración).
 *
 * (Nota 2026-08-21: el botón "Probar conexión de prueba" y su contraseña
 * hardcodeada, que vivían acá para probar en vivo durante el desarrollo,
 * se quitaron a pedido del usuario — ver README, ya no es un bloqueo de
 * commit. "Probar todas las conexiones", en el menú Conexiones, es el
 * camino real para probar conexiones — usa las credenciales guardadas de
 * cada base, no una hardcodeada.)
 */
public class MainController {

    @FXML
    private Label statusLabel;

    @FXML
    private Circle poolStatusDot;

    @FXML
    private Label poolStatusLabel;

    @FXML
    private Label timeoutFetchLabel;

    @FXML
    private HBox exportSpinnerBox;

    @FXML
    private Region exportSpinner;

    @FXML
    private Label exportSpinnerLabel;

    @FXML
    private Label memoryLabel;

    @FXML
    private Label engineJdkLabel;

    @FXML
    private TreeView<Object> connectionTree;

    @FXML
    private TextField connectionFilterField;

    @FXML
    private ToggleButton connectionsRailButton;

    @FXML
    private ToggleButton historyRailButton;

    @FXML
    private ToggleButton favoritesRailButton;

    // ToggleButton, no Button — ver nota en styles.css: mismo tipo de
    // control que sus tres hermanos del riel para que comparta el mismo
    // estilo base de Modena (Button trae más capas de relieve/foco por
    // defecto que ToggleButton, eso causaba el cuadro sólido que no
    // correspondía a ningún estado). No entra a railToggleGroup, así que
    // su "selected" nunca se lee — solo importa su onAction.
    @FXML
    private ToggleButton settingsRailButton;

    @FXML
    private VBox connectionsPanel;

    @FXML
    private VBox historyPanel;

    @FXML
    private VBox favoritesPanel;

    @FXML
    private ListView<String> historyListView;

    @FXML
    private ListView<Favorite> favoritesListView;

    @FXML
    private TabPane queryTabPane;

    @FXML
    private HBox findBar;

    @FXML
    private TextField findField;

    @FXML
    private Label findStatusLabel;

    @FXML
    private Button closeFindBarButton;

    @FXML
    private StackPane resultsContainer;

    @FXML
    private StackPane executionContainer;

    @FXML
    private StackPane diagnosticContainer;

    @FXML
    private Tab resultsTab;

    @FXML
    private Tab executionTab;

    @FXML
    private Tab diagnosticTab;

    @FXML
    private Button exportCsvButton;

    @FXML
    private Label selectedCountLabel;

    @FXML
    private Button runButton;

    @FXML
    private Button newQueryButton;

    @FXML
    private Button openButton;

    @FXML
    private Button saveButton;

    @FXML
    private Button formatButton;

    @FXML
    private Button favoriteButton;

    @FXML
    private Button addDatabaseButton;

    @FXML
    private Button selectAllDatabasesButton;

    @FXML
    private Button themeToggleButton;

    /** Distinto nombre que el método {@link #log(String)} de abajo (el log visual de la pestaña Diagnóstico) a propósito, para no confundir al leer — este es el logger real de archivo (SLF4J/Logback, ver logback.xml), {@link #log(LogLevel, String)} le reenvía cada entrada. */
    private static final Logger logger = LoggerFactory.getLogger(MainController.class);

    private static final int MAX_HISTORY = 50;

    /** Nivel de una entrada del log de Diagnóstico — mismo vocabulario visual que `faro-java-prototipo.html` (INFO/WARN/ERROR/DEBUG, coloreados). */
    private enum LogLevel { INFO, WARN, ERROR, DEBUG }

    private record DiagnosticEntry(LocalTime time, LogLevel level, String message) {
    }

    private final CredentialStore credentials = new CredentialStore();
    private final ConnectionPoolManager pool = new ConnectionPoolManager();
    /** Versión real de cada motor, cacheada la primera vez que una conexión de ese tipo tiene éxito (ver {@code QueryExecutionService#runOne}) — para la barra de estado de abajo ("PostgreSQL 15.4 · SQL Server 2019"). */
    private final Map<DbEngine, String> engineVersions = new ConcurrentHashMap<>();
    /** "db:label:objeto" en vuelo ahora mismo — evita mandar 2 fetches JDBC idénticos si el usuario repite el mismo "Generar…" antes de que el primero termine (hallazgo real de revisión de código, 2026-08-25: {@link #generateFromCacheOrFetch} no tenía ningún candado, a diferencia de {@code loading}/{@code categoryLoading} en {@code SchemaIntrospector}, mismo criterio ahora acá). */
    private final Set<String> pendingGenerations = ConcurrentHashMap.newKeySet();
    private final AppPreferences preferences = new AppPreferences();
    private final FavoritesStore favorites = new FavoritesStore();
    private final ObservableList<DiagnosticEntry> diagnosticLog = FXCollections.observableArrayList();
    private final ObservableList<String> queryHistory = FXCollections.observableArrayList();
    private ConnectionRegistry registry;
    private TableView<ObservableList<Object>> resultsTable;
    private ListView<ExecutionStatus> executionTable;
    private RotateTransition exportSpinAnimation;
    private Label executionSummaryLabel;
    private List<ExecutionStatus> currentExecutionRows = List.of();
    /** {@code true} mientras el {@code Task} de {@link #onRunQuery()} está corriendo — decide si el botón "Ejecutar"/"Cancelar" (mismo botón, ver {@link #onRunButtonClicked()}) dispara una cosa u otra. */
    private boolean queryRunning = false;
    /** Alias de la base (o "N-bases" si la última corrida tocó varias) y texto SQL que produjeron el resultado que hay ahora mismo en {@link #resultsTable} — insumos para el nombre sugerido de {@link #onExportResultsCsv()}, ver {@link CsvFileNamer}. */
    private String lastResultDatabaseLabel = "";
    private String lastResultSql = "";
    /** Texto actual del buscador de bases del árbol de conexiones — ver {@link #refreshTree()}/{@link ConnectionTreeBuilder#buildRoot(ConnectionRegistry, String)}. */
    private String connectionFilterText = "";
    /** Hora en que arrancó la última corrida — se perdió al quitar los `log(...)` duplicados de {@link #onRunQuery()}, el usuario lo notó, se movió al encabezado de Ejecución en vez de repetirlo en el log. */
    private String lastExecutionStartTime = "";
    private int queryTabCounter;
    private Timer autosaveTimer;
    private Timer statusBarTimer;

    private static final long AUTOSAVE_INTERVAL_MILLIS = 120_000;
    /** Pool activo/total y memoria SÍ cambian en cualquier momento (no solo al terminar una ejecución/exportación, que es cuando refreshStatusBar() ya se llamaba) — hallazgo real del usuario probando en vivo: "lo veo todo estático no veo que cambie". 2.5s de por medio: suficiente para sentirse en vivo, demasiado espaciado como para que leer HikariCP/Runtime en cada tick importe de verdad. */
    private static final long STATUS_BAR_REFRESH_INTERVAL_MILLIS = 2500;

    @FXML
    private void initialize() {
        logger.info("MainController.initialize() — arrancando.");
        registry = loadOrCreateRegistry();
        loadCredentials();
        connectionTree.setCellFactory(tree ->
                new ConnectionTreeCell(this::openEditDialog, this::onNewQueryForDatabase, this::confirmAndDeleteDatabase,
                        this::onDiscoverForDatabase, this::onGenerateScript));
        connectionTree.setOnMouseClicked(this::onTreeClicked);
        connectionFilterField.textProperty().addListener((obs, oldText, newText) -> {
            connectionFilterText = newText;
            refreshTree();
        });
        refreshTree();
        startAutosave();
        startStatusBarRefresh();

        addQueryTab("", null);
        findField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                onCloseFindBar();
            }
        });
        closeFindBarButton.setGraphic(Icons.strokeIcon(Icons.X));
        connectionsRailButton.setGraphic(Icons.strokeIcon(Icons.DATABASE));
        historyRailButton.setGraphic(Icons.strokeIcon(Icons.CLOCK));
        favoritesRailButton.setGraphic(Icons.strokeIcon(Icons.STAR));
        settingsRailButton.setGraphic(Icons.strokeIcon(Icons.SETTINGS));

        resultsTable = ResultsTableFactory.create(preferences.fontScaleDelta());
        resultsContainer.getChildren().add(resultsTable);
        exportCsvButton.setDisable(true);

        exportSpinAnimation = new RotateTransition(Duration.seconds(0.8), exportSpinner);
        exportSpinAnimation.setByAngle(360);
        exportSpinAnimation.setCycleCount(Animation.INDEFINITE);
        exportSpinAnimation.setInterpolator(Interpolator.LINEAR);
        refreshStatusBar();

        executionTable = ExecutionTableFactory.create();
        VBox.setVgrow(executionTable, Priority.ALWAYS);
        executionSummaryLabel = new Label();
        executionSummaryLabel.getStyleClass().add("exec-summary");
        HBox executionHeader = new HBox(executionSummaryLabel);
        executionHeader.getStyleClass().add("exec-header");
        VBox executionWrapper = new VBox(executionHeader, executionTable);
        executionContainer.getChildren().add(executionWrapper);
        updateExecutionSummary();

        ListView<DiagnosticEntry> diagnosticListView = new ListView<>(diagnosticLog);
        // Clase propia, NO "diagnostic-log" (esa la comparten history-
        // ListView/favoritesListView, que sí son listas de verdad con filas
        // clicables — el log de Diagnóstico es texto corrido sin bordes ni
        // resaltado por fila, contra faro-java-prototipo.html; reusar la
        // misma clase le había puesto bordes por línea que no van ahí,
        // hallazgo real del usuario).
        diagnosticListView.getStyleClass().add("diagnostic-panel");
        diagnosticListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(DiagnosticEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                if (empty || entry == null) {
                    setGraphic(null);
                    return;
                }
                Label time = new Label(entry.time().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
                time.getStyleClass().add("log-timestamp");
                Label level = new Label(entry.level().name());
                level.getStyleClass().add("log-level-" + entry.level().name().toLowerCase());
                Label message = new Label(entry.message());
                message.getStyleClass().add("log-message");
                HBox row = new HBox(8, time, level, message);
                row.setAlignment(Pos.CENTER_LEFT);
                setGraphic(row);
            }
        });
        // Diagnóstico vuelve a ser su propia pestaña (ver el comentario del
        // FXML) — ya no hace falta la cabecera "Registro" que llevaba
        // cuando vivía anidada dentro de Ejecución; el nombre de la pestaña
        // ya cumple ese papel, igual que faro-java-prototipo.html (el log
        // llena toda la pestaña directo, sin cabecera propia adentro).
        diagnosticContainer.getChildren().add(diagnosticListView);

        // Contadores directo en cada pestaña ("Resultados 1,240" etc.),
        // contra faro-java-prototipo.html — el usuario se quejó de tener
        // que mirar la barra de arriba (lejos de los resultados) para ver
        // cuántas filas salieron; el diseño real pone el número justo en
        // la pestaña, donde ya está mirando.
        setTabBadge(resultsTab, "Resultados", 0);
        setTabBadge(executionTab, "Ejecución", 0);
        setTabBadge(diagnosticTab, "Diagnóstico", 0);

        historyListView.setItems(queryHistory);
        historyListView.getStyleClass().add("diagnostic-log");
        historyListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String sql, boolean empty) {
                super.updateItem(sql, empty);
                setText(empty || sql == null ? null : summarize(sql));
            }
        });
        historyListView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selected = historyListView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    addQueryTab(selected, null);
                }
            }
        });

        favoritesListView.getStyleClass().add("diagnostic-log");
        favoritesListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Favorite favorite, boolean empty) {
                super.updateItem(favorite, empty);
                setText(empty || favorite == null ? null : favorite.name());
            }
        });
        refreshFavorites();

        runButton.setGraphic(runButtonGraphic());
        runButton.setOnAction(e -> onRunButtonClicked());
        newQueryButton.setGraphic(Icons.strokeIcon(Icons.PLUS));
        openButton.setGraphic(Icons.strokeIcon(Icons.FOLDER));
        saveButton.setGraphic(Icons.strokeIcon(Icons.SAVE));
        formatButton.setGraphic(Icons.strokeIcon(Icons.ALIGN_LEFT));
        favoriteButton.setGraphic(Icons.strokeIcon(Icons.STAR));
        addDatabaseButton.setGraphic(Icons.strokeIcon(Icons.PLUS));
        updateThemeToggleIcon();
        logger.info("MainController.initialize() completo — {} servidor(es), {} base(s) sin agrupar registradas.",
                registry.servers().size(), registry.ungroupedDatabases().size());
    }

    /**
     * El tema en sí (qué hoja de estilos trae la ventana al arrancar) lo
     * decide {@code Main.java} — acá solo se expone la preferencia ya
     * cargada de disco para que {@code Main#start} sepa cuál elegir antes
     * de armar el {@code Scene}. {@link #initialize()} corre ANTES de que
     * exista un {@code Scene} (FXMLLoader.load() todavía no ha vuelto), así
     * que no se puede aplicar la hoja acá mismo — ver {@link #onToggleTheme}
     * para el cambio en caliente, que sí corre con la ventana ya armada.
     */
    boolean isDarkTheme() {
        return preferences.isDarkTheme();
    }

    String accentName() {
        return preferences.accentName();
    }

    int fontScaleDelta() {
        return preferences.fontScaleDelta();
    }

    /** Cambia entre tema claro/oscuro en caliente y lo deja guardado para la próxima vez que abra la app. */
    @FXML
    private void onToggleTheme() {
        preferences.setDarkTheme(!preferences.isDarkTheme());
        applyCurrentTheme();
        log("Tema cambiado a " + (preferences.isDarkTheme() ? "oscuro" : "claro") + ".");
    }

    /**
     * Vuelve a aplicar la hoja de estilos correspondiente al valor actual de
     * {@code preferences.isDarkTheme()} sobre la ventana principal. Separado
     * de {@link #onToggleTheme} porque el diálogo de Preferencias también
     * puede cambiar el tema (su propio combo en la pestaña Apariencia) y
     * necesita esta misma lógica para reflejarlo en caliente en la ventana
     * principal — ver el callback pasado a {@code PreferencesDialog.show}.
     */
    private void applyCurrentTheme() {
        Scene scene = connectionTree.getScene();
        scene.getStylesheets().clear();
        Theme.applyTo(scene, preferences.isDarkTheme(), preferences.accentName(), preferences.fontScaleDelta());
        // Forzado explícito, sin poder verificarlo en una ventana real (reportado por el
        // usuario, 2026-08-26: la barra de scroll del árbol de conexiones y la del grid
        // de resultados se quedan con los colores del tema anterior al cambiar en vivo,
        // aunque el resto de la ventana sí se repinta bien). El cambio a la lista de
        // stylesheets ya debería marcar toda la escena para reaplicar CSS en el próximo
        // pulso — este applyCss() lo adelanta de forma síncrona en vez de esperarlo, por
        // si el nodo interno del scrollbar (creado por su Skin, no parte del FXML) no
        // queda cubierto por ese pulso normal. Si el usuario confirma que el bug sigue
        // igual con esto, hace falta diagnóstico en vivo (mismo criterio que el bug de
        // visibilidad del scroll de esta misma fecha — log real contra el nodo real, no
        // otro intento a ciegas).
        scene.getRoot().applyCss();
        updateThemeToggleIcon();
        applyEditorFontSize();
        // La altura de fila del grid de resultados es fija (fixedCellSize, ver
        // ResultsTableFactory) — sin esto, mover el slider de tamaño de interfaz en
        // Preferencias deja el texto de las filas creciendo dentro de una altura que ya
        // no le alcanza (texto de filas contiguas superpuesto, reportado con captura
        // 2026-08-26).
        resultsTable.setFixedCellSize(ResultsTableFactory.rowHeight(preferences.fontScaleDelta()));
        // `refresh()` solo (primer intento, no alcanzó — el usuario confirmó que
        // agrandar el zoom quedó bien pero AL ACHICARLO el texto seguía cortándose,
        // mismo bug otra vez) — su propio javadoc dice que es para cuando "the
        // underlying data source has changed", no para un cambio de estilo/tamaño de
        // fuente; no fuerza que las celdas YA RENDERIZADAS por el VirtualFlow vuelvan a
        // medirse contra el `-fx-font-size` nuevo, solo repuebla valores. Vaciar y
        // volver a poner los mismos items SÍ fuerza a TableView a descartar y
        // reconstruir TODAS las celdas de cero — la única forma confiable encontrada de
        // que ninguna quede con el tamaño de fuente de antes del cambio.
        ObservableList<ObservableList<Object>> currentItems = resultsTable.getItems();
        resultsTable.setItems(FXCollections.observableArrayList());
        resultsTable.setItems(currentItems);
    }

    /** Muestra el ícono de lo que el clic va a hacer — luna (pasar a oscuro) en tema claro, sol (pasar a claro) en oscuro. */
    private void updateThemeToggleIcon() {
        themeToggleButton.setGraphic(Icons.strokeIcon(preferences.isDarkTheme() ? Icons.SUN : Icons.MOON));
    }

    /** Agrega una línea con hora al log de la pestaña Diagnóstico — más reciente arriba. */
    private void log(String message) {
        log(LogLevel.INFO, message);
    }

    private void log(LogLevel level, String message) {
        diagnosticLog.add(0, new DiagnosticEntry(LocalTime.now(), level, message));
        setTabBadge(diagnosticTab, "Diagnóstico", diagnosticLog.size());
        switch (level) {
            case ERROR -> logger.error(message);
            case WARN -> logger.warn(message);
            case DEBUG -> logger.debug(message);
            case INFO -> logger.info(message);
        }
    }

    /** Ícono + "Ejecutar" + "F5" atenuado — el prototipo muestra el atajo junto al botón, no solo en el menú. */
    private Node runButtonGraphic() {
        Label label = new Label("Ejecutar");
        label.getStyleClass().add("run-button-label");
        Label shortcut = new Label("F5");
        shortcut.getStyleClass().add("run-button-shortcut");
        HBox box = new HBox(7, Icons.fillIcon(Icons.PLAY), label, shortcut);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /** Ícono de detener + "Cancelar" — reemplaza a {@link #runButtonGraphic()} mientras {@link #queryRunning} es verdadero. */
    private Node cancelButtonGraphic() {
        Label label = new Label("Cancelar");
        label.getStyleClass().add("cancel-button-label");
        HBox box = new HBox(7, Icons.strokeIcon(Icons.STOP), label);
        box.setAlignment(Pos.CENTER_LEFT);
        return box;
    }

    /**
     * Único punto de clic del botón que antes solo decía "Ejecutar"
     * (`fx:id="runButton"`) — hallazgo real del usuario probando con 3
     * millones de filas: "no veo la opción de cancelar... que sea dentro
     * del mismo botón de correr". Mismo botón que el prototipo alterna
     * entre {@code idle}/{@code busy}: si no hay una consulta corriendo,
     * dispara {@link #onRunQuery()} como siempre; si ya hay una en curso,
     * dispara {@link #onCancelQuery()} en su lugar — el aspecto visual
     * (ícono/texto/color) lo cambian {@code task.setOnRunning}/
     * {@code setOnSucceeded}/{@code setOnFailed} dentro de
     * {@link #onRunQuery()}.
     */
    private void onRunButtonClicked() {
        if (queryRunning) {
            onCancelQuery();
        } else {
            onRunQuery();
        }
    }

    /** Vuelve el botón a su estado "Ejecutar" normal — llamado cuando el `Task` de {@link #onRunQuery()} termina, sin importar si fue con éxito, error, o cancelación (las tres pasan por `setOnSucceeded`/`setOnFailed`, ver ahí). */
    private void resetRunButton() {
        queryRunning = false;
        runButton.getStyleClass().setAll("button");
        runButton.setGraphic(runButtonGraphic());
    }

    // ---- Pestañas de consulta ----

    /** Estado de una pestaña de consulta — su editor, el archivo asociado (si ya se guardó/abrió) y si tiene cambios sin guardar. Guardado como userData del Tab. */
    private static final class QueryTabState {
        final CodeArea codeArea;
        File file;
        boolean dirty;

        QueryTabState(CodeArea codeArea) {
            this.codeArea = codeArea;
        }
    }

    /**
     * Crea una pestaña de consulta nueva con su propio {@code CodeArea}
     * independiente y la selecciona. No se puede cerrar la última pestaña
     * que quede — siempre debe haber al menos un editor abierto. Cerrar
     * una pestaña con cambios sin guardar pide confirmación primero
     * (ver {@link #confirmSaveOrDiscard}) — antes se perdían en silencio.
     */
    private Tab addQueryTab(String initialText, File file) {
        CodeArea codeArea = SqlEditorFactory.create();
        if (initialText != null && !initialText.isEmpty()) {
            codeArea.replaceText(initialText);
        }
        codeArea.setStyle("-fx-font-size: " + preferences.editorFontSize() + "px;");
        // Ctrl+Plus/Ctrl+Minus/Ctrl+0 — SOLO el tamaño de fuente de este editor (todas las
        // pestañas comparten un único valor, ver applyEditorFontSize), a propósito distinto
        // del "zoom global" que se probó 3 veces y se quitó por completo el 2026-08-22 (ese
        // escalaba TODA la interfaz: menús, botones, árbol). Filtro puesto directo en el
        // CodeArea (no un acelerador de menú global) para que solo dispare con el editor
        // enfocado, igual que documenta demo_html (agrupado bajo "Editor SQL", no global).
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
        // Ctrl+rueda del mouse/trackpad — pedido explícito del usuario (2026-08-26). Antes
        // se había dejado fuera a propósito ("riesgo real de interferir con el scroll propio
        // de RichTextFX sin poder probarlo en una ventana real") — mismo riesgo real hoy, pero
        // mitigado: sin Ctrl, el evento nunca se toca (return temprano, el scroll normal del
        // editor sigue exactamente igual); con Ctrl, se consume SIEMPRE (incluso si el tamaño
        // ya está en el límite de AppPreferences#setEditorFontSize, 10-24px) — sin esto, un
        // Ctrl+scroll en el límite dejaría pasar el evento sin consumir, y RichTextFX movería
        // el scroll vertical A LA VEZ que "no" hace zoom, justo el efecto doble que se quería
        // evitar desde el principio. 1px por evento (mismo paso que Ctrl+Plus/Minus) — un
        // acumulador para promediar gestos de trackpad (muchos eventos chicos por swipe) sería
        // más suave, pero es complejidad real sin poder probarla en vivo; mejor esfuerzo, no
        // bloqueante, mismo criterio que el resto de esta función.
        codeArea.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (!event.isControlDown()) {
                return;
            }
            preferences.setEditorFontSize(preferences.editorFontSize() + (event.getDeltaY() > 0 ? 1 : -1));
            applyEditorFontSize();
            event.consume();
        });

        QueryTabState state = new QueryTabState(codeArea);
        state.file = file;

        Tab tab = new Tab(file != null ? file.getName() : "Consulta " + (++queryTabCounter));
        tab.setContent(new VirtualizedScrollPane<>(codeArea));
        tab.setUserData(state);

        // Agregado DESPUÉS de replaceText() de arriba — si no, cargar el
        // texto inicial (ej. un archivo abierto) marcaría la pestaña como
        // "con cambios sin guardar" apenas se crea, lo cual sería falso.
        codeArea.textProperty().addListener((obs, oldText, newText) -> {
            if (!state.dirty) {
                state.dirty = true;
                tab.setText("● " + tab.getText());
            }
        });

        tab.setOnCloseRequest(event -> {
            if (queryTabPane.getTabs().size() <= 1) {
                event.consume();
                return;
            }
            if (state.dirty && !confirmSaveOrDiscard(tab, state)) {
                event.consume();
            }
        });

        queryTabPane.getTabs().add(tab);
        queryTabPane.getSelectionModel().select(tab);
        return tab;
    }

    /**
     * Reaplica {@code preferences.editorFontSize()} a TODAS las pestañas de
     * consulta abiertas (una sola preferencia compartida, no una por
     * pestaña) — llamado al arrancar, cada vez que se abre una pestaña
     * nueva (ver {@link #addQueryTab}, que ya lo pone en la suya al
     * crearla, esto cubre las que YA estaban abiertas), desde
     * Preferencias → Apariencia al guardar, y desde los atajos
     * Ctrl+Plus/Ctrl+Minus/Ctrl+0 del editor mismo.
     */
    private void applyEditorFontSize() {
        String style = "-fx-font-size: " + preferences.editorFontSize() + "px;";
        for (Tab tab : queryTabPane.getTabs()) {
            if (tab.getUserData() instanceof QueryTabState state) {
                state.codeArea.setStyle(style);
            }
        }
    }

    /**
     * Pregunta qué hacer al cerrar una pestaña con cambios sin guardar —
     * Guardar / Descartar / Cancelar. Devuelve {@code true} si está bien
     * seguir cerrando la pestaña (se guardó, o el usuario decidió
     * descartar), {@code false} si hay que vetar el cierre (canceló, o
     * el guardado en sí falló/se canceló a mitad de camino).
     */
    private boolean confirmSaveOrDiscard(Tab tab, QueryTabState state) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Cambios sin guardar");
        alert.setHeaderText("La pestaña \"" + tab.getText().replace("● ", "") + "\" tiene cambios sin guardar.");
        alert.setContentText("¿Qué quieres hacer antes de cerrarla?");
        ButtonType saveType = new ButtonType("Guardar", ButtonBar.ButtonData.OK_DONE);
        ButtonType discardType = new ButtonType("Descartar cambios");
        ButtonType cancelType = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(saveType, discardType, cancelType);
        alert.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(alert);

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
     * Llamado desde {@code Main#start} cuando el usuario intenta cerrar la
     * ventana entera (la X del sistema operativo, no el botón de cerrar de
     * una pestaña) — sin esto, cerrar la app con una pestaña con cambios
     * sin guardar los perdía en silencio, el mismo problema que
     * {@link #confirmSaveOrDiscard} ya resuelve por pestaña, así que lo
     * reusa acá una vez por cada pestaña con cambios pendientes. Devuelve
     * {@code false} en cuanto una de esas confirmaciones se cancela — el
     * resto de pestañas dirty que quedaban por preguntar ya no se tocan.
     */
    boolean confirmCloseAllTabs() {
        for (Tab tab : List.copyOf(queryTabPane.getTabs())) {
            QueryTabState state = (QueryTabState) tab.getUserData();
            if (state.dirty && !confirmSaveOrDiscard(tab, state)) {
                return false;
            }
        }
        return true;
    }

    @FXML
    private void onNewQueryTab() {
        addQueryTab("", null);
    }

    private QueryTabState currentTabState() {
        Tab tab = queryTabPane.getSelectionModel().getSelectedItem();
        return tab == null ? null : (QueryTabState) tab.getUserData();
    }

    // ---- Buscar en el script ----

    /** Muestra la barra de búsqueda (una sola, compartida entre pestañas — busca siempre en la pestaña activa) y le da foco. */
    @FXML
    private void onFindInScript() {
        findBar.setVisible(true);
        findBar.setManaged(true);
        findStatusLabel.setText("");
        findField.requestFocus();
        findField.selectAll();
    }

    @FXML
    private void onCloseFindBar() {
        findBar.setVisible(false);
        findBar.setManaged(false);
        QueryTabState state = currentTabState();
        if (state != null) {
            state.codeArea.requestFocus();
        }
    }

    @FXML
    private void onFindNext() {
        findInCurrentTab(true);
    }

    @FXML
    private void onFindPrevious() {
        findInCurrentTab(false);
    }

    /**
     * Busca la siguiente/anterior aparición de {@code findField} en el
     * {@code CodeArea} de la pestaña activa, insensible a mayúsculas, y la
     * selecciona. Si no hay más ocurrencias en esa dirección desde el
     * cursor, da la vuelta al principio/final del texto (búsqueda
     * circular) — no un "sin resultados" apenas se pasa del final.
     */
    private void findInCurrentTab(boolean forward) {
        QueryTabState state = currentTabState();
        String needle = findField.getText();
        if (state == null || needle.isEmpty()) {
            return;
        }
        CodeArea codeArea = state.codeArea;
        String haystackLower = codeArea.getText().toLowerCase(Locale.ROOT);
        String needleLower = needle.toLowerCase(Locale.ROOT);

        int caret = codeArea.getCaretPosition();
        int index;
        if (forward) {
            index = haystackLower.indexOf(needleLower, caret);
            if (index < 0) {
                index = haystackLower.indexOf(needleLower);
            }
        } else {
            int searchFrom = caret - needle.length() - 1;
            index = searchFrom >= 0 ? haystackLower.lastIndexOf(needleLower, searchFrom) : -1;
            if (index < 0) {
                index = haystackLower.lastIndexOf(needleLower);
            }
        }

        if (index < 0) {
            findStatusLabel.setText("Sin resultados");
            return;
        }
        findStatusLabel.setText("");
        codeArea.selectRange(index, index + needle.length());
        codeArea.requestFollowCaret();
    }

    /**
     * Editar → Formatear SQL (Ctrl+L) — reescribe el texto de la pestaña
     * activa con {@link SqlFormatter#format}. Marca la pestaña como "con
     * cambios sin guardar" igual que cualquier otra edición, ya que sí
     * cambia el texto real.
     */
    @FXML
    private void onFormatSql() {
        QueryTabState state = currentTabState();
        if (state == null) {
            return;
        }
        state.codeArea.replaceText(SqlFormatter.format(state.codeArea.getText()));
        log("SQL formateado.");
    }

    /**
     * Editar → Autocompletado (Ctrl+Espacio) — ver el javadoc de
     * {@link SqlAutocomplete}. La base "activa" para sugerir tabla/columnas
     * reales es la primera marcada en el árbol (mismo criterio que
     * "Explicar plan") — si no hay ninguna marcada, sigue funcionando
     * igual que antes, solo con palabras clave.
     */
    @FXML
    private void onAutocomplete() {
        QueryTabState state = currentTabState();
        if (state == null) {
            return;
        }
        List<DatabaseEntry> selected = ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot())
            .stream()
            .filter(CheckBoxTreeItem::isSelected)
            .map(item -> (DatabaseEntry) item.getValue())
            .toList();
        DatabaseEntry activeDb = selected.isEmpty() ? null : selected.get(0);
        SqlAutocomplete.show(state.codeArea, activeDb, credentials, pool);
    }

    // ---- Diálogos ----

    @FXML
    private void onAddDatabase() {
        AddDatabaseDialog.showForAdd(connectionTree.getScene().getWindow(), credentials, preferences)
            .ifPresent(entry -> {
                registry.ungroupedDatabases().add(entry);
                refreshTree();
                log("Base agregada: " + entry.alias());
            });
    }

    /**
     * "Todas" del panel de Conexiones — marca (o desmarca, si ya estaban
     * todas marcadas) todas las bases del árbol de un solo clic, sin
     * importar si están agrupadas bajo un servidor o sueltas. El contador
     * "N bases seleccionadas" de la barra de herramientas ya está atado
     * (`Bindings`) a {@code selectedProperty()} de cada una — se actualiza
     * solo, no hace falta tocarlo acá.
     */
    @FXML
    private void onSelectAllDatabases() {
        List<CheckBoxTreeItem<Object>> items = ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot());
        boolean allSelected = !items.isEmpty() && items.stream().allMatch(CheckBoxTreeItem::isSelected);
        logger.debug("onSelectAllDatabases: {} ítem(s), {} → {}", items.size(),
                allSelected ? "todas marcadas" : "no todas marcadas", allSelected ? "desmarcar todas" : "marcar todas");
        for (CheckBoxTreeItem<Object> item : items) {
            item.setSelected(!allSelected);
        }
    }

    @FXML
    private void onOpenCredentials() {
        if (CredentialsDialog.show(connectionTree.getScene().getWindow(), credentials, preferences)) {
            statusLabel.setText("Credenciales por defecto guardadas.");
            log("Credenciales por defecto guardadas.");
        }
    }

    @FXML
    private void onDiscoverDatabases() {
        List<DatabaseEntry> found =
                DiscoverDialog.show(connectionTree.getScene().getWindow(), credentials, preferences);
        if (!found.isEmpty()) {
            registry.ungroupedDatabases().addAll(found);
            refreshTree();
            statusLabel.setText(found.size() + " base(s) agregada(s) desde el escaneo.");
            log(found.size() + " base(s) agregada(s) desde el escaneo de bases de datos.");
        }
    }

    /** "Descubrir bases en esta IP…" del menú contextual de una fila (ver {@link ConnectionTreeCell}) — mismo diálogo que {@link #onDiscoverDatabases()}, precargado con el host de esa base para no tener que retiparlo. */
    private void onDiscoverForDatabase(DatabaseEntry db) {
        List<DatabaseEntry> found =
                DiscoverDialog.show(connectionTree.getScene().getWindow(), credentials, preferences, db.host());
        if (!found.isEmpty()) {
            registry.ungroupedDatabases().addAll(found);
            refreshTree();
            statusLabel.setText(found.size() + " base(s) agregada(s) desde el escaneo.");
            log(found.size() + " base(s) agregada(s) desde el escaneo de " + db.host() + ".");
        }
    }

    @FXML
    private void onImportCsv() {
        CsvImportDialog.show(connectionTree.getScene().getWindow(), registry.allDatabases(), credentials, pool,
                preferences);
    }

    @FXML
    private void onOpenPreferences() {
        // .show() bloquea (showAndWait) hasta que el diálogo cierra, así que
        // acá abajo ya es seguro destildar el engrane — sin esto, al ser
        // ToggleButton (ver por qué en styles.css) se hubiera quedado
        // marcado como "activo" para siempre después del primer clic, nadie
        // más lo destilda.
        PreferencesDialog.show(connectionTree.getScene().getWindow(), preferences, this::applyCurrentTheme);
        settingsRailButton.setSelected(false);
    }

    @FXML
    private void onShowShortcuts() {
        PreferencesDialog.showShortcuts(connectionTree.getScene().getWindow(), preferences, this::applyCurrentTheme);
    }

    @FXML
    private void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("Acerca de Faro");
        alert.setHeaderText("Faro — cliente SQL multi-base");
        alert.setContentText("Reemplazo en JavaFX del cliente Flutter original. "
                + "Consulta ligera y masiva contra muchas bodegas/sucursales a la vez.\n\n"
                + "Java " + System.getProperty("java.version") + " · JavaFX");
        alert.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(alert);
        alert.showAndWait();
    }

    /**
     * {@code Alert}/{@code TextInputDialog} (los dos extienden
     * {@code Dialog}) arman su propia {@code Scene} interna que NO hereda
     * automáticamente las hojas de estilo de la ventana principal — sin
     * esto, se mostraban con el diálogo nativo de Modena sin tema,
     * desfasados del resto de la app (hallazgo real durante el barrido
     * pedido por el usuario tras encontrar varios de estos casos).
     *
     * <p>De paso les da jerarquía visual real a los botones — antes todos
     * se veían igual de "importantes" (límite conocido, documentado desde
     * el barrido de estilos, nunca arreglado hasta ahora). {@link
     * ButtonBar.ButtonData#isDefaultButton()} ya distingue cuál botón es
     * la acción principal (`OK_DONE`, `YES`, etc. — `ButtonType.OK` lo trae
     * así de fábrica) sin tener que adivinar comparando texto; ese botón
     * se pinta como {@code .button} (el acento sólido que ya usa el resto
     * de la app), el resto como {@code .button-secondary}.
     */
    private void applyThemeToAlert(Dialog<?> dialog) {
        Theme.applyTo(dialog.getDialogPane().getScene(), preferences.isDarkTheme(), preferences.accentName(), preferences.fontScaleDelta());
        for (ButtonType buttonType : dialog.getDialogPane().getButtonTypes()) {
            Node button = dialog.getDialogPane().lookupButton(buttonType);
            if (button != null) {
                button.getStyleClass().add(
                        buttonType.getButtonData().isDefaultButton() ? "button" : "button-secondary");
            }
        }
    }

    @FXML
    private void onExit() {
        // Platform.exit() no dispara Stage#setOnCloseRequest (eso solo pasa
        // con la X del sistema/Alt+F4 nativo) — sin este chequeo acá, "Archivo
        // → Salir" se saltaba por completo el aviso de cambios sin guardar.
        if (confirmCloseAllTabs()) {
            Platform.exit();
        }
    }

    /**
     * Prueba la conexión de cada base registrada (no solo las marcadas) y
     * actualiza su punto de estado en el árbol. Usa
     * {@code connectionTree.refresh()} en vez de {@link #refreshTree()} a
     * propósito — {@code refreshTree()} reconstruye todo el árbol
     * (`CheckBoxTreeItem` nuevos), lo que borraría cualquier casilla que
     * el usuario ya haya marcado; `refresh()` solo repinta las celdas
     * visibles con los datos actuales sin tocar la estructura.
     */
    @FXML
    private void onTestAllConnections() {
        List<DatabaseEntry> all = registry.allDatabases();
        if (all.isEmpty()) {
            statusLabel.setText("No hay bases configuradas.");
            return;
        }

        // Hallazgo en vivo del usuario (2026-08-25, bases reales de cliente): "N/M
        // exitosas" no dice CUÁLES fallaron ni por qué — para corregir un nombre de BD,
        // usuario, red o permisos había que ir a buscar al log del archivo. failures se
        // llena solo dentro de call() (un único hilo de fondo, sin concurrencia real) y
        // se lee después en setOnSucceeded, que la máquina de estados de Task garantiza
        // que corre después de que call() ya terminó del todo.
        List<String> failures = new ArrayList<>();
        Task<Long> task = new Task<>() {
            @Override
            protected Long call() {
                long connected = 0;
                for (DatabaseEntry db : all) {
                    Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                    if (creds.isEmpty()) {
                        failures.add(db.alias() + ": sin usuario/contraseña guardados");
                        continue;
                    }
                    Platform.runLater(() -> {
                        db.setConnectionStatus(DatabaseEntry.ConnectionStatus.TESTING);
                        connectionTree.refresh();
                    });
                    try (Connection conn = DriverManager.getConnection(
                            db.jdbcUrl(), creds.get().user(), creds.get().password())) {
                        db.setConnectionStatus(DatabaseEntry.ConnectionStatus.CONNECTED);
                        connected++;
                    } catch (SQLException e) {
                        db.setConnectionStatus(DatabaseEntry.ConnectionStatus.FAILED);
                        failures.add(db.alias() + ": " + e.getMessage());
                    }
                    Platform.runLater(connectionTree::refresh);
                }
                return connected;
            }
        };
        task.setOnRunning(e -> statusLabel.setText("Probando " + all.size() + " conexión(es)…"));
        task.setOnSucceeded(e -> {
            long connected = task.getValue();
            statusLabel.setText(connected + "/" + all.size() + " conexión(es) exitosa(s)."
                    + (failures.isEmpty() ? "" : " Ver Diagnóstico."));
            log("Probar todas las conexiones: " + connected + "/" + all.size() + " exitosas.");
            for (String failure : failures) {
                log(LogLevel.WARN, "Conexión falló — " + failure);
            }
        });

        Thread thread = new Thread(task, "faro-test-all");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onOpenFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Abrir archivo .sql");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQL", "*.sql"));
        File file = chooser.showOpenDialog(connectionTree.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            String content = Files.readString(file.toPath());
            addQueryTab(content, file);
            statusLabel.setText("Abierto: " + file.getName());
            logger.info("onOpenFile: {} ({} caracteres)", file.getAbsolutePath(), content.length());
        } catch (IOException e) {
            logger.warn("No se pudo abrir el archivo {}", file.getAbsolutePath(), e);
            statusLabel.setText("Error al abrir el archivo: " + e.getMessage());
        }
    }

    @FXML
    private void onSaveFile() {
        Tab tab = queryTabPane.getSelectionModel().getSelectedItem();
        QueryTabState state = currentTabState();
        if (tab == null || state == null) {
            return;
        }
        if (state.file == null) {
            onSaveFileAs();
            return;
        }
        writeScriptTo(tab, state, state.file);
    }

    @FXML
    private void onSaveFileAs() {
        Tab tab = queryTabPane.getSelectionModel().getSelectedItem();
        QueryTabState state = currentTabState();
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
        return chooser.showSaveDialog(connectionTree.getScene().getWindow());
    }

    private void writeScriptTo(Tab tab, QueryTabState state, File file) {
        try {
            Files.writeString(file.toPath(), state.codeArea.getText());
            tab.setText(file.getName());
            state.dirty = false;
            statusLabel.setText("Guardado: " + file.getName());
            log("Script guardado en " + file.getName());
        } catch (IOException e) {
            logger.warn("No se pudo guardar el script en {}", file.getAbsolutePath(), e);
            statusLabel.setText("Error al guardar: " + e.getMessage());
        }
    }

    @FXML
    private void onExportResultsCsv() {
        if (resultsTable.getItems().isEmpty()) {
            statusLabel.setText("No hay resultados para exportar.");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exportar resultados a CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        // Nombre sugerido inteligente, a pedido del usuario ("que se
        // autocomplete el nombre del csv por el nombre de la bd consultada,
        // la fecha y hora, tabla, y filtros clave") — analiza el SQL que
        // produjo el resultado actual (capturado en onRunQuery/onExplainPlan,
        // no releído del editor, que para este punto pudo haber cambiado).
        // Ver CsvFileNamer para los límites conocidos de esta heurística.
        chooser.setInitialFileName(CsvFileNamer.suggest(lastResultDatabaseLabel, lastResultSql, LocalDateTime.now()) + ".csv");
        File file = chooser.showSaveDialog(connectionTree.getScene().getWindow());
        if (file == null) {
            return;
        }

        // Encabezados en el hilo de la UI (barato, solo nombres de
        // columna) — pero YA NO se copia todo el resultado a una lista
        // aparte de strings escapados antes de arrancar el Task. Con un
        // resultado grande de verdad (varias bodegas x 500,000 filas cada
        // una) esa copia entera duplicaba en memoria TODO lo que
        // resultsTable ya tenía guardado — el congelamiento de 5 segundos
        // y el OutOfMemoryError que reportó el usuario pasaban AQUÍ,
        // adentro de este método, antes siquiera de que el Task arrancara
        // (por eso tampoco se veía el spinner de "Exportando…": solo se
        // activa cuando el Task arranca, y esto tronaba antes de llegar
        // ahí).
        //
        // Arreglo real: solo se guarda la REFERENCIA a la lista de filas
        // actual, no una copia — segura de iterar desde otro hilo porque
        // ResultsTableFactory#populate SIEMPRE reemplaza la lista completa
        // (table.setItems(nueva)), nunca la muta en el lugar; si el
        // usuario corre otra consulta mientras exporta, esta referencia
        // vieja se queda intacta y sigue siendo válida, la tabla visible
        // simplemente pasa a apuntar a una lista nueva y distinta. El
        // escapado de cada valor se hace FILA POR FILA dentro del Task,
        // escrito al archivo y descartado al vuelo — nunca vuelve a
        // existir una segunda copia completa del resultado en memoria.
        List<String> headers = resultsTable.getColumns().stream()
                .map(TableColumn::getText)
                .toList();
        ObservableList<ObservableList<Object>> rows = resultsTable.getItems();
        logger.info("onExportResultsCsv: exportando {} fila(s) a {}", rows.size(), file.getAbsolutePath());

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws IOException {
                try (BufferedWriter writer = Files.newBufferedWriter(file.toPath())) {
                    writer.write(String.join(",", headers));
                    writer.newLine();
                    int written = 0;
                    int total = rows.size();
                    StringBuilder line = new StringBuilder();
                    for (List<Object> row : rows) {
                        line.setLength(0);
                        for (int i = 0; i < row.size(); i++) {
                            if (i > 0) {
                                line.append(',');
                            }
                            Object value = row.get(i);
                            line.append(csvEscape(value == null ? "" : value.toString()));
                        }
                        writer.write(line.toString());
                        writer.newLine();
                        written++;
                        if (written % 500 == 0) {
                            updateMessage(written + " de " + total + " fila(s)…");
                        }
                    }
                }
                return null;
            }
        };
        task.messageProperty().addListener((obs, oldMsg, newMsg) -> {
            statusLabel.setText("Exportando: " + newMsg);
            exportSpinnerLabel.setText(newMsg);
        });
        task.setOnRunning(e -> {
            statusLabel.setText("Exportando a " + file.getName() + "…");
            exportSpinnerLabel.setText("Exportando…");
            showExportSpinner(true);
        });
        task.setOnSucceeded(e -> {
            statusLabel.setText("Exportado: " + file.getName());
            log("Resultados exportados a " + file.getName() + " (" + rows.size() + " fila(s)).");
            showExportSpinner(false);
            refreshStatusBar();
        });
        task.setOnFailed(e -> {
            statusLabel.setText("Error al exportar: " + task.getException().getMessage());
            logger.error("onExportResultsCsv: falló exportando a {}", file.getAbsolutePath(), task.getException());
            log(LogLevel.ERROR, "Error al exportar a " + file.getName() + ": " + task.getException().getMessage());
            showExportSpinner(false);
            refreshStatusBar();
        });

        Thread thread = new Thread(task, "faro-export-csv");
        thread.setDaemon(true);
        thread.start();
    }

    private static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /** Muestra/oculta el spinner de "Exportando…" de la barra de estado, igual que faro-java-prototipo.html — antes solo había texto plano, sin ninguna señal de que algo seguía en curso. */
    private void showExportSpinner(boolean exporting) {
        exportSpinnerBox.setVisible(exporting);
        exportSpinnerBox.setManaged(exporting);
        if (exporting) {
            exportSpinAnimation.playFromStart();
        } else {
            exportSpinAnimation.stop();
        }
    }

    /**
     * Refresca la barra de estado de abajo con datos reales — pool de
     * conexiones (HikariCP), timeout/fetch configurados, memoria usada por
     * la JVM, y motor(es)/JDK reales. Igual que faro-java-prototipo.html,
     * que muestra exactamente estos mismos datos (antes la barra solo
     * tenía "Faro" y el mensaje de estado puntual). Se llama al arrancar,
     * después de cada ejecución/exportación (para que el resultado quede
     * reflejado sin esperar el próximo tick), y además cada
     * {@link #STATUS_BAR_REFRESH_INTERVAL_MILLIS} vía
     * {@link #startStatusBarRefresh()} — pool activo/total y memoria
     * cambian en cualquier momento, no solo cuando algo termina (hallazgo
     * real del usuario probando en vivo, 2026-08-25: "lo veo todo
     * estático"). Timeout/fetch y motor(es)/JDK sí son estáticos a
     * propósito (preferencias/datos cacheados que no cambian en caliente),
     * recalcularlos en cada tick es gratis igual, no vale la pena separar
     * la función en dos solo por eso.
     */
    private void refreshStatusBar() {
        ConnectionPoolManager.PoolSummary poolSummary = pool.poolSummary();
        String dotStyleClass = poolSummary.databaseCount() > 0 ? "pool-dot-connected" : "pool-dot-idle";
        if (!poolStatusDot.getStyleClass().contains(dotStyleClass)) {
            poolStatusDot.getStyleClass().removeIf(c -> c.startsWith("pool-dot-"));
            poolStatusDot.getStyleClass().add(dotStyleClass);
        }
        poolStatusLabel.setText(
                poolSummary.databaseCount() + (poolSummary.databaseCount() == 1 ? " conexión · pool " : " conexiones · pool ")
                        + poolSummary.activeConnections() + "/" + poolSummary.totalConnections());

        timeoutFetchLabel.setText("Timeout " + preferences.defaultQueryTimeoutSeconds() + " s · fetch " + preferences.fetchSize());

        long usedMb = (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024);
        memoryLabel.setText("Memoria " + usedMb + " MB");

        String engines = engineVersions.entrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().label()))
                .map(e -> e.getKey().label() + " " + e.getValue())
                .collect(Collectors.joining(" · "));
        String jdk = "JDK " + Runtime.version().feature();
        engineJdkLabel.setText(engines.isEmpty() ? jdk : engines + " · " + jdk);
    }

    /**
     * SQL a correr para "Ejecutar"/"Explicar plan": si el usuario tiene
     * texto seleccionado con el mouse en el editor, se corre SOLO eso — si
     * no hay selección, el script completo, como antes. Hallazgo real del
     * usuario (2026-08-22): "si yo selecciono un script parcial con el
     * mouse no se corre eso específicamente, si no que toma igual todo el
     * script" — antes {@code onRunQuery}/{@code onExplainPlan} leían
     * {@code codeArea.getText()} directo, ignorando por completo cualquier
     * selección. Mismo criterio que cualquier cliente SQL de escritorio
     * (pgAdmin, SSMS, DataGrip): seleccionar una sentencia y correrla sola,
     * sin tocar el resto del script, es un flujo de trabajo real, no un
     * caso raro.
     */
    private static String sqlToRun(QueryTabState state) {
        String selected = state.codeArea.getSelectedText();
        return selected.isBlank() ? state.codeArea.getText() : selected;
    }

    /**
     * Corre el texto de la pestaña de consulta activa (o solo la selección,
     * ver {@link #sqlToRun}) contra todas las bases marcadas en el árbol —
     * ver {@link QueryExecutionService}. Conectado al botón "Ejecutar" de
     * la barra de herramientas y al ítem de menú "Consulta → Ejecutar en
     * las bases seleccionadas".
     */
    @FXML
    private void onRunQuery() {
        // El botón de la barra ya no puede disparar esto mientras corre
        // (se vuelve "Cancelar", ver #onRunButtonClicked) — pero el
        // acelerador F5 del menú sigue llamando a este método directo, sin
        // pasar por ese dispatcher. Sin este guard, F5 a medio de una
        // corrida larga (ej. 3 millones de filas) arrancaría una SEGUNDA
        // ejecución encima de la primera, dos Task escribiendo a la vez
        // sobre currentExecutionRows/executionTable.
        if (queryRunning) {
            return;
        }
        QueryTabState state = currentTabState();
        if (state == null) {
            return;
        }
        String sql = sqlToRun(state);
        if (sql.isBlank()) {
            statusLabel.setText("Escribe una consulta primero.");
            return;
        }

        List<DatabaseEntry> selected = ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot())
            .stream()
            .filter(CheckBoxTreeItem::isSelected)
            .map(item -> (DatabaseEntry) item.getValue())
            .toList();
        if (selected.isEmpty()) {
            statusLabel.setText("Selecciona al menos una base de datos.");
            return;
        }

        addToHistory(sql);
        // Directo a SLF4J, sin pasar por log()/LogLevel — el usuario ya pidió explícitamente
        // que "Ejecutando consulta..." NO aparezca en la pestaña Diagnóstico (se sentía
        // duplicado con las filas de Ejecución), pero para el archivo de log sigue siendo el
        // punto de partida de toda la trazabilidad de esta corrida.
        logger.info("onRunQuery: {} base(s) seleccionadas — {}", selected.size(),
                selected.stream().map(DatabaseEntry::alias).collect(Collectors.joining(", ")));

        Map<String, ExecutionStatus> statusByDatabaseId = new LinkedHashMap<>();
        for (DatabaseEntry db : selected) {
            statusByDatabaseId.put(db.id(), new ExecutionStatus(db.alias(), db.host() + ":" + db.port()));
        }
        currentExecutionRows = List.copyOf(statusByDatabaseId.values());
        lastExecutionStartTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        executionTable.setItems(FXCollections.observableArrayList(currentExecutionRows));
        for (ExecutionStatus status : currentExecutionRows) {
            status.stateProperty().addListener((obs, oldState, newState) -> updateExecutionSummary());
        }
        updateExecutionSummary();

        Task<QueryResult> task = QueryExecutionService.execute(
                new ArrayList<>(selected), credentials, pool, statusByDatabaseId, sql,
                preferences.maxConcurrentDatabases(), preferences.fetchSize(), engineVersions);
        // Cambio de pestaña automático, a pedido del usuario: Ejecución
        // apenas arranca (para que se vea la corrida en vivo, mismo criterio
        // que "el usuario tiene que ver que se está cargando la
        // información"), Resultados apenas termina con éxito. En un fallo
        // catastrófico del task (setOnFailed, más abajo) se deja al usuario
        // en Ejecución en vez de brincar a Resultados — ahí es donde se ve
        // qué base falló, un Resultados vacío no ayudaría.
        task.setOnRunning(e -> {
            executionTab.getTabPane().getSelectionModel().select(executionTab);
            queryRunning = true;
            runButton.getStyleClass().setAll("button-danger");
            runButton.setGraphic(cancelButtonGraphic());
        });
        // Nada de mensajes de "ejecutando"/"terminado"/"error" en la barra
        // de estado de arriba (junto al botón de tema) — el usuario dejó
        // explícito que no le sirve de nada ahí, viendo los resultados
        // abajo. Tampoco en el log de Diagnóstico — con Ejecución y
        // Diagnóstico ya fusionadas en la misma pestaña, "Ejecutando
        // consulta en N base(s)"/"Consulta terminada: ..." quedaba
        // duplicado con lo que cada fila de Ejecución ya muestra (estado,
        // filas, tiempo, y el mensaje de error completo) — hallazgo real
        // del usuario ("veo ahí cosas repetidas"). El único log que sigue
        // teniendo sentido es el de más abajo (task.setOnFailed) — un
        // fallo catastrófico del task completo no queda reflejado en
        // ninguna fila individual.
        // Insumos para el nombre sugerido de "Exportar CSV" (ver
        // CsvFileNamer) — capturados aquí, no leídos de vuelta del editor al
        // exportar, porque para entonces el usuario ya pudo haber cambiado
        // de pestaña o editado el script.
        String resultDatabaseLabel = selected.size() == 1 ? selected.get(0).alias() : selected.size() + "-bases";
        task.setOnSucceeded(e -> {
            QueryResult result = task.getValue();
            // "Resultados" sin columnas significa que NINGUNA base tuvo
            // éxito (columnsRef solo se llena tras un executeQuery real, ver
            // QueryExecutionService#runOne) — el Task en sí "tuvo éxito"
            // igual (no lanzó excepción, cada base fallida se registra como
            // error y se sigue con las demás), así que saltar a Resultados
            // sin condición mandaba al usuario a una tabla vacía cada vez
            // que TODAS las bases fallaban. Hallazgo real del usuario,
            // probando con los contenedores Docker apagados (6/6 con
            // error): "por qué verga voy a ver resultados si hay error".
            boolean anySucceeded = !result.columns().isEmpty();
            logger.info("onRunQuery: corrida terminada — {} fila(s), {} base(s) con error{}", result.rows().size(),
                    result.errors().size(), result.errors().isEmpty() ? "" : ": " + result.errors());
            if (anySucceeded) {
                ResultsTableFactory.populate(resultsTable, result.columns(), result.rows());
            }
            setTabBadge(resultsTab, "Resultados", result.rows().size());
            updateResultsSummary(result.rows().size());
            lastResultDatabaseLabel = resultDatabaseLabel;
            lastResultSql = sql;
            if (anySucceeded) {
                resultsTab.getTabPane().getSelectionModel().select(resultsTab);
            }
            refreshStatusBar();
            resetRunButton();
        });
        task.setOnFailed(e -> {
            resetRunButton();
            // Traza completa (con stack) solo al archivo — el mensaje corto ya va al usuario
            // vía log()/Diagnóstico, pero para depurar un fallo catastrófico real del Task
            // (no un error normal de una base, que ya se ve por fila en Ejecución) hace falta
            // el stack completo.
            logger.error("onRunQuery: fallo catastrófico del Task", task.getException());
            log(LogLevel.ERROR, "Error al ejecutar: " + task.getException().getMessage());
            refreshStatusBar();
        });

        Thread thread = new Thread(task, "faro-query-exec");
        thread.setDaemon(true);
        thread.start();
    }

    /** Habilita/deshabilita "Exportar CSV" (sin filas no hay nada que exportar) — la cuenta de filas en sí vive en el badge de la pestaña Resultados, ver {@link #setTabBadge}. */
    private void updateResultsSummary(int rowCount) {
        exportCsvButton.setDisable(rowCount == 0);
    }

    /** Cabecera de la pestaña Ejecución — "N bases · M correctas · K con error(es)", contra faro-java-prototipo.html. */
    private void updateExecutionSummary() {
        if (currentExecutionRows.isEmpty()) {
            executionSummaryLabel.setText("Sin ejecuciones todavía.");
            return;
        }
        long succeeded = currentExecutionRows.stream()
                .filter(s -> s.stateProperty().get() == ExecutionStatus.State.SUCCEEDED).count();
        long failed = currentExecutionRows.stream()
                .filter(s -> s.stateProperty().get() == ExecutionStatus.State.FAILED).count();
        long cancelled = currentExecutionRows.stream()
                .filter(s -> s.stateProperty().get() == ExecutionStatus.State.CANCELLED).count();
        StringBuilder summary = new StringBuilder(lastExecutionStartTime + " · " + currentExecutionRows.size() + " base(s) · " + succeeded + " correcta(s)");
        if (failed > 0) {
            summary.append(" · ").append(failed).append(" con error(es)");
        }
        if (cancelled > 0) {
            summary.append(" · ").append(cancelled).append(" cancelada(s)");
        }
        executionSummaryLabel.setText(summary.toString());
        setTabBadge(executionTab, "Ejecución", currentExecutionRows.size());
    }

    /** Nombre + contador tipo pill directo en la pestaña — "Resultados 1,240", contra faro-java-prototipo.html. 0 no muestra pill (nada que contar todavía). */
    private void setTabBadge(Tab tab, String name, int count) {
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass().add("tab-name");
        HBox graphic;
        if (count > 0) {
            Label badge = new Label(String.valueOf(count));
            badge.getStyleClass().add("tab-badge");
            graphic = new HBox(6, nameLabel, badge);
        } else {
            graphic = new HBox(nameLabel);
        }
        graphic.setAlignment(Pos.CENTER_LEFT);
        tab.setText(null);
        tab.setGraphic(graphic);
    }

    /**
     * Cancela todas las bases que sigan en {@code RUNNING} de la última
     * corrida — el botón "Cancelar" de cada fila en la pestaña Ejecución
     * hace lo mismo, pero para una sola base (ver
     * {@code ExecutionTableFactory}).
     */
    @FXML
    private void onCancelQuery() {
        List<ExecutionStatus> running = currentExecutionRows.stream()
                .filter(status -> status.stateProperty().get() == ExecutionStatus.State.RUNNING)
                .toList();
        logger.info("onCancelQuery: clic en 'Cancelar' — {} base(s) en RUNNING: {}", running.size(),
                running.stream().map(status -> status.databaseAliasProperty().get()).collect(Collectors.joining(", ")));
        running.forEach(ExecutionStatus::cancelQuery);
        if (!running.isEmpty()) {
            log("Cancelando " + running.size() + " base(s).");
        }
    }

    /** Agrega al historial (más reciente arriba, sin duplicados, tope de {@link #MAX_HISTORY}) — no se persiste entre sesiones, ver README. */
    private void addToHistory(String sql) {
        queryHistory.remove(sql);
        queryHistory.add(0, sql);
        while (queryHistory.size() > MAX_HISTORY) {
            queryHistory.remove(queryHistory.size() - 1);
        }
    }

    /** Texto de una sola línea para la celda del historial — el SQL guardado en sí conserva sus saltos de línea reales, esto es solo para mostrarlo compacto. */
    private static String summarize(String sql) {
        String oneLine = sql.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }

    // ---- Favoritos ----

    @FXML
    private void onSaveFavorite() {
        QueryTabState state = currentTabState();
        if (state == null) {
            return;
        }
        String sql = state.codeArea.getText();
        if (sql.isBlank()) {
            statusLabel.setText("Escribe una consulta primero.");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Guardar como favorito");
        dialog.setHeaderText(null);
        dialog.setContentText("Nombre del favorito:");
        dialog.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(dialog);
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        String trimmedName = name.get().trim();
        favorites.add(new Favorite(UUID.randomUUID().toString(), trimmedName, sql));
        refreshFavorites();
        statusLabel.setText("Favorito guardado: " + trimmedName);
        log("Favorito guardado: " + trimmedName);
    }

    @FXML
    private void onOpenFavorite() {
        Favorite selected = favoritesListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        addQueryTab(selected.sql(), null);
        log("Favorito abierto: " + selected.name());
    }

    @FXML
    private void onDeleteFavorite() {
        Favorite selected = favoritesListView.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        favorites.remove(selected.id());
        refreshFavorites();
        log("Favorito eliminado: " + selected.name());
    }

    private void refreshFavorites() {
        favoritesListView.setItems(FXCollections.observableArrayList(favorites.all()));
    }

    // ---- Riel izquierdo (Ver → Panel de conexiones/Historial/Favoritos) ----

    /**
     * Riel real de íconos (Conexiones/Historial/Favoritos, `ToggleButton` en
     * un `ToggleGroup` compartido para que el resaltado sea siempre uno
     * solo) — contra `faro-java-prototipo.html` con "Mapa JavaFX" activado,
     * no las pestañas con texto que había acá antes. `showLeftPanel`
     * controla cuál de los tres `VBox` del `StackPane` es visible;
     * `setSelected(true)` sincroniza el ícono resaltado también cuando el
     * cambio viene de Ver → Alt+1/2/3 en vez de un clic directo en el riel.
     */
    @FXML
    private void onShowConnectionsPanel() {
        showLeftPanel(connectionsPanel);
        connectionsRailButton.setSelected(true);
    }

    @FXML
    private void onShowHistoryPanel() {
        showLeftPanel(historyPanel);
        historyRailButton.setSelected(true);
    }

    @FXML
    private void onShowFavoritesPanel() {
        showLeftPanel(favoritesPanel);
        favoritesRailButton.setSelected(true);
    }

    private void showLeftPanel(VBox panel) {
        connectionsPanel.setVisible(panel == connectionsPanel);
        connectionsPanel.setManaged(panel == connectionsPanel);
        historyPanel.setVisible(panel == historyPanel);
        historyPanel.setManaged(panel == historyPanel);
        favoritesPanel.setVisible(panel == favoritesPanel);
        favoritesPanel.setManaged(panel == favoritesPanel);
    }

    // ---- Explicar plan de ejecución ----

    /**
     * Consulta → Explicar plan de ejecución — a diferencia de "Ejecutar",
     * corre solo contra la PRIMERA base marcada (un plan es por
     * naturaleza específico de una base/motor, no tiene sentido
     * mezclarlos). Ver {@code QueryExecutionService#explain} para el
     * detalle de qué corre en cada motor — sin probar contra un servidor
     * real todavía, ver README.
     */
    @FXML
    private void onExplainPlan() {
        QueryTabState state = currentTabState();
        if (state == null) {
            return;
        }
        String sql = sqlToRun(state);
        if (sql.isBlank()) {
            statusLabel.setText("Escribe una consulta primero.");
            return;
        }
        List<DatabaseEntry> selected = ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot())
            .stream()
            .filter(CheckBoxTreeItem::isSelected)
            .map(item -> (DatabaseEntry) item.getValue())
            .toList();
        if (selected.isEmpty()) {
            statusLabel.setText("Selecciona al menos una base de datos.");
            return;
        }
        DatabaseEntry db = selected.get(0);
        if (selected.size() > 1) {
            log("Explicar plan: usando solo " + db.alias()
                    + " (la primera base marcada) — el plan es por base, no se mezclan varias.");
        }

        Task<QueryResult> task = QueryExecutionService.explain(db, credentials, pool, sql);
        task.setOnSucceeded(e -> {
            QueryResult result = task.getValue();
            ResultsTableFactory.populate(resultsTable, result.columns(), result.rows());
            setTabBadge(resultsTab, "Resultados", result.rows().size());
            updateResultsSummary(result.rows().size());
            lastResultDatabaseLabel = db.alias();
            lastResultSql = sql;
            log("Plan de ejecución pedido para " + db.alias() + ".");
        });
        task.setOnFailed(e -> {
            logger.warn("onExplainPlan: falló para {}", db.alias(), task.getException());
            log(LogLevel.ERROR, "Error al pedir el plan de " + db.alias() + ": " + task.getException().getMessage());
        });

        Thread thread = new Thread(task, "faro-explain");
        thread.setDaemon(true);
        thread.start();
    }

    // ---- Importar/Exportar configuración ----

    @FXML
    private void onExportConfig() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exportar configuración");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        chooser.setInitialFileName("faro-config.json");
        File file = chooser.showSaveDialog(connectionTree.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            ConnectionRegistryStore.save(registry, preferences, favorites, file.toPath());
            statusLabel.setText("Configuración exportada: " + file.getName());
            log("Configuración exportada a " + file.getName() + " (sin credenciales, a propósito).");
        } catch (IOException e) {
            logger.warn("No se pudo exportar la configuración a {}", file.getAbsolutePath(), e);
            statusLabel.setText("Error al exportar configuración: " + e.getMessage());
        }
    }

    @FXML
    private void onImportConfig() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Importar configuración");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File file = chooser.showOpenDialog(connectionTree.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            registry = ConnectionRegistryStore.load(file.toPath(), preferences, favorites);
            refreshTree();
            refreshFavorites();
            statusLabel.setText("Configuración importada: " + file.getName());
            log("Configuración importada de " + file.getName() + " — reemplazó conexiones y favoritos actuales.");
        } catch (IOException | RuntimeException e) {
            logger.warn("No se pudo importar la configuración desde {}", file.getAbsolutePath(), e);
            statusLabel.setText("Error al importar configuración: " + e.getMessage());
        }
    }

    private void onTreeClicked(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
            return;
        }
        var selected = connectionTree.getSelectionModel().getSelectedItem();
        if (selected == null || !(selected.getValue() instanceof DatabaseEntry entry)) {
            return;
        }
        openEditDialog(entry);
    }

    /**
     * Único camino real para editar una base: el ícono de lápiz visible en
     * cada fila (ver {@link ConnectionTreeCell}). El doble clic en
     * {@link #onTreeClicked} llama a lo mismo, pero solo como atajo extra —
     * un cliente no adivina un gesto sin ninguna pista visual.
     */
    private void openEditDialog(DatabaseEntry entry) {
        AddDatabaseDialog.showForEdit(connectionTree.getScene().getWindow(), credentials, preferences, entry)
            .ifPresent(updated -> {
                // El pool de HikariCP quedó armado con el host/puerto/credenciales
                // de antes de editar — descartarlo para que la próxima ejecución
                // arme uno nuevo con los datos actuales.
                pool.evict(updated.id());
                refreshTree();
            });
    }

    /**
     * Bote de basura/"Eliminar esta base" (ver {@link ConnectionTreeCell}) —
     * antes no existía ningún camino para quitar una base ya agregada.
     * Confirmación primero porque es una acción difícil de deshacer (no hay
     * papelera/undo en esta app) — mismo criterio que
     * {@link #confirmSaveOrDiscard}, ninguno de los 2 botones queda como
     * "default" (ni estilo primario ni Enter la dispara) para no arriesgar
     * un borrado accidental de un Enter de más.
     */
    private void confirmAndDeleteDatabase(DatabaseEntry entry) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Eliminar base de datos");
        alert.setHeaderText("¿Eliminar \"" + entry.alias() + "\"?");
        alert.setContentText("Esta acción no se puede deshacer. Las credenciales guardadas para esta base también se van a borrar.");
        ButtonType deleteType = new ButtonType("Eliminar");
        ButtonType cancelType = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(deleteType, cancelType);
        alert.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(alert);

        if (alert.showAndWait().orElse(cancelType) != deleteType) {
            return;
        }

        registry.removeDatabase(entry);
        credentials.remove(entry.id());
        pool.evict(entry.id());
        refreshTree();
        log("Base eliminada: " + entry.alias());
    }

    /**
     * Clic derecho → "Nueva consulta para esta base" (ver {@code ConnectionTreeCell}) —
     * marca SOLO la casilla de {@code db} (desmarca cualquier otra ya
     * marcada) y abre una pestaña de consulta nueva, para no tener que
     * adivinar/buscar cuál base marcar antes de escribir el SQL. Pedido
     * explícito del usuario, que encontró poco intuitivo el botón "+"
     * genérico de Nueva consulta (sin ninguna base asociada de entrada).
     */
    private void onNewQueryForDatabase(DatabaseEntry db) {
        selectOnlyDatabase(db);
        addQueryTab("", null);
        statusLabel.setText("Nueva consulta para " + db.alias() + " — su casilla ya quedó marcada.");
        log("Nueva consulta abierta para " + db.alias() + " (casilla marcada automáticamente).");
    }

    /** Marca SOLO la casilla de {@code db} en el árbol, desmarca cualquier otra — usado por {@link #onNewQueryForDatabase} y cada "Generar…" del explorador de esquema, para no tener que adivinar/buscar cuál base marcar antes de abrir el script generado. */
    private void selectOnlyDatabase(DatabaseEntry db) {
        for (CheckBoxTreeItem<Object> item : ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot())) {
            item.setSelected(item.getValue() == db);
        }
    }

    /**
     * Único punto de entrada de "Generar…" desde {@link ConnectionTreeCell}
     * — reparte según la acción pedida (ver {@code SchemaTreeNode
     * .GenerateAction}) al método real, sin cambiar la firma del
     * constructor de {@code ConnectionTreeCell} cada vez que se agregue una
     * acción nueva.
     */
    private void onGenerateScript(SchemaTreeNode.Item item, SchemaTreeNode.GenerateAction action) {
        switch (action) {
            case SELECT -> onGenerateSelect(item);
            case INSERT -> onGenerateInsert(item);
            case UPDATE -> onGenerateUpdate(item);
            case DELETE -> onGenerateDelete(item);
            case CREATE_TABLE -> onGenerateCreateTable(item);
            case CREATE_SCRIPT -> onGenerateCreateScript(item);
        }
    }

    /**
     * "Generar SELECT" del explorador de esquema (clic derecho o doble clic
     * en una fila de Tabla/Vista, ver {@code ConnectionTreeCell}) — arma
     * {@code SELECT col1, col2, ... FROM tabla} con las columnas reales.
     *
     * <p><b>Esquema progresivo (2026-08-25):</b> antes esto leía
     * {@code columnsByTable}, un caché masivo con las columnas de TODAS las
     * tablas de la base, cargado de un jalón al expandirla — hallazgo en
     * vivo del usuario contra bases DEV reales de cliente: ese fetch masivo
     * era lo que dejaba el árbol pegado en "Cargando esquema…" en bases
     * grandes. Ahora pasa por {@link #generateFromColumnDetails}, el mismo
     * camino caché-primero-si-no-fetch que ya usaban UPDATE/DELETE/CREATE
     * TABLE — instantáneo si esta tabla ya se tocó antes en la sesión, o un
     * fetch real (con "Generando…" en {@code statusLabel}) la primera vez.
     */
    private void onGenerateSelect(SchemaTreeNode.Item item) {
        generateFromColumnDetails(item, "SELECT", columns -> {
            String columnList = columns.isEmpty() ? "*"
                    : columns.stream().map(ColumnMetadata::name).collect(Collectors.joining(", "));
            return "SELECT " + columnList + " FROM " + item.name();
        });
    }

    /** "Generar INSERT" (solo tablas) — mismo camino que {@link #onGenerateSelect} ahora (ver su javadoc), solo los nombres de columna le importan a {@code SqlScriptGenerator#generateInsertScript}. */
    private void onGenerateInsert(SchemaTreeNode.Item item) {
        generateFromColumnDetails(item, "INSERT", columns ->
                SqlScriptGenerator.generateInsertScript(item.name(), columns.stream().map(ColumnMetadata::name).toList()));
    }

    /** "Generar UPDATE" (solo tablas) — a diferencia de SELECT/INSERT, necesita saber cuál columna es la PK (para el WHERE), así que sí puede implicar un viaje real a la base si nunca se pidió antes en esta sesión (ver {@link #generateFromColumnDetails}). */
    private void onGenerateUpdate(SchemaTreeNode.Item item) {
        generateFromColumnDetails(item, "UPDATE", columns -> SqlScriptGenerator.generateUpdateScript(item.name(), columns));
    }

    /** "Generar DELETE" (solo tablas) — mismo motivo que UPDATE: necesita la PK real. */
    private void onGenerateDelete(SchemaTreeNode.Item item) {
        generateFromColumnDetails(item, "DELETE", columns -> SqlScriptGenerator.generateDeleteScript(item.name(), columns));
    }

    /** "Generar script CREATE" de una tabla — mejor esfuerzo desde columnas (tipo/NOT NULL/PK), ver {@code SqlScriptGenerator#generateCreateTableScript}. Ningún motor expone un DDL completo listo para tablas como sí tiene para rutinas — de ahí la diferencia con {@link #onGenerateCreateScript}. */
    private void onGenerateCreateTable(SchemaTreeNode.Item item) {
        generateFromColumnDetails(item, "CREATE TABLE", columns -> SqlScriptGenerator.generateCreateTableScript(item.name(), columns));
    }

    /**
     * Columnas con tipo/PK reales — instantáneo si ya se pidieron antes en
     * esta sesión ({@link SchemaIntrospector#cachedColumns}); si no, un
     * fetch real en segundo plano (con feedback en {@code statusLabel}
     * mientras corre). Camino compartido por las 5 acciones de tabla
     * (SELECT/INSERT/UPDATE/DELETE/CREATE TABLE) desde el esquema
     * progresivo (2026-08-25) — antes SELECT/INSERT tenían su propio atajo
     * "instantáneo" leyendo un caché masivo de columnas que se cargaba
     * completo al expandir la base; ese caché desapareció (era la causa
     * real de que el árbol se quedara pegado en "Cargando esquema…" contra
     * bases DEV grandes del cliente), así que ahora las 5 comparten este
     * mismo mecanismo bajo demanda.
     */
    private void generateFromColumnDetails(SchemaTreeNode.Item item, String label, Function<List<ColumnMetadata>, String> scriptBuilder) {
        generateFromCacheOrFetch(item, label, "faro-script-columns",
                () -> SchemaIntrospector.cachedColumns(item.database().id(), item.name()),
                () -> SchemaIntrospector.fetchColumns(item.database(), credentials, pool, item.name()),
                scriptBuilder);
    }

    /**
     * "Generar script CREATE" de vista/función/procedimiento/trigger — a
     * diferencia de una tabla, sí hay un DDL real que el motor puede dar de
     * un solo viaje ({@code pg_get_viewdef}/{@code pg_get_functiondef}/
     * {@code pg_get_triggerdef} en PostgreSQL, {@code OBJECT_DEFINITION()}
     * en SQL Server — ver {@code SchemaIntrospector#fetchDefinition}).
     * Mismo patrón caché-primero-si-no-fetch que {@link #generateFromColumnDetails}.
     */
    private void onGenerateCreateScript(SchemaTreeNode.Item item) {
        generateFromCacheOrFetch(item, "script CREATE", "faro-script-definition",
                () -> SchemaIntrospector.cachedDefinition(item.database().id(), item.kind(), item.name()),
                () -> SchemaIntrospector.fetchDefinition(item.database(), credentials, pool, item.kind(), item.name(), item.parentTable()),
                script -> script);
    }

    /**
     * Único armazón real de "caché primero, si no fetch en segundo plano con
     * feedback" — {@link #generateFromColumnDetails}/{@link #onGenerateCreateScript}
     * eran dos copias casi idénticas de esto (hallazgo real de revisión de
     * código, 2026-08-25: cache-lookup+early-return, mensaje "Generando…",
     * construir el {@code Task}, éxito→{@link #applyGeneratedScript}, falla→log
     * + mensaje, hilo demonio — diferían solo en de dónde sale el valor
     * cacheado/el {@code Task} y el nombre del hilo).
     *
     * <p><b>{@code pendingKey} (hallazgo real de revisión de código,
     * 2026-08-25):</b> repetir el mismo "Generar…" antes de que el primer
     * fetch termine (doble clic en la fila + clic derecho, o el usuario
     * impaciente repitiendo el clic) mandaba 2 consultas JDBC idénticas en
     * paralelo, sin ningún candado — a diferencia de
     * {@code loading}/{@code categoryLoading} en {@code SchemaIntrospector},
     * que sí dedupan la carga de esquema. La llave usa {@code label} (no
     * {@code threadName}) a propósito: SELECT/INSERT/UPDATE/DELETE/CREATE
     * TABLE de una tabla comparten el mismo {@code threadName}
     * ("faro-script-columns", ver {@link #generateFromColumnDetails}) pero
     * cada una es una acción distinta que el usuario sí quiere ver
     * completada por separado (su propia pestaña con su propio SQL) — dedup
     * por {@code threadName} habría descartado en silencio, sin pestaña ni
     * aviso, un SELECT pedido justo después de un UPDATE sobre la misma
     * tabla mientras el UPDATE seguía en curso. Por {@code label} solo
     * dedupa el caso real que importa: repetir la MISMA acción sobre el
     * MISMO objeto antes de que termine.
     */
    private <T> void generateFromCacheOrFetch(
            SchemaTreeNode.Item item, String label, String threadName,
            Supplier<Optional<T>> cacheLookup, Supplier<Task<T>> taskFactory, Function<T, String> scriptBuilder) {
        Optional<T> cached = cacheLookup.get();
        if (cached.isPresent()) {
            applyGeneratedScript(item, label, scriptBuilder.apply(cached.get()));
            return;
        }
        String pendingKey = label + ":" + item.database().id() + ":" + item.name();
        if (!pendingGenerations.add(pendingKey)) {
            return;
        }
        statusLabel.setText("Generando " + label + " para " + item.name() + "…");
        Task<T> task = taskFactory.get();
        task.setOnSucceeded(e -> {
            pendingGenerations.remove(pendingKey);
            applyGeneratedScript(item, label, scriptBuilder.apply(task.getValue()));
        });
        task.setOnFailed(e -> {
            pendingGenerations.remove(pendingKey);
            logger.warn("Generar {} falló para [{}] {}", label, item.database().alias(), item.name(), task.getException());
            statusLabel.setText("No se pudo generar " + label + " de " + item.name() + " — revisa Diagnóstico.");
        });
        Thread thread = new Thread(task, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    /** Último paso común de cualquier "Generar…": marcar SOLO la casilla de la base dueña, abrir una pestaña nueva con el script, y avisar en {@code statusLabel}/Diagnóstico. */
    private void applyGeneratedScript(SchemaTreeNode.Item item, String label, String sql) {
        selectOnlyDatabase(item.database());
        addQueryTab(sql, null);
        statusLabel.setText(label + " generado para " + item.name() + " — su casilla ya quedó marcada.");
        logger.info("Generar {}: [{}] {}", label, item.database().alias(), sql);
    }

    /**
     * Reconstruye el árbol desde cero (siempre — no hay actualización
     * incremental) aplicando el filtro de texto actual, si hay uno. Antes
     * de tirar el árbol viejo, guarda qué bases estaban marcadas y las
     * vuelve a marcar en el árbol nuevo — sin esto, cada tecleo en el
     * buscador (que llama a este método) habría borrado la selección del
     * usuario a medio armar una consulta masiva, un problema real que ya
     * existía de forma más silenciosa en cualquier llamada a este método
     * (ej. después de agregar una base), pero que con un buscador en vivo
     * se hubiera notado en cada letra.
     */
    private void refreshTree() {
        Set<String> selectedIds = connectionTree.getRoot() == null
                ? Set.of()
                : Set.copyOf(ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot()).stream()
                        .filter(CheckBoxTreeItem::isSelected)
                        .map(item -> ((DatabaseEntry) item.getValue()).id())
                        .toList());

        connectionTree.setRoot(ConnectionTreeBuilder.buildRoot(registry, connectionFilterText, credentials, pool));
        bindSelectedCount();
        bindSelectAllButtonText();

        if (!selectedIds.isEmpty()) {
            for (CheckBoxTreeItem<Object> item : ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot())) {
                if (selectedIds.contains(((DatabaseEntry) item.getValue()).id())) {
                    item.setSelected(true);
                }
            }
        }
    }

    private void bindSelectedCount() {
        List<CheckBoxTreeItem<Object>> databaseItems =
            ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot());
        Observable[] selectedProperties = databaseItems.stream()
            .map(CheckBoxTreeItem::selectedProperty)
            .toArray(Observable[]::new);

        selectedCountLabel.textProperty().bind(Bindings.createStringBinding(() -> {
            long selected = databaseItems.stream().filter(CheckBoxTreeItem::isSelected).count();
            return selected == 1 ? "1 base seleccionada" : selected + " bases seleccionadas";
        }, selectedProperties));
    }

    /**
     * Texto del botón "Todas" reactivo — antes se quedaba fijo en "Todas"
     * sin importar el estado real (hallazgo real del usuario: "el texto no
     * cambia entre todas y ninguna"). Mismo patrón de {@code Bindings} que
     * {@link #bindSelectedCount()}, sobre los mismos {@code selectedProperty()}
     * — reacciona tanto a un clic del botón como a marcar/desmarcar bases a
     * mano una por una.
     */
    private void bindSelectAllButtonText() {
        List<CheckBoxTreeItem<Object>> databaseItems =
            ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot());
        Observable[] selectedProperties = databaseItems.stream()
            .map(CheckBoxTreeItem::selectedProperty)
            .toArray(Observable[]::new);

        selectAllDatabasesButton.textProperty().bind(Bindings.createStringBinding(() -> {
            boolean allSelected = !databaseItems.isEmpty() && databaseItems.stream().allMatch(CheckBoxTreeItem::isSelected);
            return allSelected ? "Ninguna" : "Todas";
        }, selectedProperties));
    }

    /**
     * Intenta cargar conexiones/preferencias guardadas de una sesión
     * anterior (ver {@link ConnectionRegistryStore}); si el archivo no
     * existe (primera vez que se corre la app) o está corrupto/con un
     * formato que ya no reconoce, no truena el arranque — cae de vuelta a
     * un registro vacío (sin datos de ejemplo, se quitaron a pedido del
     * usuario, ver el javadoc de {@link ConnectionRegistry}).
     */
    private ConnectionRegistry loadOrCreateRegistry() {
        if (Files.exists(ConnectionRegistryStore.DEFAULT_FILE)) {
            try {
                return ConnectionRegistryStore.load(ConnectionRegistryStore.DEFAULT_FILE, preferences, favorites);
            } catch (IOException | RuntimeException e) {
                logger.warn("No se pudo cargar {}, empezando con un registro vacío", ConnectionRegistryStore.DEFAULT_FILE, e);
            }
        }
        return new ConnectionRegistry();
    }

    /**
     * Carga las credenciales guardadas de una sesión anterior (cifradas
     * con DPAPI, ver {@link CredentialVaultStore}). Igual criterio que
     * {@link #loadOrCreateRegistry}: si el archivo no existe todavía (primer
     * arranque) o no se pudo descifrar (ej. el perfil de Windows cambió),
     * sigue con {@link CredentialStore} vacío en vez de tronar el arranque.
     */
    private void loadCredentials() {
        if (Files.exists(CredentialVaultStore.DEFAULT_FILE)) {
            try {
                CredentialVaultStore.load(credentials, CredentialVaultStore.DEFAULT_FILE);
            } catch (IOException | RuntimeException e) {
                logger.warn("No se pudieron cargar las credenciales guardadas", e);
            }
        }
    }

    /**
     * Guardado incremental — antes solo se guardaba al cerrar la ventana
     * ({@link #shutdown()}), así que un cierre anormal (proceso matado)
     * perdía los cambios de toda la sesión. Reintenta cada
     * {@link #AUTOSAVE_INTERVAL_MILLIS} sin importar si algo cambió de
     * verdad desde el último guardado — más simple y seguro que rastrear
     * un flag "sucio" en cada punto donde se muta {@code registry}/
     * {@code favorites}/{@code credentials}/{@code preferences} (son
     * varios: agregar/editar/eliminar base, Descubrir bases, Favoritos,
     * Preferencias, Credenciales), y el costo de reescribir un JSON chico
     * de más en más es insignificante.
     */
    private void startAutosave() {
        autosaveTimer = new Timer("faro-autosave", true);
        autosaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(MainController.this::autosave);
            }
        }, AUTOSAVE_INTERVAL_MILLIS, AUTOSAVE_INTERVAL_MILLIS);
    }

    /**
     * Antes {@link #refreshStatusBar()} solo se llamaba al arrancar y al
     * terminar una ejecución/exportación — "pool activo/total" y "Memoria"
     * se quedaban congelados con ese último valor mientras tanto, aunque el
     * pool/la memoria real siguieran cambiando de verdad (ej. mientras una
     * consulta pesada seguía corriendo). Hallazgo real del usuario probando
     * en vivo (2026-08-25): "lo veo todo estático no veo que cambie".
     * Mismo patrón que {@link #startAutosave()} — {@code Timer} demonio,
     * {@code Platform.runLater} porque el tick corre en el hilo del
     * {@code Timer}, no en el de JavaFX.
     */
    private void startStatusBarRefresh() {
        statusBarTimer = new Timer("faro-status-bar-refresh", true);
        statusBarTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(MainController.this::refreshStatusBar);
            }
        }, STATUS_BAR_REFRESH_INTERVAL_MILLIS, STATUS_BAR_REFRESH_INTERVAL_MILLIS);
    }

    private void autosave() {
        try {
            ConnectionRegistryStore.save(registry, preferences, favorites, ConnectionRegistryStore.DEFAULT_FILE);
            CredentialVaultStore.save(credentials, CredentialVaultStore.DEFAULT_FILE);
            logger.debug("Autoguardado completo.");
        } catch (IOException | RuntimeException e) {
            logger.error("Autoguardado falló", e);
            log(LogLevel.ERROR, "Autoguardado falló: " + e.getMessage());
        }
    }

    /**
     * Cierra los pools de HikariCP y guarda conexiones/preferencias/
     * credenciales en disco — llamado desde {@code Main#stop()} al cerrar
     * la ventana. Las credenciales van a un archivo aparte y cifrado, ver
     * {@link CredentialVaultStore}.
     */
    void shutdown() {
        logger.info("MainController.shutdown() — guardando y cerrando pools.");
        autosaveTimer.cancel();
        statusBarTimer.cancel();
        pool.closeAll();
        try {
            ConnectionRegistryStore.save(registry, preferences, favorites, ConnectionRegistryStore.DEFAULT_FILE);
        } catch (IOException e) {
            logger.warn("No se pudieron guardar conexiones al cerrar", e);
        }
        try {
            CredentialVaultStore.save(credentials, CredentialVaultStore.DEFAULT_FILE);
        } catch (IOException | RuntimeException e) {
            logger.warn("No se pudieron guardar las credenciales al cerrar", e);
        }
        logger.info("MainController.shutdown() completo.");
    }

}
