package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Cruza las clases de estilo que usa el código contra las reglas de {@code app.css}
 * (2026-09-14, cierra el hallazgo C9 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
 *
 * <p><b>Por qué existe.</b> Dos bugs llegaron a producción y solo se vieron en tema
 * oscuro, cuando el usuario los reportó: {@code .trust-cert-check} (2026-09-07) y
 * {@code .discover-results-scroll} (2026-09-11). En los dos el patrón fue idéntico —
 * la clase se aplicaba desde Java o FXML y <b>no existía ninguna regla</b> para ella
 * en {@code app.css}, así que el nodo se quedaba con los colores por defecto de
 * Modena. En tema claro eso pasa desapercibido (los defaults de Modena SON claros);
 * en oscuro queda texto blanco sobre fondo blanco. Este test los habría atrapado a
 * los dos sin que nadie abriera la aplicación.
 *
 * <p><b>Solo en esa dirección.</b> El cruce inverso — reglas en el CSS que nadie usa —
 * no se hace a propósito: {@code app.css} restiliza decenas de clases internas de
 * JavaFX ({@code .check-box}, {@code .combo-box}, {@code .arrow}, {@code .scroll-bar}…)
 * y de RichTextFX ({@code .keyword}, {@code .lineno}), que por definición no aparecen
 * en ningún {@code getStyleClass().add(...)} nuestro y saldrían todas como falsos
 * "muertos".
 *
 * <p><b>Solo {@code app.css}.</b> {@code theme-dark.css} y {@code theme-light.css} no
 * entran porque no definen ni una sola clase de componente: son únicamente el bloque
 * {@code .root} con los tokens de color ({@code -token-*}) que {@code app.css} después
 * busca. Si algún día definen reglas propias, hay que sumarlas acá.
 *
 * <p>No arranca JavaFX — lee los archivos fuente como texto, igual que el resto de la
 * suite permanente.
 */
class StyleClassCoverageTest {

    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path RESOURCES = Path.of("src", "main", "resources", "com", "faro", "app");
    private static final Path APP_CSS = RESOURCES.resolve("app.css");

    /**
     * Clases que el código arma concatenando en tiempo de ejecución, con las variantes
     * concretas que de verdad puede producir. Un prefijo suelto no se puede cruzar
     * contra el CSS (no es el nombre de ninguna regla), así que acá se declara qué
     * termina generando y el test comprueba <b>esas</b> contra el CSS.
     *
     * <p>Cada entrada sale de un {@code switch} exhaustivo o de un {@code enum}, así que
     * la lista es cerrada y verificable a mano:
     * <ul>
     *   <li>{@code log-level-} → {@code MainController.LogLevel} (INFO, WARN, ERROR, DEBUG),
     *       en minúsculas.</li>
     *   <li>{@code tree-status-dot-} → {@code ConnectionTreeCell#statusStyleSuffix},
     *       un switch sobre {@code DatabaseEntry.ConnectionStatus}.</li>
     *   <li>{@code pool-dot-} → {@code MainController#refreshStatusBar}, ternario entre
     *       "hay conexiones" y "no hay".</li>
     * </ul>
     *
     * <p>Si alguien agrega un prefijo nuevo y no lo declara acá, el test falla en vez de
     * dejarlo pasar: ver {@link #losPrefijosCompuestosEstanTodosDeclarados()}.
     */
    private static final Map<String, List<String>> COMPOSED_CLASSES = Map.of(
            "log-level-", List.of("info", "warn", "error", "debug"),
            "tree-status-dot-", List.of("connected", "failed", "testing", "unknown"),
            "pool-dot-", List.of("connected", "idle"));

    /**
     * Clases que se aplican <b>a propósito</b> sin ninguna regla detrás. No son el bug
     * que este test busca: son ganchos de estilo declarados y documentados, no un
     * descuido.
     *
     * <p>Entrar acá pide dos cosas: que el CSS explique por qué la clase no tiene
     * reglas (así el próximo que pase no la "arregle"), y que la decisión esté tomada,
     * no pendiente. Hoy hay una sola:
     * <ul>
     *   <li>{@code exec-cell-root} — tenía un borde inferior por fila y se le quitó el
     *       2026-08-28 a pedido del usuario ("que no se vea que sea una tabla"). La
     *       clase se dejó puesta en {@code ExecutionTableFactory} como gancho; ver el
     *       comentario en {@code app.css}, justo encima de {@code .exec-row}.</li>
     * </ul>
     */
    private static final Set<String> HOOKS_WITHOUT_RULES = Set.of("exec-cell-root");

    // ------------------------------------------------------------------
    // Los tres cruces
    // ------------------------------------------------------------------

    @Test
    void todaClaseAplicadaDesdeJavaTieneReglaEnAppCss() {
        assertEveryClassIsStyled(styleClassesInJava());
    }

    @Test
    void todaClaseAplicadaDesdeFxmlTieneReglaEnAppCss() {
        assertEveryClassIsStyled(styleClassesInFxml());
    }

    /** Las variantes de los prefijos declarados en {@link #COMPOSED_CLASSES}. */
    @Test
    void lasClasesCompuestasEnTiempoDeEjecucionTienenTodasSusVariantes() {
        Map<String, Path> used = new LinkedHashMap<>();
        COMPOSED_CLASSES.forEach((prefix, suffixes) ->
                suffixes.forEach(suffix -> used.put(prefix + suffix, APP_CSS)));

        assertEveryClassIsStyled(used);
    }

    /**
     * Un prefijo compuesto sin declarar es justamente el caso que el cruce normal no
     * puede ver, así que se falla acá en vez de ignorarlo en silencio.
     */
    @Test
    void losPrefijosCompuestosEstanTodosDeclarados() {
        Set<String> undeclared = new TreeSet<>();
        styleClassesInJava().forEach((name, file) -> {
            if (name.endsWith("-") && !COMPOSED_CLASSES.containsKey(name)) {
                undeclared.add(name + "  (" + file.getFileName() + ")");
            }
        });

        assertTrue(undeclared.isEmpty(),
                "Prefijos de clase compuestos en tiempo de ejecución que no están en "
                        + "COMPOSED_CLASSES. Agregalos ahí con sus variantes concretas "
                        + "para que el test pueda cruzarlas contra app.css:\n  "
                        + String.join("\n  ", undeclared));
    }

    /**
     * Las dos listas de excepciones se quedan viejas solas: si mañana alguien borra el
     * {@code exec-cell-root} del código o le agrega una regla, la entrada sigue ahí
     * tapando algo que ya no existe. Se comprueba que cada excepción siga haciendo
     * falta, no solo que exista.
     */
    @Test
    void lasListasDeExcepcionesNoSeQuedanViejas() {
        Map<String, Path> usedInJava = styleClassesInJava();
        Set<String> definedInCss = classesDefinedInCss();

        for (String hook : HOOKS_WITHOUT_RULES) {
            assertTrue(usedInJava.containsKey(hook) || styleClassesInFxml().containsKey(hook),
                    "HOOKS_WITHOUT_RULES declara '" + hook + "' pero ya no se aplica en ningún lado — sobra");
            assertTrue(!definedInCss.contains(hook),
                    "HOOKS_WITHOUT_RULES declara '" + hook + "' como gancho sin reglas, pero app.css "
                            + "ya tiene una regla para él — sacalo de la lista");
        }

        for (String prefix : COMPOSED_CLASSES.keySet()) {
            assertTrue(usedInJava.containsKey(prefix),
                    "COMPOSED_CLASSES declara el prefijo '" + prefix + "' pero ningún "
                            + "getStyleClass() lo arma ya — sobra");
        }
    }

    /**
     * Red de seguridad del propio test: si una expresión regular deja de encontrar lo
     * que buscaba, los cruces de arriba pasarían con el conjunto vacío y nadie se
     * enteraría. Los mínimos están holgados a propósito (los valores reales al
     * escribir esto eran 63 / 29 / 163); son un piso, no una cuenta exacta que haya
     * que mantener.
     */
    @Test
    void losEscaneosDeVerdadEncuentranAlgo() {
        assertTrue(Files.isDirectory(MAIN_JAVA),
                "El test se corre desde " + Path.of("").toAbsolutePath()
                        + " y ahí no está src/main/java — surefire debería usar el directorio del pom.");

        assertTrue(styleClassesInJava().size() >= 50,
                "El escaneo de Java encontró solo " + styleClassesInJava().size() + " clases de estilo");
        assertTrue(styleClassesInFxml().size() >= 25,
                "El escaneo de FXML encontró solo " + styleClassesInFxml().size() + " clases de estilo");
        assertTrue(classesDefinedInCss().size() >= 100,
                "El escaneo de app.css encontró solo " + classesDefinedInCss().size() + " reglas");
    }

    // ------------------------------------------------------------------
    // Aserción común
    // ------------------------------------------------------------------

    /** @param used clase de estilo → archivo donde se usa, para que el fallo diga dónde mirar. */
    private static void assertEveryClassIsStyled(Map<String, Path> used) {
        Set<String> defined = classesDefinedInCss();

        Map<String, Path> missing = new TreeMap<>();
        used.forEach((name, file) -> {
            // Los prefijos compuestos se verifican por separado, con sus variantes.
            if (!name.endsWith("-") && !defined.contains(name) && !HOOKS_WITHOUT_RULES.contains(name)) {
                missing.put(name, file);
            }
        });

        StringBuilder detail = new StringBuilder(
                "Clases de estilo usadas que no tienen ninguna regla en app.css. "
                        + "Sin regla el nodo se queda con los defaults de Modena, que en tema "
                        + "oscuro suele significar invisible (ver el javadoc de esta clase). "
                        + "Si alguna es un gancho a proposito, va en HOOKS_WITHOUT_RULES "
                        + "y el porque se explica en app.css:\n");
        missing.forEach((name, file) -> detail.append("  .").append(name)
                .append("  <- ").append(file.getFileName()).append('\n'));

        assertTrue(missing.isEmpty(), detail.toString());
    }

    // ------------------------------------------------------------------
    // Escaneo de Java
    // ------------------------------------------------------------------

    /**
     * Se queda con los literales de cadena que se le pasan a <b>cualquier</b> método de
     * {@code getStyleClass()}.
     *
     * <p>Sirve cualquiera y no solo {@code add}/{@code addAll}/{@code setAll} porque una
     * cadena que se le pasa a esa lista es el nombre de una clase de estilo venga como
     * venga, y las otras formas son justamente las que un escaneo de texto no podría
     * alcanzar de otro modo:
     * <ul>
     *   <li>{@code removeAll("status-success", "status-error")} — se ponen desde un
     *       parámetro del método, así que en el {@code add} no hay ningún literal.</li>
     *   <li>{@code removeIf(c -> c.startsWith("tree-status-dot-"))} — es donde el código
     *       declara que esa familia entera de clases es suya; el nombre completo se arma
     *       concatenando y nunca aparece escrito.</li>
     * </ul>
     * Una clase que el código <b>quita</b> es una clase que en algún lado <b>pone</b>, así
     * que ninguna de las dos es un falso positivo. Y no hay riesgo al revés: los métodos
     * que no reciben clases ({@code toString}, {@code size}…) no llevan literales.
     *
     * <p>Los argumentos se leen balanceando paréntesis en vez de con una regex, porque
     * hay llamadas anidadas ({@code add("log-level-" + entry.level().name()...)}) que
     * una regex no-codiciosa cortaría en el paréntesis equivocado.
     */
    private static Map<String, Path> styleClassesInJava() {
        Pattern call = Pattern.compile("getStyleClass\\(\\)\\s*\\.\\s*\\w+\\s*\\(");
        Map<String, Path> found = new LinkedHashMap<>();

        for (Path file : filesEndingIn(MAIN_JAVA, ".java")) {
            String code = stripJavaComments(read(file));
            Matcher matcher = call.matcher(code);
            while (matcher.find()) {
                String args = balancedArguments(code, matcher.end() - 1);
                for (String literal : stringLiterals(args)) {
                    found.putIfAbsent(literal, file);
                }
            }
        }
        return found;
    }

    /**
     * Reemplaza comentarios de línea y de bloque por espacios, respetando cadenas y
     * caracteres.
     *
     * <p>Hace falta porque el proyecto comenta mucho y con ejemplos de código dentro:
     * un javadoc que menciona {@code getStyleClass().add("algo")} sería un falso
     * positivo. Un {@code replaceAll} no sirve — {@code "jdbc:sqlserver://host"} tiene
     * un {@code //} que no abre ningún comentario.
     */
    private static String stripJavaComments(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '"' || c == '\'') {
                int end = endOfLiteral(source, i);
                out.append(source, i, end);
                i = end;
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                out.append(' ');
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** Índice justo después de la comilla de cierre del literal que abre en {@code start}. */
    private static int endOfLiteral(String source, int start) {
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\\') {
                i += 2;
            } else if (c == quote) {
                return i + 1;
            } else if (c == '\n') {
                return i;   // literal sin cerrar: no debería pasar en código que compila
            } else {
                i++;
            }
        }
        return source.length();
    }

    /** Contenido entre el paréntesis de {@code openIndex} y el que lo cierra. */
    private static String balancedArguments(String code, int openIndex) {
        int depth = 0;
        int i = openIndex;
        while (i < code.length()) {
            char c = code.charAt(i);
            if (c == '"' || c == '\'') {
                i = endOfLiteral(code, i);
                continue;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return code.substring(openIndex + 1, i);
                }
            }
            i++;
        }
        return code.substring(openIndex + 1);
    }

    private static final Pattern STRING_LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private static List<String> stringLiterals(String code) {
        List<String> literals = new ArrayList<>();
        Matcher matcher = STRING_LITERAL.matcher(code);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (!value.isEmpty()) {
                literals.add(value);
            }
        }
        return literals;
    }

    // ------------------------------------------------------------------
    // Escaneo de FXML
    // ------------------------------------------------------------------

    /**
     * Las dos formas que usa el proyecto: el atributo {@code styleClass="a b"} (varias
     * clases separadas por espacio) y el bloque
     * {@code <styleClass><String fx:value="a"/></styleClass>}, que es como FXML deja
     * poner varias cuando el atributo queda incómodo de leer.
     *
     * <p>Los {@code <String fx:value="…"/>} se buscan <b>solo dentro</b> de un bloque
     * {@code <styleClass>}: ese mismo elemento se usa en FXML para llenar listas
     * (los ítems de un {@code ComboBox}, por ejemplo), y ahí "UTF-8" o "LATIN1" no son
     * clases de estilo.
     */
    private static Map<String, Path> styleClassesInFxml() {
        Pattern attribute = Pattern.compile("styleClass\\s*=\\s*\"([^\"]*)\"");
        Pattern block = Pattern.compile("<styleClass>(.*?)</styleClass>", Pattern.DOTALL);
        Pattern value = Pattern.compile("fx:value\\s*=\\s*\"([^\"]*)\"");

        Map<String, Path> found = new LinkedHashMap<>();
        for (Path file : filesEndingIn(RESOURCES, ".fxml")) {
            String xml = stripXmlComments(read(file));

            Matcher attributeMatcher = attribute.matcher(xml);
            while (attributeMatcher.find()) {
                for (String name : attributeMatcher.group(1).trim().split("\\s+")) {
                    if (!name.isEmpty()) {
                        found.putIfAbsent(name, file);
                    }
                }
            }

            Matcher blockMatcher = block.matcher(xml);
            while (blockMatcher.find()) {
                Matcher valueMatcher = value.matcher(blockMatcher.group(1));
                while (valueMatcher.find()) {
                    found.putIfAbsent(valueMatcher.group(1).trim(), file);
                }
            }
        }
        return found;
    }

    private static String stripXmlComments(String xml) {
        return xml.replaceAll("(?s)<!--.*?-->", " ");
    }

    // ------------------------------------------------------------------
    // Escaneo de app.css
    // ------------------------------------------------------------------

    /**
     * Nombres de clase que aparecen en algún selector de {@code app.css}.
     *
     * <p>Solo se mira <b>fuera</b> de las llaves. Dentro de un bloque hay valores que
     * un {@code \.[a-z]} confundiría con clases si la regex barriera el archivo entero,
     * y además da igual lo que haya adentro: lo que se cruza es qué selectores existen.
     */
    private static Set<String> classesDefinedInCss() {
        String css = read(APP_CSS).replaceAll("(?s)/\\*.*?\\*/", " ");

        StringBuilder selectors = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < css.length(); i++) {
            char c = css.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (depth == 0) {
                selectors.append(c);
            }
        }

        Set<String> defined = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\.([a-zA-Z][a-zA-Z0-9_-]*)").matcher(selectors);
        while (matcher.find()) {
            defined.add(matcher.group(1));
        }
        return defined;
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static List<Path> filesEndingIn(Path root, String extension) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(p -> p.getFileName().toString().endsWith(extension)).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo recorrer " + root.toAbsolutePath(), e);
        }
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("No se pudo leer " + file.toAbsolutePath(), e);
        }
    }
}
