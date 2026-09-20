package com.faro.app.query;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;

import javafx.concurrent.Task;

/**
 * Exporta a CSV leyendo <b>directo de la base y escribiendo directo a disco</b>, sin que
 * el resultado completo llegue a existir en memoria (2026-09-20).
 *
 * <h2>Qué problema resuelve</h2>
 *
 * El camino de siempre es: correr → ver en Resultados → Exportar, y "Exportar" lee de lo
 * que el grid tiene cargado. Eso ata el tamaño máximo exportable a lo que quepa en el
 * heap, que es el techo estructural que documentaba {@code OPTIMIZACION_RENDIMIENTO.md}
 * §5.1 y la causa del {@code OutOfMemoryError} real con 6 bodegas × 500.000 filas.
 *
 * <p>Acá no hay lista intermedia: cada fila se lee del {@code ResultSet}, se escribe al
 * archivo y se descarta. La memoria queda <b>plana</b> sin importar si son mil filas o
 * treinta millones. Por eso también es lo que hace seguro el tope de filas en pantalla
 * ({@code AppPreferences#maxDisplayRows}): el grid puede recortar porque exportar ya no
 * depende de él.
 *
 * <h2>Decisiones que no son obvias</h2>
 *
 * <p><b>En paralelo, no una base tras otra.</b> Secuencial sería más simple y la memoria
 * igual de plana, pero contra 6 bodegas multiplicaría por 6 el tiempo de una exportación
 * que ya es larga. Cada base corre en su hilo, con el mismo tope de concurrencia que una
 * corrida normal.
 *
 * <p><b>Un solo archivo, escrito por lotes bajo candado.</b> Varios hilos escribiendo el
 * mismo {@code Writer} se corromperían entre sí. En vez de tomar el candado por fila —que
 * con millones de filas sería la operación más cara de todo el proceso— cada hilo arma un
 * lote de {@link #ROWS_PER_BATCH} filas en su propio buffer y solo entonces lo vuelca. El
 * costo en memoria sigue acotado: un lote por hilo, no un resultado por hilo.
 *
 * <p><b>El encabezado lo escribe el primer hilo que llegue</b>, dentro del mismo candado
 * y antes de su lote. No se puede escribir antes de empezar porque los nombres de columna
 * salen de la metadata del {@code ResultSet}, que no existe hasta ejecutar. Cualquier
 * base sirve: es el mismo SQL para todas — mismo criterio que ya usa
 * {@code QueryExecutionService} al quedarse con las columnas de la primera que responde.
 *
 * <p><b>El orden de las filas entre bases no es predecible</b>, igual que en una corrida
 * normal desde que se paralelizó. La columna "Base de datos" dice de dónde vino cada una.
 */
public final class CsvExportService {

    private static final Logger log = LoggerFactory.getLogger(CsvExportService.class);

    /**
     * Filas que junta cada hilo antes de pedir el candado del archivo.
     *
     * <p>512 es el equilibrio entre las dos cosas que se pagan: tomar el candado (caro si
     * fuera por fila, con millones de filas) y sostener el lote en memoria (trivial acá —
     * 512 filas × unos pocos hilos). No se afinó midiendo: cualquier valor de este orden
     * hace que el candado deje de importar, y subirlo más solo agranda el buffer.
     */
    private static final int ROWS_PER_BATCH = 512;

    /** Lo que terminó pasando, para poder avisarlo con números reales. */
    public record ExportSummary(long rowsWritten, List<String> errors) {
    }

    private CsvExportService() {
    }

    /**
     * @param databases las bases marcadas — se consultan todas, como en una corrida normal
     * @param sql       el MISMO script que produjo el resultado que se está viendo. Se
     *                  vuelve a ejecutar: el archivo refleja la base <b>ahora</b>, que
     *                  puede diferir de lo que quedó en pantalla si algo cambió en medio
     * @param file      destino; se sobrescribe
     */
    public static Task<ExportSummary> export(
            List<DatabaseEntry> databases, CredentialStore credentials, ConnectionPoolManager pool,
            String sql, int maxConcurrentDatabases, int fetchSize, Path file) {
        return new Task<>() {
            @Override
            protected ExportSummary call() throws IOException, InterruptedException {
                long startedAt = System.currentTimeMillis();
                List<String> statements = SqlStatementSplitter.split(sql);
                List<String> errors = Collections.synchronizedList(new ArrayList<>());
                AtomicLong rowsWritten = new AtomicLong();
                AtomicBoolean headerWritten = new AtomicBoolean();

                int poolSize = Math.min(Math.max(1, databases.size()), Math.max(1, maxConcurrentDatabases));
                log.info("Exportando a {} desde {} base(s), concurrencia={}", file, databases.size(), poolSize);

                try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    ExecutorService executor = Executors.newFixedThreadPool(poolSize);
                    try {
                        List<Callable<Void>> jobs = databases.stream()
                                .<Callable<Void>>map(db -> () -> {
                                    exportOne(db, credentials, pool, statements, fetchSize,
                                            writer, headerWritten, rowsWritten, errors, this::isCancelled);
                                    return null;
                                })
                                .toList();
                        executor.invokeAll(jobs);
                    } finally {
                        executor.shutdown();
                    }
                }

                long elapsed = System.currentTimeMillis() - startedAt;
                log.info("Exportación completa en {} ms — {} fila(s), {} error(es)",
                        elapsed, rowsWritten.get(), errors.size());
                return new ExportSummary(rowsWritten.get(), new ArrayList<>(errors));
            }
        };
    }

    /**
     * Una base: ejecuta y vuelca sus filas por lotes.
     *
     * <p>Un fallo acá se registra y <b>no aborta a las demás</b> — mismo criterio que una
     * corrida normal: es preferible un archivo con 5 de 6 bodegas y un aviso claro, que
     * ningún archivo.
     */
    private static void exportOne(
            DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool,
            List<String> statements, int fetchSize,
            BufferedWriter writer, AtomicBoolean headerWritten, AtomicLong rowsWritten,
            List<String> errors, Cancellation cancelled) {

        Optional<CredentialStore.Credentials> creds = credentials.resolve(db.id());
        if (creds.isEmpty()) {
            errors.add(db.alias() + ": sin usuario/contraseña guardados");
            return;
        }

        StringBuilder batch = new StringBuilder(64 * 1024);
        long written = 0;
        try (Connection conn = pool.getConnection(db, creds.get())) {
            // Cursor real en PostgreSQL: exportar es por definición de solo lectura, así
            // que aplica siempre acá — al revés que en una corrida, donde depende del
            // script. Ver QueryExecutionService#shouldUseCursor para por qué hace falta
            // desactivar el autocommit.
            boolean cursor = db.engine() == DbEngine.POSTGRES;
            if (cursor) {
                conn.setAutoCommit(false);
            }
            try {
                // SOLO se exporta el resultado de la ÚLTIMA sentencia que devuelva uno —
                // el mismo criterio con el que QueryExecutionService decide qué mostrar en
                // el grid. Si se exportaran todas concatenadas, un script con dos SELECT
                // de columnas distintas produciría un CSV corrupto: filas de dos formas
                // bajo un solo encabezado. Y aunque coincidieran, el archivo no sería lo
                // que el usuario vio en pantalla.
                //
                // Por eso un Statement POR sentencia y no uno reusado: ejecutar sobre el
                // mismo Statement invalida el ResultSet anterior, así que no habría forma
                // de llegar al final sabiendo cuál era el último sin haberlo leído ya.
                // Cada ResultSet es un cursor, no un buffer — tenerlos abiertos un momento
                // no carga filas en memoria.
                Statement lastStatement = null;
                ResultSet lastResultSet = null;
                try {
                    for (String statement : statements) {
                        Statement jdbcStatement = conn.createStatement(
                                ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                        jdbcStatement.setQueryTimeout(db.queryTimeoutSeconds());
                        jdbcStatement.setFetchSize(fetchSize);
                        if (jdbcStatement.execute(statement)) {
                            closeQuietly(lastResultSet, lastStatement);
                            lastResultSet = jdbcStatement.getResultSet();
                            lastStatement = jdbcStatement;
                        } else {
                            jdbcStatement.close();
                        }
                    }
                    if (lastResultSet != null) {
                        written = streamResultSet(lastResultSet, db, writer, batch, headerWritten, cancelled);
                    }
                } finally {
                    closeQuietly(lastResultSet, lastStatement);
                }
            } finally {
                if (cursor) {
                    conn.rollback();
                    conn.setAutoCommit(true);
                }
            }
            rowsWritten.addAndGet(written);
            log.info("[{}] Exportadas {} fila(s).", db.alias(), written);
        } catch (SQLException | IOException | RuntimeException e) {
            log.warn("[{}] Falló la exportación", db.alias(), e);
            errors.add(db.alias() + ": " + e.getMessage());
        }
    }

    /** Cierra el par cursor/sentencia sin tapar el error original si algo ya venía fallando. */
    private static void closeQuietly(ResultSet rs, Statement statement) {
        try {
            if (rs != null) {
                rs.close();
            }
        } catch (SQLException e) {
            log.debug("No se pudo cerrar el ResultSet de la exportación", e);
        }
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (SQLException e) {
            log.debug("No se pudo cerrar el Statement de la exportación", e);
        }
    }

    /**
     * El bucle que de verdad mantiene la memoria plana: leer una fila, escribirla al
     * buffer del lote, soltarla. Nunca hay una lista con el resultado.
     *
     * @return cuántas filas se escribieron
     */
    private static long streamResultSet(
            ResultSet rs, DatabaseEntry db, BufferedWriter writer, StringBuilder batch,
            AtomicBoolean headerWritten, Cancellation cancelled) throws SQLException, IOException {

        ResultSetMetaData meta = rs.getMetaData();
        int columnCount = meta.getColumnCount();
        Object[] header = new Object[columnCount + 1];
        header[0] = "Base de datos";
        for (int i = 1; i <= columnCount; i++) {
            header[i] = meta.getColumnLabel(i);
        }

        Object[] row = new Object[columnCount + 1];
        row[0] = db.alias();
        long written = 0;
        int inBatch = 0;
        while (rs.next()) {
            if (cancelled.isCancelled()) {
                log.info("[{}] Exportación cancelada por el usuario tras {} fila(s).", db.alias(), written);
                break;
            }
            for (int i = 1; i <= columnCount; i++) {
                row[i] = rs.getObject(i);
            }
            CsvWriter.appendRow(batch, row);
            written++;
            if (++inBatch >= ROWS_PER_BATCH) {
                flush(writer, batch, header, headerWritten);
                inBatch = 0;
            }
        }
        flush(writer, batch, header, headerWritten);
        return written;
    }

    /**
     * Vuelca el lote al archivo. El encabezado va dentro del MISMO bloque sincronizado que
     * el primer lote que se escriba: si se escribiera aparte, otro hilo podría colar sus
     * filas entre medio y el archivo quedaría con datos antes del encabezado.
     */
    private static void flush(
            BufferedWriter writer, StringBuilder batch, Object[] header, AtomicBoolean headerWritten)
            throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        synchronized (writer) {
            if (headerWritten.compareAndSet(false, true)) {
                StringBuilder headerLine = new StringBuilder(256);
                CsvWriter.appendRow(headerLine, header);
                writer.write(headerLine.toString());
            }
            writer.write(batch.toString());
        }
        batch.setLength(0);
    }

    /** Para poder preguntarle al {@link Task} si lo cancelaron, sin acoplar el bucle a JavaFX. */
    @FunctionalInterface
    private interface Cancellation {
        boolean isCancelled();
    }
}
