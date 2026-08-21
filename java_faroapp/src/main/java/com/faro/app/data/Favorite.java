package com.faro.app.data;

/** Un script SQL guardado desde Consulta → Guardar como favorito — nombre elegido por el usuario + el texto del script tal cual estaba en ese momento. */
public record Favorite(String id, String name, String sql) {
}
