package com.faro.app.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Parte un script SQL en sentencias individuales, por {@code ;} de nivel
 * superior — necesario porque {@code QueryExecutionService} mandaba el
 * texto completo del editor tal cual a {@code Statement.executeQuery(sql)}
 * en una sola llamada, y eso truena en cuanto el script tiene más de una
 * sentencia real: PostgreSQL corre todas por protocolo simple pero
 * {@code executeQuery} solo admite UN {@code ResultSet} ("Multiple
 * ResultSets were returned by the query."); SQL Server corre algo pero sin
 * avisar cuál sentencia quedó reflejada. Hallazgo real del usuario
 * (2026-08-22), probando un script con varias sentencias sin darse cuenta.
 *
 * <p><b>Escáner manual, no una expresión regular</b> — a diferencia de
 * {@code SqlFormatter}/{@code CsvFileNamer} (que solo necesitan reconocer
 * tokens de forma aislada), partir por {@code ;} requiere arrastrar estado
 * secuencial (¿estoy dentro de un string ahora mismo?) carácter por
 * carácter, algo que una sola expresión regular no expresa bien. Nunca
 * corta dentro de: comentarios ({@code -- …}/{@code /* … *&#47;}),
 * literales de texto ({@code '…'}, con {@code ''} como comilla escapada),
 * identificadores entre comillas dobles o corchetes ({@code "…"}/
 * {@code […]}), ni bloques con comillas de dólar de PostgreSQL
 * ({@code $$…$$} o con etiqueta, {@code $tag$…$tag$} — la forma real en
 * que Postgres escribe el cuerpo de una función/procedimiento sin tener
 * que escapar cada comilla de adentro; sin este caso, un
 * {@code CREATE FUNCTION ... AS $$ ... ; ... $$} se hubiera partido mal en
 * cuanto el cuerpo de la función tuviera su propio {@code ;}).
 *
 * <p><b>No es un parser SQL completo</b> — mismo criterio aceptado en todo
 * este proyecto (ver {@code QueryExecutionService#isReadOnlyStatement}):
 * cubre el caso real que importa, no pretende validar sintaxis.
 */
public final class SqlStatementSplitter {

    private SqlStatementSplitter() {
    }

    public static List<String> split(String sql) {
        List<String> statements = new ArrayList<>();
        int start = 0;
        int i = 0;
        int len = sql.length();

        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"') {
                i = skipQuoted(sql, i, c);
            } else if (c == '[') {
                i = skipBracketed(sql, i);
            } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
            } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
            } else if (c == '$') {
                i = skipDollarQuotedIfAny(sql, i);
            } else if (c == ';') {
                addTrimmed(statements, sql, start, i);
                start = i + 1;
                i++;
            } else {
                i++;
            }
        }
        addTrimmed(statements, sql, start, len);
        return statements;
    }

    private static void addTrimmed(List<String> statements, String sql, int from, int to) {
        String statement = sql.substring(from, to).trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
    }

    /** {@code quote} es {@code '} o {@code "} — ambos usan el mismo carácter duplicado como escape ({@code ''}/{@code ""}). */
    private static int skipQuoted(String sql, int i, char quote) {
        int len = sql.length();
        i++;
        while (i < len) {
            if (sql.charAt(i) == quote) {
                if (i + 1 < len && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return len;
    }

    private static int skipBracketed(String sql, int i) {
        int len = sql.length();
        i++;
        while (i < len && sql.charAt(i) != ']') {
            i++;
        }
        return Math.min(i + 1, len);
    }

    private static int skipLineComment(String sql, int i) {
        int len = sql.length();
        while (i < len && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int i) {
        int len = sql.length();
        i += 2;
        while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) {
            i++;
        }
        return Math.min(i + 2, len);
    }

    /**
     * {@code $$…$$} o {@code $tag$…$tag$} — si lo que sigue al {@code $}
     * no forma un delimitador válido (letras/dígitos/guion bajo hasta el
     * siguiente {@code $}), es un {@code $} suelto (no válido en SQL
     * Server/Postgres fuera de este contexto de todas formas) y se avanza
     * un solo carácter, sin tratarlo como quoting.
     */
    private static int skipDollarQuotedIfAny(String sql, int i) {
        int len = sql.length();
        int tagEnd = i + 1;
        while (tagEnd < len && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_')) {
            tagEnd++;
        }
        if (tagEnd >= len || sql.charAt(tagEnd) != '$') {
            return i + 1;
        }
        String delimiter = sql.substring(i, tagEnd + 1);
        int closeIndex = sql.indexOf(delimiter, tagEnd + 1);
        return closeIndex < 0 ? len : closeIndex + delimiter.length();
    }
}
