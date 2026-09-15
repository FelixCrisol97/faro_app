package com.faro.app.ui;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.faro.app.query.SqlFormatter;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import javafx.application.Platform;

/**
 * Arma el editor SQL: un {@link CodeArea} de RichTextFX con números de línea
 * y resaltado de sintaxis básico (palabras clave/cadenas/números/
 * comentarios). Devuelve el {@code CodeArea} mismo (no envuelto en su
 * {@code VirtualizedScrollPane}) para que {@code MainController} pueda
 * guardar la referencia y leer {@code getText()} al ejecutar una consulta
 * — envolverlo en el scroll pane queda del lado del llamador. Se usa una
 * vez por cada pestaña de consulta (ver
 * {@code MainController#addQueryTab}) — cada llamada arma un
 * {@code CodeArea} independiente, vacío; el texto de ejemplo de la
 * primera pestaña lo pone {@code MainController}, no esta fábrica (una
 * pestaña nueva debe empezar vacía, no repetir la consulta de ejemplo).
 *
 * <p><b>El resaltado se calcula FUERA del hilo de la UI</b> (2026-09-10,
 * hallazgo B1 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}) — ver
 * {@link #requestHighlight}.
 *
 * <p><b>Una sola lista de palabras clave</b> (hallazgo A6) — {@link #PATTERN} se
 * arma desde {@link SqlFormatter#KEYWORDS}, que es {@code public} justamente para
 * eso. Antes esta clase mantenía su propio arreglo paralelo y las dos listas ya
 * habían divergido: 26 palabras (EXEC, PROCEDURE, DECLARE, BEGIN, COMMIT,
 * TRIGGER…) se autocompletaban pero nunca se resaltaban —justo las que se
 * agregaron a pedido del usuario el 2026-08-22— y 5 agregados (COUNT, SUM, AVG,
 * MIN, MAX) se resaltaban pero no se autocompletaban.
 */
public final class SqlEditorFactory {

    /**
     * Alternancia del regex, armada desde la única lista de palabras clave del
     * proyecto. Ordenada por longitud descendente: en una alternancia de regex gana
     * la primera rama que calce, así que con {@code IN|INSERT} un {@code INSERT}
     * podría intentar casar {@code IN} primero. Los {@code \b} de
     * {@link #PATTERN} ya lo evitan por su cuenta (el límite de palabra falla entre
     * la N y la S y el motor retrocede), pero depender de ese rebote es frágil de
     * leer; ordenar de más larga a más corta lo hace obvio y no cuesta nada — se
     * calcula una sola vez al cargar la clase.
     */
    private static final String KEYWORD_ALTERNATION = SqlFormatter.KEYWORDS.stream()
            .sorted(Comparator.comparingInt(String::length).reversed())
            .collect(Collectors.joining("|"));

    /**
     * Comentarios de línea ({@code -- …}) Y de bloque ({@code /* … *&#47;}) — los de
     * bloque faltaban (2026-09-10): {@code SqlFormatter} y
     * {@code SqlStatementSplitter} sí los reconocían, solo el resaltado los ignoraba,
     * así que un bloque comentado se veía como código normal. {@code [\s\S]} en vez
     * de la bandera DOTALL para que el {@code .} de las otras ramas siga sin cruzar
     * saltos de línea.
     */
    private static final Pattern PATTERN = Pattern.compile(
        "(?<KEYWORD>\\b(?:" + KEYWORD_ALTERNATION + ")\\b)"
            + "|(?<STRING>'([^'\\\\]|\\\\.)*')"
            + "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
            + "|(?<COMMENT>--[^\\n]*|/\\*[\\s\\S]*?\\*/)",
        Pattern.CASE_INSENSITIVE);

    /**
     * Un solo hilo demonio para el resaltado de TODAS las pestañas — el trabajo es
     * corto y CPU-bound, y serializarlo evita que varias pestañas grandes compitan
     * entre sí. Demonio explícito (el {@code ExecutorService} de fábrica no los marca
     * así) para no bloquear el cierre de la app con un resaltado a medias, mismo
     * criterio que {@code SchemaIntrospector#schemaExecutor}.
     */
    private static final ExecutorService HIGHLIGHT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "faro-sql-highlight");
        thread.setDaemon(true);
        return thread;
    });

    /** Cuánto se espera tras la última tecla antes de recalcular — agrupa una ráfaga de tecleo en un solo cálculo. */
    private static final Duration HIGHLIGHT_DELAY = Duration.ofMillis(150);

    private SqlEditorFactory() {
    }

    public static CodeArea create() {
        CodeArea codeArea = new CodeArea();
        codeArea.getStyleClass().add("sql-editor");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));

        // Contador de generación POR EDITOR — ver requestHighlight.
        AtomicLong generation = new AtomicLong();
        codeArea.multiPlainChanges()
            .successionEnds(HIGHLIGHT_DELAY)
            .subscribe(ignore -> requestHighlight(codeArea, generation));

        return codeArea;
    }

    /**
     * Calcula el resaltado en {@link #HIGHLIGHT_EXECUTOR} y lo aplica de vuelta en el
     * hilo de la UI.
     *
     * <p><b>Qué arregla</b> (hallazgo B1): antes esto era una sola línea síncrona
     * —{@code subscribe(ignore -> codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText())))}—
     * que corría ENTERA en el hilo de JavaFX cada vez que el usuario dejaba de
     * teclear 150 ms: recorrer el documento completo con el regex y construir dos
     * entradas de {@code StyleSpans} por coincidencia, para TODO el texto. Con un
     * script grande (un dump pegado, o un {@code INSERT} generado de miles de líneas
     * — algo que esta misma app produce con "Generar INSERT") eso se siente como que
     * el editor se traba al escribir. Es la variante síncrona del demo de RichTextFX,
     * que la propia biblioteca publica junto a una asíncrona precisamente porque no
     * aguanta documentos grandes.
     *
     * <p><b>Lo que NO se puede mover:</b> {@code getText()} tiene que quedarse acá, en
     * el hilo de la UI — el documento de RichTextFX no es seguro de leer desde otro
     * hilo. Es la parte barata de las tres; el regex y la construcción de los spans,
     * que son el grueso, sí salen.
     *
     * <p><b>Por qué un contador de generación y no cancelar tareas:</b> mismo patrón
     * que {@code SchemaIntrospector#generation}, ya establecido en el proyecto. Si el
     * usuario sigue escribiendo mientras un cálculo está en vuelo, el resultado viejo
     * llega tarde y describe un texto que ya no existe — aplicarlo pintaría los
     * colores corridos respecto del contenido actual. Comparar la generación justo
     * antes de escribir lo descarta sin necesidad de interrumpir el hilo.
     */
    private static void requestHighlight(CodeArea codeArea, AtomicLong generation) {
        String text = codeArea.getText();
        long myGeneration = generation.incrementAndGet();
        HIGHLIGHT_EXECUTOR.execute(() -> {
            StyleSpans<Collection<String>> spans = computeHighlighting(text);
            Platform.runLater(() -> {
                if (generation.get() != myGeneration) {
                    // Llegó tarde: ya hay otro cálculo más nuevo en camino.
                    return;
                }
                // El documento pudo encogerse entre la captura y este momento (ej. Ctrl+Z
                // justo ahora) — setStyleSpans con un largo mayor que el texto actual
                // lanza IndexOutOfBounds. La generación cubre el caso normal; esto cubre
                // la carrera estrecha que queda.
                if (spans.length() == codeArea.getLength()) {
                    codeArea.setStyleSpans(0, spans);
                }
            });
        });
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();

        while (matcher.find()) {
            String styleClass = matcher.group("KEYWORD") != null ? "keyword"
                : matcher.group("STRING") != null ? "string"
                : matcher.group("NUMBER") != null ? "number"
                // COMMENT es la última rama posible: si hubo coincidencia y no fue
                // ninguna de las tres de arriba, solo puede ser esta. Antes había un
                // `: null` final inalcanzable que, de alcanzarse, habría tronado en
                // Collections.singleton(null) más adelante (hallazgo C6).
                : "comment";

            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }
}
