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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.stream.Collectors;

@WebListener
public class DatabaseInitializer implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("[SIJ-Core] Verificando e inicializando esquema de Base de Datos en MySQL...");
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

            for (String command : sqlScript.split(";")) {
                if (!command.trim().isEmpty()) {
                    stmt.execute(command.trim());
                }
            }

            System.out.println("[SIJ-Core] Esquema base y catalogos inicializados correctamente.");
            ensureSuperAdminFromSecureConfig(conn);
        } catch (Exception e) {
            System.err.println("[SIJ-Core] Error al inicializar la Base de Datos: " + e.getMessage());
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
                System.out.println("[SIJ-Core] Aviso: Variables app.bootstrap.* no definidas en db.properties. Se omite inyeccion de SUPER_ADMIN.");
                return;
            }

            try (PreparedStatement cleanOld = conn.prepareStatement(
                    "DELETE FROM usuarios WHERE clave_empleado = 'ROOT-SIJ-0001' AND rol = 'ADMIN_OFICIALIA'")) {
                cleanOld.executeUpdate();
            }

            String checkSql = "SELECT id_usuario FROM usuarios WHERE rol = 'SUPER_ADMIN' LIMIT 1";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(checkSql)) {
                if (!rs.next()) {
                    String hashedPass = BCrypt.hashpw(rawPass, BCrypt.gensalt(12));
                    String insertSql = "INSERT INTO usuarios (clave_empleado, nombre_completo, correo, password, rol, id_juzgado, activo) " +
                            "VALUES (?, ?, ?, ?, 'SUPER_ADMIN', NULL, 1)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        ps.setString(1, clave);
                        ps.setString(2, nombre);
                        ps.setString(3, correo);
                        ps.setString(4, hashedPass);
                        ps.executeUpdate();
                        System.out.println("[SIJ-Core] SUPER_ADMIN inicial aprovisionado exitosamente desde configuracion privada.");
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