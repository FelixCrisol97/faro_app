package com.faro.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    // ---- Codificación del cliente (2026-09-10) — ver DatabaseEntry#clientEncoding ----

    /**
     * El default vacío tiene que producir EXACTAMENTE la URL de siempre. Es lo que
     * garantiza que agregar esta opción no cambie el comportamiento de ninguna base
     * ya configurada.
     */
    @Test
    void sinCodificacionForzadaLaUrlDePostgresNoCambia() {
        DatabaseEntry entry = new DatabaseEntry(
                "crisol", "localhost", 5432, "crisol", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        assertEquals("", entry.clientEncoding());
        assertEquals("jdbc:postgresql://localhost:5432/crisol", entry.jdbcUrl());
    }

    /**
     * Los DOS parámetros, no solo uno: {@code options} le pide la codificación al
     * servidor y {@code allowEncodingChanges} evita que pgJDBC corte la conexión al
     * ver que {@code client_encoding} dejó de ser UTF8 (sin esa bandera el driver
     * aborta, así que la opción no serviría de nada).
     */
    @Test
    void laCodificacionForzadaAgregaLosDosParametrosQueHacenFalta() {
        DatabaseEntry entry = new DatabaseEntry(
                "bodega", "10.92.12.87", 5432, "bodega", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        entry.setClientEncoding("LATIN1");

        assertEquals(
                "jdbc:postgresql://10.92.12.87:5432/bodega"
                        + "?options=-c%20client_encoding%3DLATIN1&allowEncodingChanges=true",
                entry.jdbcUrl());
    }

    /** El espacio y el {@code =} van escapados — sin eso el parámetro se cortaría a la mitad dentro de la cadena de consulta de la URL. */
    @Test
    void laCodificacionVaEscapadaEnLaUrl() {
        DatabaseEntry entry = new DatabaseEntry(
                "bodega", "host", 5432, "bodega", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        entry.setClientEncoding("WIN1252");

        assertTrue(entry.jdbcUrl().contains("%20"), "el espacio de '-c client_encoding' tiene que ir escapado");
        assertTrue(entry.jdbcUrl().contains("%3D"), "el '=' de 'client_encoding=' tiene que ir escapado");
        assertFalse(entry.jdbcUrl().contains("-c client_encoding="), "no debe quedar ningún espacio literal");
    }

    /** En SQL Server la codificación no aplica — su URL no se toca, igual que la casilla del certificado no toca la de PostgreSQL. */
    @Test
    void laCodificacionNoAfectaLaUrlDeSqlServer() {
        DatabaseEntry entry = new DatabaseEntry(
                "tienda", "10.20.4.10", 1433, "tienda", DbEngine.SQL_SERVER, ServerMode.READ_ONLY);

        entry.setClientEncoding("LATIN1");

        assertEquals(
                "jdbc:sqlserver://10.20.4.10:1433;databaseName=tienda;encrypt=true;trustServerCertificate=true",
                entry.jdbcUrl());
    }

    /** Espacios de más y {@code null} caen al mismo caso "sin codificación" — el combo del diálogo puede entregar cualquiera de los dos. */
    @Test
    void codificacionEnBlancoONulaEquivaleAAutomatica() {
        DatabaseEntry entry = new DatabaseEntry(
                "db", "host", 5432, "db", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        entry.setClientEncoding("   ");
        assertEquals("jdbc:postgresql://host:5432/db", entry.jdbcUrl());

        entry.setClientEncoding(null);
        assertEquals("", entry.clientEncoding());
        assertEquals("jdbc:postgresql://host:5432/db", entry.jdbcUrl());
    }
}
