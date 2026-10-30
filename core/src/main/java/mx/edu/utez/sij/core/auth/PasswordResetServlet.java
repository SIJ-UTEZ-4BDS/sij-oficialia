package mx.edu.utez.sij.core.auth;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mx.edu.utez.sij.core.config.ConexionMySQL;
import mx.edu.utez.sij.core.util.EmailService;
import mx.edu.utez.sij.core.util.EmployeeCodeUtil;
import mx.edu.utez.sij.core.util.JsonResponseUtil;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@WebServlet("/api/auth/reset-password")
public class PasswordResetServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        String step = req.getParameter("step"); // "request" o "confirm"
        String correo = req.getParameter("correo");

        if (correo == null || correo.isBlank()) {
            JsonResponseUtil.sendMessage(resp, 400, false, "Ingrese su correo institucional.");
            return;
        }

        try (Connection conn = ConexionMySQL.getConnection()) {
            if ("request".equalsIgnoreCase(step)) {
                String sql = "SELECT id_usuario, clave_empleado FROM usuarios WHERE correo = ? AND activo = 1";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, correo.trim());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            JsonResponseUtil.sendMessage(resp, 404, false, "No se encontró una cuenta activa con ese correo.");
                            return;
                        }
                        String code = EmployeeCodeUtil.generateSixDigitCode();
                        String clave = rs.getString("clave_empleado");

                        try (PreparedStatement up = conn.prepareStatement(
                                "UPDATE usuarios SET codigo_recuperacion = ?, expira_recuperacion = DATE_ADD(NOW(), INTERVAL 15 MINUTE) WHERE id_usuario = ?")) {
                            up.setString(1, code);
                            up.setInt(2, rs.getInt("id_usuario"));
                            up.executeUpdate();
                        }
                        EmailService.sendRecoveryCode(correo.trim(), clave, code);
                        JsonResponseUtil.sendMessage(resp, 200, true, "Código de 6 dígitos enviado a su correo institucional.");
                    }
                }
            } else if ("confirm".equalsIgnoreCase(step)) {
                String codigo = req.getParameter("codigo");
                String nuevaPass = req.getParameter("nuevaPassword");

                String sql = "SELECT id_usuario FROM usuarios WHERE correo = ? AND codigo_recuperacion = ? AND expira_recuperacion > NOW()";
                try (PreparedStatement ps = conn.prepareStatement(sql)) {
                    ps.setString(1, correo.trim());
                    ps.setString(2, codigo != null ? codigo.trim() : "");
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            JsonResponseUtil.sendMessage(resp, 400, false, "El código es inválido o ha expirado.");
                            return;
                        }
                        String hashed = BCrypt.hashpw(nuevaPass, BCrypt.gensalt());
                        try (PreparedStatement up = conn.prepareStatement(
                                "UPDATE usuarios SET password = ?, codigo_recuperacion = NULL, expira_recuperacion = NULL, intentos_fallidos = 0, bloqueado_hasta = NULL WHERE id_usuario = ?")) {
                            up.setString(1, hashed);
                            up.setInt(2, rs.getInt("id_usuario"));
                            up.executeUpdate();
                        }
                        JsonResponseUtil.sendMessage(resp, 200, true, "Contraseña actualizada y cuenta desbloqueada con éxito.");
                    }
                }
            }
        } catch (SQLException e) {
            JsonResponseUtil.sendMessage(resp, 500, false, "Error en el servidor: " + e.getMessage());
        }
    }
}