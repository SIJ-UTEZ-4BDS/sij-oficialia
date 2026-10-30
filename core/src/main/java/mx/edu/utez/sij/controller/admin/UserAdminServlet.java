package mx.edu.utez.sij.controller.admin;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import mx.edu.utez.sij.core.config.ConexionMySQL;
import mx.edu.utez.sij.core.util.EmployeeCodeUtil;
import mx.edu.utez.sij.core.util.JsonResponseUtil;
import org.mindrot.jbcrypt.BCrypt;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

@WebServlet("/api/admin/users")
public class UserAdminServlet extends HttpServlet {

    private List<Map<String, String>> getAllowedTargetRoles(String callerRole) {
        return switch (callerRole) {
            case "SUPER_ADMIN" -> List.of(
                    Map.of("codigo", "ADMIN_OFICIALIA", "etiqueta", "Administrador de Oficialía de Partes"),
                    Map.of("codigo", "ADMIN_AUDIENCIAS", "etiqueta", "Administrador de Gestión de Audiencias"),
                    Map.of("codigo", "ADMIN_TOCAS", "etiqueta", "Administrador de Segunda Instancia (Tocas)"),
                    Map.of("codigo", "ADMIN_PERITOS", "etiqueta", "Administrador de Coordinación de Peritos")
            );
            case "ADMIN_OFICIALIA" -> List.of(
                    Map.of("codigo", "OPERADOR_OFICIALIA", "etiqueta", "Operador de Ventanilla (Oficialía)"),
                    Map.of("codigo", "SECRETARIO_OFICIALIA", "etiqueta", "Oficial Mayor / Turnador (Oficialía)")
            );
            case "ADMIN_AUDIENCIAS" -> List.of(
                    Map.of("codigo", "JUEZ_AUDIENCIA", "etiqueta", "Juez de Audiencia Oral"),
                    Map.of("codigo", "ENCARGADO_SALA", "etiqueta", "Encargado de Sala y Agenda (Audiencias)")
            );
            case "ADMIN_TOCAS" -> List.of(
                    Map.of("codigo", "MAGISTRADO_TOCAS", "etiqueta", "Magistrado Ponente (Tocas)"),
                    Map.of("codigo", "SECRETARIO_ACUERDOS_TOCAS", "etiqueta", "Secretario de Acuerdos (Tocas)")
            );
            case "ADMIN_PERITOS" -> List.of(
                    Map.of("codigo", "PERITO_JUDICIAL", "etiqueta", "Perito Judicial Adscrito")
            );
            default -> Collections.emptyList();
        };
    }

    private boolean isRoleAllowedForCaller(String callerRole, String targetRole) {
        return getAllowedTargetRoles(callerRole).stream()
                .anyMatch(r -> r.get("codigo").equals(targetRole));
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("rol") == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "No autenticado");
            return;
        }

        String callerRole = (String) session.getAttribute("rol");
        Integer callerJuzgado = (Integer) session.getAttribute("idJuzgado");
        List<Map<String, String>> allowedRoles = getAllowedTargetRoles(callerRole);

        if (allowedRoles.isEmpty()) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false, "Sin permisos administrativos");
            return;
        }

        List<String> roleCodes = allowedRoles.stream().map(r -> r.get("codigo")).toList();
        String placeholders = String.join(",", Collections.nCopies(roleCodes.size(), "?"));

        StringBuilder sql = new StringBuilder(
                "SELECT u.id_usuario, u.clave_empleado, u.nombre_completo, u.correo, u.rol, " +
                        "u.id_juzgado, u.activo, u.intentos_fallidos, u.bloqueado_hasta, " +
                        "COALESCE(CONCAT(j.numero_juzgado, ' - ', j.nombre_juzgado), 'Sin Adscripción') AS nombre_juzgado, " +
                        "COALESCE(d.nombre_distrito, '') AS nombre_distrito " +
                        "FROM usuarios u " +
                        "LEFT JOIN juzgados j ON u.id_juzgado = j.id_juzgado " +
                        "LEFT JOIN distritos d ON j.id_distrito = d.id_distrito " +
                        "WHERE u.rol IN (" + placeholders + ") "
        );

        if (!"SUPER_ADMIN".equals(callerRole)) {
            sql.append("AND u.id_juzgado = ? ");
        }
        sql.append("ORDER BY u.id_usuario DESC");

        List<Map<String, Object>> usuarios = new ArrayList<>();
        List<Map<String, Object>> juzgados = new ArrayList<>();

        try (Connection conn = ConexionMySQL.getConnection()) {
            String sqlJuzgados = "SUPER_ADMIN".equals(callerRole)
                    ? "SELECT j.id_juzgado, j.numero_juzgado, j.nombre_juzgado, d.nombre_distrito, m.nombre_materia " +
                    "FROM juzgados j JOIN distritos d ON j.id_distrito = d.id_distrito " +
                    "JOIN materias m ON j.id_materia = m.id_materia WHERE j.activo = 1 ORDER BY j.id_juzgado"
                    : "SELECT j.id_juzgado, j.numero_juzgado, j.nombre_juzgado, d.nombre_distrito, m.nombre_materia " +
                    "FROM juzgados j JOIN distritos d ON j.id_distrito = d.id_distrito " +
                    "JOIN materias m ON j.id_materia = m.id_materia WHERE j.id_juzgado = ?";

            try (PreparedStatement psJ = conn.prepareStatement(sqlJuzgados)) {
                if (!"SUPER_ADMIN".equals(callerRole)) {
                    psJ.setInt(1, callerJuzgado != null ? callerJuzgado : -1);
                }
                try (ResultSet rsJ = psJ.executeQuery()) {
                    while (rsJ.next()) {
                        Map<String, Object> j = new HashMap<>();
                        j.put("idJuzgado", rsJ.getInt("id_juzgado"));
                        j.put("descripcion", rsJ.getString("numero_juzgado") + " - " +
                                rsJ.getString("nombre_juzgado") + " (" + rsJ.getString("nombre_distrito") + ")");
                        juzgados.add(j);
                    }
                }
            }

            try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
                int idx = 1;
                for (String code : roleCodes) {
                    ps.setString(idx++, code);
                }
                if (!"SUPER_ADMIN".equals(callerRole)) {
                    ps.setInt(idx, callerJuzgado != null ? callerJuzgado : -1);
                }

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> u = new HashMap<>();
                        u.put("idUsuario", rs.getInt("id_usuario"));
                        u.put("claveEmpleado", rs.getString("clave_empleado"));
                        u.put("nombreCompleto", rs.getString("nombre_completo"));
                        u.put("correo", rs.getString("correo"));
                        u.put("rol", rs.getString("rol"));
                        u.put("idJuzgado", rs.getObject("id_juzgado"));
                        u.put("juzgado", rs.getString("nombre_juzgado") +
                                (rs.getString("nombre_distrito").isEmpty() ? "" : " (" + rs.getString("nombre_distrito") + ")"));
                        u.put("activo", rs.getBoolean("activo"));
                        u.put("intentosFallidos", rs.getInt("intentos_fallidos"));
                        u.put("bloqueado", rs.getTimestamp("bloqueado_hasta") != null);
                        usuarios.add(u);
                    }
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("ok", true);
            response.put("miRol", callerRole);
            response.put("miNombre", session.getAttribute("nombreCompleto"));
            response.put("miClave", session.getAttribute("claveEmpleado"));
            response.put("miJuzgado", session.getAttribute("nombreJuzgado"));
            response.put("esSuperAdmin", "SUPER_ADMIN".equals(callerRole));
            response.put("rolesPermitidos", allowedRoles);
            response.put("juzgadosPermitidos", juzgados);
            response.put("usuarios", usuarios);
            JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, response);

        } catch (Exception e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false,
                    "Error al consultar padrón: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("rol") == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "Sesión no válida");
            return;
        }

        String callerRole = (String) session.getAttribute("rol");
        Integer callerJuzgado = (Integer) session.getAttribute("idJuzgado");
        String accion = req.getParameter("accion");
        if (accion == null || accion.isBlank()) {
            accion = "crear";
        }

        try (Connection conn = ConexionMySQL.getConnection()) {
            switch (accion) {
                case "crear" -> handleCreate(req, resp, conn, callerRole, callerJuzgado);
                case "editar" -> handleUpdate(req, resp, conn, callerRole, callerJuzgado);
                case "toggle" -> handleToggle(req, resp, conn, callerRole, callerJuzgado);
                case "desbloquear" -> handleUnlock(req, resp, conn, callerRole, callerJuzgado);
                case "eliminar" -> handleDelete(req, resp, conn, callerRole, callerJuzgado);
                default -> JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false, "Acción no reconocida.");
            }
        } catch (IllegalArgumentException e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false, e.getMessage());
        } catch (Exception e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false,
                    "Error en operación: " + e.getMessage());
        }
    }

    private void handleCreate(HttpServletRequest req, HttpServletResponse resp, Connection conn,
                              String callerRole, Integer callerJuzgado) throws Exception {
        String nombre = req.getParameter("nombreCompleto");
        String correo = req.getParameter("correo");
        String password = req.getParameter("password");
        String rolDestino = req.getParameter("rol");
        String idJuzgadoStr = req.getParameter("idJuzgado");

        if (nombre == null || correo == null || password == null || rolDestino == null ||
                nombre.isBlank() || correo.isBlank() || password.isBlank() || rolDestino.isBlank()) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                    "Todos los campos obligatorios deben completarse.");
            return;
        }

        if (!isRoleAllowedForCaller(callerRole, rolDestino)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "Violación de jerarquía: su rol (" + callerRole + ") no está autorizado para enrolar el rol " + rolDestino);
            return;
        }

        int idJuzgadoFinal;
        if ("SUPER_ADMIN".equals(callerRole)) {
            idJuzgadoFinal = parsePositiveInt(idJuzgadoStr, "idJuzgado");
        } else if (callerJuzgado != null) {
            idJuzgadoFinal = callerJuzgado;
        } else {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "La sesión administrativa no tiene un juzgado asignado.");
            return;
        }

        String claveGenerada = EmployeeCodeUtil.generateCode(rolDestino, nombre);
        String hashedPass = BCrypt.hashpw(password.trim(), BCrypt.gensalt(12));

        String sql = "INSERT INTO usuarios (clave_empleado, nombre_completo, correo, password, rol, id_juzgado, activo) " +
                "VALUES (?, ?, ?, ?, ?, ?, 1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, claveGenerada);
            ps.setString(2, nombre.trim());
            ps.setString(3, correo.trim().toLowerCase());
            ps.setString(4, hashedPass);
            ps.setString(5, rolDestino);
            ps.setInt(6, idJuzgadoFinal);
            ps.executeUpdate();
        }

        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_CREATED, true,
                "Servidor público registrado con éxito. Clave asignada: " + claveGenerada);
    }

    private void handleUpdate(HttpServletRequest req, HttpServletResponse resp, Connection conn,
                              String callerRole, Integer callerJuzgado) throws Exception {
        int idUsuario = parsePositiveInt(req.getParameter("idUsuario"), "idUsuario");
        String nombre = req.getParameter("nombreCompleto");
        String correo = req.getParameter("correo");
        String rolDestino = req.getParameter("rol");
        String passwordNueva = req.getParameter("password");
        String idJuzgadoStr = req.getParameter("idJuzgado");

        if (nombre == null || nombre.isBlank() || correo == null || correo.isBlank()
                || rolDestino == null || rolDestino.isBlank()) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                    "Nombre, correo y rol son obligatorios.");
            return;
        }

        if (!canManageTargetUser(conn, idUsuario, callerRole, callerJuzgado)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene permisos sobre este servidor público.");
            return;
        }

        if (!isRoleAllowedForCaller(callerRole, rolDestino)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "Rol destino no permitido para su nivel jerárquico.");
            return;
        }

        int idJuzgadoFinal;
        if ("SUPER_ADMIN".equals(callerRole)) {
            idJuzgadoFinal = parsePositiveInt(idJuzgadoStr, "idJuzgado");
        } else if (callerJuzgado != null) {
            idJuzgadoFinal = callerJuzgado;
        } else {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "La sesión administrativa no tiene un juzgado asignado.");
            return;
        }

        if (passwordNueva != null && !passwordNueva.isBlank()) {
            String hashed = BCrypt.hashpw(passwordNueva.trim(), BCrypt.gensalt(12));
            String sql = "UPDATE usuarios SET nombre_completo = ?, correo = ?, rol = ?, id_juzgado = ?, password = ? WHERE id_usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nombre.trim());
                ps.setString(2, correo.trim().toLowerCase());
                ps.setString(3, rolDestino);
                ps.setInt(4, idJuzgadoFinal);
                ps.setString(5, hashed);
                ps.setInt(6, idUsuario);
                ps.executeUpdate();
            }
        } else {
            String sql = "UPDATE usuarios SET nombre_completo = ?, correo = ?, rol = ?, id_juzgado = ? WHERE id_usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nombre.trim());
                ps.setString(2, correo.trim().toLowerCase());
                ps.setString(3, rolDestino);
                ps.setInt(4, idJuzgadoFinal);
                ps.setInt(5, idUsuario);
                ps.executeUpdate();
            }
        }

        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true,
                "Datos del servidor público actualizados correctamente.");
    }

    private void handleToggle(HttpServletRequest req, HttpServletResponse resp, Connection conn,
                              String callerRole, Integer callerJuzgado) throws Exception {
        int idUsuario = parsePositiveInt(req.getParameter("idUsuario"), "idUsuario");
        if (!canManageTargetUser(conn, idUsuario, callerRole, callerJuzgado)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene permisos para modificar el estado de este usuario.");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE usuarios SET activo = CASE WHEN activo = 1 THEN 0 ELSE 1 END WHERE id_usuario = ?")) {
            ps.setInt(1, idUsuario);
            ps.executeUpdate();
        }
        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true, "Estado de acceso actualizado.");
    }

    private void handleUnlock(HttpServletRequest req, HttpServletResponse resp, Connection conn,
                              String callerRole, Integer callerJuzgado) throws Exception {
        int idUsuario = parsePositiveInt(req.getParameter("idUsuario"), "idUsuario");
        if (!canManageTargetUser(conn, idUsuario, callerRole, callerJuzgado)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene permisos para desbloquear este usuario.");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE usuarios SET intentos_fallidos = 0, bloqueado_hasta = NULL WHERE id_usuario = ?")) {
            ps.setInt(1, idUsuario);
            ps.executeUpdate();
        }
        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true,
                "Cuenta desbloqueada y contador de intentos reiniciado.");
    }

    private void handleDelete(HttpServletRequest req, HttpServletResponse resp, Connection conn,
                              String callerRole, Integer callerJuzgado) throws Exception {
        int idUsuario = parsePositiveInt(req.getParameter("idUsuario"), "idUsuario");
        if (!canManageTargetUser(conn, idUsuario, callerRole, callerJuzgado)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene permisos para eliminar este usuario.");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM usuarios WHERE id_usuario = ?")) {
            ps.setInt(1, idUsuario);
            ps.executeUpdate();
        }
        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true, "Registro eliminado permanentemente.");
    }

    private boolean canManageTargetUser(Connection conn, int targetUserId, String callerRole, Integer callerJuzgado) throws Exception {
        String sql = "SELECT rol, id_juzgado FROM usuarios WHERE id_usuario = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String targetRol = rs.getString("rol");
                int targetJuzgado = rs.getInt("id_juzgado");

                if (!isRoleAllowedForCaller(callerRole, targetRol)) {
                    return false;
                }
                if (!"SUPER_ADMIN".equals(callerRole) && (callerJuzgado == null || callerJuzgado != targetJuzgado)) {
                    return false;
                }
                return true;
            }
        }
    }

    private int parsePositiveInt(String value, String fieldName) {
        try {
            int parsedValue = Integer.parseInt(value);
            if (parsedValue > 0) {
                return parsedValue;
            }
        } catch (NumberFormatException ignored) {
            // Handle missing, malformed, and non-positive IDs with one client error.
        }
        throw new IllegalArgumentException("El campo " + fieldName + " debe ser un entero positivo.");
    }
}