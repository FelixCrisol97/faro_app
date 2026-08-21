package com.faro.app.ui;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/**
 * Controlador del diálogo "Agregar/editar base de datos" — mismo formulario
 * para las dos operaciones, igual que documenta el README ("Agregar/editar
 * base de datos" es un solo diálogo, no dos). {@link #startEdit} precarga
 * los campos desde una {@link DatabaseEntry} existente y, al guardar, muta
 * esa misma instancia en vez de crear una nueva.
 *
 * <p>El usuario/contraseña se guardan en un {@link CredentialStore} en
 * memoria (no en {@link DatabaseEntry} — motor/host/modo/etc. son datos de
 * conexión, las credenciales son un concepto aparte, mismo criterio que
 * {@code credentialsRepositoryProvider} en la versión Flutter) para que la
 * ejecución real de consultas tenga con qué conectarse. No hay
 * persistencia ni cifrado todavía — se pierden al cerrar la app; el
 * diálogo "Credenciales" real (uno de los 5 pendientes, ver README) lo
 * reemplazará.
 */
public class AddDatabaseDialogController {

    @FXML private Label dialogTitleLabel;
    @FXML private TextField aliasField;
    @FXML private ComboBox<DbEngine> engineCombo;
    @FXML private TextField hostField;
    @FXML private TextField portField;
    @FXML private TextField databaseNameField;
    @FXML private TextField userField;
    @FXML private PasswordField passwordField;
    @FXML private ComboBox<ServerMode> modeCombo;
    @FXML private TextField poolSizeField;
    @FXML private TextField queryTimeoutField;
    @FXML private Label testStatusLabel;

    private Stage stage;
    private CredentialStore credentials;
    private AppPreferences preferences;
    private DatabaseEntry editing;
    private DatabaseEntry result;

    @FXML
    private void initialize() {
        engineCombo.setConverter(labelConverter(DbEngine::label));
        engineCombo.getItems().setAll(DbEngine.values());
        engineCombo.getSelectionModel().selectFirst();
        engineCombo.valueProperty().addListener((obs, previous, engine) -> {
            if (engine != null && editing == null) {
                portField.setText(String.valueOf(engine.defaultPort()));
            }
        });

        modeCombo.setConverter(labelConverter(ServerMode::label));
        modeCombo.getItems().setAll(ServerMode.values());

        // startAdd()/startEdit() se llaman explícitamente desde AddDatabaseDialog
        // después de attachPreferences()/attachCredentialStore() — no acá, porque
        // el loader ya invocó initialize() antes de que el factory alcance a
        // inyectar esas dependencias (startAdd() las necesita para los valores
        // por defecto de pool/timeout).
    }

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    void attachCredentialStore(CredentialStore credentials) {
        this.credentials = credentials;
    }

    void attachPreferences(AppPreferences preferences) {
        this.preferences = preferences;
    }

    /** Formulario vacío, con los valores por defecto del motor seleccionado. */
    void startAdd() {
        editing = null;
        dialogTitleLabel.setText("Agregar base de datos");
        aliasField.clear();
        hostField.clear();
        databaseNameField.clear();
        userField.clear();
        passwordField.clear();
        engineCombo.getSelectionModel().selectFirst();
        portField.setText(String.valueOf(engineCombo.getValue().defaultPort()));
        modeCombo.getSelectionModel().select(ServerMode.READ_ONLY);
        poolSizeField.setText(String.valueOf(preferences.defaultPoolSize()));
        queryTimeoutField.setText(String.valueOf(preferences.defaultQueryTimeoutSeconds()));
        testStatusLabel.setText(null);
    }

    /** Precarga el formulario con los datos de una base ya existente. */
    void startEdit(DatabaseEntry entry) {
        editing = entry;
        dialogTitleLabel.setText("Editar base de datos");
        aliasField.setText(entry.alias());
        hostField.setText(entry.host());
        portField.setText(String.valueOf(entry.port()));
        databaseNameField.setText(entry.databaseName());
        credentials.get(entry.id()).ifPresentOrElse(saved -> {
            userField.setText(saved.user());
            passwordField.setText(saved.password());
        }, () -> {
            userField.clear();
            passwordField.clear();
        });
        engineCombo.getSelectionModel().select(entry.engine());
        modeCombo.getSelectionModel().select(entry.mode());
        poolSizeField.setText(String.valueOf(entry.poolSize()));
        queryTimeoutField.setText(String.valueOf(entry.queryTimeoutSeconds()));
        testStatusLabel.setText(null);
    }

    Optional<DatabaseEntry> result() {
        return Optional.ofNullable(result);
    }

    @FXML
    private void onTestConnection() {
        FormValues values = readForm();
        if (values == null) {
            testStatusLabel.setText("Completa alias, host, puerto y base de datos primero.");
            return;
        }
        DatabaseEntry probe = new DatabaseEntry(values.alias, values.host, values.port,
                values.databaseName, engineCombo.getValue(), modeCombo.getValue());
        testStatusLabel.setText("Conectando…");
        try (Connection conn = DriverManager.getConnection(
                probe.jdbcUrl(), userField.getText(), passwordField.getText())) {
            String version = conn.getMetaData().getDatabaseProductVersion();
            testStatusLabel.setText("Conectado — " + version.lines().findFirst().orElse(version));
        } catch (SQLException e) {
            testStatusLabel.setText("Error de conexión: " + e.getMessage());
        }
    }

    @FXML
    private void onCancel() {
        result = null;
        stage.close();
    }

    @FXML
    private void onSave() {
        FormValues values = readForm();
        if (values == null) {
            testStatusLabel.setText("Completa alias, host, puerto y base de datos.");
            return;
        }

        DatabaseEntry entry = editing != null ? editing
                : new DatabaseEntry(values.alias, values.host, values.port, values.databaseName,
                        engineCombo.getValue(), modeCombo.getValue());
        entry.setAlias(values.alias);
        entry.setHost(values.host);
        entry.setPort(values.port);
        entry.setDatabaseName(values.databaseName);
        entry.setEngine(engineCombo.getValue());
        entry.setMode(modeCombo.getValue());
        entry.setPoolSize(values.poolSize);
        entry.setQueryTimeoutSeconds(values.queryTimeout);

        if (isBlank(userField.getText())) {
            // Vaciar el usuario a propósito quita el override guardado (si había uno) — antes
            // se quedaba vivo en CredentialStore sin que el formulario lo mostrara (hallazgo
            // real de /code-review).
            credentials.remove(entry.id());
        } else {
            credentials.put(entry.id(), userField.getText(), passwordField.getText());
        }

        result = entry;
        stage.close();
    }

    private FormValues readForm() {
        String alias = aliasField.getText();
        String host = hostField.getText();
        String databaseName = databaseNameField.getText();
        if (isBlank(alias) || isBlank(host) || isBlank(databaseName)) {
            return null;
        }
        try {
            int port = Integer.parseInt(portField.getText().trim());
            int poolSize = Integer.parseInt(poolSizeField.getText().trim());
            int queryTimeout = Integer.parseInt(queryTimeoutField.getText().trim());
            return new FormValues(alias.trim(), host.trim(), port, databaseName.trim(), poolSize, queryTimeout);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private record FormValues(
            String alias, String host, int port, String databaseName, int poolSize, int queryTimeout) {
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> StringConverter<T> labelConverter(java.util.function.Function<T, String> toLabel) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : toLabel.apply(value);
            }

            @Override
            public T fromString(String string) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
