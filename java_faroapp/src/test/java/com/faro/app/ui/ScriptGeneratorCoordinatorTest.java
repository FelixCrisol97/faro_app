package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

/**
 * La llave del candado de duplicados de {@link ScriptGeneratorCoordinator} (2026-09-15).
 *
 * <p>Es la única parte de esa clase que es lógica pura y no un adaptador: el resto arma
 * {@code Task} de JavaFX y abre pestañas, o sea que pediría arrancar el toolkit, y la
 * suite permanente no lo hace a propósito.
 *
 * <p>Vale la pena testearla porque la decisión que codifica <b>no es obvia y ya se
 * equivocó una vez</b>: dedupar por nombre de hilo en vez de por acción descartaba en
 * silencio un SELECT pedido mientras un UPDATE sobre la misma tabla seguía en curso —
 * sin pestaña y sin aviso. Un test que lo fije evita que el próximo que "simplifique"
 * la llave reintroduzca ese comportamiento.
 */
class ScriptGeneratorCoordinatorTest {

    private static SchemaTreeNode.Item item(String database, String objectName) {
        DatabaseEntry db = new DatabaseEntry(
                database, "10.0.0.1", 5432, database, DbEngine.POSTGRES, ServerMode.READ_ONLY);
        return new SchemaTreeNode.Item(db, SchemaTreeNode.Kind.TABLES, objectName, null);
    }

    /**
     * El caso que el candado sí tiene que atrapar: la misma acción sobre el mismo objeto
     * repetida antes de que la primera termine (doble clic más clic derecho, o el
     * usuario impaciente).
     */
    @Test
    void laMismaAccionSobreElMismoObjetoDaLaMismaLlave() {
        // La MISMA base, no dos equivalentes: DatabaseEntry#id es un UUID por instancia,
        // así que dos entradas con el mismo alias son dos bases distintas para el candado
        // — y está bien que lo sean.
        SchemaTreeNode.Item pedidos = item("bodega-01", "pedidos");

        assertEquals(
                ScriptGeneratorCoordinator.pendingKey("SELECT", pedidos),
                ScriptGeneratorCoordinator.pendingKey("SELECT", pedidos));
    }

    /**
     * El caso que NO debe deduparse, y el motivo de que la llave use la acción y no el
     * nombre del hilo: SELECT y UPDATE de la misma tabla comparten el hilo
     * {@code faro-script-columns}, pero son dos scripts distintos que el usuario quiere
     * ver en dos pestañas.
     */
    @Test
    void dosAccionesDistintasSobreLaMismaTablaNoSePisan() {
        SchemaTreeNode.Item pedidos = item("bodega-01", "pedidos");

        assertNotEquals(
                ScriptGeneratorCoordinator.pendingKey("SELECT", pedidos),
                ScriptGeneratorCoordinator.pendingKey("UPDATE", pedidos));
    }

    /** La misma acción sobre el mismo NOMBRE de tabla en dos bodegas distintas son dos trabajos distintos. */
    @Test
    void laMismaTablaEnDosBasesDistintasNoSePisa() {
        assertNotEquals(
                ScriptGeneratorCoordinator.pendingKey("SELECT", item("bodega-01", "pedidos")),
                ScriptGeneratorCoordinator.pendingKey("SELECT", item("bodega-02", "pedidos")));
    }

    /** Y dos objetos distintos de la misma base, tampoco. */
    @Test
    void dosObjetosDistintosDeLaMismaBaseNoSePisan() {
        assertNotEquals(
                ScriptGeneratorCoordinator.pendingKey("SELECT", item("bodega-01", "pedidos")),
                ScriptGeneratorCoordinator.pendingKey("SELECT", item("bodega-01", "clientes")));
    }
}
