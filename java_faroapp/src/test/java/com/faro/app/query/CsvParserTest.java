package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvParserTest {

    @TempDir
    Path tempDir;

    @Test
    void parseSimpleFields() throws IOException {
        List<List<String>> rows = parse("id,nombre\n1,Ana\n2,Beto\n");

        assertEquals(List.of("id", "nombre"), rows.get(0));
        assertEquals(List.of("1", "Ana"), rows.get(1));
        assertEquals(List.of("2", "Beto"), rows.get(2));
    }

    @Test
    void parseQuotedFieldWithComma() throws IOException {
        List<List<String>> rows = parse("id,direccion\n1,\"Calle 5, Colonia Centro\"\n");

        assertEquals(List.of("id", "direccion"), rows.get(0));
        assertEquals(List.of("1", "Calle 5, Colonia Centro"), rows.get(1));
    }

    @Test
    void parseEscapedQuoteInsideQuotedField() throws IOException {
        List<List<String>> rows = parse("id,apodo\n1,\"El \"\"Jefe\"\"\"\n");

        assertEquals(List.of("1", "El \"Jefe\""), rows.get(1));
    }

    @Test
    void blankLinesAreSkipped() throws IOException {
        List<List<String>> rows = parse("id,nombre\n1,Ana\n\n2,Beto\n");

        assertEquals(3, rows.size());
    }

    private List<List<String>> parse(String content) throws IOException {
        Path file = tempDir.resolve("test.csv");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return CsvParser.parse(file).rows();
    }

    // ---- Detección de codificación (2026-09-14, hallazgo A8) ----
    //
    // El caso real: Excel en Windows en español exporta "CSV (delimitado por comas)" en
    // cp1252, no en UTF-8. Antes, CUALQUIER archivo con un acento o una ñ hacía fallar
    // el import entero con "Input length = 1", un mensaje que no dice nada sobre
    // codificaciones.

    private Path write(String name, String content, Charset charset) throws IOException {
        Path file = tempDir.resolve(name);
        Files.write(file, content.getBytes(charset));
        return file;
    }

    @Test
    void leeUtf8YLoReporta() throws IOException {
        Path file = write("utf8.csv", "id,articulo\n1,Camión ñandú\n", StandardCharsets.UTF_8);

        CsvParser.Result result = CsvParser.parse(file);

        assertEquals(StandardCharsets.UTF_8, result.charset());
        assertEquals(List.of("1", "Camión ñandú"), result.rows().get(1));
    }

    /** El caso reportado: acentos en cp1252. Antes tronaba con MalformedInputException. */
    @Test
    void releeComoCp1252CuandoElArchivoNoEsUtf8() throws IOException {
        Charset cp1252 = Charset.forName("windows-1252");
        Path file = write("excel.csv", "id,articulo\n1,Camión ñandú\n", cp1252);

        CsvParser.Result result = CsvParser.parse(file);

        assertNotEquals(StandardCharsets.UTF_8, result.charset(), "no debería haberse leído como UTF-8");
        assertEquals(List.of("1", "Camión ñandú"), result.rows().get(1),
                "los acentos tienen que llegar intactos, no como caracteres partidos");
    }

    /**
     * Un archivo solo-ASCII es válido en las dos codificaciones — tiene que resolverse
     * como UTF-8, que es el intento preferido, y no caer al respaldo.
     */
    @Test
    void unArchivoAsciiSeLeeComoUtf8SinCaerAlRespaldo() throws IOException {
        Path file = write("ascii.csv", "id,nombre\n1,Ana\n", StandardCharsets.US_ASCII);

        assertEquals(StandardCharsets.UTF_8, CsvParser.parse(file).charset());
    }

    /**
     * Excel, al exportar "CSV UTF-8", antepone un BOM. Sin quitarlo el primer
     * encabezado llega con un carácter invisible pegado adelante y
     * {@code CsvImportService} lo rechaza como identificador inválido — el usuario
     * vería "Nombre inválido: id" sobre una columna que se llama exactamente así.
     */
    @Test
    void quitaElBomDeUtf8DelPrimerEncabezado() throws IOException {
        Path file = write("bom.csv", "﻿id,nombre\n1,Ana\n", StandardCharsets.UTF_8);

        CsvParser.Result result = CsvParser.parse(file);

        assertEquals(List.of("id", "nombre"), result.rows().get(0));
    }
}
