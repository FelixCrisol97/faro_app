package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
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
        return CsvParser.parse(file);
    }
}
