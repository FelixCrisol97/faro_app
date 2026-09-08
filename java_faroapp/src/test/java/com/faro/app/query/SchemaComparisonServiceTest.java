package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Lógica pura de {@link SchemaComparisonService} — el MD5 con el que se decide
 * si dos bodegas tienen la misma versión de un objeto. Lo demás de esa clase
 * (abrir conexiones, leer el DDL de cada motor) necesita bases reales y no se
 * testea acá, mismo criterio que el resto del proyecto.
 */
class SchemaComparisonServiceTest {

    private static final String FUNCION = "CREATE FUNCTION calcula_total()\nRETURNS int AS $$\nSELECT 1;\n$$;";

    @Test
    void elMismoScriptDaElMismoHash() {
        assertEquals(SchemaComparisonService.md5(FUNCION), SchemaComparisonService.md5(FUNCION));
    }

    /**
     * El caso que motiva toda la función: dos bodegas con versiones distintas de
     * la misma función tienen que dar hashes distintos.
     */
    @Test
    void unCambioRealCambiaElHash() {
        String otraVersion = FUNCION.replace("SELECT 1;", "SELECT 2;");

        assertNotEquals(SchemaComparisonService.md5(FUNCION), SchemaComparisonService.md5(otraVersion));
    }

    /**
     * Ruido que NO debe contar como diferencia: el mismo objeto guardado desde un
     * cliente Windows (CRLF) y uno Linux (LF). Sin esta normalización, una
     * comparación entre bodegas marcaría todo como distinto por un detalle que no
     * cambia nada de lo que la función hace.
     */
    @Test
    void losFinalesDeLineaDeWindowsNoCuentanComoDiferencia() {
        String versionWindows = FUNCION.replace("\n", "\r\n");

        assertEquals(SchemaComparisonService.md5(FUNCION), SchemaComparisonService.md5(versionWindows));
    }

    /** Mismo criterio: espacio sobrante al principio/final del script no es un cambio real. */
    @Test
    void elEspacioAlrededorNoCuentaComoDiferencia() {
        assertEquals(SchemaComparisonService.md5(FUNCION), SchemaComparisonService.md5("\n  " + FUNCION + "  \n\n"));
    }

    /**
     * En cambio, un cambio de sangría INTERNA sí cuenta — es una modificación
     * real del texto guardado en el servidor, y el usuario quiere enterarse de
     * que alguien tocó el objeto aunque el resultado sea equivalente.
     */
    @Test
    void laSangriaInternaSiCuentaComoDiferencia() {
        String reindentada = FUNCION.replace("SELECT 1;", "    SELECT 1;");

        assertNotEquals(SchemaComparisonService.md5(FUNCION), SchemaComparisonService.md5(reindentada));
    }

    @Test
    void elHashEsUnMd5HexadecimalDe32Caracteres() {
        String hash = SchemaComparisonService.md5(FUNCION);

        assertEquals(32, hash.length());
        assertEquals(hash.toLowerCase(java.util.Locale.ROOT), hash, "en minúsculas, para comparar sin sorpresas");
    }
}
