package mx.edu.utez.sij.core.config;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import org.mindrot.jbcrypt.BCrypt;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@WebListener
public class DatabaseInitializer implements ServletContextListener {

    private static final Pattern CREATE_TABLE_PATTERN = Pattern.compile(
            "(?i)CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?`?([a-zA-Z0-9_]+)`?\\s*\\((.*)\\)",
            Pattern.DOTALL
    );

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("[SIJ-Core] Verificando y sincronizando esquema de Base de Datos en MySQL...");
        try (Connection conn = ConexionMySQL.getConnection();
             Statement stmt = conn.createStatement();
             InputStream is = getClass().getClassLoader().getResourceAsStream("sql/schema.sql")) {

            if (is == null) {
                System.err.println("[SIJ-Core] No se encontro sql/schema.sql en resources.");
                return;
            }

            String sqlScript = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .filter(line -> !line.trim().startsWith("--"))
                    .collect(Collectors.joining("\n"));

            for (String rawCommand : sqlScript.split(";")) {
                String command = rawCommand.trim();
                if (command.isEmpty()) continue;

                try {
                    stmt.execute(command);
                    if (command.toUpperCase().startsWith("CREATE TABLE")) {
                        autoSyncMissingColumns(conn, stmt, command);
                    }
                } catch (Exception cmdEx) {
                    System.err.println("[SIJ-Core] Aviso en sentencia SQL: " + cmdEx.getMessage());
                }
            }

            System.out.println("[SIJ-Core] Esquema base, columnas nuevas y catalogos sincronizados correctamente.");
            ensureSuperAdminFromSecureConfig(conn);
        } catch (Exception e) {
            System.err.println("[SIJ-Core] Error al inicializar la Base de Datos: " + e.getMessage());
        }
    }

    /**
     * Sincronizador universal estilo Hibernate ddl-auto=update:
     * Compara las columnas declaradas en cualquier CREATE TABLE de schema.sql contra MySQL
     * y agrega automáticamente cualquier columna nueva sin necesidad de comandos manuales.
     */
    private void autoSyncMissingColumns(Connection conn, Statement stmt, String createTableSql) {
        try {
            Matcher matcher = CREATE_TABLE_PATTERN.matcher(createTableSql);
            if (!matcher.find()) return;

            String tableName = matcher.group(1).trim();
            String body = matcher.group(2).trim();

            Set<String> existingColumns = new HashSet<>();
            DatabaseMetaData meta = conn.getMetaData();
            try (ResultSet rs = meta.getColumns(conn.getCatalog(), null, tableName, null)) {
                while (rs.next()) {
                    existingColumns.add(rs.getString("COLUMN_NAME").toLowerCase());
                }
            }

            if (existingColumns.isEmpty()) return;

            for (String rawLine : body.split(",\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty()) continue;

                String upper = line.toUpperCase();
                if (upper.startsWith("PRIMARY KEY") || upper.startsWith("CONSTRAINT") ||
                        upper.startsWith("FOREIGN KEY") || upper.startsWith("UNIQUE KEY") ||
                        upper.startsWith("KEY ") || upper.startsWith("INDEX ")) {
                    continue;
                }

                String[] tokens = line.split("\\s+", 2);
                if (tokens.length < 2) continue;

                String colName = tokens[0].replace("`", "").trim();
                if (!existingColumns.contains(colName.toLowerCase())) {
                    String cleanDef = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
                    String alterSql = "ALTER TABLE " + tableName + " ADD COLUMN " + cleanDef;
                    try {
                        stmt.execute(alterSql);
                        System.out.println("[SIJ-Core] Columna nueva sincronizada automaticamente en '" + tableName + "': " + colName);
                    } catch (Exception alterEx) {
                        System.err.println("[SIJ-Core] Aviso al sincronizar columna " + colName + ": " + alterEx.getMessage());
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureSuperAdminFromSecureConfig(Connection conn) {
        try {
            Properties props = new Properties();
            try (InputStream is = getClass().getClassLoader().getResourceAsStream("db.properties")) {
                if (is != null) {
                    props.load(is);
                }
            }

            String clave = getSecureValue("SIJ_BOOTSTRAP_CLAVE", "app.bootstrap.clave", props);
            String nombre = getSecureValue("SIJ_BOOTSTRAP_NOMBRE", "app.bootstrap.nombre", props);
            String correo = getSecureValue("SIJ_BOOTSTRAP_CORREO", "app.bootstrap.correo", props);
            String rawPass = getSecureValue("SIJ_BOOTSTRAP_PASSWORD", "app.bootstrap.password", props);

            if (clave == null || nombre == null || correo == null || rawPass == null) {
                return;
            }

            String checkSql = "SELECT id_usuario FROM usuarios WHERE rol = 'SUPER_ADMIN' LIMIT 1";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(checkSql)) {
                if (!rs.next()) {
                    String hashedPass = BCrypt.hashpw(rawPass, BCrypt.gensalt(12));
                    String insertSql = "INSERT INTO usuarios (clave_empleado, nombre_completo, correo, telefono_contacto, cargo_especifico, modulo_adscripcion, password, rol, id_juzgado, activo) " +
                            "VALUES (?, ?, ?, '7771000000', 'Dirección General TI', 'GLOBAL', ?, 'SUPER_ADMIN', NULL, 1)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setString(1, clave);
                        ps.setString(2, nombre);
                        ps.setString(3, correo);
                        ps.setString(4, hashedPass);
                        ps.executeUpdate();
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[SIJ-Core] Error al verificar SUPER_ADMIN: " + e.getMessage());
        }
    }

    private String getSecureValue(String envKey, String propKey, Properties props) {
        String val = System.getenv(envKey);
        if (val == null || val.trim().isEmpty()) {
            val = props.getProperty(propKey);
        }
        return (val != null && !val.trim().isEmpty()) ? val.trim() : null;
    }
}