package com.faro.app.query;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Formateador SQL heurístico (Editar → Formatear SQL, Ctrl+L) — pone en
 * mayúscula las palabras clave reconocidas y agrega un salto de línea
 * antes de las cláusulas principales (SELECT/FROM/WHERE/GROUP BY/ORDER
 * BY/HAVING/UNION/los distintos JOIN). <b>No es un parser SQL
 * completo</b> — igual criterio que la heurística de solo lectura de
 * {@code QueryExecutionService}: reconoce tokens por patrón, no entiende
 * la gramática completa.
 *
 * <p><b>Nunca toca el contenido de literales de texto</b> (
 * {@code 'así'}, con {@code ''} como comilla escapada), identificadores
 * entre comillas dobles o corchetes ({@code "así"}/{@code [así]}, este
 * último sin soportar {@code ]]} escapado dentro — límite aceptado), ni
 * comentarios ({@code -- …}/{@code /* … *&#47;}) — cambiar mayúsculas o
 * insertar saltos de línea ahí adentro corrompería datos reales de la
 * consulta, que es peor que no formatear nada.
 *
 * <p><b>Simplificación deliberada:</b> un {@code JOIN} sin calificar
 * (sin {@code LEFT}/{@code RIGHT}/{@code INNER}/{@code OUTER}/
 * {@code FULL}/{@code CROSS} antes) no fuerza su propio salto de línea —
 * evita el caso contrario de partir {@code LEFT JOIN} en dos líneas sin
 * tener que rastrear el token anterior.
 */
public final class SqlFormatter {

    /** Público a propósito — {@code SqlAutocomplete} reusa este mismo set en vez de mantener una segunda lista de palabras clave separada. */
    public static final Set<String> KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "JOIN", "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS", "ON",
            "GROUP", "BY", "ORDER", "HAVING", "AND", "OR", "NOT", "IN", "AS", "DISTINCT",
            "LIMIT", "OFFSET", "UNION", "ALL", "CASE", "WHEN", "THEN", "ELSE", "END",
            "NULL", "IS", "LIKE", "BETWEEN", "EXISTS", "CREATE", "TABLE", "ALTER",
            "DROP", "WITH", "TOP", "ASC", "DESC");

    private static final Set<String> NEWLINE_BEFORE = Set.of(
            "SELECT", "FROM", "WHERE", "GROUP", "ORDER", "HAVING",
            "LEFT", "RIGHT", "INNER", "OUTER", "FULL", "CROSS",
            "UNION", "SET", "VALUES", "INSERT", "UPDATE", "DELETE", "LIMIT", "OFFSET");

    // En orden: comentario de bloque, comentario de línea, cadena entre comillas
    // simples (con '' como comilla escapada), identificador entre comillas dobles,
    // identificador entre corchetes, o una palabra suelta — todo lo demás (espacios,
    // puntuación) se copia tal cual entre un token reconocido y el siguiente.
    private static final Pattern TOKEN = Pattern.compile(
            "/\\*.*?\\*/" + "|" + "--[^\\n]*" + "|"
                    + "'(?:[^']|'')*'" + "|" + "\"(?:[^\"]|\"\")*\"" + "|" + "\\[[^\\]]*\\]" + "|"
                    + "[A-Za-z_][A-Za-z0-9_]*",
            Pattern.DOTALL);

    private SqlFormatter() {
    }

    public static String format(String sql) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = TOKEN.matcher(sql);
        int lastEnd = 0;
        while (matcher.find()) {
            out.append(sql, lastEnd, matcher.start());
            String token = matcher.group();
            char first = token.charAt(0);
            if (Character.isLetter(first) || first == '_') {
                String upper = token.toUpperCase(Locale.ROOT);
                if (NEWLINE_BEFORE.contains(upper) && out.length() > 0
                        && out.charAt(out.length() - 1) != '\n') {
                    trimTrailingSpaces(out);
                    out.append('\n');
                }
                out.append(KEYWORDS.contains(upper) ? upper : token);
            } else {
                // Comentario/literal/identificador entre comillas — pasa sin tocar.
                out.append(token);
            }
            lastEnd = matcher.end();
        }
        out.append(sql, lastEnd, sql.length());
        return out.toString();
    }

    private static void trimTrailingSpaces(StringBuilder sb) {
        int end = sb.length();
        while (end > 0 && (sb.charAt(end - 1) == ' ' || sb.charAt(end - 1) == '\t')) {
            end--;
        }
        sb.setLength(end);
    }
}
