package com.faro.app.data;

import java.util.ArrayList;
import java.util.List;

/**
 * Scripts SQL guardados como favoritos — respaldo del panel "Favoritos"
 * del riel izquierdo (Ver → Favoritos, Alt+3) y de "Consulta → Guardar
 * como favorito". Se persiste junto con {@link ConnectionRegistry} en
 * {@link ConnectionRegistryStore} (mismo archivo {@code connections.json}
 * — no tiene nada sensible que proteger, a diferencia de las
 * credenciales, así que no necesita un archivo cifrado aparte).
 */
public final class FavoritesStore {

    private final List<Favorite> favorites = new ArrayList<>();

    public List<Favorite> all() {
        return List.copyOf(favorites);
    }

    public void add(Favorite favorite) {
        favorites.add(favorite);
    }

    public void remove(String id) {
        favorites.removeIf(f -> f.id().equals(id));
    }

    /** Usado al cargar desde disco — reemplaza todo el contenido actual. */
    public void replaceAll(List<Favorite> loaded) {
        favorites.clear();
        favorites.addAll(loaded);
    }
}
