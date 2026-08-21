package com.faro.app.ui;

import com.faro.app.data.AppPreferences;

import javafx.fxml.FXML;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

/** Controlador del diálogo "Preferencias" — Rendimiento (real) / Atajos (referencia) / Apariencia (tema claro/oscuro real). */
public class PreferencesDialogController {

    private static final String THEME_LIGHT = "Claro";
    private static final String THEME_DARK = "Oscuro";

    @FXML private TabPane tabPane;
    @FXML private TextField maxConcurrentField;
    @FXML private TextField poolSizeField;
    @FXML private TextField queryTimeoutField;
    @FXML private ComboBox<String> themeCombo;
    @FXML private Label statusLabel;

    private Stage stage;
    private AppPreferences preferences;
    private Runnable onThemeChanged;

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    void attachPreferences(AppPreferences preferences) {
        this.preferences = preferences;
        maxConcurrentField.setText(String.valueOf(preferences.maxConcurrentDatabases()));
        poolSizeField.setText(String.valueOf(preferences.defaultPoolSize()));
        queryTimeoutField.setText(String.valueOf(preferences.defaultQueryTimeoutSeconds()));
        themeCombo.getItems().setAll(THEME_LIGHT, THEME_DARK);
        themeCombo.getSelectionModel().select(preferences.isDarkTheme() ? THEME_DARK : THEME_LIGHT);
    }

    /** MainController pasa acá cómo re-aplicar el stylesheet en la ventana principal si el tema cambió. */
    void attachThemeChangeCallback(Runnable onThemeChanged) {
        this.onThemeChanged = onThemeChanged;
    }

    void selectShortcutsTab() {
        tabPane.getSelectionModel().select(1);
    }

    @FXML
    private void onSave() {
        try {
            preferences.setMaxConcurrentDatabases(Integer.parseInt(maxConcurrentField.getText().trim()));
            preferences.setDefaultPoolSize(Integer.parseInt(poolSizeField.getText().trim()));
            preferences.setDefaultQueryTimeoutSeconds(Integer.parseInt(queryTimeoutField.getText().trim()));

            boolean wasDark = preferences.isDarkTheme();
            boolean nowDark = THEME_DARK.equals(themeCombo.getValue());
            preferences.setDarkTheme(nowDark);
            if (wasDark != nowDark && onThemeChanged != null) {
                onThemeChanged.run();
            }

            stage.close();
        } catch (NumberFormatException e) {
            statusLabel.setText("Los tres campos de Rendimiento deben ser números enteros.");
        }
    }

    @FXML
    private void onClose() {
        stage.close();
    }
}
