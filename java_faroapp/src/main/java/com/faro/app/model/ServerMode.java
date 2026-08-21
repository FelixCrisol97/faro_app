package com.faro.app.model;

/**
 * Modo de ejecución de una base de datos — mismo concepto que
 * {@code ServerMode} en Flutter: vive por {@link DatabaseEntry}, no por
 * {@link Server}, para que un mismo grupo pueda tener bases protegidas y
 * bases sin restricciones a la vez.
 */
public enum ServerMode {
    READ_ONLY("Solo lectura"),
    UNRESTRICTED("Sin restricciones");

    private final String label;

    ServerMode(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
