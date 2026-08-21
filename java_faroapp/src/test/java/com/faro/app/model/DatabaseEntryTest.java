package com.faro.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    @Test
    void jdbcUrlReflectsEditsLive() {
        DatabaseEntry entry = new DatabaseEntry(
                "db", "old-host", 5432, "db", DbEngine.POSTGRES, ServerMode.READ_ONLY);

        entry.setHost("new-host");
        entry.setPort(5433);

        assertEquals("jdbc:postgresql://new-host:5433/db", entry.jdbcUrl());
    }
}
