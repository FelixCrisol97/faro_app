package com.faro.app.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Un servidor es solo un agrupador libre y opcional de bases de datos — no
 * implica un host, motor ni credenciales compartidas (esas viven por
 * {@link DatabaseEntry}). Una base de datos también puede no pertenecer a
 * ningún servidor ("Sin grupo") — eso se modela con la ausencia de un
 * {@code Server}, no con un campo nulo aquí; ver
 * {@code com.faro.app.data.ConnectionRegistry}.
 */
public class Server {

    private final String id;
    private String name;
    private final List<DatabaseEntry> databases = new ArrayList<>();

    public Server(String name) {
        this(UUID.randomUUID().toString(), name);
    }

    public Server(String id, String name) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<DatabaseEntry> databases() {
        return databases;
    }

    @Override
    public String toString() {
        return name;
    }
}
