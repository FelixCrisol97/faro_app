package com.faro.app;

/**
 * Punto de entrada real del JAR empaquetado ({@code jpackage}/`java -jar`) —
 * NO {@link Main} directamente. La JVM rechaza arrancar ("JavaFX runtime
 * components are missing") cuando la clase declarada como {@code Main-Class}
 * en el manifest extiende {@code javafx.application.Application} y el
 * módulo {@code javafx.graphics} no está en el module-path — exactamente el
 * caso de este proyecto, que a propósito no es modular (ver {@code pom.xml},
 * perfil {@code package}). Esta clase, al NO extender {@code Application},
 * evita ese chequeo por completo y simplemente delega a {@link Main#main}.
 * {@code mvn javafx:run} no necesita esto — ese plugin arma su propio
 * module-path con {@code javafx.graphics} presente, así que sigue apuntando
 * a {@link Main} directo (ver la propiedad {@code mainClass} en el pom).
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Main.main(args);
    }
}
