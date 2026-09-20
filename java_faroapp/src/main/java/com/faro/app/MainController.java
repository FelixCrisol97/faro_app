package com.faro.app;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.ConnectionRegistry;
import com.faro.app.data.ConnectionRegistryStore;
import com.faro.app.data.CredentialStore;
import com.faro.app.data.Favorite;
import com.faro.app.data.FavoritesStore;
import com.faro.app.data.SavedQueryTab;
import com.faro.app.data.SessionPersistence;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.CsvExportService;
import com.faro.app.query.CsvWriter;
import com.faro.app.query.CsvFileNamer;
import com.faro.app.query.ExecutionStatus;
import com.faro.app.query.QueryExecutionService;
import com.faro.app.query.QueryResult;
import com.faro.app.query.SchemaComparisonService;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.ui.AddDatabaseDialog;
import com.faro.app.ui.ConnectionTreeActions;
import com.faro.app.ui.ConnectionTreeBuilder;
import com.faro.app.ui.ConnectionTreeCoordinator;
import com.faro.app.ui.ConnectionTreeCell;
import com.faro.app.ui.CredentialsDialog;
import com.faro.app.ui.CsvImportDialog;
import com.faro.app.ui.DiscoverDialog;
import com.faro.app.ui.ExecutionTableFactory;
import com.faro.app.ui.Icons;
import com.faro.app.ui.PreferencesDialog;
import com.faro.app.ui.ResultsTableFactory;
import com.faro.app.ui.SchemaTreeNode;
import com.faro.app.ui.QueryTabManager;
import com.faro.app.ui.ScriptGeneratorCoordinator;
import com.faro.app.ui.SqlAutocomplete;
import com.faro.app.ui.Theme;

import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.animation.Interpolator;
import javafx.animation.RotateTransition;
import javafx.application.Platform;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.CheckBoxTreeItem;
import javafx.scene.control.ChoiceDialog;
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
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
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
    private VBox resultsContainer;
    @FXML
    private Label resultsTruncatedBanner;

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

    /** Tope del log visual de la pestaña Diagnóstico — ver {@link #log(LogLevel, String)}. Más alto que {@link #MAX_HISTORY} porque acá sí importa poder mirar hacia atrás varias corridas seguidas (una corrida contra 20 bases puede dejar 20 líneas de golpe), pero acotado igual. */
    private static final int MAX_DIAGNOSTIC_ENTRIES = 500;

    /** Nivel de una entrada del log de Diagnóstico — mismo vocabulario visual que `faro-java-prototipo.html` (INFO/WARN/ERROR/DEBUG, coloreados). */
    private enum LogLevel { INFO, WARN, ERROR, DEBUG }

    private record DiagnosticEntry(LocalTime time, LogLevel level, String message) {
    }

    private final CredentialStore credentials = new CredentialStore();
    private final ConnectionPoolManager pool = new ConnectionPoolManager();
    /** Versión real de cada motor, cacheada la primera vez que una conexión de ese tipo tiene éxito (ver {@code QueryExecutionService#runOne}) — para la barra de estado de abajo ("PostgreSQL 15.4 · SQL Server 2019"). */
    private final Map<DbEngine, String> engineVersions = new ConcurrentHashMap<>();
    /** Las seis acciones "Generar…" del explorador de esquema — ver {@link ScriptGeneratorCoordinator} (2026-09-15, segundo paso de C1). */
    private ScriptGeneratorCoordinator scriptGenerator;
    /** Las pestañas de consulta, buscar y formatear — ver {@link QueryTabManager} (2026-09-18, tercer paso de C1). */
    private QueryTabManager tabs;
    /** Selección, filas abiertas, scroll y buscador del árbol de conexiones — ver {@link ConnectionTreeCoordinator} (2026-09-19, cuarto paso de C1). */
    private ConnectionTreeCoordinator tree;
    private final AppPreferences preferences = new AppPreferences();
    private final FavoritesStore favorites = new FavoritesStore();
    private final ObservableList<DiagnosticEntry> diagnosticLog = FXCollections.observableArrayList();
    private final ObservableList<String> queryHistory = FXCollections.observableArrayList();
    private ConnectionRegistry registry;
    private TableView<Object[]> resultsTable;
    private ListView<ExecutionStatus> executionTable;
    private RotateTransition exportSpinAnimation;
    private Label executionSummaryLabel;
    private List<ExecutionStatus> currentExecutionRows = List.of();
    /** {@code true} mientras el {@code Task} de {@link #onRunQuery()} está corriendo — decide si el botón "Ejecutar"/"Cancelar" (mismo botón, ver {@link #onRunButtonClicked()}) dispara una cosa u otra. */
    private boolean queryRunning = false;
    /** Alias de la base (o "N-bases" si la última corrida tocó varias) y texto SQL que produjeron el resultado que hay ahora mismo en {@link #resultsTable} — insumos para el nombre sugerido de {@link #onExportResultsCsv()}, ver {@link CsvFileNamer}. */
    private String lastResultDatabaseLabel = "";
    private String lastResultSql = "";
    /**
     * Las bases de la última corrida y si su resultado quedó recortado por el tope de
     * filas en memoria (2026-09-20) — los dos insumos que necesita "Exportar CSV" para
     * volver a leer de la base en vez de exportar lo que quedó en pantalla.
     *
     * <p>Vacía cuando el resultado NO vino de una corrida re-ejecutable: "Explicar
     * plan" y "Comparar objeto" producen filas que no salen de correr este SQL contra
     * estas bases, así que para ellas exportar sigue leyendo de la tabla.
     */
    private List<DatabaseEntry> lastResultDatabases = List.of();
    private boolean lastResultTruncated;
    /** Hora en que arrancó la última corrida — se perdió al quitar los `log(...)` duplicados de {@link #onRunQuery()}, el usuario lo notó, se movió al encabezado de Ejecución en vez de repetirlo en el log. */
    private String lastExecutionStartTime = "";
    /**
     * Título de la pestaña que produjo la última corrida (ej. "Consulta 2",
     * o el nombre de archivo si la pestaña tiene uno) — pedido explícito
     * del usuario (2026-08-28): con varias pestañas de consulta abiertas,
     * "Ejecutar" solo corre la ACTIVA (documentado así desde siempre, ver
     * README), pero la pestaña de Ejecución nunca decía CUÁL — el usuario
     * no tenía forma de confirmar que de verdad corrió el script que
     * esperaba, ni de distinguirlo si tenía 4 pestañas abiertas a la vez.
     */
    private String lastExecutionTabLabel = "";
    /** Poblado al cargar la sesión anterior (corre antes de armar las pestañas en {@link #initialize()}) — si no está vacío, {@link #initialize()} recrea estas pestañas en vez de abrir una sola en blanco. Ver {@link SavedQueryTab}. */
    private List<SavedQueryTab> restoredQueryTabs = List.of();
    private Timer statusBarTimer;
    /**
     * Carga, autoguardado y guardado final — ver {@link SessionPersistence} (2026-09-15,
     * primer paso de C1). El registro entra como {@code Supplier} porque "Importar
     * configuración…" lo <b>reemplaza</b>: con una referencia fija, esta clase habría
     * seguido guardando el registro viejo después de cada importación.
     */
    private SessionPersistence session;

    /**
     * Espera tras la última tecla del buscador de bases antes de reconstruir el árbol
     * — ver el comentario en {@link #initialize()}. 200 ms: suficiente para agrupar el
     * tecleo normal, lo bastante corto como para que se sienta inmediato.
     */
    private final PauseTransition filterDebounce =
            new PauseTransition(Duration.millis(200));

    /** Pool activo/total y memoria SÍ cambian en cualquier momento (no solo al terminar una ejecución/exportación, que es cuando refreshStatusBar() ya se llamaba) — hallazgo real del usuario probando en vivo: "lo veo todo estático no veo que cambie". 2.5s de por medio: suficiente para sentirse en vivo, demasiado espaciado como para que leer HikariCP/Runtime en cada tick importe de verdad. */
    private static final long STATUS_BAR_REFRESH_INTERVAL_MILLIS = 2500;

    @FXML
    private void initialize() {
        logger.info("MainController.initialize() — arrancando.");
        SessionPersistence.LoadedSession loaded = SessionPersistence.load(preferences, favorites);
        registry = loaded.registry();
        restoredQueryTabs = loaded.queryTabs();
        SessionPersistence.loadCredentials(credentials);
        // Las seis acciones "Generar…" — el controlador solo le presta abrir una pestaña
        // y escribir en la barra de estado. Ver ScriptGeneratorCoordinator.
        scriptGenerator = new ScriptGeneratorCoordinator(
                credentials, pool,
                (sql, databaseIds) -> tabs.addQueryTab(sql, null, databaseIds),
                statusLabel::setText);
        // El registro entra como Supplier, no como referencia: "Importar configuración…"
        // lo reemplaza por otro objeto. Ver el javadoc de SessionPersistence.
        session = new SessionPersistence(
                () -> registry, preferences, favorites, credentials,
                () -> tabs.captureForSave(),
                message -> log(LogLevel.ERROR, message));
        // El árbol y las pestañas se construyen ACÁ, antes de la primera reconstrucción
        // del árbol (tree.refresh(), más abajo). Se necesitan entre sí —el árbol avisa a
        // las pestañas cuando cambia la selección y las pestañas le piden la selección al
        // árbol— pero siempre a través de lambdas que leen el campo al invocarse, así que
        // el orden entre los dos no importa; lo que importa es que ambos existan antes de
        // esa primera reconstrucción.
        tree = new ConnectionTreeCoordinator(
                connectionTree, () -> registry, credentials, pool,
                selectedCountLabel, selectAllDatabasesButton,
                () -> tabs.refreshActiveTabHeader());
        // Las pestañas se construyen ACÁ, antes de la primera reconstrucción del árbol:
        // tree.refresh() ya repinta el encabezado de la pestaña activa, y aunque al
        // arrancar todavía no haya ninguna, para "no hacer nada" necesita que tabs exista.
        // Ver el javadoc del constructor de QueryTabManager.
        tabs = new QueryTabManager(queryTabPane, preferences, new QueryTabManager.Host() {
            @Override
            public Window window() {
                return connectionTree.getScene().getWindow();
            }

            @Override
            public List<DatabaseEntry> selectedDatabases() {
                return tree.selectedDatabases();
            }

            @Override
            public Set<String> capturedSelectedDatabaseIds() {
                return tree.capturedSelectedDatabaseIds();
            }

            @Override
            public void applySelectedDatabaseIds(Set<String> ids) {
                tree.applySelectedDatabaseIds(ids);
            }

            @Override
            public boolean treeReady() {
                return tree.isBuilt();
            }

            @Override
            public ConnectionRegistry registry() {
                return registry;
            }

            @Override
            public void applyTheme(Dialog<?> dialog) {
                applyThemeToAlert(dialog);
            }

            @Override
            public void status(String message) {
                statusLabel.setText(message);
            }

            @Override
            public void diagnostic(String message) {
                log(message);
            }
        });
        // Un solo objeto con todas las acciones (2026-09-11) — ver ConnectionTreeActions
        // para por qué, en vez de 14 parámetros posicionales del mismo tipo.
        ConnectionTreeActions treeActions = new ConnectionTreeActions(
                this::openEditDialog,
                this::onNewQueryForDatabase,
                this::confirmAndDeleteDatabase,
                this::onDiscoverForDatabase,
                this::onToggleDatabaseMode,
                this::onMoveDatabaseToGroup,
                this::onMoveDatabaseInOrder,
                (item, action) -> scriptGenerator.generate(item, action),
                this::onCompareObject,
                this::onRenameGroup,
                this::onSetGroupSelection,
                this::onMoveGroupInOrder,
                this::onSortGroupDatabases);
        connectionTree.setCellFactory(view -> new ConnectionTreeCell(treeActions, preferences::fontScaleDelta));
        // Ya no viene fijo en el FXML (2026-09-14, hallazgo A9) — se calcula igual que el
        // del grid de resultados y se reaplica al mover el slider, ver applyCurrentTheme.
        connectionTree.setFixedCellSize(ConnectionTreeCell.rowHeight(preferences.fontScaleDelta()));
        // Alt+↑/Alt+↓ mueve la fila seleccionada (grupo o base) — el mismo camino que el
        // menú contextual, para no tener que abrirlo en cada paso cuando hay que mover
        // algo varias posiciones. Alt y no ↑/↓ a secas: esas ya navegan el árbol.
        connectionTree.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!event.isAltDown()) {
                return;
            }
            int delta = switch (event.getCode()) {
                case UP -> -1;
                case DOWN -> 1;
                default -> 0;
            };
            TreeItem<Object> selected = connectionTree.getSelectionModel().getSelectedItem();
            if (delta == 0 || selected == null) {
                return;
            }
            if (selected.getValue() instanceof Server server) {
                onMoveGroupInOrder(server, delta);
                event.consume();
            } else if (selected.getValue() instanceof DatabaseEntry db) {
                onMoveDatabaseInOrder(db, delta);
                event.consume();
            }
        });
        // Debounce (2026-09-10, hallazgo B2 de ANALISIS_OPTIMIZACION_ESTRUCTURA.md) —
        // antes cada tecla reconstruía el árbol ENTERO de inmediato: tirar todos los
        // TreeItem, rearmarlos, recorrerlos y rehacer los bindings de selección. Escribir
        // "bodega norte" eran 12 reconstrucciones completas para llegar al mismo
        // resultado que da la última. PauseTransition se reinicia con cada tecla, así que
        // solo corre cuando el usuario de verdad para de escribir.
        filterDebounce.setOnFinished(event -> tree.refreshFromFilter());
        connectionFilterField.textProperty().addListener((obs, oldText, newText) -> {
            tree.setFilterText(newText);
            filterDebounce.playFromStart();
        });
        tree.refresh();
        session.startAutosave(Platform::runLater);
        startStatusBarRefresh();

        // Restaura las pestañas de la sesión anterior (2026-08-28, pedido explícito
        // del usuario) — si connections.json no traía ninguna (primer arranque, o un
        // archivo de antes de este campo), cae al comportamiento de siempre: 1
        // pestaña en blanco.
        if (restoredQueryTabs.isEmpty()) {
            tabs.addQueryTab("", null, null);
        } else {
            for (SavedQueryTab saved : restoredQueryTabs) {
                File savedFile = saved.filePath() != null ? new File(saved.filePath()) : null;
                tabs.addQueryTab(saved.sql(), savedFile, new LinkedHashSet<>(saved.selectedDatabaseIds()));
            }
        }
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
        // El grid se queda con todo el alto sobrante; el banner solo ocupa lo suyo
        // cuando está visible (y nada cuando no, por managed=false en el FXML).
        VBox.setVgrow(resultsTable, Priority.ALWAYS);
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
                level.getStyleClass().add("log-level-" + entry.level().name().toLowerCase(Locale.ROOT));
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
                    tabs.addQueryTab(selected, null, null);
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
        // Forzado explícito — el usuario había reportado (2026-08-26) que la barra de
        // scroll del árbol de conexiones y la del grid de resultados se quedaban con los
        // colores del tema anterior al cambiar en vivo, aunque el resto de la ventana sí
        // se repintaba bien (candidato: el nodo interno del scrollbar, creado por su
        // Skin y no por el FXML, no quedaba cubierto por el mismo pulso de CSS que el
        // resto de la ventana). Este applyCss() adelanta el reflujo de CSS de forma
        // síncrona en vez de esperar al próximo pulso — **confirmado por el usuario
        // (2026-08-28) que arregló el bug**, sin haber aislado la causa exacta con un
        // log real; si el síntoma volviera a aparecer en otro control, sí hace falta ese
        // diagnóstico en vivo (mismo criterio que el bug de visibilidad del scroll).
        scene.getRoot().applyCss();
        updateThemeToggleIcon();
        tabs.applyEditorFontSize();
        // La altura de fila del grid de resultados es fija (fixedCellSize, ver
        // ResultsTableFactory) — sin esto, mover el slider de tamaño de interfaz en
        // Preferencias deja el texto de las filas creciendo dentro de una altura que ya
        // no le alcanza (texto de filas contiguas superpuesto, reportado con captura
        // 2026-08-26).
        resultsTable.setFixedCellSize(ResultsTableFactory.rowHeight(preferences.fontScaleDelta()));
        // Lo mismo para el árbol (2026-09-14, hallazgo A9) — su fila de base tiene DOS
        // líneas de texto que crecen con el slider, y hasta ahora el alto estaba fijo en
        // 44px, así que en el extremo del slider el contenido dejaba de caber. Mismo modo
        // de falla que ya se corrigió para el grid el 2026-08-26.
        connectionTree.setFixedCellSize(ConnectionTreeCell.rowHeight(preferences.fontScaleDelta()));
        // La flecha de expandir del árbol también sigue al tamaño de fuente de la
        // interfaz (2026-09-07, ver ConnectionTreeCell#applyDisclosureScale) — su escala
        // se aplica en updateItem(), así que hay que forzar el repintado de las celdas
        // visibles para que tomen el valor nuevo apenas se mueve el slider.
        connectionTree.refresh();
        // QUITADO (2026-09-07, hallazgo #11 de AUDITORIA_BUGS_RENDIMIENTO.md, a pedido
        // explícito del usuario). Acá había un `setItems(lista vacía)` + `setItems(la
        // misma lista de antes)` — vaciar y reponer la tabla para forzar a JavaFX a
        // reconstruir TODAS las celdas de cero en cada cambio de tema/acento/tamaño de
        // fuente (o sea, en cada paso del slider de Preferencias, con el resultado
        // completo cargado). Era un intento fallido contra el bug del grid (texto de
        // filas cortado al bajar el zoom): el usuario lo probó con este bloque puesto y
        // confirmó "sigue igual, mismo comportamiento". Lo que de verdad cerró ese bug
        // fue el margen fijo `ResultsTableFactory#SAFETY_MARGIN_PX`, que sigue en su
        // lugar y es lo único que hace falta. Si el síntoma reapareciera al mover el
        // slider de tamaño con resultados en pantalla, esto es lo primero que habría que
        // revisar — pero volver a ponerlo solo tendría sentido con evidencia real de que
        // ayuda, que nunca existió.
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
        // Tope real (2026-09-07, hallazgo #6 de AUDITORIA_BUGS_RENDIMIENTO.md) — antes
        // esta lista crecía sin límite, a diferencia del historial de consultas que sí
        // se corta en MAX_HISTORY. Una sesión larga de trabajo real (varias corridas por
        // hora contra muchas bodegas, cada error de base con su línea, más credenciales/
        // escaneos/pruebas de conexión) acumulaba miles de entradas que nunca se
        // liberaban — y como cada entrada nueva se inserta en la posición 0, insertar se
        // volvía progresivamente más caro (desplaza todo el arreglo) además de nunca
        // devolver memoria. El archivo de log (logback, con rotación propia) sigue
        // teniendo la traza completa — esto es solo el visor de la sesión.
        while (diagnosticLog.size() > MAX_DIAGNOSTIC_ENTRIES) {
            diagnosticLog.remove(diagnosticLog.size() - 1);
        }
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

    // ---- Pestañas de consulta, buscar y formatear ----
    //
    // Todo vive en QueryTabManager (2026-09-18, tercer paso de C1). Acá quedan solo
    // los manejadores @FXML, porque el FXML los enlaza por nombre al controlador.

    @FXML
    private void onNewQueryTab() {
        tabs.addQueryTab("", null, null);
    }

    /** Lo llama {@code Main} al cerrar la ventana — ver {@link QueryTabManager#confirmCloseAllTabs()}. */
    boolean confirmCloseAllTabs() {
        return tabs.confirmCloseAllTabs();
    }

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
        tabs.focusCurrentEditor();
    }

    @FXML
    private void onFindNext() {
        showFindOutcome(tabs.find(findField.getText(), true));
    }

    @FXML
    private void onFindPrevious() {
        showFindOutcome(tabs.find(findField.getText(), false));
    }

    /** La barra de búsqueda vive en este FXML; {@link QueryTabManager#find} solo dice qué pasó. */
    private void showFindOutcome(QueryTabManager.FindOutcome outcome) {
        switch (outcome) {
            case NOT_FOUND -> findStatusLabel.setText("Sin resultados");
            case FOUND -> findStatusLabel.setText("");
            case NOTHING_TO_SEARCH -> { }
        }
    }

    /** Editar → Formatear SQL (Ctrl+L) — ver {@link QueryTabManager#formatCurrent()}. */
    @FXML
    private void onFormatSql() {
        if (tabs.formatCurrent()) {
            log("SQL formateado.");
        }
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
        QueryTabManager.TabState state = tabs.current();
        if (state == null) {
            return;
        }
        List<DatabaseEntry> selected = tree.selectedDatabases();
        DatabaseEntry activeDb = selected.isEmpty() ? null : selected.get(0);
        SqlAutocomplete.show(state.codeArea(), activeDb, credentials, pool);
    }

    // ---- Diálogos ----

    @FXML
    private void onAddDatabase() {
        AddDatabaseDialog.showForAdd(connectionTree.getScene().getWindow(), credentials, preferences)
            .ifPresent(entry -> {
                registry.ungroupedDatabases().add(entry);
                tree.refresh();
                // Dejarla a la vista y seleccionada — antes el árbol se reconstruía y
                // volvía al tope, así que con muchas bases registradas la recién creada
                // quedaba fuera de pantalla sin ninguna pista de dónde había caído.
                tree.revealDatabase(entry);
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
                DiscoverDialog.show(connectionTree.getScene().getWindow(), credentials, preferences, registry.allDatabases());
        if (!found.isEmpty()) {
            registry.ungroupedDatabases().addAll(found);
            tree.refresh();
            // Misma razón que en onAddDatabase — dejar a la vista la última agregada en
            // vez de mandar el árbol de vuelta al tope.
            tree.revealDatabase(found.get(found.size() - 1));
            statusLabel.setText(found.size() + " base(s) agregada(s) desde el escaneo.");
            log(found.size() + " base(s) agregada(s) desde el escaneo de bases de datos.");
        }
    }

    /**
     * Clic en el candado del árbol (2026-08-28, pedido explícito del usuario: "que
     * ese mismo icono sirva para intercambiar entre esas 2 opciones") — alterna
     * Solo lectura ↔ Sin restricciones directo, sin pasar por el diálogo de
     * Agregar/editar base (que sigue teniendo su propio combo de modo intacto, a
     * pedido explícito — "en la opción de editar que se quede igual"). Sin
     * confirmación al pasar a Sin restricciones — el diálogo de editar tampoco la
     * pide hoy, mismo criterio, no se inventa una regla nueva solo para este atajo.
     */
    private void onToggleDatabaseMode(DatabaseEntry db) {
        ServerMode newMode = db.mode() == ServerMode.READ_ONLY ? ServerMode.UNRESTRICTED : ServerMode.READ_ONLY;
        db.setMode(newMode);
        connectionTree.refresh();
        statusLabel.setText(db.alias() + " ahora es " + newMode.label() + ".");
        log(db.alias() + ": modo cambiado a " + newMode.label() + " desde el árbol.");
    }

    /** Etiqueta de "sin grupo" en el selector de {@link #onMoveDatabaseToGroup} — no puede ser un nombre real de {@link Server} (el usuario no puede nombrar un grupo así sin querer chocar con esto). */
    private static final String UNGROUPED_CHOICE = "(Sin grupo)";
    private static final String NEW_GROUP_CHOICE = "(Nuevo grupo…)";

    /**
     * "Mover a grupo…" del menú contextual de una base (2026-08-28, pedido
     * explícito del usuario, con imagen de referencia: "no veo el tema de
     * poder agrupar las BD") — antes NO existía ninguna forma real de crear
     * un grupo ni de mover una base a uno desde la UI; los servidores solo
     * llegaban ya armados en {@code connections.json} (a mano, o de una
     * sesión vieja). {@link ChoiceDialog} con los grupos ya existentes +
     * "(Sin grupo)" + "(Nuevo grupo…)" — elegir esta última pide el nombre
     * aparte con un segundo diálogo, mismo patrón de 2 pasos que
     * {@link #onSaveFavorite}.
     */
    private void onMoveDatabaseToGroup(DatabaseEntry db) {
        List<String> choices = new ArrayList<>();
        choices.add(UNGROUPED_CHOICE);
        for (Server server : registry.servers()) {
            choices.add(server.name());
        }
        choices.add(NEW_GROUP_CHOICE);

        Server owner = registry.groupOf(db);
        String currentGroup = owner == null ? UNGROUPED_CHOICE : owner.name();

        ChoiceDialog<String> dialog = new ChoiceDialog<>(currentGroup, choices);
        dialog.setTitle("Mover a grupo");
        dialog.setHeaderText(null);
        dialog.setContentText("Mover \"" + db.alias() + "\" a:");
        dialog.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(dialog);
        Optional<String> chosen = dialog.showAndWait();
        if (chosen.isEmpty()) {
            return;
        }

        String targetGroupName = chosen.get();
        if (targetGroupName.equals(NEW_GROUP_CHOICE)) {
            TextInputDialog nameDialog = new TextInputDialog();
            nameDialog.setTitle("Nuevo grupo de conexiones");
            nameDialog.setHeaderText(null);
            nameDialog.setContentText("Nombre del grupo:");
            nameDialog.initOwner(connectionTree.getScene().getWindow());
            applyThemeToAlert(nameDialog);
            Optional<String> name = nameDialog.showAndWait();
            if (name.isEmpty() || name.get().isBlank()) {
                return;
            }
            targetGroupName = name.get().trim();
        } else if (targetGroupName.equals(currentGroup)) {
            // Sin cambio real — no molestar con un mensaje de "movida" si el usuario
            // solo confirmó el grupo en el que ya estaba.
            return;
        }

        registry.removeDatabase(db);
        if (targetGroupName.equals(UNGROUPED_CHOICE)) {
            registry.ungroupedDatabases().add(db);
        } else {
            String finalTargetGroupName = targetGroupName;
            Server target = registry.servers().stream()
                    .filter(server -> server.name().equals(finalTargetGroupName))
                    .findFirst()
                    .orElseGet(() -> {
                        Server created = new Server(finalTargetGroupName);
                        registry.servers().add(created);
                        return created;
                    });
            target.databases().add(db);
        }
        tree.refresh();
        String label = targetGroupName.equals(UNGROUPED_CHOICE) ? "Sin grupo" : targetGroupName;
        statusLabel.setText(db.alias() + " movida a " + label + ".");
        log(db.alias() + ": movida al grupo \"" + label + "\".");
    }

    // ---- Acciones de grupo del árbol (2026-09-11, pedido del usuario: marcar/
    // desmarcar por grupo, renombrar, y cambiar el orden de grupos y bases) ----

    /**
     * Marca o desmarca todas las bases de un grupo — {@code server} nulo = las sueltas
     * ("Sin grupo"). Antes solo existía el botón "Todas" de la barra, que es GLOBAL:
     * con varios grupos registrados no había forma de marcar uno solo sin ir base por
     * base.
     *
     * <p>Se tocan las casillas de los {@code TreeItem} hijos directamente, no la
     * propagación de {@code CheckBoxTreeItem} — esa está deshabilitada a propósito
     * ({@code setIndependent(true)}, hallazgo #1 de
     * {@code AUDITORIA_BUGS_RENDIMIENTO.md}) justamente porque recorría los hijos de
     * cada base y disparaba su carga de esquema. Pedirle los hijos a una fila de GRUPO
     * es gratis (son {@code TreeItem} planos, ya creados); lo que nunca hay que hacer
     * es pedírselos a una fila de base.
     */
    private void onSetGroupSelection(Server server, boolean selected) {
        TreeItem<Object> groupItem = tree.findGroupItem(server);
        if (groupItem == null) {
            return;
        }
        int touched = 0;
        for (TreeItem<Object> child : groupItem.getChildren()) {
            if (child instanceof CheckBoxTreeItem<Object> checkItem) {
                checkItem.setSelected(selected);
                touched++;
            }
        }
        String label = server == null ? "Sin grupo" : server.name();
        statusLabel.setText((selected ? "Marcadas " : "Desmarcadas ") + touched + " base(s) de " + label + ".");
    }

    /** "Renombrar grupo…" — antes no existía ninguna forma de cambiarle el nombre a un grupo una vez creado. */
    private void onRenameGroup(Server server) {
        if (server == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(server.name());
        dialog.setTitle("Renombrar grupo");
        dialog.setHeaderText(null);
        dialog.setContentText("Nombre del grupo:");
        dialog.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(dialog);
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank() || name.get().trim().equals(server.name())) {
            return;
        }
        String previous = server.name();
        server.setName(name.get().trim());
        tree.refresh();
        statusLabel.setText("Grupo renombrado: " + server.name());
        log("Grupo \"" + previous + "\" renombrado a \"" + server.name() + "\".");
    }

    /**
     * Sube ({@code delta} -1) o baja (+1) un grupo entre los demás. El orden del árbol
     * ES el orden de la lista del registro, y ese orden se persiste en
     * {@code connections.json} — así que mover acá alcanza, no hay ningún campo de
     * posición aparte que mantener.
     */
    private void onMoveGroupInOrder(Server server, int delta) {
        if (server == null || reorderBlockedByFilter()) {
            return;
        }
        if (registry.moveServer(server, delta)) {
            tree.refresh();
            tree.revealGroup(server);
        }
    }

    /** Igual que {@link #onMoveGroupInOrder} pero para una base, dentro de su propio grupo — ver {@code ConnectionRegistry#moveDatabase}. */
    private void onMoveDatabaseInOrder(DatabaseEntry db, int delta) {
        if (reorderBlockedByFilter()) {
            return;
        }
        if (registry.moveDatabase(db, delta)) {
            tree.refresh();
            tree.revealDatabase(db);
        }
    }

    /**
     * Reordenar con el buscador activo no se permite (2026-09-12).
     *
     * <p>Mover opera sobre la lista REAL del registro, no sobre lo que el filtro deja a
     * la vista. Con un filtro puesto, "Subir" puede intercambiar la fila con una
     * oculta: el registro cambia de verdad pero en pantalla **no pasa nada**, porque
     * las dos filas involucradas no están las dos visibles. El usuario presiona otra
     * vez, vuelve a "no pasar nada", y el orden guardado se va desordenando sin que lo
     * vea.
     *
     * <p>Se bloquea con un aviso en vez de intentar mover "entre los visibles": eso
     * significaría reordenar la lista real de una forma que depende del filtro que
     * había puesto en ese momento, que es bastante más difícil de predecir que no
     * dejar hacerlo.
     */
    private boolean reorderBlockedByFilter() {
        if (!tree.isFilterActive()) {
            return false;
        }
        statusLabel.setText("Limpia el buscador para cambiar el orden — con un filtro puesto, mover afectaría filas que no estás viendo.");
        return true;
    }

    /** "Ordenar A-Z" — las bases de un grupo, o las sueltas si {@code server} es nulo. */
    private void onSortGroupDatabases(Server server) {
        registry.sortDatabasesByAlias(server);
        tree.refresh();
        statusLabel.setText("Bases ordenadas A-Z en " + (server == null ? "Sin grupo" : server.name()) + ".");
    }

    /**
     * "Conexiones → Nuevo grupo de conexiones…" — crea un {@link Server}
     * vacío, listo para que "Mover a grupo…" (ver
     * {@link #onMoveDatabaseToGroup}) le asigne bases. Ver el javadoc de ese
     * método para el motivo real de ambos.
     */
    @FXML
    private void onNewGroup() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Nuevo grupo de conexiones");
        dialog.setHeaderText(null);
        dialog.setContentText("Nombre del grupo:");
        dialog.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(dialog);
        Optional<String> name = dialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        String trimmed = name.get().trim();
        registry.servers().add(new Server(trimmed));
        tree.refresh();
        statusLabel.setText("Grupo creado: " + trimmed);
        log("Grupo de conexiones creado: " + trimmed);
    }

    private void onDiscoverForDatabase(DatabaseEntry db) {
        List<DatabaseEntry> found =
                DiscoverDialog.show(connectionTree.getScene().getWindow(), credentials, preferences, registry.allDatabases(), db.host());
        if (!found.isEmpty()) {
            // Al MISMO grupo que la base desde la que se escaneó (2026-09-11, pregunta del
            // usuario) — antes caían siempre en "Sin grupo" aunque el escaneo hubiera
            // salido de una base agrupada, y había que moverlas una por una después. Son
            // bases del mismo servidor, así que heredar su grupo es lo esperable. Si la
            // base de origen está suelta, las nuevas también.
            registry.addAllNextTo(db, found);
            Server group = registry.groupOf(db);
            tree.refresh();
            // Misma razón que en onAddDatabase — dejar a la vista la última agregada en
            // vez de mandar el árbol de vuelta al tope.
            tree.revealDatabase(found.get(found.size() - 1));
            String where = group == null ? "Sin grupo" : group.name();
            statusLabel.setText(found.size() + " base(s) agregada(s) en " + where + ".");
            log(found.size() + " base(s) agregada(s) desde el escaneo de " + db.host() + " — en " + where + ".");
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
     * {@code connectionTree.refresh()} —el de {@code TreeView}, que solo repinta
     * las celdas visibles con los datos actuales sin tocar la estructura— y NO
     * {@link ConnectionTreeCoordinator#refresh()}, que tira y rearma todos los
     * {@code CheckBoxTreeItem}. Para cambiar el color de un punto de estado no
     * hace falta reconstruir nada.
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
                    try (Connection _ = DriverManager.getConnection(
                            db.jdbcUrl(), creds.get().user(), creds.get().password())) {
                        // Bug real (hallazgo de esta ronda de optimización): a diferencia del
                        // TESTING de arriba, CONNECTED/FAILED se mutaban DIRECTO desde este hilo
                        // de fondo (faro-test-all), sin Platform.runLater — connectionStatus es
                        // una propiedad de JavaFX, mutarla fuera del hilo de la UI no es seguro
                        // (mismo criterio ya documentado en DatabaseEntry y aplicado en
                        // QueryExecutionService#runOne, que este método nunca había igualado).
                        Platform.runLater(() -> db.setConnectionStatus(DatabaseEntry.ConnectionStatus.CONNECTED));
                        connected++;
                    } catch (SQLException e) {
                        String message = e.getMessage();
                        Platform.runLater(() -> db.setConnectionStatus(DatabaseEntry.ConnectionStatus.FAILED));
                        failures.add(db.alias() + ": " + message);
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
            tabs.addQueryTab(content, file, null);
            statusLabel.setText("Abierto: " + file.getName());
            logger.info("onOpenFile: {} ({} caracteres)", file.getAbsolutePath(), content.length());
        } catch (IOException e) {
            logger.warn("No se pudo abrir el archivo {}", file.getAbsolutePath(), e);
            statusLabel.setText("Error al abrir el archivo: " + e.getMessage());
        }
    }

    @FXML
    private void onSaveFile() {
        tabs.saveCurrent();
    }

    @FXML
    private void onSaveFileAs() {
        tabs.saveCurrentAs();
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

        // Resultado recortado ⇒ exportar leyendo de la base, no de la tabla (2026-09-20).
        // Lo que está en pantalla NO es el resultado completo, así que exportarlo sería
        // entregar un archivo incompleto sin que se note — el peor de los dos errores
        // posibles acá. Ver CsvExportService: lee y escribe fila por fila, así que el
        // tamaño del archivo no tiene nada que ver con la memoria disponible.
        if (lastResultTruncated && !lastResultDatabases.isEmpty()) {
            exportStreamingToCsv(file);
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
        ObservableList<Object[]> rows = resultsTable.getItems();
        logger.info("onExportResultsCsv: exportando {} fila(s) a {}", rows.size(), file.getAbsolutePath());

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws IOException {
                try (BufferedWriter writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                    StringBuilder line = new StringBuilder();
                    // Las dos exportaciones (esta, desde la tabla, y la de
                    // CsvExportService, desde la base) escriben con el MISMO
                    // CsvWriter.appendRow. Antes esta armaba la fila a mano y cerraba con
                    // writer.newLine(), que en Windows escribe CRLF mientras que la otra
                    // escribe \n: el mismo resultado exportado por un camino o por el otro
                    // habría salido con finales de línea distintos. Los encabezados también
                    // van escapados (hallazgo A10) — un alias con coma partía la línea.
                    CsvWriter.appendRow(line, headers.toArray());
                    writer.write(line.toString());

                    int written = 0;
                    int total = rows.size();
                    for (Object[] row : rows) {
                        line.setLength(0);
                        CsvWriter.appendRow(line, row);
                        writer.write(line.toString());
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

    /**
     * Exportación que NO pasa por la tabla: vuelve a leer de las bases y escribe directo
     * a disco (2026-09-20). Es el camino cuando el resultado en pantalla quedó recortado
     * por el tope de filas en memoria — ver {@link CsvExportService}, donde está el
     * detalle de por qué la memoria queda plana.
     *
     * <p><b>Vuelve a ejecutar la consulta</b>, así que el archivo refleja la base en este
     * momento y no el instante en que se corrió. Con datos que cambian, puede diferir de
     * lo que se ve arriba. Es el costo inevitable de no haber guardado en memoria lo que
     * justamente no cabía.
     */
    private void exportStreamingToCsv(File file) {
        Task<CsvExportService.ExportSummary> task = CsvExportService.export(
                lastResultDatabases, credentials, pool, lastResultSql,
                preferences.maxConcurrentDatabases(), preferences.fetchSize(), file.toPath());

        task.setOnRunning(e -> {
            statusLabel.setText("Exportando el resultado completo a " + file.getName() + "…");
            exportSpinnerLabel.setText("Exportando todo…");
            showExportSpinner(true);
        });
        task.setOnSucceeded(e -> {
            CsvExportService.ExportSummary summary = task.getValue();
            String mensaje = "Exportado completo: " + file.getName()
                    + " (" + String.format("%,d", summary.rowsWritten()) + " fila(s))";
            statusLabel.setText(mensaje);
            log(summary.errors().isEmpty() ? LogLevel.INFO : LogLevel.WARN, mensaje
                    + (summary.errors().isEmpty() ? "" : " — " + summary.errors().size() + " base(s) con error"));
            for (String error : summary.errors()) {
                log(LogLevel.ERROR, "Exportar — " + error);
            }
            showExportSpinner(false);
            refreshStatusBar();
        });
        task.setOnFailed(e -> {
            statusLabel.setText("Error al exportar: " + task.getException().getMessage());
            logger.error("exportStreamingToCsv: falló exportando a {}", file.getAbsolutePath(), task.getException());
            log(LogLevel.ERROR, "Error al exportar a " + file.getName() + ": " + task.getException().getMessage());
            showExportSpinner(false);
            refreshStatusBar();
        });

        Thread thread = new Thread(task, "faro-csv-export");
        thread.setDaemon(true);
        thread.start();
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
    private static String sqlToRun(QueryTabManager.TabState state) {
        String selected = state.codeArea().getSelectedText();
        return selected.isBlank() ? state.codeArea().getText() : selected;
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
        QueryTabManager.TabState state = tabs.current();
        if (state == null) {
            return;
        }
        String sql = sqlToRun(state);
        if (sql.isBlank()) {
            statusLabel.setText("Escribe una consulta primero.");
            return;
        }
        lastExecutionTabLabel = state.baseName();

        List<DatabaseEntry> selected = tree.selectedDatabases();
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
            ExecutionStatus status = new ExecutionStatus(db.alias(), db.host() + ":" + db.port());
            statusByDatabaseId.put(db.id(), status);
            // DatabaseEntry#inUse (2026-08-28, pedido explícito del usuario: "si se está
            // haciendo uso de esa BD... que pardee o se mueva ese círculo... para que se
            // entienda mejor") — prendido apenas arranca esta corrida (ConnectionTreeCell
            // ya reacciona sola vía inUseProperty(), ver su javadoc), apagado en cuanto el
            // estado de ESTA base deja de ser RUNNING (éxito, error, o cancelada — las 3
            // cuentan como "ya no está en uso").
            db.setInUse(true);
            status.stateProperty().addListener((obs, oldState, newState) -> {
                updateExecutionSummary();
                if (newState != ExecutionStatus.State.RUNNING) {
                    db.setInUse(false);
                }
            });
        }
        currentExecutionRows = List.copyOf(statusByDatabaseId.values());
        lastExecutionStartTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        executionTable.setItems(FXCollections.observableArrayList(currentExecutionRows));
        updateExecutionSummary();

        Task<QueryResult> task = QueryExecutionService.execute(
                new ArrayList<>(selected), credentials, pool, statusByDatabaseId, sql,
                preferences.maxConcurrentDatabases(), preferences.fetchSize(),
                preferences.maxDisplayRows(), engineVersions);
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
            lastResultDatabases = List.copyOf(selected);
            lastResultTruncated = result.truncated();
            showTruncatedBanner(result.truncated(), result.rows().size());
            if (anySucceeded) {
                resultsTab.getTabPane().getSelectionModel().select(resultsTab);
            }
            refreshStatusBar();
            resetRunButton();
        });
        task.setOnFailed(e -> {
            // Red de seguridad del punto "en uso" (2026-09-10, hallazgo A11) — se apaga
            // desde el listener de estado de cada base, o sea SOLO si esa base llegó a
            // reportar algo distinto de RUNNING. Si el Task muere entero antes (una
            // interrupción que cancela las tareas pendientes sin llegar a correrlas, un
            // Error de la JVM), esas bases se quedarían pulsando en el árbol para siempre,
            // sin ninguna consulta detrás, hasta reiniciar la app. No tengo un disparador
            // reproducible hoy —runOne captura SQLException y RuntimeException, que cubren
            // casi todo— pero el costo de cerrarlo es esta línea.
            selected.forEach(db -> db.setInUse(false));
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

    /**
     * Muestra u oculta el aviso de resultado recortado.
     *
     * <p>Va ARRIBA del grid y no en la barra de estado de abajo a propósito — el usuario
     * ya señaló que los mensajes de abajo no se leen. Recortar en silencio sería peor que
     * recortar: alguien podría sacar conclusiones de un resultado incompleto creyéndolo
     * completo.
     *
     * <p>No dice cuántas filas quedaron fuera porque no se sabe, y averiguarlo exigiría
     * traerlas — justo lo que el tope evita. Ver {@code QueryResult#truncated}.
     */
    private void showTruncatedBanner(boolean truncated, int shownRows) {
        resultsTruncatedBanner.setVisible(truncated);
        resultsTruncatedBanner.setManaged(truncated);
        if (truncated) {
            resultsTruncatedBanner.setText(
                    "Se muestran las primeras " + String.format("%,d", shownRows) + " filas y hay más — "
                    + "se alcanzó el tope de filas en memoria (Preferencias → Rendimiento). "
                    + "\"Exportar CSV\" sí baja el resultado COMPLETO, no solo lo que ves acá.");
            log(LogLevel.WARN, "Resultado recortado en " + shownRows + " fila(s) — hay más. "
                    + "Exportar CSV trae el resultado completo.");
        }
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
        // Una sola pasada, no tres (2026-09-10, hallazgo B8) — este método lo dispara el
        // listener de estado de CADA base, o sea N veces por corrida, y cada llamada
        // recorría la lista completa tres veces para contar tres estados.
        int succeeded = 0;
        int failed = 0;
        int cancelled = 0;
        for (ExecutionStatus status : currentExecutionRows) {
            switch (status.stateProperty().get()) {
                case SUCCEEDED -> succeeded++;
                case FAILED -> failed++;
                case CANCELLED -> cancelled++;
                case RUNNING -> { }
            }
        }
        StringBuilder summary = new StringBuilder(
                lastExecutionTabLabel + " · " + lastExecutionStartTime + " · " + currentExecutionRows.size()
                        + " base(s) · " + succeeded + " correcta(s)");
        if (failed > 0) {
            summary.append(" · ").append(failed).append(" con error(es)");
        }
        if (cancelled > 0) {
            summary.append(" · ").append(cancelled).append(" cancelada(s)");
        }
        executionSummaryLabel.setText(summary.toString());
        setTabBadge(executionTab, "Ejecución", currentExecutionRows.size());
    }

    /**
     * Los nodos del badge de cada pestaña de resultados, creados UNA vez — ver
     * {@link #setTabBadge}.
     */
    private record TabBadge(Label count, HBox graphic) {
    }

    private final Map<Tab, TabBadge> tabBadges = new HashMap<>();

    /**
     * Nombre + contador tipo pill directo en la pestaña — "Resultados 1,240", contra
     * faro-java-prototipo.html. 0 no muestra pill (nada que contar todavía).
     *
     * <p><b>Los nodos se reusan</b> (2026-09-10, hallazgo B5 de
     * {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}): antes este método creaba dos
     * {@code Label} y un {@code HBox} NUEVOS y reemplazaba el gráfico de la pestaña en
     * cada llamada. Y {@link #log(LogLevel, String)} lo llama con CADA línea que entra
     * a Diagnóstico — una corrida contra 20 bodegas con errores deja 20 líneas de
     * golpe, o sea 20 reconstrucciones del gráfico con 3 nodos nuevos, resolución de
     * clases de estilo y una pasada de layout de la barra de pestañas cada una.
     * {@link #updateExecutionSummary()} lo llamaba igual por cada cambio de estado.
     * Ahora solo cambia el texto del contador.
     */
    private void setTabBadge(Tab tab, String name, int count) {
        TabBadge badge = tabBadges.computeIfAbsent(tab, t -> {
            Label nameLabel = new Label(name);
            nameLabel.getStyleClass().add("tab-name");
            Label countLabel = new Label();
            countLabel.getStyleClass().add("tab-badge");
            HBox graphic = new HBox(6, nameLabel, countLabel);
            graphic.setAlignment(Pos.CENTER_LEFT);
            t.setText(null);
            t.setGraphic(graphic);
            return new TabBadge(countLabel, graphic);
        });
        badge.count().setText(String.valueOf(count));
        // visible+managed, no quitarlo de la lista de hijos: con managed=false el HBox
        // ni siquiera le reserva espacio, así que se ve igual que cuando no estaba, sin
        // tocar el grafo de escena.
        badge.count().setVisible(count > 0);
        badge.count().setManaged(count > 0);
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

    /**
     * Texto de una sola línea para la celda del historial — el SQL guardado en sí
     * conserva sus saltos de línea reales, esto es solo para mostrarlo compacto.
     *
     * <p>Colapsa RUNS de espacio en blanco, no reemplaza carácter por carácter
     * (2026-09-14, encontrado al escribir su primer test): antes hacía
     * {@code replace('\n',' ').replace('\r',' ')}, así que un salto de línea de Windows
     * (CRLF) producía DOS espacios — y un script abierto de un archivo .sql los tiene
     * en cada línea, con lo que la vista previa del historial salía con huecos dobles
     * por todos lados. De paso también colapsa la sangría, que es ruido puro en una
     * vista de una sola línea.
     */
    static String summarize(String sql) {
        String oneLine = WHITESPACE_RUN.matcher(sql).replaceAll(" ").trim();
        return oneLine.length() > 80 ? oneLine.substring(0, 80) + "…" : oneLine;
    }

    /** Compilado una vez — {@link #summarize} corre por cada celda visible del historial. */
    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    // ---- Favoritos ----

    @FXML
    private void onSaveFavorite() {
        QueryTabManager.TabState state = tabs.current();
        if (state == null) {
            return;
        }
        String sql = state.codeArea().getText();
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
        tabs.addQueryTab(selected.sql(), null, null);
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
        QueryTabManager.TabState state = tabs.current();
        if (state == null) {
            return;
        }
        String sql = sqlToRun(state);
        if (sql.isBlank()) {
            statusLabel.setText("Escribe una consulta primero.");
            return;
        }
        List<DatabaseEntry> selected = tree.selectedDatabases();
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
            // Un plan de ejecución no se re-ejecuta para exportar: el SQL que lo produjo
            // lleva EXPLAIN/SHOWPLAN delante y devolver el plan otra vez no es "el
            // resultado completo" de nada. Exportar lee de la tabla, que acá siempre cabe.
            lastResultDatabases = List.of();
            lastResultTruncated = false;
            showTruncatedBanner(false, result.rows().size());
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

    /**
     * Pregunta si la exportación debe llevar las credenciales — {@code empty} si el
     * usuario canceló, {@code true}/{@code false} con su respuesta si siguió
     * adelante. La casilla nace DESMARCADA a propósito: incluir contraseñas legibles
     * tiene que ser un acto deliberado de cada exportación, no algo que se herede de
     * la vez anterior.
     *
     * <p>La advertencia se muestra siempre, aunque la casilla esté apagada, para que
     * quien la marque sepa exactamente qué está produciendo antes de elegir dónde
     * guardar el archivo.
     */
    private Optional<Boolean> askIncludeCredentials() {
        CheckBox includeCheck = new CheckBox("Incluir usuarios y contraseñas");
        Label warning = new Label(
                "Si la marcas, las contraseñas quedan LEGIBLES dentro del archivo .json: "
                        + "cualquiera que lo abra las puede leer. Guárdalo como guardarías una "
                        + "contraseña — no por correo ni en una carpeta compartida.\n\n"
                        + "Sirve para no volver a capturarlas al montar Faro en otro equipo. "
                        + "Tu connections.json de siempre no cambia: ahí las credenciales siguen "
                        + "cifradas y aparte.");
        warning.setWrapText(true);
        warning.setMaxWidth(420);
        warning.getStyleClass().add("muted");
        VBox content = new VBox(10, includeCheck, warning);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Exportar configuración");
        alert.setHeaderText("¿Qué incluye el archivo exportado?");
        alert.getDialogPane().setContent(content);
        ButtonType exportType = new ButtonType("Exportar", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelType = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(exportType, cancelType);
        alert.initOwner(connectionTree.getScene().getWindow());
        applyThemeToAlert(alert);

        return alert.showAndWait()
                .filter(choice -> choice == exportType)
                .map(choice -> includeCheck.isSelected());
    }

    @FXML
    private void onExportConfig() {
        Optional<Boolean> includeCredentials = askIncludeCredentials();
        if (includeCredentials.isEmpty()) {
            return;
        }
        boolean withCredentials = includeCredentials.get();

        FileChooser chooser = new FileChooser();
        chooser.setTitle("Exportar configuración");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        chooser.setInitialFileName(withCredentials ? "faro-config-con-credenciales.json" : "faro-config.json");
        File file = chooser.showSaveDialog(connectionTree.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            // List.of() a propósito — las pestañas abiertas nunca van en un archivo
            // exportado, ver el javadoc de SavedQueryTab.
            ConnectionRegistryStore.save(registry, preferences, favorites, List.of(), file.toPath(),
                    withCredentials ? credentials : null);
            statusLabel.setText("Configuración exportada: " + file.getName());
            log(withCredentials ? LogLevel.WARN : LogLevel.INFO,
                    "Configuración exportada a " + file.getName()
                            + (withCredentials
                                    ? " — CON usuarios y contraseñas en texto legible. Trátalo como un archivo con secretos."
                                    : " (sin credenciales)."));
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
            // Descartar TODOS los pools antes de reemplazar el registro (2026-09-07,
            // hallazgo #3 de AUDITORIA_BUGS_RENDIMIENTO.md). Los pools se indexan por id
            // de base y se arman una sola vez con el host/puerto/credenciales de ese
            // momento (ver el javadoc de ConnectionPoolManager) — por eso editar o
            // eliminar una base ya llamaba a evict(). Importar no lo hacía, y un archivo
            // exportado CONSERVA los ids: si una base cambió de host entre la exportación
            // y la importación, el pool viejo seguía vivo y la siguiente consulta corría
            // contra el servidor ANTERIOR, en silencio, mientras el árbol mostraba el host
            // nuevo. Aparte, los pools de bases que ya no existen en el archivo importado
            // quedaban abiertos (con sus conexiones TCP) hasta cerrar la app, sin nadie
            // que pudiera descartarlos. closeAll() y no evict() base por base: importar
            // reemplaza el registro ENTERO, así que no hay forma de saber cuáles ids
            // siguen siendo "la misma base" de verdad.
            pool.closeAll();
            // Y lo mismo con el esquema cacheado (2026-09-08, hallazgo A4 de
            // ANALISIS_OPTIMIZACION_ESTRUCTURA.md) — las cachés de SchemaIntrospector
            // están indexadas por id de base EXACTAMENTE igual que los pools de arriba,
            // así que el párrafo anterior se les aplica palabra por palabra: con los ids
            // conservados en el archivo importado, una base que cambió de servidor
            // seguía mostrando en el árbol las tablas del servidor ANTERIOR, el
            // autocompletado las sugería, y "Generar SELECT" armaba SQL sobre columnas
            // de otra base — sin ningún error visible. El arreglo del hallazgo #3 cubrió
            // los pools y dejó esta capa afuera.
            SchemaIntrospector.invalidateAll();
            // .registry() nada más — un archivo importado nunca trae pestañas (ver
            // onExportConfig), así que no hay nada que restaurar en las pestañas ya
            // abiertas de esta sesión.
            // `credentials` (2026-09-10) — si el archivo se exportó con la casilla de
            // credenciales marcada, sus usuarios/contraseñas entran a la sesión acá; un
            // archivo sin esa sección (cualquiera de antes de esa fecha) no las toca.
            // Ese es el punto de la feature: montar Faro en un equipo nuevo sin
            // recapturar 50 contraseñas a mano.
            registry = ConnectionRegistryStore.load(file.toPath(), preferences, favorites, credentials).registry();
            tree.refresh();
            refreshFavorites();
            statusLabel.setText("Configuración importada: " + file.getName());
            log("Configuración importada de " + file.getName() + " — reemplazó conexiones y favoritos actuales.");
        } catch (IOException | RuntimeException e) {
            logger.warn("No se pudo importar la configuración desde {}", file.getAbsolutePath(), e);
            statusLabel.setText("Error al importar configuración: " + e.getMessage());
        }
    }

    /**
     * Camino real para editar una base: el ícono de lápiz visible en cada
     * fila, o doble clic sobre el TEXTO del alias — los dos van a
     * {@link ConnectionTreeCell}, que llama acá mismo.
     *
     * <p><b>Quitado (2026-08-28) — el doble clic ya no era global a toda la
     * fila.</b> Antes había un {@code connectionTree.setOnMouseClicked}
     * (nivel de TODO el árbol) que abría este diálogo con CUALQUIER doble
     * clic mientras una base siguiera con el resaltado de fila del
     * `TreeView` — sin importar sobre qué parte específica de la fila
     * cayera el clic. Eso es justo lo que reportó el usuario como bug real,
     * dos veces seguidas y expandiéndose ("también me abre la ventana de
     * editar BD" al hacer doble clic en el candado, luego en cualquier
     * objeto del esquema) — cada intento de arreglarlo consumiendo el
     * evento en un nodo más solo tapaba un síntoma a la vez (candado, luego
     * alias, luego fila de esquema, y seguía faltando el label de la IP
     * nuevo). Pedido explícito del usuario tras el segundo reporte: "no hay
     * forma de quitar el evento global... que solo se active... cuando esté
     * en el texto solamente". Se quitó el manejador global por completo —
     * el gesto de doble clic ahora vive SOLO en {@code aliasLabel}
     * ({@link ConnectionTreeCell}), no en ningún nivel más alto que pueda
     * capturar clics de partes no relacionadas de la fila.
     */
    private void openEditDialog(DatabaseEntry entry) {
        AddDatabaseDialog.showForEdit(connectionTree.getScene().getWindow(), credentials, preferences, entry)
            .ifPresent(updated -> {
                // El pool de HikariCP quedó armado con el host/puerto/credenciales
                // de antes de editar — descartarlo para que la próxima ejecución
                // arme uno nuevo con los datos actuales.
                pool.evict(updated.id());
                tree.refresh();
            });
    }

    /**
     * Bote de basura/"Eliminar esta base" (ver {@link ConnectionTreeCell}) —
     * antes no existía ningún camino para quitar una base ya agregada.
     * Confirmación primero porque es una acción difícil de deshacer (no hay
     * papelera/undo en esta app) — mismo criterio que
     * {@code QueryTabManager#confirmSaveOrDiscard}, ninguno de los 2 botones queda como
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
        // Cuarta limpieza, que faltaba (2026-09-08, hallazgo A4 de
        // ANALISIS_OPTIMIZACION_ESTRUCTURA.md) — el esquema cacheado de esta base
        // (estructura, columnas por tabla, definiciones DDL completas de sus
        // funciones/procedimientos) se quedaba en los mapas estáticos de
        // SchemaIntrospector hasta cerrar la app, sin que nadie lo pudiera alcanzar
        // ya. Fuga silenciosa, y peor si un id se reusara.
        SchemaIntrospector.invalidate(entry.id());
        tree.refresh();
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
        tabs.addQueryTab("", null, Set.of(db.id()));
        statusLabel.setText("Nueva consulta para " + db.alias() + " — su casilla ya quedó marcada.");
        log("Nueva consulta abierta para " + db.alias() + " (casilla marcada automáticamente).");
    }

    /**
     * "Comparar en las bases marcadas…" del menú contextual de un objeto de
     * esquema (2026-09-07, pedido explícito del usuario: verificar que una misma
     * función/tabla/trigger sea idéntica en todas las bodegas, sin tener que
     * escribir una consulta ni revisarlas a mano una por una).
     *
     * <p>Corre contra las bases MARCADAS en la pestaña activa — mismo criterio
     * que "Ejecutar", no contra todas las registradas: comparar es una operación
     * masiva más, y cuáles bodegas entran es justamente lo que el usuario elige
     * con las casillas. La base del objeto sobre el que se hizo clic derecho se
     * agrega sola si no estaba marcada (sería absurdo comparar "esta función"
     * excluyendo la base de donde salió).
     *
     * <p>El resultado cae en la pestaña Resultados como cualquier corrida —
     * incluido "Exportar CSV", que funciona sin ningún camino nuevo.
     */
    private void onCompareObject(SchemaTreeNode.Item item) {
        List<DatabaseEntry> selected = new ArrayList<>(tree.selectedDatabases());
        if (selected.stream().noneMatch(db -> db.id().equals(item.database().id()))) {
            selected.add(0, item.database());
        }
        if (selected.size() < 2) {
            statusLabel.setText("Marca al menos 2 bases para comparar " + item.name() + ".");
            log(LogLevel.WARN, "Comparar '" + item.name() + "': hace falta marcar al menos 2 bases.");
            return;
        }

        statusLabel.setText("Comparando " + item.name() + " en " + selected.size() + " base(s)…");
        logger.info("onCompareObject: comparando {} '{}' en {} base(s)", item.kind(), item.name(), selected.size());
        Task<QueryResult> task = SchemaComparisonService.compare(
                item.kind(), item.name(), item.parentTable(), selected, credentials, pool,
                preferences.maxConcurrentDatabases());
        task.setOnSucceeded(e -> {
            QueryResult result = task.getValue();
            ResultsTableFactory.populate(resultsTable, result.columns(), result.rows());
            setTabBadge(resultsTab, "Resultados", result.rows().size());
            updateResultsSummary(result.rows().size());
            // Insumos del nombre sugerido de "Exportar CSV" — acá no hay SQL de por
            // medio, así que se le pasa un texto que describa la comparación (ver
            // CsvFileNamer: usa esto solo para armar el nombre del archivo).
            lastResultDatabaseLabel = selected.size() + "-bases";
            lastResultSql = "comparacion " + item.kind().label() + " " + item.name();
            // Comparar no produce filas de un SELECT: son MD5 calculados por la app. No hay
            // SQL que volver a correr, y el resultado es una fila por base, siempre chico.
            lastResultDatabases = List.of();
            lastResultTruncated = false;
            showTruncatedBanner(false, result.rows().size());
            long distintas = result.rows().stream().filter(row -> String.valueOf(row[4]).startsWith("NO")).count();
            String resumen = distintas == 0
                    ? "Todas las bases tienen la misma versión de " + item.name() + "."
                    : distintas + " base(s) con una versión DISTINTA de " + item.name() + ".";
            statusLabel.setText(resumen);
            log(distintas == 0 ? LogLevel.INFO : LogLevel.WARN, resumen);
            for (String error : result.errors()) {
                log(LogLevel.ERROR, "Comparar " + item.name() + " — " + error);
            }
            resultsTab.getTabPane().getSelectionModel().select(resultsTab);
            refreshStatusBar();
        });
        task.setOnFailed(e -> {
            logger.error("onCompareObject: falló la comparación de {}", item.name(), task.getException());
            statusLabel.setText("No se pudo comparar " + item.name() + " — revisa Diagnóstico.");
            log(LogLevel.ERROR, "Comparar " + item.name() + " falló: " + task.getException().getMessage());
        });

        Thread thread = new Thread(task, "faro-schema-compare");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Antes {@link #refreshStatusBar()} solo se llamaba al arrancar y al
     * terminar una ejecución/exportación — "pool activo/total" y "Memoria"
     * se quedaban congelados con ese último valor mientras tanto, aunque el
     * pool/la memoria real siguieran cambiando de verdad (ej. mientras una
     * consulta pesada seguía corriendo). Hallazgo real del usuario probando
     * en vivo (2026-08-25): "lo veo todo estático no veo que cambie".
     * Mismo patrón que el autoguardado de {@link SessionPersistence} — {@code Timer} demonio,
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

    /**
     * Cierra los pools de HikariCP y guarda la sesión — llamado desde {@code Main#stop()}
     * al cerrar la ventana. El guardado en sí (esperar al autoguardado en vuelo, escribir
     * conexiones y credenciales) vive en {@link SessionPersistence#saveOnShutdown()}.
     */
    void shutdown() {
        logger.info("MainController.shutdown() — guardando y cerrando pools.");
        statusBarTimer.cancel();
        session.stopAutosaveAndWait();
        // closeAllAndWait, no closeAll — la JVM sale enseguida y los hilos de cierre en
        // segundo plano son demonio; acá sí hay que esperarlos. Ver su javadoc.
        //
        // El guardado va DESPUÉS de cerrar los pools, igual que antes de separar
        // SessionPersistence — por eso el cierre son dos llamadas y no una. Ver el
        // javadoc de stopAutosaveAndWait().
        pool.closeAllAndWait();
        session.saveNow();
        logger.info("MainController.shutdown() completo.");
    }

}
