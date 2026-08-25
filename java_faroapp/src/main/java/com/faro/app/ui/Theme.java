package com.faro.app.ui;

import java.util.List;

import javafx.scene.Scene;

/**
 * Punto único donde vive la ruta de cada hoja de estilos — la ventana
 * principal (`Main.java`) y cada diálogo (que carga su propio FXML/Scene
 * por separado, ver {@code AddDatabaseDialog} y similares) la usan para no
 * quedar siempre en tema claro mientras la ventana principal ya cambió a
 * oscuro. Sin esto, un diálogo se ve con un tema distinto al de la
 * ventana que lo abrió — inconsistente, no solo feo.
 *
 * <p><b>Sistema nuevo (2026-08-21) — variables reales en vez de dos
 * archivos duplicados.</b> {@link #stylesheetResourcePaths(boolean)}
 * devuelve DOS hojas que hay que cargar juntas: la paleta
 * (`theme-light.css`/`theme-dark.css`, solo variables `-token-*` sobre
 * `.root`) y `app.css` (todas las reglas de verdad, las mismas sin
 * importar el tema, usando esas variables en vez de hex literal —
 * "looked-up colors", un mecanismo real de JavaFX que existe desde
 * siempre, no algo nuevo de JDK 24 como se dijo en una versión anterior
 * de este comentario). Motivo del cambio: el sistema viejo (dos archivos
 * completos duplicados, `styles.css`/`styles-dark.css`) hizo que el mismo
 * bug de estilos ("me olvidé de tocar el otro archivo") apareciera doce
 * veces seguidas en una sola sesión — ver README, "Sistema de temas".
 * Con variables compartidas, cada regla vive en un solo lugar.
 *
 * <p>{@link #legacyStylesheetResourcePath(boolean)} sigue ahí, sin usarse
 * desde ningún lado del código — es el método viejo, de una sola hoja
 * completa por tema. `styles.css`/`styles-dark.css` (los archivos que
 * apunta) se dejaron sin tocar a propósito como respaldo instantáneo: si
 * el sistema de variables nuevo resulta tener un problema real al
 * probarlo en una ventana (no se pudo verificar en este entorno, solo por
 * lectura de CSS), volver al sistema viejo es cambiar las llamadas de
 * {@link #stylesheetResourcePaths} a este método en los 7 lugares que lo
 * usan (`Main.java` + 5 diálogos + `MainController#applyCurrentTheme`),
 * sin tener que reconstruir nada.
 */
public final class Theme {

    private Theme() {
    }

    /** Sistema nuevo — paleta de variables + hoja estructural única. Ver el javadoc de la clase. */
    public static List<String> stylesheetResourcePaths(boolean darkTheme) {
        return List.of(
                darkTheme ? "/com/faro/app/theme-dark.css" : "/com/faro/app/theme-light.css",
                "/com/faro/app/app.css");
    }

    /**
     * Agrega las hojas del tema activo a {@code scene} — un solo lugar para
     * las 7 ventanas que lo necesitan (`Main.java`, `MainController` dos
     * veces — el toggle en caliente y los `Alert`/`TextInputDialog` — y los
     * 5 diálogos) en vez de repetir el mismo bucle "resolver cada ruta +
     * agregarla" en cada uno. Las rutas son absolutas
     * ("/com/faro/app/..."), así que no importa qué {@code Class} se use
     * para resolverlas — {@code Theme.class} sirve igual que cualquier
     * otra.
     *
     * <p><b>Acento real (2026-08-25)</b> — después de las 2 hojas de
     * siempre, sobreescribe los 5 tokens {@code -token-accent-*} en el nodo
     * raíz de la escena con un estilo inline (`Node#setStyle`), que en la
     * cascada de JavaFX gana sobre la regla `.root { ... }` de
     * `theme-light.css`/`theme-dark.css` — mismo mecanismo de "looked-up
     * colors" que ya usan esas hojas, solo que el override vive en código,
     * no en un archivo `.css` nuevo por combinación de acento/tema (serían
     * 12). Sin esto, cualquier ventana se queda siempre en "indigo" sin
     * importar {@code accentName}, porque las hojas de siempre son las
     * únicas que definían esos 5 tokens hasta ahora.
     */
    public static void applyTo(Scene scene, boolean darkTheme, String accentName) {
        for (String path : stylesheetResourcePaths(darkTheme)) {
            scene.getStylesheets().add(Theme.class.getResource(path).toExternalForm());
        }
        applyAccent(scene, darkTheme, accentName);
    }

    /** Reaplica solo el acento (sin tocar/recargar las hojas de tema) — usado cuando el acento cambia pero el tema claro/oscuro no, para no perder el trabajo de recargar 2 stylesheets completos por nada. */
    public static void applyAccent(Scene scene, boolean darkTheme, String accentName) {
        AccentPalette.Tokens t = AccentPalette.tokens(accentName, darkTheme);
        scene.getRoot().setStyle(
                "-token-accent-base: " + t.base() + ";"
                + " -token-accent-hover: " + t.hover() + ";"
                + " -token-accent-active: " + t.active() + ";"
                + " -token-accent-soft: " + t.soft() + ";"
                + " -token-accent-soft-text: " + t.softText() + ";");
    }

    /** Sistema viejo, sin usar — ver el javadoc de la clase para cuándo volver a esto. */
    public static String legacyStylesheetResourcePath(boolean darkTheme) {
        return darkTheme ? "/com/faro/app/styles-dark.css" : "/com/faro/app/styles.css";
    }
}
