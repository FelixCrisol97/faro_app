package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

import org.junit.jupiter.api.Test;

/**
 * Lógica pura de {@link QueryExecutionService} — sin JDBC ni JavaFX de por medio
 * (2026-09-10, hallazgo C8 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}: estos dos
 * métodos no tenían ningún test).
 *
 * <p>{@link QueryExecutionService#isReadOnlyStatement} es el que urgía:
 * <b>es lo ÚNICO que hace cumplir el modo Solo lectura</b>. El candado del árbol no
 * protege nada por su cuenta — si esta heurística se rompe en un refactor, una
 * bodega marcada como protegida deja de estarlo, en silencio y sin que ningún otro
 * test se entere.
 *
 * <p>Los dos son package-private a propósito para poder ejercitarlos directo, mismo
 * criterio que ya usa {@code SchemaIntrospector#sqlServerTypeWithLength}.
 */
class QueryExecutionServiceTest {

    // ---- Lo que SÍ puede correr contra una base de solo lectura ----

    @Test
    void aceptaLasPalabrasDeSoloLectura() {
        assertTrue(QueryExecutionService.isReadOnlyStatement("SELECT 1"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("SHOW ALL"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("EXPLAIN SELECT 1"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("DESCRIBE productos"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("DESC productos"));
    }

    @Test
    void noDistingueMayusculas() {
        assertTrue(QueryExecutionService.isReadOnlyStatement("select 1"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("SeLeCt 1"));
    }

    /** Espacios, saltos de línea y comentarios antes de la palabra clave no deben despistar a la heurística. */
    @Test
    void ignoraEspaciosYComentariosIniciales() {
        assertTrue(QueryExecutionService.isReadOnlyStatement("   \n\t SELECT 1"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("-- un comentario\nSELECT 1"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("/* bloque */ SELECT 1"));
        assertTrue(QueryExecutionService.isReadOnlyStatement("/* varias\n   líneas */\n-- y de línea\nSELECT 1"));
    }

    // ---- Lo que NO ----

    @Test
    void rechazaLasSentenciasQueEscriben() {
        assertFalse(QueryExecutionService.isReadOnlyStatement("DELETE FROM productos"));
        assertFalse(QueryExecutionService.isReadOnlyStatement("UPDATE productos SET precio = 1"));
        assertFalse(QueryExecutionService.isReadOnlyStatement("DROP TABLE productos"));
        assertFalse(QueryExecutionService.isReadOnlyStatement("TRUNCATE productos"));
        assertFalse(QueryExecutionService.isReadOnlyStatement("ALTER TABLE productos ADD x int"));
        assertFalse(QueryExecutionService.isReadOnlyStatement("GRANT ALL ON productos TO alguien"));
    }

    /**
     * El caso que más fácil se cuela: <b>empieza</b> con INSERT aunque contenga un
     * SELECT. La heurística mira la PRIMERA palabra, no si el texto tiene un SELECT
     * en algún lado — y tiene que seguir siendo así.
     */
    @Test
    void rechazaUnInsertQueContieneUnSelect() {
        assertFalse(QueryExecutionService.isReadOnlyStatement("INSERT INTO copia SELECT * FROM productos"));
    }

    @Test
    void rechazaTextoVacioOSinPalabraClaveReconocible() {
        assertFalse(QueryExecutionService.isReadOnlyStatement(""));
        assertFalse(QueryExecutionService.isReadOnlyStatement("   "));
        assertFalse(QueryExecutionService.isReadOnlyStatement("-- solo un comentario"));
        assertFalse(QueryExecutionService.isReadOnlyStatement("123 no empieza con letra"));
    }

    /**
     * <b>Límite conocido y aceptado, documentado a propósito.</b> El javadoc de la
     * clase dice que es una heurística por primera palabra clave, no un parser: un
     * CTE que escribe ({@code WITH ... AS (DELETE ...)}, válido en PostgreSQL) pasa el
     * filtro. Este test NO dice que esté bien — fija el comportamiento real para que
     * quede a la vista en la suite, y para que si algún día se cierra el agujero sea
     * una decisión consciente y no un cambio accidental.
     */
    @Test
    void limiteConocidoUnCteQueEscribePasaElFiltro() {
        assertTrue(QueryExecutionService.isReadOnlyStatement(
                "WITH borradas AS (DELETE FROM productos RETURNING *) SELECT * FROM borradas"),
                "límite documentado: la heurística mira la primera palabra, no el contenido del CTE");
    }

    // ---- enrichErrorMessage ----

    /**
     * El caso real que este método existe para explicar (2026-08-22): un script con
     * varias sentencias sin {@code ;} entre ellas. PostgreSQL falla nombrando la
     * primera palabra de la "siguiente" sentencia, y sin la pista el usuario no tiene
     * cómo saber que le falta un punto y coma.
     */
    @Test
    void agregaLaPistaDelPuntoYComaCuandoElErrorApuntaAUnaPalabraDeInicio() {
        String enriquecido = QueryExecutionService.enrichErrorMessage(
                DbEngine.POSTGRES, "ERROR: syntax error at or near \"SELECT\"");

        assertTrue(enriquecido.contains("punto y coma"), "debería sugerir el ';' faltante");
        assertTrue(enriquecido.startsWith("ERROR: syntax error at or near \"SELECT\""),
                "el mensaje original del motor no se pierde, la pista se suma al final");
    }

    /** Una palabra que NO empieza una sentencia (ej. un alias mal escrito) no debe disparar una sugerencia equivocada. */
    @Test
    void noAgregaLaPistaSiLaPalabraNoEmpiezaUnaSentencia() {
        String mensaje = "ERROR: syntax error at or near \"produtos\"";

        assertEquals(mensaje, QueryExecutionService.enrichErrorMessage(DbEngine.POSTGRES, mensaje));
    }

    /** SQL Server no necesita la pista — ahí el {@code ;} entre sentencias es opcional, así que sugerirlo confundiría. */
    @Test
    void noTocaLosMensajesDeSqlServer() {
        String mensaje = "Incorrect syntax near 'SELECT'.";

        assertEquals(mensaje, QueryExecutionService.enrichErrorMessage(DbEngine.SQL_SERVER, mensaje));
    }

    /** Un {@code SQLException} puede traer {@code getMessage() == null} — no debe tronar al intentar enriquecerlo. */
    @Test
    void toleraUnMensajeNulo() {
        assertEquals(null, QueryExecutionService.enrichErrorMessage(DbEngine.POSTGRES, null));
    }

    // ---- Modo cursor (2026-09-14) — ver QueryExecutionService#shouldUseCursor ----
    //
    // Estos tests fijan las DOS condiciones que hacen seguro el cambio. La primera
    // (PostgreSQL) es la que lo hace útil; la segunda (solo lectura) es la que garantiza
    // que no altere la semántica de ningún script que escriba.

    private static DatabaseEntry base(DbEngine engine) {
        return new DatabaseEntry("bodega", "10.0.0.1", engine.defaultPort(), "bodega", engine, ServerMode.UNRESTRICTED);
    }

    private static QueryExecutionService.RunPlan plan(String... statements) {
        return new QueryExecutionService.RunPlan(List.of(statements), 500, Integer.MAX_VALUE);
    }

    @Test
    void usaCursorEnPostgresConUnScriptDeSoloLectura() {
        assertTrue(QueryExecutionService.shouldUseCursor(
                base(DbEngine.POSTGRES), plan("SELECT * FROM productos")));
        assertTrue(QueryExecutionService.shouldUseCursor(
                base(DbEngine.POSTGRES), plan("SELECT 1", "SELECT 2")));
    }

    /**
     * La condición que protege la semántica: con una sola sentencia que escriba, NO se
     * toca el autocommit. Si no, el script entero pasaría a ser una transacción y un
     * fallo en la tercera sentencia desharía las dos primeras — hoy cada una se
     * confirma sola, y eso no debe cambiar.
     */
    @Test
    void noUsaCursorSiAlgunaSentenciaEscribe() {
        assertFalse(QueryExecutionService.shouldUseCursor(
                base(DbEngine.POSTGRES), plan("INSERT INTO copia SELECT * FROM productos")));
        assertFalse(QueryExecutionService.shouldUseCursor(
                base(DbEngine.POSTGRES), plan("SELECT 1", "UPDATE productos SET precio = 1")));
        assertFalse(QueryExecutionService.shouldUseCursor(
                base(DbEngine.POSTGRES), plan("DELETE FROM productos")));
    }

    /** SQL Server respeta {@code setFetchSize} sin tocar el autocommit — ahí no hay nada que arreglar. */
    @Test
    void noUsaCursorEnSqlServer() {
        assertFalse(QueryExecutionService.shouldUseCursor(
                base(DbEngine.SQL_SERVER), plan("SELECT * FROM productos")));
    }

    /** {@code RunPlan} calcula {@code allStatementsReadOnly} en su constructor — es la misma heurística de arriba, aplicada a TODAS las sentencias. */
    @Test
    void elPlanCalculaSoloLecturaSobreTodasLasSentencias() {
        assertTrue(plan("SELECT 1", "  -- nota\n SELECT 2").allStatementsReadOnly());
        assertFalse(plan("SELECT 1", "DROP TABLE t").allStatementsReadOnly());
    }

    // ------------------------------------------------------------------
    // Tope de filas en memoria (2026-09-20)
    // ------------------------------------------------------------------
    //
    // remainingCapacity es la decisión que corta la lectura del ResultSet. El bucle en sí
    // necesita una base real, pero la decisión es aritmética pura y es donde estaría el
    // error: un signo cambiado acá no recorta nada (y vuelve el OutOfMemoryError) o
    // recorta todo (y el grid sale vacío).

    @Test
    void mientrasQuepanFilasSigueLeyendo() {
        assertEquals(200_000, QueryExecutionService.remainingCapacity(0, 200_000));
        assertEquals(1, QueryExecutionService.remainingCapacity(199_999, 200_000));
    }

    /** Cero es la señal de cortar: no caben más. */
    @Test
    void alLlegarAlTopeDejaDeCaber() {
        assertEquals(0, QueryExecutionService.remainingCapacity(200_000, 200_000));
    }

    /**
     * Nunca negativo. Con varias bases en paralelo compartiendo el contador, dos hilos
     * pueden pasarse del tope entre el chequeo y el incremento; si eso devolviera un
     * número negativo, un {@code > 0} seguiría cortando bien pero cualquier uso futuro
     * como "cuántas faltan" quedaría roto. Se satura en 0 a propósito.
     */
    @Test
    void pasarseDelTopeNoDaNegativo() {
        assertEquals(0, QueryExecutionService.remainingCapacity(200_005, 200_000));
    }
}
