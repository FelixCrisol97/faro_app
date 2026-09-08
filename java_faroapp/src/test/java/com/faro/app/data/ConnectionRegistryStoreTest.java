package com.faro.app.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ida y vuelta de {@link ConnectionRegistryStore} para el campo
 * {@code trustServerCertificate} (2026-09-07, hallazgo #12 de
 * {@code AUDITORIA_BUGS_RENDIMIENTO.md}) — con foco en el caso que de verdad
 * importa: que un {@code connections.json} guardado ANTES de que este campo
 * existiera siga comportándose exactamente igual al abrirlo con esta versión.
 */
class ConnectionRegistryStoreTest {

    @TempDir
    Path tempDir;

    private static DatabaseEntry sqlServerEntry() {
        return new DatabaseEntry(
                "id-1", "Bodega Centro", "10.20.4.10", 1433, "bodega",
                DbEngine.SQL_SERVER, ServerMode.READ_ONLY);
    }

    private static ConnectionRegistryStore.LoadResult saveAndLoad(ConnectionRegistry registry, Path file)
            throws IOException {
        ConnectionRegistryStore.save(registry, new AppPreferences(), new FavoritesStore(), List.of(), file);
        return ConnectionRegistryStore.load(file, new AppPreferences(), new FavoritesStore());
    }

    @Test
    void laCasillaDesmarcadaSobreviveGuardarYCargar() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        DatabaseEntry entry = sqlServerEntry();
        entry.setTrustServerCertificate(false);
        registry.ungroupedDatabases().add(entry);

        ConnectionRegistryStore.LoadResult loaded = saveAndLoad(registry, tempDir.resolve("connections.json"));

        DatabaseEntry restored = loaded.registry().ungroupedDatabases().get(0);
        assertFalse(restored.trustServerCertificate(), "desmarcada tiene que seguir desmarcada al reabrir la app");
        assertTrue(restored.jdbcUrl().contains("trustServerCertificate=false"));
    }

    @Test
    void laCasillaMarcadaSobreviveGuardarYCargar() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(sqlServerEntry());

        ConnectionRegistryStore.LoadResult loaded = saveAndLoad(registry, tempDir.resolve("connections.json"));

        assertTrue(loaded.registry().ungroupedDatabases().get(0).trustServerCertificate());
    }

    /**
     * El caso de compatibilidad real: un archivo escrito por una versión anterior
     * no trae la llave {@code trustServerCertificate}. Tiene que caer al default
     * {@code true} — o sea, exactamente el comportamiento que ese archivo tenía
     * cuando se guardó, no un apagón silencioso que rompa la conexión.
     */
    @Test
    void unArchivoViejoSinLaLlaveCaeAConfiarComoAntes() throws IOException {
        Path file = tempDir.resolve("connections-viejo.json");
        Files.writeString(file, """
                {
                  "preferences": {},
                  "favorites": [],
                  "servers": [],
                  "ungroupedDatabases": [
                    {
                      "id": "id-1", "alias": "Bodega Centro", "host": "10.20.4.10", "port": 1433,
                      "databaseName": "bodega", "engine": "SQL_SERVER", "mode": "READ_ONLY",
                      "poolSize": 4, "queryTimeoutSeconds": 30
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        ConnectionRegistryStore.LoadResult loaded =
                ConnectionRegistryStore.load(file, new AppPreferences(), new FavoritesStore());

        DatabaseEntry restored = loaded.registry().ungroupedDatabases().get(0);
        assertTrue(restored.trustServerCertificate(), "un archivo de antes de este campo no debe cambiar de conducta");
        assertEquals(
                "jdbc:sqlserver://10.20.4.10:1433;databaseName=bodega;encrypt=true;trustServerCertificate=true",
                restored.jdbcUrl());
    }
}
