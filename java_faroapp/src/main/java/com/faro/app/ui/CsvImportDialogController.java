package com.faro.app.ui;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.CsvImportService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.StringConverter;

/** Controlador del diálogo "Importar CSV a una tabla" — ver {@link CsvImportService}. */
public class CsvImportDialogController {

    private static final Logger log = LoggerFactory.getLogger(CsvImportDialogController.class);

    @FXML private ComboBox<DatabaseEntry> databaseCombo;
    @FXML private TextField tableNameField;
    @FXML private Label fileLabel;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private Button importButton;

    private Stage stage;
    private CredentialStore credentials;
    private ConnectionPoolManager pool;
    private File chosenFile;

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    void configure(List<DatabaseEntry> databases, CredentialStore credentials, ConnectionPoolManager pool) {
        this.credentials = credentials;
        this.pool = pool;
        databaseCombo.setConverter(new StringConverter<>() {
            @Override
            public String toString(DatabaseEntry db) {
                return db == null ? "" : db.alias() + " (" + db.engine().badge() + ")";
            }

            @Override
            public DatabaseEntry fromString(String string) {
                throw new UnsupportedOperationException();
            }
        });
        databaseCombo.getItems().setAll(databases);
        if (!databases.isEmpty()) {
            databaseCombo.getSelectionModel().selectFirst();
        }
    }

    @FXML
    private void onChooseFile() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Elegir archivo CSV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File file = chooser.showOpenDialog(stage);
        if (file != null) {
            chosenFile = file;
            fileLabel.setText(file.getName());
            log.debug("Archivo CSV elegido: {}", file.getAbsolutePath());
        }
    }

    @FXML
    private void onImport() {
        DatabaseEntry database = databaseCombo.getValue();
        String tableName = tableNameField.getText();
        if (database == null) {
            statusLabel.setText("Elige una base de datos.");
            return;
        }
        if (tableName == null || tableName.isBlank()) {
            statusLabel.setText("Escribe el nombre de la tabla destino.");
            return;
        }
        if (chosenFile == null) {
            statusLabel.setText("Elige un archivo CSV primero.");
            return;
        }
        Optional<CredentialStore.Credentials> creds = credentials.resolve(database.id());
        if (creds.isEmpty()) {
            statusLabel.setText("Esa base no tiene credenciales guardadas — edítala o define credenciales por defecto.");
            return;
        }

        importButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.progressProperty().unbind();
        statusLabel.setText("Importando…");

        log.info("Importando CSV '{}' a '{}'.'{}'", chosenFile.getName(), database.alias(), tableName.trim());
        Task<Integer> task = CsvImportService.importCsv(
                database, creds.get(), pool, Path.of(chosenFile.getAbsolutePath()), tableName.trim());
        progressBar.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> {
            log.info("Importación de '{}' completa — {} fila(s) a '{}'.'{}'",
                    chosenFile.getName(), task.getValue(), database.alias(), tableName.trim());
            importButton.setDisable(false);
            statusLabel.setText(task.getValue() + " fila(s) importada(s) a " + tableName + ".");
        });
        task.setOnFailed(e -> {
            log.warn("Importación de '{}' falló: {}", chosenFile.getName(), task.getException().getMessage());
            importButton.setDisable(false);
            statusLabel.setText("Error al importar: " + task.getException().getMessage());
        });

        Thread thread = new Thread(task, "faro-csv-import");
        thread.setDaemon(true);
        thread.start();
    }

    @FXML
    private void onClose() {
        stage.close();
    }
}
