package com.faro.app.query;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnmappableCharacterException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parser CSV mínimo pero correcto para campos entre comillas (comas y
 * comillas escapadas `""` dentro de un campo) — no un {@code split(",")}
 * ingenuo, que rompería con cualquier CSV exportado desde Excel/Sheets que
 * traiga texto con comas. Límite conocido: no soporta saltos de línea
 * dentro de un campo entre comillas (cada línea física es una fila).
 *
 * <p><b>Detecta la codificación</b> (2026-09-14, hallazgo A8 de
 * {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}) — ver {@link #parse}.
 */
public final class CsvParser {

    private static final Logger log = LoggerFactory.getLogger(CsvParser.class);

    /** BOM de UTF-8. Excel lo escribe al exportar "CSV UTF-8"; si se deja, el primer encabezado llega con un carácter invisible pegado adelante y {@code CsvImportService} lo rechaza como identificador inválido. */
    private static final char BOM = '﻿';

    private CsvParser() {
    }

    /**
     * Las filas del archivo más la codificación con la que se pudo leer — ver
     * {@link #parse}. La codificación se devuelve para poder MOSTRARLA: si el archivo
     * no era UTF-8 y se leyó con otra cosa, el usuario debería enterarse antes de que
     * los acentos aparezcan raros dentro de su tabla.
     */
    public record Result(List<List<String>> rows, Charset charset) {
    }

    /**
     * Lee el CSV probando UTF-8 primero y, si el archivo no es UTF-8 válido, la
     * codificación por defecto del sistema.
     *
     * <p><b>Por qué</b> (2026-09-14): antes esto era {@code Files.newBufferedReader(file)},
     * que usa UTF-8 con {@code CodingErrorAction.REPORT} — no reemplaza los bytes
     * inválidos, lanza {@code MalformedInputException}. Excel en Windows en español
     * exporta "CSV (delimitado por comas)" en la página de códigos ANSI del sistema
     * (cp1252), no en UTF-8, así que <b>cualquier archivo con un acento, una ñ o un °
     * hacía fallar el import completo</b> — y el mensaje que veía el usuario era
     * {@code Input length = 1}, que no dice nada sobre codificaciones.
     *
     * <p>El orden importa: UTF-8 primero porque es lo correcto y porque su validación
     * es estricta (un archivo cp1252 con acentos casi siempre falla como UTF-8, que es
     * justo lo que hace útil la detección). Al revés no funcionaría: cp1252 acepta
     * cualquier byte, así que un archivo UTF-8 se leería sin error pero con los
     * acentos partidos en dos caracteres.
     *
     * <p><b>Límite:</b> un CSV en una tercera codificación (UTF-16, por ejemplo) sigue
     * fallando o leyéndose mal. No hay forma de detectarlo con certeza sin heurísticas
     * más grandes, y no es el caso real que se reportó.
     */
    public static Result parse(Path file) throws IOException {
        try {
            return new Result(read(file, StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        } catch (MalformedInputException | UnmappableCharacterException notUtf8) {
            Charset fallback = nativeCharset();
            log.info("'{}' no es UTF-8 válido — releyendo con la codificación nativa del sistema ({}).",
                    file.getFileName(), fallback.displayName());
            return new Result(read(file, fallback), fallback);
        }
    }

    /**
     * La codificación NATIVA del sistema operativo — cp1252 en un Windows en español,
     * que es lo que Excel usa al exportar "CSV (delimitado por comas)".
     *
     * <p><b>No es {@code Charset.defaultCharset()}</b>, y la diferencia es justo lo que
     * hace falta acá: desde Java 18 (JEP 400) ese método devuelve <b>siempre UTF-8</b>,
     * sin importar el idioma del sistema. Usarlo como respaldo significaría reintentar
     * con la misma codificación que acaba de fallar — error real, encontrado porque el
     * test de cp1252 siguió fallando con el respaldo puesto.
     *
     * <p>{@code native.encoding} (propiedad estándar desde Java 17) sí reporta la del
     * sistema. Si faltara o nombrara algo que esta JVM no soporta, se cae a
     * windows-1252 explícito: la app es de escritorio Windows y ese es el caso que se
     * está cubriendo.
     */
    private static Charset nativeCharset() {
        String name = System.getProperty("native.encoding");
        if (name != null && !name.isBlank()) {
            try {
                Charset candidate = Charset.forName(name);
                if (!candidate.equals(StandardCharsets.UTF_8)) {
                    return candidate;
                }
            } catch (IllegalArgumentException unsupported) {
                log.debug("native.encoding='{}' no es una codificación reconocida por esta JVM.", name);
            }
        }
        return Charset.forName("windows-1252");
    }

    private static List<List<String>> read(Path file, Charset charset) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String line;
            boolean firstLine = true;
            while ((line = reader.readLine()) != null) {
                if (firstLine) {
                    firstLine = false;
                    if (!line.isEmpty() && line.charAt(0) == BOM) {
                        line = line.substring(1);
                    }
                }
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
