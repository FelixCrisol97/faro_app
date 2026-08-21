package com.faro.app;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.ConnectionRegistry;
import com.faro.app.data.ConnectionRegistryStore;
import com.faro.app.data.CredentialStore;
import com.faro.app.data.CredentialVaultStore;
import com.faro.app.data.Favorite;
import com.faro.app.data.FavoritesStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.ExecutionStatus;
import com.faro.app.query.QueryExecutionService;
import com.faro.app.query.QueryResult;
import com.faro.app.query.SqlFormatter;
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
import com.faro.app.ui.SqlAutocomplete;
import com.faro.app.ui.SqlEditorFactory;
import com.faro.app.ui.Theme;

import javafx.application.Platform;
import javafx.beans.Observable;
import javafx.beans.binding.Bindings;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBoxTreeItem;
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
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

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
 * Las credenciales del botón de prueba NUNCA deberían ir hardcodeadas en un
 * archivo que se comitea — se leen de variables de entorno por diseño.
 * (Nota 2026-08-19: quedaron hardcodeadas abajo temporalmente para probar
 * en vivo, a pedido explícito del usuario — revertir antes de comitear este
 * archivo.) Uso normal:
 *
 *   FARO_TEST_DB_USER=tu_usuario FARO_TEST_DB_PASSWORD=tu_password \
 *     mvn javafx:run
 */
public class MainController {

    private static final String TEST_JDBC_URL = "jdbc:postgresql://localhost:5432/crisol";
    private static final String DEMO_SQL =
            "SELECT id, nombre, total\nFROM ventas\nWHERE fecha >= '2026-01-01'\nORDER BY total DESC;";

    @FXML
    private Label statusLabel;

    @FXML
    private TreeView<Object> connectionTree;

    @FXML
    private ToggleButton connectionsRailButton;

    @FXML
    private ToggleButton historyRailButton;

    @FXML
    private ToggleButton favoritesRailButton;

    @FXML
    private Button settingsRailButton;

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
    private Button newQueryTabButton;

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
    private Label selectedCountLabel;

    @FXML
    private Button runButton;

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
    private Button themeToggleButton;

    private static final int MAX_HISTORY = 50;

    private final CredentialStore credentials = new CredentialStore();
    private final ConnectionPoolManager pool = new ConnectionPoolManager();
    private final AppPreferences preferences = new AppPreferences();
    private final FavoritesStore favorites = new FavoritesStore();
    private final ObservableList<String> diagnosticLog = FXCollections.observableArrayList();
    private final ObservableList<String> queryHistory = FXCollections.observableArrayList();
    private ConnectionRegistry registry;
    private TableView<ObservableList<Object>> resultsTable;
    private TableView<ExecutionStatus> executionTable;
    private List<ExecutionStatus> currentExecutionRows = List.of();
    private int queryTabCounter;

    @FXML
    private void initialize() {
        registry = loadOrCreateRegistry();
        loadCredentials();
        connectionTree.setCellFactory(tree -> new ConnectionTreeCell(this::openEditDialog));
        connectionTree.setOnMouseClicked(this::onTreeClicked);
        refreshTree();

        addQueryTab(DEMO_SQL, null);
        newQueryTabButton.setGraphic(Icons.strokeIcon(Icons.PLUS));
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

        resultsTable = ResultsTableFactory.create();
        resultsContainer.getChildren().add(resultsTable);

        executionTable = ExecutionTableFactory.create();
        executionContainer.getChildren().add(executionTable);

        ListView<String> diagnosticListView = new ListView<>(diagnosticLog);
        diagnosticListView.getStyleClass().add("diagnostic-log");
        diagnosticContainer.getChildren().add(diagnosticListView);

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
        openButton.setGraphic(Icons.strokeIcon(Icons.FOLDER));
        saveButton.setGraphic(Icons.strokeIcon(Icons.SAVE));
        formatButton.setGraphic(Icons.strokeIcon(Icons.ALIGN_LEFT));
        favoriteButton.setGraphic(Icons.strokeIcon(Icons.STAR));
        addDatabaseButton.setGraphic(Icons.strokeIcon(Icons.PLUS));
        updateThemeToggleIcon();
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
        connectionTree.getScene().getStylesheets().setAll(MainController.class
                .getResource(Theme.stylesheetResourcePath(preferences.isDarkTheme())).toExternalForm());
        updateThemeToggleIcon();
    }

    /** Muestra el ícono de lo que el clic va a hacer — luna (pasar a oscuro) en tema claro, sol (pasar a claro) en oscuro. */
    private void updateThemeToggleIcon() {
        themeToggleButton.setGraphic(Icons.strokeIcon(preferences.isDarkTheme() ? Icons.SUN : Icons.MOON));
    }

    /** Agrega una línea con hora al log de la pestaña Diagnóstico — más reciente arriba. */
    private void log(String message) {
        diagnosticLog.add(0, LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + "  " + message);
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

        QueryTabState state = new QueryTabState(codeArea);
        state.file = file;

        Tab tab = new Tab(file != null ? file.getName() : "Consulta " + (++queryTabCounter));
        tab.setContent(new VirtualizedScrollPane<>(codeArea));
        tab.setUserData(state);

        // Agregado DESPUÉS de replaceText() de arriba — si no, cargar el
        // texto inicial (demo o archivo abierto) marcaría la pestaña como
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
        ButtonType saveType = new ButtonType("Guardar");
        ButtonType discardType = new ButtonType("Descartar cambios");
        ButtonType cancelType = new ButtonType("Cancelar", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(saveType, discardType, cancelType);
        alert.initOwner(connectionTree.getScene().getWindow());

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

    /** Editar → Autocompletado (Ctrl+Espacio) — ver el javadoc de {@link SqlAutocomplete}. */
    @FXML
    private void onAutocomplete() {
        QueryTabState state = currentTabState();
        if (state == null) {
            return;
        }
        SqlAutocomplete.show(state.codeArea);
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

    @FXML
    private void onImportCsv() {
        CsvImportDialog.show(connectionTree.getScene().getWindow(), registry.allDatabases(), credentials, pool,
                preferences);
    }

    @FXML
    private void onOpenPreferences() {
        PreferencesDialog.show(connectionTree.getScene().getWindow(), preferences, this::applyCurrentTheme);
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
        alert.showAndWait();
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

        Task<Long> task = new Task<>() {
            @Override
            protected Long call() {
                long connected = 0;
                for (DatabaseEntry db : all) {
                    Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
                    if (creds.isEmpty()) {
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
                    }
                    Platform.runLater(connectionTree::refresh);
                }
                return connected;
            }
        };
        task.setOnRunning(e -> statusLabel.setText("Probando " + all.size() + " conexión(es)…"));
        task.setOnSucceeded(e -> {
            long connected = task.getValue();
            statusLabel.setText(connected + "/" + all.size() + " conexión(es) exitosa(s).");
            log("Probar todas las conexiones: " + connected + "/" + all.size() + " exitosas.");
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
        } catch (IOException e) {
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
        File file = chooser.showSaveDialog(connectionTree.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            StringBuilder csv = new StringBuilder();
            List<String> headers = resultsTable.getColumns().stream()
                    .map(TableColumn::getText)
                    .toList();
            csv.append(String.join(",", headers)).append('\n');
            for (ObservableList<Object> row : resultsTable.getItems()) {
                List<String> cells = row.stream()
                        .map(value -> csvEscape(value == null ? "" : value.toString()))
                        .toList();
                csv.append(String.join(",", cells)).append('\n');
            }
            Files.writeString(file.toPath(), csv.toString());
            statusLabel.setText("Exportado: " + file.getName());
            log("Resultados exportados a " + file.getName() + " (" + resultsTable.getItems().size() + " fila(s)).");
        } catch (IOException e) {
            statusLabel.setText("Error al exportar: " + e.getMessage());
        }
    }

    private static String csvEscape(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    /**
     * Corre el texto de la pestaña de consulta activa contra todas las
     * bases marcadas en el árbol — ver {@link QueryExecutionService}.
     * Conectado al botón "Ejecutar" de la barra de herramientas y al
     * ítem de menú "Consulta → Ejecutar en las bases seleccionadas".
     */
    @FXML
    private void onRunQuery() {
        QueryTabState state = currentTabState();
        if (state == null) {
            return;
        }
        String sql = state.codeArea.getText();
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

        Map<String, ExecutionStatus> statusByDatabaseId = new LinkedHashMap<>();
        for (DatabaseEntry db : selected) {
            statusByDatabaseId.put(db.id(), new ExecutionStatus(db.alias()));
        }
        currentExecutionRows = List.copyOf(statusByDatabaseId.values());
        executionTable.setItems(FXCollections.observableArrayList(currentExecutionRows));

        Task<QueryResult> task = QueryExecutionService.execute(
                new ArrayList<>(selected), credentials, pool, statusByDatabaseId, sql,
                preferences.maxConcurrentDatabases());
        task.setOnRunning(e -> statusLabel.setText("Ejecutando en " + selected.size() + " base(s)…"));
        log("Ejecutando consulta en " + selected.size() + " base(s).");
        task.setOnSucceeded(e -> {
            QueryResult result = task.getValue();
            if (!result.columns().isEmpty()) {
                ResultsTableFactory.populate(resultsTable, result.columns(), result.rows());
            }
            String errorSummary = result.errors().isEmpty() ? "" : " · " + result.errors().size() + " error(es): "
                + String.join(" | ", result.errors());
            statusLabel.setText(result.rows().size() + " fila(s)" + errorSummary);
            log("Consulta terminada: " + result.rows().size() + " fila(s)" + errorSummary);
        });
        task.setOnFailed(e -> {
            statusLabel.setText("Error al ejecutar: " + task.getException().getMessage());
            log("Error al ejecutar: " + task.getException().getMessage());
        });

        Thread thread = new Thread(task, "faro-query-exec");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Cancela todas las bases que sigan en {@code RUNNING} de la última
     * corrida — el botón "Cancelar" de cada fila en la pestaña Ejecución
     * hace lo mismo, pero para una sola base (ver
     * {@code ExecutionTableFactory}).
     */
    @FXML
    private void onCancelQuery() {
        long cancelling = currentExecutionRows.stream()
                .filter(status -> status.stateProperty().get() == ExecutionStatus.State.RUNNING)
                .peek(ExecutionStatus::cancelQuery)
                .count();
        statusLabel.setText(cancelling == 0
                ? "No hay ninguna base ejecutándose."
                : "Cancelando " + cancelling + " base(s)…");
        if (cancelling > 0) {
            log("Cancelando " + cancelling + " base(s).");
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
        String sql = state.codeArea.getText();
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
        task.setOnRunning(e -> statusLabel.setText("Pidiendo plan de ejecución a " + db.alias() + "…"));
        task.setOnSucceeded(e -> {
            QueryResult result = task.getValue();
            ResultsTableFactory.populate(resultsTable, result.columns(), result.rows());
            statusLabel.setText("Plan de ejecución de " + db.alias() + " en la pestaña Resultados.");
            log("Plan de ejecución pedido para " + db.alias() + ".");
        });
        task.setOnFailed(e -> {
            statusLabel.setText("Error al pedir el plan: " + task.getException().getMessage());
            log("Error al pedir el plan de " + db.alias() + ": " + task.getException().getMessage());
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

    private void refreshTree() {
        connectionTree.setRoot(ConnectionTreeBuilder.buildRoot(registry));
        bindSelectedCount();
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
     * Intenta cargar conexiones/preferencias guardadas de una sesión
     * anterior (ver {@link ConnectionRegistryStore}); si el archivo no
     * existe (primera vez que se corre la app) o está corrupto/con un
     * formato que ya no reconoce, no truena el arranque — cae de vuelta a
     * los datos de ejemplo, igual que antes de que existiera persistencia.
     */
    private ConnectionRegistry loadOrCreateRegistry() {
        if (Files.exists(ConnectionRegistryStore.DEFAULT_FILE)) {
            try {
                return ConnectionRegistryStore.load(ConnectionRegistryStore.DEFAULT_FILE, preferences, favorites);
            } catch (IOException | RuntimeException e) {
                System.err.println("No se pudo cargar " + ConnectionRegistryStore.DEFAULT_FILE
                        + ", usando datos de ejemplo: " + e.getMessage());
            }
        }
        return ConnectionRegistry.withDemoData();
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
                System.err.println("No se pudieron cargar las credenciales guardadas: " + e.getMessage());
            }
        }
    }

    /**
     * Cierra los pools de HikariCP y guarda conexiones/preferencias/
     * credenciales en disco — llamado desde {@code Main#stop()} al cerrar
     * la ventana. Las credenciales van a un archivo aparte y cifrado, ver
     * {@link CredentialVaultStore}.
     */
    void shutdown() {
        pool.closeAll();
        try {
            ConnectionRegistryStore.save(registry, preferences, favorites, ConnectionRegistryStore.DEFAULT_FILE);
        } catch (IOException e) {
            System.err.println("No se pudieron guardar conexiones: " + e.getMessage());
        }
        try {
            CredentialVaultStore.save(credentials, CredentialVaultStore.DEFAULT_FILE);
        } catch (IOException | RuntimeException e) {
            System.err.println("No se pudieron guardar las credenciales: " + e.getMessage());
        }
    }

    @FXML
    private void onTestConnection() {
        String user = System.getenv("FARO_TEST_DB_USER");
        String password = System.getenv("FARO_TEST_DB_PASSWORD");

        user = "postgres";
        password = "crisol";

        if (user == null || password == null) {
            statusLabel.setText(
                "Faltan FARO_TEST_DB_USER / FARO_TEST_DB_PASSWORD en el entorno.");
            return;
        }

        statusLabel.setText("Conectando…");
        try (Connection conn = DriverManager.getConnection(TEST_JDBC_URL, user, password)) {
            String version = conn.getMetaData().getDatabaseProductVersion();
            statusLabel.setText("Conectado — " + version.lines().findFirst().orElse(version));
        } catch (SQLException e) {
            statusLabel.setText("Error de conexión: " + e.getMessage());
        }
    }
}
