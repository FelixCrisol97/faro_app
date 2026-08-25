package com.faro.app.ui;

import java.io.IOException;
import java.util.Optional;
import java.util.function.Consumer;

import com.faro.app.data.AppPreferences;
import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/** Abre el diálogo modal "Agregar/editar base de datos" y devuelve la base creada o editada, si el usuario confirmó. */
public final class AddDatabaseDialog {

    private AddDatabaseDialog() {
    }

    public static Optional<DatabaseEntry> showForAdd(
            Window owner, CredentialStore credentials, AppPreferences preferences) {
        return show(owner, credentials, preferences, "Agregar base de datos", AddDatabaseDialogController::startAdd);
    }

    public static Optional<DatabaseEntry> showForEdit(
            Window owner, CredentialStore credentials, AppPreferences preferences, DatabaseEntry entry) {
        return show(owner, credentials, preferences, "Editar base de datos", controller -> controller.startEdit(entry));
    }

    private static Optional<DatabaseEntry> show(
            Window owner, CredentialStore credentials, AppPreferences preferences, String title,
            Consumer<AddDatabaseDialogController> setup) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    AddDatabaseDialog.class.getResource("/com/faro/app/add-database-dialog.fxml"));
            Parent root = loader.load();
            AddDatabaseDialogController controller = loader.getController();
            controller.attachCredentialStore(credentials);
            controller.attachPreferences(preferences);
            setup.accept(controller);

            // Alto real 420x600 con un ScrollPane interno hasta 2026-08-22 —
            // el usuario lo rechazó ("no quiero un scroll, quiero que se vea
            // bien"). Ahora 420x720 sin ScrollPane, y SÍ redimensionable
            // (antes no lo era) como respaldo: 720px ya cubre con margen un
            // mensaje de "Probar conexión" largo (2-3 líneas), pero si algún
            // mensaje resulta más largo todavía, el usuario puede agrandar
            // la ventana arrastrando el borde en vez de toparse con texto
            // cortado otra vez.
            Scene scene = new Scene(root, 420, 720);
            Theme.applyTo(scene, preferences.isDarkTheme(), preferences.accentName());

            Stage stage = new Stage();
            stage.setTitle(title);
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(scene);
            stage.setResizable(true);
            stage.setMinWidth(420);
            stage.setMinHeight(560);
            controller.attachStage(stage);

            stage.showAndWait();
            return controller.result();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar add-database-dialog.fxml", e);
        }
    }
}
