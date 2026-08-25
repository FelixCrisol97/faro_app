package com.faro.app.query;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Arma un nombre de archivo descriptivo para "Exportar resultados a CSV" —
 * a partir de la base consultada, la tabla real (de la cláusula
 * {@code FROM}) y las condiciones clave del {@code WHERE} de la consulta
 * que produjo los resultados actuales, más fecha/hora. Pedido explícito
 * del usuario (2026-08-22): para
 * {@code SELECT * FROM productos WHERE id > 200 AND id < 300} sobre la
 * base "Bodega Norte", da algo como
 * {@code BodegaNorte_productos_id_mayor_200_id_menor_300_20260822_143012}.
 *
 * <p><b>Heurística por patrón de texto, no un parser SQL completo</b> —
 * mismo criterio que {@link SqlFormatter}/la heurística de solo lectura de
 * {@link QueryExecutionService}: reconoce {@code FROM <tabla>} y
 * condiciones simples {@code columna OPERADOR valor} separadas por
 * {@code AND}/{@code OR} a nivel superior. <b>Límites conocidos,
 * aceptados a propósito:</b> no entiende paréntesis anidados en el
 * {@code WHERE} (una condición dentro de un grupo se trata igual que una
 * de nivel superior — puede producir fragmentos de más, nunca de menos,
 * en un {@code WHERE} con lógica compuesta compleja); condiciones que no
 * calcen con {@code columna OPERADOR valor} (subconsultas, funciones,
 * {@code IN (...)}, {@code LIKE}, etc.) simplemente no aportan fragmento,
 * no rompen el resto del nombre. Si no se puede extraer nada útil
 * (consulta sin {@code FROM} reconocible, o vacía), el nombre cae de
 * vuelta a solo base+fecha — nunca deja el nombre vacío.
 */
public final class CsvFileNamer {

    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private static final Map<String, String> OPERATOR_WORDS = new LinkedHashMap<>();
    static {
        // Orden importa — el matcher de abajo prueba en este mismo orden,
        // así que los operadores de 2 caracteres van antes que sus
        // versiones de 1 (si no, ">=" nunca calzaría, ">" se lo comería
        // primero).
        OPERATOR_WORDS.put(">=", "mayor_igual");
        OPERATOR_WORDS.put("<=", "menor_igual");
        OPERATOR_WORDS.put("<>", "distinto");
        OPERATOR_WORDS.put("!=", "distinto");
        OPERATOR_WORDS.put("=", "igual");
        OPERATOR_WORDS.put(">", "mayor");
        OPERATOR_WORDS.put("<", "menor");
    }

    private static final Pattern FROM_TABLE = Pattern.compile(
            "(?i)\\bFROM\\s+([A-Za-z_][A-Za-z0-9_.\"\\[\\]]*)");
    private static final Pattern WHERE_CLAUSE = Pattern.compile(
            "(?i)\\bWHERE\\b(.*?)(?=\\bGROUP\\s+BY\\b|\\bORDER\\s+BY\\b|\\bHAVING\\b|\\bLIMIT\\b|;|$)",
            Pattern.DOTALL);
    private static final Pattern CONDITION_SPLIT = Pattern.compile("(?i)\\bAND\\b|\\bOR\\b");
    private static final Pattern CONDITION = Pattern.compile(
            "([A-Za-z_][A-Za-z0-9_.]*)\\s*(>=|<=|<>|!=|=|>|<)\\s*'?([A-Za-z0-9_.\\-]+)'?");

    private CsvFileNamer() {
    }

    /** {@code databaseLabel} ya viene armado por el llamador (alias de una sola base, o algo tipo "3-bases" si la corrida tocó varias). */
    public static String suggest(String databaseLabel, String sql, LocalDateTime when) {
        StringBuilder name = new StringBuilder();
        appendSanitized(name, databaseLabel);

        String sanitizedSql = stripComments(sql == null ? "" : sql);

        Matcher tableMatcher = FROM_TABLE.matcher(sanitizedSql);
        if (tableMatcher.find()) {
            String table = tableMatcher.group(1);
            int lastDot = table.lastIndexOf('.');
            if (lastDot >= 0) {
                table = table.substring(lastDot + 1);
            }
            appendSanitized(name, table);
        }

        Matcher whereMatcher = WHERE_CLAUSE.matcher(sanitizedSql);
        if (whereMatcher.find()) {
            for (String part : CONDITION_SPLIT.split(whereMatcher.group(1))) {
                Matcher conditionMatcher = CONDITION.matcher(part);
                if (conditionMatcher.find()) {
                    String column = conditionMatcher.group(1);
                    String operatorWord = OPERATOR_WORDS.get(conditionMatcher.group(2));
                    String value = conditionMatcher.group(3);
                    appendSanitized(name, column);
                    appendSanitized(name, operatorWord);
                    appendSanitized(name, value);
                }
            }
        }

        appendSanitized(name, when.format(TIMESTAMP_FORMAT));
        return name.toString();
    }

    private static void appendSanitized(StringBuilder name, String piece) {
        String clean = sanitize(piece);
        if (clean.isEmpty()) {
            return;
        }
        if (name.length() > 0) {
            name.append('_');
        }
        name.append(clean);
    }

    /** Deja solo lo que Windows/macOS/Linux aceptan sin pelear en un nombre de archivo — letras/dígitos/guion bajo, todo lo demás se vuelve "_", sin guiones bajos repetidos ni al principio/final. */
    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        String result = value.replaceAll("[^A-Za-z0-9]+", "_");
        result = result.replaceAll("_+", "_");
        result = result.replaceAll("^_|_$", "");
        return result;
    }

    /**
     * Reemplaza SOLO comentarios ({@code /* *&#47;}/{@code --}) por espacios
     * de la misma longitud — nunca literales de texto entre comillas, a
     * propósito: el valor de un {@code WHERE columna = 'activo'} vive
     * justo ahí, y es exactamente lo que se quiere capturar en el nombre
     * del CSV (no solo el caso numérico del ejemplo del usuario). El
     * costo aceptado de no tocar los strings: una palabra tipo
     * {@code FROM}/{@code WHERE}/{@code GROUP BY} que aparezca DENTRO de
     * un literal de texto podría, en teoría, confundir el límite de una
     * cláusula — mismo tipo de límite ya aceptado en
     * {@code QueryExecutionService#isReadOnlyStatement}, no un caso que
     * se vaya a dar en el uso real de este editor.
     */
    private static String stripComments(String sql) {
        Pattern comments = Pattern.compile("/\\*.*?\\*/|--[^\\n]*", Pattern.DOTALL);
        Matcher matcher = comments.matcher(sql);
        StringBuilder out = new StringBuilder();
        int lastEnd = 0;
        while (matcher.find()) {
            out.append(sql, lastEnd, matcher.start());
            out.append(" ".repeat(matcher.end() - matcher.start()));
            lastEnd = matcher.end();
        }
        out.append(sql, lastEnd, sql.length());
        return out.toString();
    }
}
