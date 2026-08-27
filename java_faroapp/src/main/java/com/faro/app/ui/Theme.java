package com.faro.app.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.scene.Scene;

/**
 * Punto único donde vive la ruta de cada hoja de estilos — la ventana
 * principal (`Main.java`) y cada diálogo (que carga su propio FXML/Scene
 * por separado, ver {@code AddDatabaseDialog} y similares) la usan para no
 * quedar siempre en tema claro mientras la ventana principal ya cambió a
 * oscuro. Sin esto, un diálogo se ve con un tema distinto al de la
 * ventana que lo abrió — inconsistente, no solo feo.
 *
 * <p><b>Sistema (2026-08-21) — variables reales en vez de dos archivos
 * duplicados.</b> {@link #stylesheetResourcePaths(boolean)} devuelve DOS
 * hojas que hay que cargar juntas: la paleta (`theme-light.css`/
 * `theme-dark.css`, solo variables `-token-*` sobre `.root`) y `app.css`
 * (todas las reglas de verdad, las mismas sin importar el tema, usando
 * esas variables en vez de hex literal — "looked-up colors", un mecanismo
 * real de JavaFX que existe desde siempre). Motivo del cambio: el sistema
 * viejo (dos archivos completos duplicados, `styles.css`/`styles-dark.css`)
 * hizo que el mismo bug de estilos ("me olvidé de tocar el otro archivo")
 * apareciera doce veces seguidas en una sola sesión — ver README, "Sistema
 * de temas".
 *
 * <p><b>Tamaño de fuente de la interfaz (2026-08-26) — NO usa el mismo
 * mecanismo de variables que los colores.</b> Se intentó primero con
 * tokens {@code -token-font-*} sobre {@code .root}, igual que los colores
 * — y falló al correr la app de verdad: {@code -fx-font-size} usa una
 * gramática dedicada en el {@code CssParser} de JavaFX
 * (verificado leyendo el propio código fuente de {@code CssParser.java},
 * método {@code parseSize()}/{@code isSize()}) que exige un literal
 * NUMBER+unidad — nunca cae al camino genérico de "looked-up value" que sí
 * usan las propiedades de tipo Paint. El primer intento rompió la hoja
 * ENTERA (un error de parseo en una sola declaración descarta el archivo
 * completo, no solo esa regla), dejando toda la app sin ningún estilo.
 * Arreglo real, sin tocar el mecanismo de colores (ese sí funciona):
 * {@code app.css} se queda con sus ~36 valores de {@code -fx-font-size}
 * literales de siempre (9.5px a 18px, el 14px de {@code .sql-editor}
 * aparte). {@link #scaledAppCssUri} genera, la primera vez que hace falta
 * cada valor entero de {@code fontScaleDelta} (rango -5..5, 11 posibles),
 * una COPIA del archivo con esos literales desplazados por regex y la
 * escribe a un archivo temporal — {@code scene.getStylesheets()} carga esa
 * copia en vez del recurso del classpath. Las copias se cachean por delta
 * ({@code scaledCssCache}) para no reescribir el archivo en cada frame
 * mientras el usuario arrastra el slider nuevo de Preferencias.
 */
public final class Theme {

    private static final Pattern FONT_SIZE_DECLARATION =
            Pattern.compile("-fx-font-size:\\s*([0-9]+(?:\\.[0-9]+)?)px;");

    /**
     * Selector exacto del bloque del editor SQL — su {@code -fx-font-size}
     * (14px) queda fuera del escalado por {@code fontScaleDelta}, controlado
     * aparte por {@code AppPreferences#editorFontSize}. Debe coincidir
     * carácter por carácter con cómo aparece en {@code app.css} (selector
     * propio, no {@code .sql-editor-scroll} ni {@code .sql-editor .algo}) —
     * si el selector cambia de forma en el archivo, esta exclusión deja de
     * encontrar el bloque y {@link #generateScaledAppCss} lo escala como a
     * cualquier otro.
     */
    private static final String SQL_EDITOR_SELECTOR = ".sql-editor {";

    /** Piso real bajo el cual un texto deja de poder leerse — ver {@link #scaledFontSize}. */
    private static final double MIN_FONT_SIZE_PX = 8;

    private static volatile String appCssTemplate;
    private static final Object templateLock = new Object();

    /** Una entrada por cada valor entero de delta ya generado en este proceso — como máximo 11 (rango -5..5). */
    private static final ConcurrentHashMap<Integer, String> scaledCssCache = new ConcurrentHashMap<>();
    private static final ReentrantLock generationLock = new ReentrantLock();

    private Theme() {
    }

    /** Sistema de paleta de variables — ver el javadoc de la clase. */
    public static List<String> stylesheetResourcePaths(boolean darkTheme) {
        return List.of(darkTheme ? "/com/faro/app/theme-dark.css" : "/com/faro/app/theme-light.css");
    }

    /**
     * Agrega las hojas del tema activo a {@code scene} — un solo lugar para
     * las 7 ventanas que lo necesitan (`Main.java`, `MainController` dos
     * veces — el toggle en caliente y los `Alert`/`TextInputDialog` — y los
     * 5 diálogos) en vez de repetir el mismo bucle "resolver cada ruta +
     * agregarla" en cada uno.
     *
     * <p><b>Acento real (2026-08-25)</b> — después de las hojas de siempre,
     * sobreescribe los 5 tokens {@code -token-accent-*} en el nodo raíz de
     * la escena con un estilo inline (`Node#setStyle`), que en la cascada
     * de JavaFX gana sobre la regla `.root { ... }` de
     * `theme-light.css`/`theme-dark.css`. Sin esto, cualquier ventana se
     * queda siempre en "indigo" sin importar {@code accentName}.
     *
     * <p>{@code app.css} (o su copia escalada, ver el javadoc de la clase)
     * se agrega DESPUÉS del inline de acento en la lista de stylesheets
     * pero eso no importa para el acento — el inline vive en el nodo raíz,
     * no en la cascada de archivos, y siempre gana.
     */
    public static void applyTo(Scene scene, boolean darkTheme, String accentName, int fontScaleDelta) {
        for (String path : stylesheetResourcePaths(darkTheme)) {
            scene.getStylesheets().add(Theme.class.getResource(path).toExternalForm());
        }
        scene.getStylesheets().add(scaledAppCssUri(fontScaleDelta));
        applyAccentOverride(scene, darkTheme, accentName);
    }

    /**
     * Reaplica acento (sin tocar/recargar ninguna hoja) — usado cuando el
     * acento cambia sin que cambien tema o tamaño de fuente, para no
     * recargar stylesheets completos por nada.
     */
    public static void applyAccentOverride(Scene scene, boolean darkTheme, String accentName) {
        AccentPalette.Tokens t = AccentPalette.tokens(accentName, darkTheme);
        scene.getRoot().setStyle(
                "-token-accent-base: " + t.base() + ";"
                + " -token-accent-hover: " + t.hover() + ";"
                + " -token-accent-active: " + t.active() + ";"
                + " -token-accent-soft: " + t.soft() + ";"
                + " -token-accent-soft-text: " + t.softText() + ";");
    }

    /**
     * URI {@code file:} de la copia de {@code app.css} con sus literales de
     * {@code -fx-font-size} desplazados por {@code fontScaleDelta} — ver el
     * javadoc de la clase para por qué no es un token {@code -token-*} como
     * el resto de las variables de esta hoja. Cacheada por valor entero de
     * delta ({@code scaledCssCache}), así que mover el slider de
     * Preferencias entre los mismos -5..5 de siempre no reescribe el
     * archivo cada vez — solo la primera vez que se pisa cada valor en todo
     * el proceso.
     */
    public static String scaledAppCssUri(int fontScaleDelta) {
        String cached = scaledCssCache.get(fontScaleDelta);
        if (cached != null) {
            return cached;
        }
        generationLock.lock();
        try {
            return scaledCssCache.computeIfAbsent(fontScaleDelta, Theme::generateScaledAppCss);
        } finally {
            generationLock.unlock();
        }
    }

    private static String generateScaledAppCss(int fontScaleDelta) {
        String template = appCssTemplate();
        int editorBlockStart = template.indexOf(SQL_EDITOR_SELECTOR);
        int editorBlockEnd = editorBlockStart >= 0 ? template.indexOf('}', editorBlockStart) : -1;

        Matcher matcher = FONT_SIZE_DECLARATION.matcher(template);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            boolean insideEditorBlock = editorBlockStart >= 0
                    && matcher.start() > editorBlockStart && matcher.start() < editorBlockEnd;
            String replacement;
            if (insideEditorBlock) {
                replacement = matcher.group();
            } else {
                double base = Double.parseDouble(matcher.group(1));
                replacement = "-fx-font-size: " + scaledFontSize(base, fontScaleDelta) + ";";
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        try {
            Path tempFile = Files.createTempFile("faro-app-fontscale-" + fontScaleDelta + "-", ".css");
            tempFile.toFile().deleteOnExit();
            Files.writeString(tempFile, result.toString(), StandardCharsets.UTF_8);
            return tempFile.toUri().toString();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo generar la copia escalada de app.css", e);
        }
    }

    private static String appCssTemplate() {
        String loaded = appCssTemplate;
        if (loaded != null) {
            return loaded;
        }
        synchronized (templateLock) {
            if (appCssTemplate == null) {
                try (InputStream in = Theme.class.getResourceAsStream("/com/faro/app/app.css")) {
                    appCssTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("No se pudo leer app.css", e);
                }
            }
            return appCssTemplate;
        }
    }

    /** {@code base} + {@code delta}, con un piso de {@link #MIN_FONT_SIZE_PX} — evita texto ilegible en el extremo -5. */
    private static String scaledFontSize(double base, int delta) {
        double px = Math.max(MIN_FONT_SIZE_PX, base + delta);
        String number = px == Math.rint(px) ? Long.toString((long) px) : Double.toString(px);
        return number + "px";
    }

    /** Sistema viejo, sin usar — ver el javadoc de la clase para cuándo volver a esto. */
    public static String legacyStylesheetResourcePath(boolean darkTheme) {
        return darkTheme ? "/com/faro/app/styles-dark.css" : "/com/faro/app/styles.css";
    }
}
