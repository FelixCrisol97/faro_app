package com.faro.app.ui;

import java.util.LinkedHashMap;
import java.util.Map;

import com.faro.app.data.AppPreferences;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/** Controlador del diálogo "Preferencias" — Rendimiento (real) / Atajos (referencia) / Apariencia (tema claro/oscuro, real). */
public class PreferencesDialogController {

    private static final Logger log = LoggerFactory.getLogger(PreferencesDialogController.class);

    private static final String THEME_LIGHT = "Claro";
    private static final String THEME_DARK = "Oscuro";

    @FXML private TabPane tabPane;
    @FXML private TextField maxConcurrentField;
    @FXML private TextField poolSizeField;
    @FXML private TextField queryTimeoutField;
    @FXML private TextField fetchSizeField;
    @FXML private ComboBox<String> themeCombo;
    @FXML private HBox accentSwatchesBox;
    @FXML private Spinner<Integer> editorFontSizeSpinner;
    @FXML private Label statusLabel;

    private Stage stage;
    private AppPreferences preferences;
    private Runnable onThemeChanged;
    /** Acento elegido en el diálogo, todavía sin guardar — aplica de verdad solo al picar "Guardar" (mismo criterio que el combo de Tema). */
    private String selectedAccent;
    private final Map<String, StackPane> accentSwatchNodes = new LinkedHashMap<>();

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    void attachPreferences(AppPreferences preferences) {
        this.preferences = preferences;
        maxConcurrentField.setText(String.valueOf(preferences.maxConcurrentDatabases()));
        poolSizeField.setText(String.valueOf(preferences.defaultPoolSize()));
        queryTimeoutField.setText(String.valueOf(preferences.defaultQueryTimeoutSeconds()));
        fetchSizeField.setText(String.valueOf(preferences.fetchSize()));
        themeCombo.getItems().setAll(THEME_LIGHT, THEME_DARK);
        themeCombo.getSelectionModel().select(preferences.isDarkTheme() ? THEME_DARK : THEME_LIGHT);

        selectedAccent = preferences.accentName();
        buildAccentSwatches();

        editorFontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 24, preferences.editorFontSize()));
    }

    /** Un círculo de color por {@link AccentPalette#NAMES} — clic selecciona (solo cambia {@link #selectedAccent}, se aplica de verdad recién al Guardar, mismo criterio que el combo de Tema). El seleccionado lleva un anillo (borde), no un cambio de tamaño/relleno, para que los 6 sigan alineados. */
    private void buildAccentSwatches() {
        accentSwatchesBox.getChildren().clear();
        accentSwatchNodes.clear();
        for (String name : AccentPalette.NAMES) {
            Circle dot = new Circle(9, Color.web(AccentPalette.swatchHex(name)));
            StackPane swatch = new StackPane(dot);
            swatch.getStyleClass().add("pref-accent-swatch");
            swatch.setCursor(Cursor.HAND);
            swatch.setOnMouseClicked(event -> {
                selectedAccent = name;
                refreshAccentSelection();
            });
            accentSwatchNodes.put(name, swatch);
            accentSwatchesBox.getChildren().add(swatch);
        }
        refreshAccentSelection();
    }

    private void refreshAccentSelection() {
        accentSwatchNodes.forEach((name, node) ->
                node.getStyleClass().removeAll("pref-accent-swatch-selected"));
        StackPane selected = accentSwatchNodes.get(selectedAccent);
        if (selected != null) {
            selected.getStyleClass().add("pref-accent-swatch-selected");
        }
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
            preferences.setFetchSize(Integer.parseInt(fetchSizeField.getText().trim()));

            boolean wasDark = preferences.isDarkTheme();
            boolean nowDark = THEME_DARK.equals(themeCombo.getValue());
            preferences.setDarkTheme(nowDark);

            String previousAccent = preferences.accentName();
            preferences.setAccentName(selectedAccent);

            int previousFontSize = preferences.editorFontSize();
            preferences.setEditorFontSize(editorFontSizeSpinner.getValue());

            // Antes solo reaplicaba si cambiaba el tema — ampliado (2026-08-25) para que
            // acento/tamaño de fuente también se reflejen en caliente en la ventana
            // principal sin tener que cerrar/reabrir la app.
            boolean needsReapply = wasDark != nowDark
                    || !previousAccent.equals(preferences.accentName())
                    || previousFontSize != preferences.editorFontSize();
            if (needsReapply && onThemeChanged != null) {
                onThemeChanged.run();
            }

            log.info("Preferencias guardadas — maxConcurrent={}, poolSize={}, queryTimeout={}s, fetchSize={}, tema={}, acento={}, fuenteEditor={}px",
                    preferences.maxConcurrentDatabases(), preferences.defaultPoolSize(),
                    preferences.defaultQueryTimeoutSeconds(), preferences.fetchSize(), nowDark ? "oscuro" : "claro",
                    preferences.accentName(), preferences.editorFontSize());
            stage.close();
        } catch (NumberFormatException e) {
            log.debug("Guardado de preferencias rechazado — campo de Rendimiento no numérico.");
            statusLabel.setText("Los cuatro campos de Rendimiento deben ser números enteros.");
        }
    }

    @FXML
    private void onClose() {
        stage.close();
    }
}
