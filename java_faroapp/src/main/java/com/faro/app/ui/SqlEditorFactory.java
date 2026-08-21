package com.faro.app.ui;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

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
 */
public final class SqlEditorFactory {

    private static final String[] KEYWORDS = {
        "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
        "DELETE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "ON",
        "GROUP", "BY", "ORDER", "HAVING", "LIMIT", "OFFSET", "AND", "OR",
        "NOT", "NULL", "IS", "IN", "LIKE", "BETWEEN", "AS", "DISTINCT",
        "UNION", "ALL", "CASE", "WHEN", "THEN", "ELSE", "END", "CREATE",
        "TABLE", "ALTER", "DROP", "INDEX", "PRIMARY", "KEY", "FOREIGN",
        "REFERENCES", "DEFAULT", "CONSTRAINT", "WITH", "EXISTS", "COUNT",
        "SUM", "AVG", "MIN", "MAX", "TOP", "DESC", "ASC",
    };

    private static final Pattern PATTERN = Pattern.compile(
        "(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)"
            + "|(?<STRING>'([^'\\\\]|\\\\.)*')"
            + "|(?<NUMBER>\\b\\d+(\\.\\d+)?\\b)"
            + "|(?<COMMENT>--[^\\n]*)",
        Pattern.CASE_INSENSITIVE);

    private SqlEditorFactory() {
    }

    public static CodeArea create() {
        CodeArea codeArea = new CodeArea();
        codeArea.getStyleClass().add("sql-editor");
        codeArea.setParagraphGraphicFactory(LineNumberFactory.get(codeArea));

        codeArea.multiPlainChanges()
            .successionEnds(Duration.ofMillis(150))
            .subscribe(ignore -> codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText())));

        return codeArea;
    }

    private static StyleSpans<Collection<String>> computeHighlighting(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();

        while (matcher.find()) {
            String styleClass = matcher.group("KEYWORD") != null ? "keyword"
                : matcher.group("STRING") != null ? "string"
                : matcher.group("NUMBER") != null ? "number"
                : matcher.group("COMMENT") != null ? "comment"
                : null;

            builder.add(Collections.emptyList(), matcher.start() - lastEnd);
            builder.add(Collections.singleton(styleClass), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        builder.add(Collections.emptyList(), text.length() - lastEnd);
        return builder.create();
    }
}
