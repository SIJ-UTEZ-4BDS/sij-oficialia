package mx.edu.utez.sij.core.config;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public class ConexionMySQL {

    private static final Properties props = new Properties();

    static {
        try (InputStream is = ConexionMySQL.class.getClassLoader().getResourceAsStream("db.properties")) {
            if (is != null) {
                props.load(is);
            } else {
                System.err.println("[SIJ-Core] Aviso: No se encontro db.properties, usando variables de entorno o valores por defecto.");
            }
        } catch (Exception e) {
            System.err.println("[SIJ-Core] Error al leer db.properties: " + e.getMessage());
        }
    }

    public static Connection getConnection() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
            String url = props.getProperty("db.url",
                    "jdbc:mysql://localhost:3306/sij_db?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=America/Mexico_City");
            String user = props.getProperty("db.user", "root");
            String pass = props.getProperty("db.password", "root");
            return DriverManager.getConnection(url, user, pass);
        } catch (ClassNotFoundException e) {
            throw new SQLException("Error al cargar el driver JDBC de MySQL", e);
        }
    }
}
