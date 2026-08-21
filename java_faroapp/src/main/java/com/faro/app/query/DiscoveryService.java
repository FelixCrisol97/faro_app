package com.faro.app.query;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import com.faro.app.model.DbEngine;

import javafx.concurrent.Task;

/**
 * Escanea un solo host (no un rango de IPs — "Descubrir bases en esta IP…"
 * es singular, ver menú Conexiones) por bases PostgreSQL/SQL Server
 * accesibles: primero un intento de conexión TCP corto a cada puerto
 * conocido (5432/1433) para no perder tiempo con hosts que ni siquiera
 * tienen el puerto abierto, y solo si responde, un intento real de login
 * JDBC contra la base de mantenimiento de cada motor
 * (`postgres`/`master`) para listar las bases reales que el usuario dado
 * puede ver. Si el login falla (credenciales incorrectas, sin permiso) esa
 * base del escaneo simplemente no aporta resultados — no se considera un
 * error fatal para el resto del escaneo.
 */
public final class DiscoveryService {

    private static final int CONNECT_TIMEOUT_MILLIS = 800;

    private DiscoveryService() {
    }

    public static Task<List<DiscoveredDatabase>> discover(String host, String user, String password) {
        return new Task<>() {
            @Override
            protected List<DiscoveredDatabase> call() {
                List<DiscoveredDatabase> found = new ArrayList<>();
                if (isPortOpen(host, DbEngine.POSTGRES.defaultPort())) {
                    found.addAll(listPostgresDatabases(host, user, password));
                }
                if (isPortOpen(host, DbEngine.SQL_SERVER.defaultPort())) {
                    found.addAll(listSqlServerDatabases(host, user, password));
                }
                return found;
            }
        };
    }

    private static boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static List<DiscoveredDatabase> listPostgresDatabases(String host, String user, String password) {
        String url = "jdbc:postgresql://" + host + ":" + DbEngine.POSTGRES.defaultPort() + "/postgres";
        List<DiscoveredDatabase> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT datname FROM pg_database WHERE datistemplate = false")) {
            while (rs.next()) {
                result.add(new DiscoveredDatabase(DbEngine.POSTGRES, rs.getString(1)));
            }
        } catch (SQLException e) {
            // Puerto abierto pero login/permiso falló — no es fatal para el resto del escaneo.
        }
        return result;
    }

    private static List<DiscoveredDatabase> listSqlServerDatabases(String host, String user, String password) {
        String url = "jdbc:sqlserver://" + host + ":" + DbEngine.SQL_SERVER.defaultPort()
                + ";databaseName=master;encrypt=true;trustServerCertificate=true";
        List<DiscoveredDatabase> result = new ArrayList<>();
        try (Connection conn = DriverManager.getConnection(url, user, password);
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery("SELECT name FROM sys.databases WHERE database_id > 4")) {
            while (rs.next()) {
                result.add(new DiscoveredDatabase(DbEngine.SQL_SERVER, rs.getString(1)));
            }
        } catch (SQLException e) {
            // Puerto abierto pero login/permiso falló — no es fatal para el resto del escaneo.
        }
        return result;
    }
}
