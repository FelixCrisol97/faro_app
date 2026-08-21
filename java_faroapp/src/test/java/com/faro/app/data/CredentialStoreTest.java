package com.faro.app.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

class CredentialStoreTest {

    @Test
    void resolveIsEmptyWithoutOverrideOrDefault() {
        CredentialStore store = new CredentialStore();

        assertTrue(store.resolve("db1").isEmpty());
    }

    @Test
    void resolveFallsBackToDefaultWithoutOverride() {
        CredentialStore store = new CredentialStore();
        store.setDefault("defaultUser", "defaultPass");

        Optional<CredentialStore.Credentials> resolved = store.resolve("db1");

        assertEquals("defaultUser", resolved.orElseThrow().user());
    }

    @Test
    void resolvePrefersOverrideOverDefault() {
        CredentialStore store = new CredentialStore();
        store.setDefault("defaultUser", "defaultPass");
        store.put("db1", "ownUser", "ownPass");

        Optional<CredentialStore.Credentials> resolved = store.resolve("db1");

        assertEquals("ownUser", resolved.orElseThrow().user());
    }

    @Test
    void removeFallsBackToDefaultAgain() {
        CredentialStore store = new CredentialStore();
        store.setDefault("defaultUser", "defaultPass");
        store.put("db1", "ownUser", "ownPass");

        store.remove("db1");

        assertEquals("defaultUser", store.resolve("db1").orElseThrow().user());
    }
}
