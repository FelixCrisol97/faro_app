package com.faro.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DatabaseEntryTest {

    @Test
    void jdbcUrlForPostgres() {
        DatabaseEntry entry = new DatabaseEntry(
                "crisol", "localhost", 5432, "crisol", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        assertEquals("jdbc:postgresql://localhost:5432/crisol", entry.jdbcUrl());
    }

    @Test
    void jdbcUrlForSqlServer() {
        DatabaseEntry entry = new DatabaseEntry(
                "tienda", "10.20.4.10", 1433, "tienda", DbEngine.SQL_SERVER, ServerMode.UNRESTRICTED);

        assertEquals(
                "jdbc:sqlserver://10.20.4.10:1433;databaseName=tienda;encrypt=true;trustServerCertificate=true",
                entry.jdbcUrl());
    }

    /**
     * El default tiene que seguir siendo {@code true} — es el comportamiento que
     * tenían TODAS las conexiones antes de que la casilla existiera (2026-09-07,
     * hallazgo #12 de {@code AUDITORIA_BUGS_RENDIMIENTO.md}); cambiarlo rompería
     * de golpe cualquier base contra un servidor con certificado autofirmado.
     */
    @Test
    void confiarEnElCertificadoVienePrendidoPorDefecto() {
        DatabaseEntry entry = new DatabaseEntry(
                "tienda", "10.20.4.10", 1433, "tienda", DbEngine.SQL_SERVER, ServerMode.UNRESTRICTED);

        assertTrue(entry.trustServerCertificate());
        assertTrue(entry.jdbcUrl().contains("trustServerCertificate=true"));
    }

    @Test
    void desmarcarLaCasillaExigeCertificadoValidoEnLaUrl() {
        DatabaseEntry entry = new DatabaseEntry(
                "tienda", "10.20.4.10", 1433, "tienda", DbEngine.SQL_SERVER, ServerMode.UNRESTRICTED);

        entry.setTrustServerCertificate(false);

        assertEquals(
                "jdbc:sqlserver://10.20.4.10:1433;databaseName=tienda;encrypt=true;trustServerCertificate=false",
                entry.jdbcUrl());
    }

    /** El cifrado NO es opcional — desmarcar la casilla aprieta la validación del certificado, nunca apaga {@code encrypt=true}. */
    @Test
    void elTraficoSigueCifradoConLaCasillaDesmarcada() {
        DatabaseEntry entry = new DatabaseEntry(
                "tienda", "10.20.4.10", 1433, "tienda", DbEngine.SQL_SERVER, ServerMode.UNRESTRICTED);

        entry.setTrustServerCertificate(false);

        assertTrue(entry.jdbcUrl().contains("encrypt=true"));
    }

    /** En PostgreSQL la casilla no toca la URL — ver el javadoc de {@code DatabaseEntry#jdbcUrl()}. */
    @Test
    void laCasillaNoAfectaLaUrlDePostgres() {
        DatabaseEntry entry = new DatabaseEntry(
                "crisol", "localhost", 5432, "crisol", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        entry.setTrustServerCertificate(false);

        assertEquals("jdbc:postgresql://localhost:5432/crisol", entry.jdbcUrl());
    }

    @Test
    void jdbcUrlReflectsEditsLive() {
        DatabaseEntry entry = new DatabaseEntry(
                "db", "old-host", 5432, "db", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        entry.setHost("new-host");
        entry.setPort(5433);

        assertEquals("jdbc:postgresql://new-host:5433/db", entry.jdbcUrl());
    }
}
