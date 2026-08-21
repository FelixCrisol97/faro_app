package com.faro.app.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Parser CSV mínimo pero correcto para campos entre comillas (comas y
 * comillas escapadas `""` dentro de un campo) — no un {@code split(",")}
 * ingenuo, que rompería con cualquier CSV exportado desde Excel/Sheets que
 * traiga texto con comas. Límite conocido: no soporta saltos de línea
 * dentro de un campo entre comillas (cada línea física es una fila).
 */
public final class CsvParser {

    private CsvParser() {
    }

    public static List<List<String>> parse(Path file) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                rows.add(parseLine(line));
            }
        }
        return rows;
    }

    private static List<String> parseLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
