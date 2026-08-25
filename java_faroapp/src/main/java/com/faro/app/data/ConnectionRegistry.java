package com.faro.app.data;

import java.util.ArrayList;
import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;

/**
 * Fuente de servidores/bases de datos para el árbol de conexiones.
 *
 * <p>Sin datos de ejemplo — a pedido explícito del usuario (2026-08-22, "me
 * caga"), un primer arranque (sin archivo guardado todavía) empieza
 * completamente vacío. Antes traía un dataset ficticio (Bodegas Centro,
 * Sucursales Muebles TX, "crisol" sin grupo, el mismo de
 * {@code demo_html/data.js}) que se quitó por completo. Guardar/cargar de
 * disco (el equivalente a {@code servers_repository.dart} en la versión
 * Flutter) sigue igual, ver {@link ConnectionRegistryStore}.
 */
public class ConnectionRegistry {

    private final List<Server> servers = new ArrayList<>();
    private final List<DatabaseEntry> ungroupedDatabases = new ArrayList<>();

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

    /**
     * Quita una base de donde esté — agrupada bajo un servidor, o suelta —
     * sin que el llamador tenga que saber cuál de los dos casos es. Usado
     * por "Eliminar" en el árbol de conexiones (antes no existía ninguna
     * forma de borrar una base ya agregada, solo editarla — hallazgo real
     * del usuario). {@code DatabaseEntry} no tiene {@code equals}/
     * {@code hashCode} propios (identidad por referencia, a propósito, ver
     * su javadoc), así que esto solo quita exactamente el objeto que se le
     * pasó — nunca por coincidencia de datos. No falla si ya no está.
     */
    public void removeDatabase(DatabaseEntry entry) {
        ungroupedDatabases.remove(entry);
        for (Server server : servers) {
            server.databases().remove(entry);
        }
    }
}
