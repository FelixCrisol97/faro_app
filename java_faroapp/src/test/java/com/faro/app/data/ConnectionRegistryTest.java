package com.faro.app.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    // ---- Grupo de una base / agregar junto a ella (2026-09-11) ----

    @Test
    void groupOfFindsTheOwningServerOrNullWhenUngrouped() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Server server = new Server("Bodegas");
        DatabaseEntry enGrupo = db("EnGrupo");
        DatabaseEntry suelta = db("Suelta");
        server.databases().add(enGrupo);
        registry.servers().add(server);
        registry.ungroupedDatabases().add(suelta);

        assertEquals(server, registry.groupOf(enGrupo));
        assertNull(registry.groupOf(suelta), "una base sin grupo devuelve null, no un grupo vacío");
        assertNull(registry.groupOf(db("nunca registrada")));
    }

    /**
     * "Descubrir bases en esta IP…" arranca desde una base concreta del árbol; las que
     * encuentra son del MISMO servidor, así que tienen que caer en el mismo grupo.
     * Antes iban siempre a "Sin grupo" y había que moverlas una por una.
     */
    @Test
    void addAllNextToPutsTheNewOnesInTheSameGroupAsTheReference() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Server server = new Server("Bodegas");
        DatabaseEntry referencia = db("Referencia");
        server.databases().add(referencia);
        registry.servers().add(server);

        registry.addAllNextTo(referencia, List.of(db("Nueva1"), db("Nueva2")));

        assertEquals(List.of("Referencia", "Nueva1", "Nueva2"), aliases(server.databases()));
        assertTrue(registry.ungroupedDatabases().isEmpty(), "no deben caer en 'Sin grupo'");
    }

    /** Si la base de referencia está suelta, las nuevas también — no se inventa un grupo. */
    @Test
    void addAllNextToKeepsThemUngroupedWhenTheReferenceIsUngrouped() {
        ConnectionRegistry registry = new ConnectionRegistry();
        DatabaseEntry referencia = db("Referencia");
        registry.ungroupedDatabases().add(referencia);
        registry.servers().add(new Server("Otro grupo"));

        registry.addAllNextTo(referencia, List.of(db("Nueva")));

        assertEquals(List.of("Referencia", "Nueva"), aliases(registry.ungroupedDatabases()));
        assertTrue(registry.servers().get(0).databases().isEmpty());
    }

    // ---- Orden del árbol (2026-09-11) ----
    //
    // El orden de estas listas ES el orden que se ve en el árbol y el que se guarda en
    // connections.json, así que estos tests cubren la funcionalidad completa de
    // "Subir/Bajar/Ordenar A-Z" sin necesitar JavaFX.

    private static DatabaseEntry db(String alias) {
        return new DatabaseEntry(alias, "10.0.0.1", 5432, "bodega", DbEngine.POSTGRES, ServerMode.READ_ONLY);
    }

    private static List<String> aliases(List<DatabaseEntry> databases) {
        return databases.stream().map(DatabaseEntry::alias).toList();
    }

    @Test
    void moveDatabaseReordersWithinItsOwnGroup() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Server server = new Server("Bodegas");
        DatabaseEntry a = db("A");
        DatabaseEntry b = db("B");
        DatabaseEntry c = db("C");
        server.databases().addAll(List.of(a, b, c));
        registry.servers().add(server);

        assertTrue(registry.moveDatabase(c, -1));
        assertEquals(List.of("A", "C", "B"), aliases(server.databases()));

        assertTrue(registry.moveDatabase(c, -1));
        assertEquals(List.of("C", "A", "B"), aliases(server.databases()));

        assertTrue(registry.moveDatabase(c, 2));
        assertEquals(List.of("A", "B", "C"), aliases(server.databases()));
    }

    /** En el borde no pasa nada y se devuelve {@code false} — que "Subir" en el primero no haga nada es lo esperado de un menú, no un error. */
    @Test
    void moveDatabaseAtTheEdgeDoesNothing() {
        ConnectionRegistry registry = new ConnectionRegistry();
        DatabaseEntry a = db("A");
        DatabaseEntry b = db("B");
        registry.ungroupedDatabases().addAll(List.of(a, b));

        assertFalse(registry.moveDatabase(a, -1));
        assertFalse(registry.moveDatabase(b, 1));
        assertEquals(List.of("A", "B"), aliases(registry.ungroupedDatabases()));
    }

    /** Una base que ya no está en el registro no debe tronar ni tocar nada. */
    @Test
    void moveDatabaseThatIsNotRegisteredIsANoOp() {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(db("A"));

        assertFalse(registry.moveDatabase(db("fantasma"), -1));
        assertEquals(List.of("A"), aliases(registry.ungroupedDatabases()));
    }

    /**
     * Mover una base NO la saca de su grupo — cambiar de grupo es "Mover a grupo…",
     * otra operación. Este test lo fija: con dos listas en juego, un {@code indexOf}
     * sobre la lista equivocada habría movido la base de contenedor sin querer.
     */
    @Test
    void moveDatabaseNeverMovesItBetweenLists() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Server server = new Server("Bodegas");
        DatabaseEntry enGrupo = db("EnGrupo");
        server.databases().addAll(List.of(enGrupo, db("Otra")));
        registry.servers().add(server);
        registry.ungroupedDatabases().add(db("Suelta"));

        registry.moveDatabase(enGrupo, 1);

        assertEquals(2, server.databases().size());
        assertEquals(1, registry.ungroupedDatabases().size());
        assertEquals(List.of("Otra", "EnGrupo"), aliases(server.databases()));
    }

    @Test
    void moveServerReordersTheGroups() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Server uno = new Server("Uno");
        Server dos = new Server("Dos");
        Server tres = new Server("Tres");
        registry.servers().addAll(List.of(uno, dos, tres));

        assertTrue(registry.moveServer(tres, -2));

        assertEquals(List.of("Tres", "Uno", "Dos"), registry.servers().stream().map(Server::name).toList());
    }

    @Test
    void sortServersByNameIgnoresCase() {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.servers().addAll(List.of(new Server("zeta"), new Server("Alfa"), new Server("beta")));

        registry.sortServersByName();

        assertEquals(List.of("Alfa", "beta", "zeta"), registry.servers().stream().map(Server::name).toList());
    }

    @Test
    void sortDatabasesByAliasHandlesBothAGroupAndTheUngroupedOnes() {
        ConnectionRegistry registry = new ConnectionRegistry();
        Server server = new Server("Bodegas");
        server.databases().addAll(List.of(db("zeta"), db("Alfa"), db("beta")));
        registry.servers().add(server);
        registry.ungroupedDatabases().addAll(List.of(db("Ypsilon"), db("alpha")));

        registry.sortDatabasesByAlias(server);
        // null = las sueltas, que el árbol muestra como "Sin grupo" — no son un grupo
        // real, ver el javadoc de Server.
        registry.sortDatabasesByAlias(null);

        assertEquals(List.of("Alfa", "beta", "zeta"), aliases(server.databases()));
        assertEquals(List.of("alpha", "Ypsilon"), aliases(registry.ungroupedDatabases()));
    }
}
