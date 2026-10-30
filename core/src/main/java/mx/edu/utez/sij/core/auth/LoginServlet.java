package mx.edu.utez.sij.core.auth;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import mx.edu.utez.sij.core.config.ConexionMySQL;
import mx.edu.utez.sij.core.util.JsonResponseUtil;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@WebServlet(urlPatterns = {"/api/auth/login", "/api/auth/session"})
public class LoginServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("idUsuario") == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "Sesión no activa");
            return;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("ok", true);
        data.put("idUsuario", session.getAttribute("idUsuario"));
        data.put("claveEmpleado", session.getAttribute("claveEmpleado"));
        data.put("nombreCompleto", session.getAttribute("nombreCompleto"));
        data.put("correo", session.getAttribute("correo"));
        data.put("rol", session.getAttribute("rol"));
        data.put("idJuzgado", session.getAttribute("idJuzgado"));
        data.put("nombreJuzgado", session.getAttribute("nombreJuzgado"));
        JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, data);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String identificador = req.getParameter("usuario");
        String password = req.getParameter("password");

        if (identificador == null || password == null || identificador.isBlank() || password.isBlank()) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false, "Ingrese su clave/correo y contraseña.");
            return;
        }

        String sql = "SELECT u.id_usuario, u.clave_empleado, u.nombre_completo, u.correo, u.password, " +
                "u.rol, u.id_juzgado, u.intentos_fallidos, u.bloqueado_hasta, u.activo, " +
                "COALESCE(CONCAT(j.numero_juzgado, ' - ', j.nombre_juzgado), 'Consejo de la Judicatura (Global)') AS nombre_juzgado " +
                "FROM usuarios u " +
                "LEFT JOIN juzgados j ON u.id_juzgado = j.id_juzgado " +
                "WHERE u.correo = ? OR u.clave_empleado = ? LIMIT 1";

        try (Connection conn = ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, identificador.trim());
            ps.setString(2, identificador.trim());

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "Credenciales inválidas.");
                    return;
                }

                int idUsuario = rs.getInt("id_usuario");
                boolean activo = rs.getBoolean("activo");
                Timestamp bloqueadoHasta = rs.getTimestamp("bloqueado_hasta");
                int intentos = rs.getInt("intentos_fallidos");

                if (!activo) {
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                            "Su cuenta ha sido dada de baja o inactivada por su administrador.");
                    return;
                }

                if (bloqueadoHasta != null && bloqueadoHasta.toLocalDateTime().isAfter(LocalDateTime.now())) {
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                            "Cuenta bloqueada temporalmente por intentos fallidos.");
                    return;
                }

                String hash = rs.getString("password");
                boolean validPass = hash != null && hash.startsWith("$2") && BCrypt.checkpw(password, hash);

                if (!validPass) {
                    int nuevosIntentos = intentos + 1;
                    String updateFail = nuevosIntentos >= 3
                            ? "UPDATE usuarios SET intentos_fallidos = ?, bloqueado_hasta = DATE_ADD(NOW(), INTERVAL 30 MINUTE) WHERE id_usuario = ?"
                            : "UPDATE usuarios SET intentos_fallidos = ? WHERE id_usuario = ?";
                    try (PreparedStatement psFail = conn.prepareStatement(updateFail)) {
                        psFail.setInt(1, nuevosIntentos);
                        psFail.setInt(2, idUsuario);
                        psFail.executeUpdate();
                    }
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false,
                            "Contraseña incorrecta. Intento " + nuevosIntentos + " de 3.");
                    return;
                }

                try (PreparedStatement psReset = conn.prepareStatement(
                        "UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = NULL WHERE id_usuario = ?")) {
                    psReset.setInt(1, idUsuario);
                    psReset.executeUpdate();
                }

                String rol = rs.getString("rol");
                Object idJuzgadoObj = rs.getObject("id_juzgado");

                HttpSession session = req.getSession(true);
                session.setAttribute("idUsuario", idUsuario);
                session.setAttribute("claveEmpleado", rs.getString("clave_empleado"));
                session.setAttribute("nombreCompleto", rs.getString("nombre_completo"));
                session.setAttribute("correo", rs.getString("correo"));
                session.setAttribute("rol", rol);
                session.setAttribute("idJuzgado", idJuzgadoObj);
                session.setAttribute("nombreJuzgado", rs.getString("nombre_juzgado"));

                String redirectUrl = (rol.equals("SUPER_ADMIN") || rol.startsWith("ADMIN_"))
                        ? req.getContextPath() + "/views/admin/index.html"
                        : req.getContextPath() + "/views/dashboard/index.html";

                Map<String, Object> respMap = new HashMap<>();
                respMap.put("ok", true);
                respMap.put("rol", rol);
                respMap.put("claveEmpleado", rs.getString("clave_empleado"));
                respMap.put("nombreCompleto", rs.getString("nombre_completo"));
                respMap.put("nombreJuzgado", rs.getString("nombre_juzgado"));
                respMap.put("redirectUrl", redirectUrl);
                JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, respMap);
            }
        } catch (Exception e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false,
                    "Error en servidor: " + e.getMessage());
        }
    }
}