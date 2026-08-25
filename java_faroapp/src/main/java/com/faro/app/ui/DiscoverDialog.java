package com.faro.app.ui;

import java.io.IOException;
import java.util.List;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Abre el diálogo modal "Descubrir bases de datos" y devuelve las bases que el usuario decidió agregar (puede ser vacío). */
public final class DiscoverDialog {

    private DiscoverDialog() {
    }

    public static List<DatabaseEntry> show(Window owner, CredentialStore credentials, AppPreferences preferences) {
        return show(owner, credentials, preferences, null);
    }

    /** {@code initialHost} precarga el campo de host — usado por "Descubrir bases en esta IP…" del menú contextual de una fila (ver ConnectionTreeCell); {@code null} deja el campo vacío como antes. */
    public static List<DatabaseEntry> show(
            Window owner, CredentialStore credentials, AppPreferences preferences, String initialHost) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    DiscoverDialog.class.getResource("/com/faro/app/discover-dialog.fxml"));
            Parent root = loader.load();
            DiscoverDialogController controller = loader.getController();
            controller.attachCredentialStore(credentials);
            if (initialHost != null) {
                controller.setInitialHost(initialHost);
            }

            Scene scene = new Scene(root, 460, 520);
            Theme.applyTo(scene, preferences.isDarkTheme(), preferences.accentName());

            Stage stage = new Stage();
            stage.setTitle("Descubrir bases de datos");
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(scene);

            controller.attachStage(stage);

            stage.showAndWait();
            return controller.addedDatabases();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar discover-dialog.fxml", e);
        }
    }
}
