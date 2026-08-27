package com.faro.app.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

import com.faro.app.data.AppPreferences;
import com.faro.app.model.DatabaseEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.fxml.FXML;
import javafx.scene.Cursor;
import javafx.scene.Scene;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;

/** Controlador del diálogo "Preferencias" — las 3 pestañas aplican y guardan de inmediato, sin botón "Guardar" (ver {@link #commitPerformanceField}/{@link #reapply}); "Atajos" es solo de referencia, no tiene nada que guardar. */
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
    @FXML private Slider fontScaleSlider;
    @FXML private Label statusLabel;

    private Stage stage;
    private AppPreferences preferences;
    private Runnable onThemeChanged;
    /** Acento elegido en el diálogo — se aplica de inmediato (ver {@link #buildAccentSwatches}), esta variable solo trackea cuál swatch dibujar con el anillo de seleccionado. */
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

        bindPerformanceField(maxConcurrentField, "Bases en paralelo al ejecutar",
                preferences::setMaxConcurrentDatabases, null);
        bindPerformanceField(poolSizeField, "Tamaño de pool por defecto",
                preferences::setDefaultPoolSize, this::validatePoolSize);
        bindPerformanceField(queryTimeoutField, "Timeout de consulta por defecto",
                preferences::setDefaultQueryTimeoutSeconds, null);
        bindPerformanceField(fetchSizeField, "Fetch size",
                preferences::setFetchSize, null);

        themeCombo.getItems().setAll(THEME_LIGHT, THEME_DARK);
        themeCombo.getSelectionModel().select(preferences.isDarkTheme() ? THEME_DARK : THEME_LIGHT);
        themeCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, val) -> {
            preferences.setDarkTheme(THEME_DARK.equals(val));
            reapply();
        });

        selectedAccent = preferences.accentName();
        buildAccentSwatches();

        editorFontSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 24, preferences.editorFontSize()));
        editorFontSizeSpinner.valueProperty().addListener((obs, old, val) -> {
            preferences.setEditorFontSize(val);
            reapply();
        });

        fontScaleSlider.setValue(preferences.fontScaleDelta());
        fontScaleSlider.valueProperty().addListener((obs, old, val) -> {
            int rounded = (int) Math.round(val.doubleValue());
            if (rounded != preferences.fontScaleDelta()) {
                preferences.setFontScaleDelta(rounded);
                reapply();
            }
        });
    }

    /**
     * Guarda un campo de Rendimiento apenas se confirma — al perder el foco
     * (clic afuera, Tab) o al presionar Enter (`TextField#setOnAction`), no
     * en cada tecla — mientras se escribe un número nuevo el campo pasa por
     * estados intermedios (borrar todo, escribir de nuevo) que no tiene
     * sentido guardar ni rechazar con un error a medio escribir. Reemplaza
     * al viejo botón "Guardar" — pedido explícito del usuario (2026-08-26):
     * "preferia que por cada valor modificado se guarde en automatico".
     *
     * @param extraValidator opcional (solo lo usa {@code poolSizeField}) —
     *         si devuelve un mensaje de error, el valor NO se guarda y ese
     *         mensaje se muestra en {@link #statusLabel}; {@code null} si no
     *         hace falta validación aparte de "es un entero".
     */
    private void bindPerformanceField(TextField field, String label, IntConsumer setter,
            java.util.function.IntFunction<String> extraValidator) {
        Runnable commit = () -> {
            int value;
            try {
                value = Integer.parseInt(field.getText().trim());
            } catch (NumberFormatException e) {
                statusLabel.setText(label + ": debe ser un número entero.");
                return;
            }
            if (extraValidator != null) {
                String error = extraValidator.apply(value);
                if (error != null) {
                    statusLabel.setText(error);
                    return;
                }
            }
            setter.accept(value);
            statusLabel.setText("");
            log.debug("Preferencia de Rendimiento guardada — {} = {}", label, value);
        };
        field.setOnAction(event -> commit.run());
        field.focusedProperty().addListener((obs, wasFocused, isFocused) -> {
            if (!isFocused) {
                commit.run();
            }
        });
    }

    /** Antes esto se guardaba en silencio (DatabaseEntry/AppPreferences ya ajustan el valor internamente a este piso) sin ningún aviso — el pedido explícito del usuario (2026-08-25) fue que la UI NO deje bajar de ahí, no solo que el dato quede corregido por dentro sin que se note. */
    private String validatePoolSize(int poolSize) {
        if (poolSize < DatabaseEntry.MIN_POOL_SIZE) {
            return "El tamaño de pool mínimo es " + DatabaseEntry.MIN_POOL_SIZE
                    + " — con menos, cancelar una consulta puede no funcionar bien.";
        }
        return null;
    }

    /** Un círculo de color por {@link AccentPalette#NAMES} — clic selecciona y aplica de inmediato (ver {@link #reapply}). El seleccionado lleva un anillo (borde), no un cambio de tamaño/relleno, para que los 6 sigan alineados. */
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
                preferences.setAccentName(name);
                refreshAccentSelection();
                reapply();
            });
            accentSwatchNodes.put(name, swatch);
            accentSwatchesBox.getChildren().add(swatch);
        }
        refreshAccentSelection();
    }

    /**
     * Re-aplica tema/acento/tamaños de fuente — llamado por cada control de
     * Apariencia apenas cambia, no espera a "Guardar" (ya no existe ese
     * botón). Restyle en DOS ventanas, no una: la principal ({@code
     * onThemeChanged}, callback de {@code MainController}) Y este mismo
     * diálogo — {@code MainController#applyCurrentTheme} solo toca SU
     * propia {@code Scene}, nunca la de este diálogo (son dos {@code Scene}
     * separadas, una por {@code Stage}). Sin este segundo restyle, el
     * usuario ve la ventana principal cambiar de tema/tamaño en vivo pero
     * este diálogo se queda con el look de cuando se abrió hasta cerrarlo y
     * volver a abrirlo — bug real reportado por el usuario (2026-08-26,
     * "la pestaña de preferencias no se actualiza a modo oscuro o light").
     */
    private void reapply() {
        if (stage != null) {
            Scene scene = stage.getScene();
            if (scene != null) {
                scene.getStylesheets().clear();
                Theme.applyTo(scene, preferences.isDarkTheme(), preferences.accentName(), preferences.fontScaleDelta());
            }
        }
        if (onThemeChanged != null) {
            onThemeChanged.run();
        }
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
    private void onClose() {
        stage.close();
    }
}
