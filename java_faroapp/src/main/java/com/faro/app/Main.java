package com.faro.app;

import java.io.IOException;
import java.net.URL;

import com.faro.app.ui.Theme;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.text.Font;
import javafx.stage.Stage;

/**
 * Punto de entrada de la app JavaFX. Deliberadamente delgado — carga la
 * ventana principal desde FXML (no construye el árbol de nodos a mano en
 * Java), siguiendo el patrón FXML+CSS que fija el diseño en
 * Migración_Flutter_Java/entrega/faro-java-handoff.html.
 */
public class Main extends Application {

    private MainController controller;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) throws IOException {
        loadFonts();

        FXMLLoader loader = new FXMLLoader(Main.class.getResource("main-view.fxml"));
        Parent root = loader.load();
        controller = loader.getController();

        Scene scene = new Scene(root, 1280, 800);
        // El tema (claro/oscuro) ya está cargado en las preferencias del controlador para
        // cuando llegamos acá — initialize() corrió como parte de loader.load() arriba.
        // Theme.stylesheetResourcePath() devuelve una ruta absoluta ("/com/faro/app/..."),
        // hace falta pasarla tal cual — Class.getResource() la resuelve desde la raíz del
        // classpath, no relativa al paquete de Main (a diferencia de "styles.css" a secas).
        scene.getStylesheets().add(Main.class
                .getResource(Theme.stylesheetResourcePath(controller.isDarkTheme())).toExternalForm());

        stage.setTitle("Faro");
        stage.setMinWidth(960);
        stage.setMinHeight(600);
        stage.setScene(scene);
        // Antes de cerrar la ventana (la X del sistema, Alt+F4, etc.) — si hay
        // alguna pestaña de consulta con cambios sin guardar, pregunta qué
        // hacer en vez de perderlos en silencio (ver MainController#confirmCloseAllTabs).
        stage.setOnCloseRequest(event -> {
            if (!controller.confirmCloseAllTabs()) {
                event.consume();
            }
        });
        stage.show();
    }

    /** Cierra los pools de HikariCP antes de que el JVM salga — si no, sus hilos internos pueden quedar colgados. */
    @Override
    public void stop() {
        controller.shutdown();
    }

    /**
     * Las mismas tres fuentes que la versión Flutter (Sora para títulos,
     * Manrope para texto, JetBrains Mono para el editor) — ver
     * {@code app_typography.dart}. Son variable fonts; JavaFX solo puede
     * cargar la instancia por defecto de un variable font (normalmente el
     * peso 400), así que los pesos más gruesos (600/700) se logran con
     * negrita sintética vía {@code -fx-font-weight} en el CSS, no con el
     * eje de peso real de la fuente — más cerca del diseño real que la
     * fuente del sistema, aunque no sea 100% idéntico al renderizado de
     * Flutter.
     */
    private void loadFonts() {
        String[] files = {"fonts/Sora-Variable.ttf", "fonts/Manrope-Variable.ttf", "fonts/JetBrainsMono-Variable.ttf"};
        for (String file : files) {
            // getResource() puede devolver null si el recurso falta del empaquetado (ej. un
            // problema de jlink/jpackage) — sin este chequeo, .toExternalForm() tronaba con
            // NullPointerException antes de siquiera llegar al chequeo de abajo que el código
            // ya preveía para "no se pudo cargar la fuente" (hallazgo real de /code-review).
            URL url = Main.class.getResource(file);
            if (url == null) {
                System.err.println("No se encontró el recurso de fuente: " + file);
                continue;
            }
            Font font = Font.loadFont(url.toExternalForm(), 12);
            if (font == null) {
                System.err.println("No se pudo cargar la fuente: " + file);
            }
        }
    }
}
