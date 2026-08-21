package com.faro.app.ui;

import java.io.IOException;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.CredentialStore;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Abre el diálogo modal "Credenciales por defecto". */
public final class CredentialsDialog {

    private CredentialsDialog() {
    }

    public static boolean show(Window owner, CredentialStore credentials, AppPreferences preferences) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    CredentialsDialog.class.getResource("/com/faro/app/credentials-dialog.fxml"));
            Parent root = loader.load();
            CredentialsDialogController controller = loader.getController();
            controller.attachCredentialStore(credentials);

            Scene scene = new Scene(root, 380, 260);
            scene.getStylesheets().add(CredentialsDialog.class
                    .getResource(Theme.stylesheetResourcePath(preferences.isDarkTheme())).toExternalForm());

            Stage stage = new Stage();
            stage.setTitle("Credenciales por defecto");
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(scene);
            stage.setResizable(false);
            controller.attachStage(stage);

            stage.showAndWait();
            return controller.wasSaved();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar credentials-dialog.fxml", e);
        }
    }
}
