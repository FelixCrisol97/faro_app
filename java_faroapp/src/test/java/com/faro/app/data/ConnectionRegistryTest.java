package com.faro.app.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;

import org.junit.jupiter.api.Test;

class ConnectionRegistryTest {

    @Test
    void allDatabasesFlattensServersAndUngrouped() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Server server = new Server("Bodegas");
        server.databases().add(new DatabaseEntry(
                "Bodega Norte", "10.0.0.1", 5432, "bodega", DbEngine.POSTGRES, ServerMode.READ_ONLY));
        server.databases().add(new DatabaseEntry(
                "Bodega Sur", "10.0.0.2", 5432, "bodega", DbEngine.POSTGRES, ServerMode.READ_ONLY));
        registry.servers().add(server);
        registry.ungroupedDatabases().add(new DatabaseEntry(
                "crisol", "localhost", 5432, "crisol", DbEngine.POSTGRES, ServerMode.READ_ONLY));

        List<DatabaseEntry> all = registry.allDatabases();

        assertEquals(3, all.size());
        assertTrue(all.stream().anyMatch(db -> db.alias().equals("Bodega Norte")));
        assertTrue(all.stream().anyMatch(db -> db.alias().equals("Bodega Sur")));
        assertTrue(all.stream().anyMatch(db -> db.alias().equals("crisol")));
    }
}
