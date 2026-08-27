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

            // 420 de alto (valor original) se quedaba corto para el contenido real de la
            // pestaña Rendimiento (4 filas de grilla + 2 notas de varias líneas) — sin
            // espacio suficiente, el VBox comprimía la nota más larga ("Fetch size...")
            // hasta dejar solo una línea con "…" (reportado con captura, 2026-08-25).
            // 460x560 dio margen para eso, pero el usuario reportó después (2026-08-26,
            // con captura) que la fila de acentos se veía apretada — el último círculo
            // (el seleccionado, con su anillo) quedaba cortado por el borde de la
            // ventana. 500x600 + mínimos subidos a juego le dan aire real a esa fila sin
            // apretar nada más; min height/width + resizable como red de seguridad si
            // una nota futura es más larga todavía — mismo criterio ya aplicado en
            // add-database-dialog.
            Scene scene = new Scene(root, 500, 600);
            Theme.applyTo(scene, preferences.isDarkTheme(), preferences.accentName(), preferences.fontScaleDelta());

            Stage stage = new Stage();
            stage.setTitle("Preferencias");
            stage.initOwner(owner);
            stage.initModality(Modality.WINDOW_MODAL);
            stage.setScene(scene);
            stage.setResizable(true);
            stage.setMinWidth(460);
            stage.setMinHeight(520);
            controller.attachStage(stage);

            stage.showAndWait();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo cargar preferences-dialog.fxml", e);
        }
    }
}
