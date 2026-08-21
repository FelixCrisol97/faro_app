package com.faro.app.query;

import com.faro.app.model.DbEngine;

/** Una base de datos encontrada al escanear un host — ver {@link DiscoveryService}. */
public record DiscoveredDatabase(DbEngine engine, String databaseName) {
}
