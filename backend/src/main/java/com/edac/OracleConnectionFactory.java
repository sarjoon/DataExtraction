package com.edac;

import java.sql.*;
import java.util.Properties;
import org.springframework.stereotype.Component;

@Component
public class OracleConnectionFactory {
    public Connection open(Models.ConnectionProfile profile, String password) throws SQLException {
        if (!"ORACLE".equals(profile.databaseType())) throw new IllegalArgumentException("Only Oracle external sources are supported.");
        // Build from structured fields, never from an editable raw JDBC URL in H2.
        String url = url(profile.host(), profile.port(), profile.connectionType(), profile.databaseName());
        Properties properties = new Properties();
        properties.setProperty("user", profile.username());
        properties.setProperty("password", password);
        properties.setProperty("oracle.net.CONNECT_TIMEOUT", "10000");
        properties.setProperty("oracle.jdbc.ReadTimeout", "30000");
        return DriverManager.getConnection(url, properties);
    }

    static String url(String host, int port, String type, String database) {
        if (host == null || !host.matches("[A-Za-z0-9][A-Za-z0-9.-]{0,252}") || port < 1 || port > 65535
                || database == null || !database.matches("[A-Za-z0-9_][A-Za-z0-9_.$#-]{0,127}"))
            throw new IllegalArgumentException("Invalid Oracle host, port or database name.");
        if (type == null) throw new IllegalArgumentException("Choose Service name or SID.");
        return switch (type) {
            case "SERVICE_NAME" -> "jdbc:oracle:thin:@//" + host + ":" + port + "/" + database;
            case "SID" -> "jdbc:oracle:thin:@" + host + ":" + port + ":" + database;
            default -> throw new IllegalArgumentException("Choose Service name or SID.");
        };
    }
}
