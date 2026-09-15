package com.faro.app.data;

import java.util.ArrayList;
import java.util.Comparator;
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

    /**
     * El grupo al que pertenece {@code entry}, o {@code null} si está suelta ("Sin
     * grupo" no es un {@link Server}, es la ausencia de uno — ver su javadoc).
     *
     * <p>Identidad por referencia, igual que {@link #removeDatabase}: {@code DatabaseEntry}
     * no define {@code equals} a propósito.
     */
    public Server groupOf(DatabaseEntry entry) {
        for (Server server : servers) {
            if (server.databases().contains(entry)) {
                return server;
            }
        }
        return null;
    }

    /**
     * Agrega {@code entries} al mismo grupo donde esté {@code reference}, o a las
     * sueltas si esa base no tiene grupo.
     *
     * <p>Existe para "Descubrir bases en esta IP…" (2026-09-11, pregunta del usuario):
     * ese escaneo arranca desde una base concreta del árbol, y hasta ahora las bases
     * encontradas caían SIEMPRE en "Sin grupo" aunque la base de origen viviera dentro
     * de un grupo — había que moverlas una por una después. Son bases del mismo
     * servidor que la de referencia, así que heredar su grupo es lo esperable.
     */
    public void addAllNextTo(DatabaseEntry reference, List<DatabaseEntry> entries) {
        Server group = groupOf(reference);
        (group == null ? ungroupedDatabases : group.databases()).addAll(entries);
    }

    // ---- Orden del árbol (2026-09-11, pedido del usuario: "tampoco puedo mover las
    // bd y grupos de acuerdo al orden que yo quiera") ----
    //
    // El orden del árbol ES el orden de estas listas: ConnectionTreeBuilder las recorre
    // tal cual y ConnectionRegistryStore las persiste tal cual. Así que reordenar acá
    // es todo lo que hace falta — no hay un campo "posición" que mantener en sincronía
    // ni un criterio de orden implícito que se pueda desalinear.

    /**
     * La lista donde vive {@code entry} — la de su grupo, o la de las sueltas.
     * {@code null} si no está en ninguna (base ya eliminada). Identidad por
     * referencia, igual que {@link #removeDatabase} y por el mismo motivo:
     * {@code DatabaseEntry} no define {@code equals}, a propósito.
     */
    private List<DatabaseEntry> listContaining(DatabaseEntry entry) {
        if (ungroupedDatabases.contains(entry)) {
            return ungroupedDatabases;
        }
        for (Server server : servers) {
            if (server.databases().contains(entry)) {
                return server.databases();
            }
        }
        return null;
    }

    /**
     * Mueve {@code entry} {@code delta} posiciones dentro de SU propia lista (su grupo,
     * o las sueltas) — no la saca de ahí; cambiar de grupo es "Mover a grupo…", otra
     * operación. {@code true} si de verdad se movió.
     *
     * <p>Devuelve {@code false} en vez de tirar una excepción cuando ya está en el
     * borde: que "Subir" en el primer elemento no haga nada es el comportamiento
     * esperado de un menú, no un error que haya que reportar.
     */
    public boolean moveDatabase(DatabaseEntry entry, int delta) {
        return move(listContaining(entry), entry, delta);
    }

    /** Mueve un grupo {@code delta} posiciones entre los grupos. Ver {@link #moveDatabase}. */
    public boolean moveServer(Server server, int delta) {
        return move(servers, server, delta);
    }

    private static <T> boolean move(List<T> list, T item, int delta) {
        if (list == null || delta == 0) {
            return false;
        }
        int from = list.indexOf(item);
        int to = from + delta;
        if (from < 0 || to < 0 || to >= list.size()) {
            return false;
        }
        list.remove(from);
        list.add(to, item);
        return true;
    }

    /** Ordena los grupos por nombre, sin distinguir mayúsculas ni acentos de por medio (comparación simple, suficiente para nombres de grupo escritos a mano). */
    public void sortServersByName() {
        servers.sort(Comparator.comparing(Server::name, String.CASE_INSENSITIVE_ORDER));
    }

    /**
     * Ordena por alias las bases de {@code server}, o las sueltas si {@code server} es
     * {@code null} — que es cómo el árbol representa "Sin grupo" (ausencia de grupo, no
     * un grupo especial; ver el javadoc de {@link Server}).
     */
    public void sortDatabasesByAlias(Server server) {
        List<DatabaseEntry> target = server == null ? ungroupedDatabases : server.databases();
        target.sort(Comparator.comparing(DatabaseEntry::alias, String.CASE_INSENSITIVE_ORDER));
    }
}
