package com.faro.app.model;

/**
 * Los dos motores que Faro habla — mismo concepto que
 * {@code lib/core/constants/db_engine.dart} en la versión Flutter: vive en
 * {@link DatabaseEntry}, no en {@link Server} — un servidor es solo un
 * agrupador libre, cada base de datos elige su propio motor.
 */
public enum DbEngine {
    POSTGRES("PostgreSQL", "PG", 5432),
    SQL_SERVER("SQL Server", "MSSQL", 1433);

    private final String label;
    private final String badge;
    private final int defaultPort;

    DbEngine(String label, String badge, int defaultPort) {
        this.label = label;
        this.badge = badge;
        this.defaultPort = defaultPort;
    }

    public String label() {
        return label;
    }

    /** Etiqueta corta para la fila del árbol — "PG" / "MSSQL", igual que el prototipo. */
    public String badge() {
        return badge;
    }

    public int defaultPort() {
        return defaultPort;
    }
}
