package com.faro.app.ui;

import java.io.IOException;

import com.faro.app.data.AppPreferences;

import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Abre el diálogo modal "Preferencias". {@link #showShortcuts} abre el
 * mismo diálogo con la pestaña Atajos ya seleccionada — así "Ayuda →
 * Atajos de teclado" no necesita su propia ventana separada para mostrar
 * lo mismo.
 */
public final class PreferencesDialog {

    private PreferencesDialog() {
    }

    public static void show(Window owner, AppPreferences preferences, Runnable onThemeChanged) {
        open(owner, preferences, false, onThemeChanged);
    }

    public static void showShortcuts(Window owner, AppPreferences preferences, Runnable onThemeChanged) {
        open(owner, preferences, true, onThemeChanged);
    }

    private static void open(Window owner, AppPreferences preferences, boolean selectShortcuts, Runnable onThemeChanged) {
        try {
            FXMLLoader loader = new FXMLLoader(
                    PreferencesDialog.class.getResource("/com/faro/app/preferences-dialog.fxml"));
            Parent root = loader.load();
            PreferencesDialogController controller = loader.getController();
            controller.attachPreferences(preferences);
            controller.attachThemeChangeCallback(onThemeChanged);
            if (selectShortcuts) {
                controller.selectShortcutsTab();
            }

            Scene scene = new Scene(root, 460, 420);
            Theme.applyTo(scene, preferences.isDarkTheme());

            Stage stage = new Stage();
            stage.setTitle("Preferencias");
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(scene);
            controller.attachStage(stage);

            stage.showAndWait();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar preferences-dialog.fxml", e);
        }
    }
}
