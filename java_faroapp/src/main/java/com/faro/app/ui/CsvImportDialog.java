package com.faro.app.ui;

import java.io.IOException;
import java.util.List;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Abre el diálogo modal "Importar CSV a una tabla". */
public final class CsvImportDialog {

    private CsvImportDialog() {
    }

    public static void show(
            Window owner, List<DatabaseEntry> databases, CredentialStore credentials, ConnectionPoolManager pool,
            AppPreferences preferences) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    CsvImportDialog.class.getResource("/com/faro/app/csv-import-dialog.fxml"));
            Parent root = loader.load();
            CsvImportDialogController controller = loader.getController();
            controller.configure(databases, credentials, pool);

            Scene scene = new Scene(root, 460, 400);
            Theme.applyTo(scene, preferences.isDarkTheme(), preferences.accentName());

            Stage stage = new Stage();
            stage.setTitle("Importar CSV a una tabla");
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(scene);
            controller.attachStage(stage);

            stage.showAndWait();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar csv-import-dialog.fxml", e);
        }
    }
}
