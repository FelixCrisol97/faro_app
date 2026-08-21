package com.faro.app.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.ServerMode;
import com.faro.app.query.DiscoveredDatabase;
import com.faro.app.query.DiscoveryService;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Controlador del diálogo "Descubrir bases de datos" — ver {@link DiscoveryService}. */
public class DiscoverDialogController {

    @FXML private TextField hostField;
    @FXML private TextField userField;
    @FXML private PasswordField passwordField;
    @FXML private Label searchStatusLabel;
    @FXML private VBox resultsBox;

    private final Map<CheckBox, DiscoveredDatabase> checkboxToResult = new LinkedHashMap<>();
    private final List<DatabaseEntry> added = new ArrayList<>();

    private Stage stage;
    private CredentialStore credentials;
    private String lastHost;
    /**
     * Generación de la búsqueda actual — si el usuario lanza una segunda
     * búsqueda antes de que termine la primera, la primera queda "vieja"
     * y sus resultados tardíos se descartan al llegar en vez de mezclarse
     * con los de la búsqueda nueva bajo el {@code lastHost} equivocado
     * (hallazgo real de /code-review).
     */
    private int searchGeneration;

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    void attachCredentialStore(CredentialStore credentials) {
        this.credentials = credentials;
        credentials.getDefault().ifPresent(def -> {
            userField.setText(def.user());
            passwordField.setText(def.password());
        });
    }

    List<DatabaseEntry> addedDatabases() {
        return added;
    }

    @FXML
    private void onSearch() {
        String host = hostField.getText();
        if (host == null || host.isBlank()) {
            searchStatusLabel.setText("Escribe un host.");
            return;
        }
        lastHost = host.trim();
        resultsBox.getChildren().clear();
        checkboxToResult.clear();
        searchStatusLabel.setText("Buscando…");

        int myGeneration = ++searchGeneration;
        Task<List<DiscoveredDatabase>> task =
                DiscoveryService.discover(lastHost, userField.getText(), passwordField.getText());
        task.setOnSucceeded(e -> {
            if (myGeneration != searchGeneration) {
                // Una búsqueda más nueva ya empezó mientras esta corría — descartar, no mezclar.
                return;
            }
            List<DiscoveredDatabase> found = task.getValue();
            if (found.isEmpty()) {
                searchStatusLabel.setText("No se encontró ninguna base accesible en ese host.");
                return;
            }
            searchStatusLabel.setText(found.size() + " base(s) encontradas.");
            for (DiscoveredDatabase db : found) {
                CheckBox checkBox = new CheckBox(db.engine().badge() + "  " + db.databaseName());
                checkboxToResult.put(checkBox, db);
                resultsBox.getChildren().add(checkBox);
            }
        });
        task.setOnFailed(e -> searchStatusLabel.setText("Error al buscar: " + task.getException().getMessage()));

        Thread thread = new Thread(task, "faro-discover");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onAddSelected() {
        for (Map.Entry<CheckBox, DiscoveredDatabase> entry : checkboxToResult.entrySet()) {
            if (!entry.getKey().isSelected()) {
                continue;
            }
            DiscoveredDatabase found = entry.getValue();
            DatabaseEntry dbEntry = new DatabaseEntry(found.databaseName(), lastHost, found.engine().defaultPort(),
                    found.databaseName(), found.engine(), ServerMode.READ_ONLY);
            credentials.put(dbEntry.id(), userField.getText(), passwordField.getText());
            added.add(dbEntry);
        }
        stage.close();
    }

    @FXML
    private void onClose() {
        stage.close();
    }
}
