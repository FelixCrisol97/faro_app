package com.faro.app.data;

import java.util.ArrayList;
import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;

/**
 * Fuente de servidores/bases de datos para el árbol de conexiones.
 *
 * <p>{@link #withDemoData()} arma el mismo dataset ficticio que
 * {@code demo_html/data.js} (Bodegas Centro, Sucursales Muebles TX,
 * "crisol" sin grupo) — se usa como datos de arranque solo la primera vez
 * que corre la app (antes de que exista un archivo guardado). Guardar/
 * cargar de disco (el equivalente a {@code servers_repository.dart} en la
 * versión Flutter) ya existe, ver {@link ConnectionRegistryStore}.
 */
public class ConnectionRegistry {

    private final List<Server> servers = new ArrayList<>();
    private final List<DatabaseEntry> ungroupedDatabases = new ArrayList<>();

    public static ConnectionRegistry withDemoData() {
        ConnectionRegistry registry = new ConnectionRegistry();

        Server bodegas = new Server("Bodegas Centro");
        bodegas.databases().add(new DatabaseEntry(
                "Bodega Norte", "192.168.1.10", 5432, "bodega",
                DbEngine.POSTGRES, ServerMode.READ_ONLY));
        bodegas.databases().add(new DatabaseEntry(
                "Bodega Sur", "192.168.1.11", 5432, "bodega",
                DbEngine.POSTGRES, ServerMode.READ_ONLY));
        registry.servers.add(bodegas);

        Server sucursales = new Server("Sucursales Muebles TX");
        sucursales.databases().add(new DatabaseEntry(
                "Tienda Reforma", "10.20.4.10", 1433, "tienda",
                DbEngine.SQL_SERVER, ServerMode.READ_ONLY));
        sucursales.databases().add(new DatabaseEntry(
                "Tienda Polanco", "10.20.4.11", 1433, "tienda",
                DbEngine.SQL_SERVER, ServerMode.UNRESTRICTED));
        registry.servers.add(sucursales);

        registry.ungroupedDatabases.add(new DatabaseEntry(
                "crisol", "localhost", 5432, "crisol",
                DbEngine.POSTGRES, ServerMode.READ_ONLY));

        return registry;
    }

    public List<Server> servers() {
        return servers;
    }

    public List<DatabaseEntry> ungroupedDatabases() {
        return ungroupedDatabases;
    }

    /** Todas las bases, de todos los servidores más las sin grupo — para diálogos que necesitan elegir "cualquier base" (ej. Importar CSV, Probar todas las conexiones). */
    public List<DatabaseEntry> allDatabases() {
        List<DatabaseEntry> all = new ArrayList<>(ungroupedDatabases);
        for (Server server : servers) {
            all.addAll(server.databases());
        }
        return all;
    }
}
