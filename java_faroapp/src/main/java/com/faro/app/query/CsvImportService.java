package com.faro.app.query;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.concurrent.Task;

/**
 * Importa un CSV a una tabla ya existente — la primera fila del archivo
 * son los nombres de columna, se arma un {@code INSERT} con esas columnas
 * y se corre en lotes de {@link #BATCH_SIZE} dentro de una sola
 * transacción. Todos los valores se mandan como texto
 * ({@code PreparedStatement.setObject(i, String)}) y se apoya en la
 * conversión implícita del driver/motor hacia el tipo real de la columna
 * — funciona para los casos comunes (números, fechas ISO, texto) pero no
 * hace inferencia de tipo ni validación propia; una columna con un tipo
 * exótico puede fallar y abortar el import completo (una sola
 * transacción, no fila por fila).
 *
 * <p><b>Nombre de tabla y de columnas van validados</b> contra
 * {@link #SAFE_IDENTIFIER} antes de armar el SQL — JDBC no permite
 * parametrizar identificadores (solo valores), así que un nombre de
 * tabla o un encabezado de CSV sin validar habría sido inyección SQL
 * directa (hallazgo real de `/code-review`, ver README). Cualquier
 * nombre que no sea letras/números/guion bajo (sin empezar con número)
 * aborta el import completo antes de tocar la base, con el nombre
 * exacto que lo rechazó.
 */
public final class CsvImportService {

    private static final Logger log = LoggerFactory.getLogger(CsvImportService.class);

    private static final int BATCH_SIZE = 500;
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private CsvImportService() {
    }

    public static Task<Integer> importCsv(
            DatabaseEntry database, CredentialStore.Credentials credentials, ConnectionPoolManager pool,
            Path file, String tableName) {
        return new Task<>() {
            @Override
            protected Integer call() throws Exception {
                log.info("Importando CSV '{}' a '{}'.'{}'", file.getFileName(), database.alias(), tableName);
                validateIdentifier(tableName);

                List<List<String>> rows = CsvParser.parse(file);
                if (rows.size() < 2) {
                    log.warn("Import abortado — '{}' tiene {} línea(s), se necesitan al menos 2 (encabezado + 1 fila).",
                            file.getFileName(), rows.size());
                    return 0;
                }
                List<String> headers = rows.get(0);
                for (String header : headers) {
                    validateIdentifier(header);
                }
                // Falla clara antes de tocar la base en vez de rellenar con NULL o
                // descartar campos de más en silencio (hallazgo real de /code-review) —
                // como la transacción todavía no empezó, no hay nada que revertir.
                for (int r = 1; r < rows.size(); r++) {
                    if (rows.get(r).size() != headers.size()) {
                        throw new IllegalArgumentException("La fila " + (r + 1) + " del CSV tiene "
                                + rows.get(r).size() + " campo(s), se esperaban " + headers.size()
                                + " según el encabezado.");
                    }
                }

                String columnList = String.join(", ", headers);
                String placeholders = headers.stream().map(h -> "?").collect(Collectors.joining(", "));
                String sql = "INSERT INTO " + tableName + " (" + columnList + ") VALUES (" + placeholders + ")";
                log.debug("SQL de import: {}", sql);

                int inserted = 0;
                int totalDataRows = rows.size() - 1;
                try (Connection conn = pool.getConnection(database, credentials)) {
                    conn.setAutoCommit(false);
                    try (PreparedStatement statement = conn.prepareStatement(sql)) {
                        for (int r = 1; r < rows.size(); r++) {
                            List<String> row = rows.get(r);
                            for (int c = 0; c < headers.size(); c++) {
                                String value = c < row.size() ? row.get(c) : null;
                                statement.setObject(c + 1, value == null || value.isEmpty() ? null : value);
                            }
                            statement.addBatch();
                            inserted++;
                            if (inserted % BATCH_SIZE == 0) {
                                statement.executeBatch();
                                log.debug("Import '{}' → '{}': {} de {} fila(s) enviadas.",
                                        file.getFileName(), tableName, inserted, totalDataRows);
                            }
                            updateProgress(inserted, totalDataRows);
                        }
                        statement.executeBatch();
                    }
                    conn.commit();
                }
                log.info("Import completo — {} fila(s) insertadas en '{}'.'{}'", inserted, database.alias(), tableName);
                return inserted;
            }
        };
    }

    private static void validateIdentifier(String name) {
        if (name == null || !SAFE_IDENTIFIER.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "Nombre inválido: \"" + name + "\" — solo letras, números y guion bajo, sin empezar con número.");
        }
    }
}
