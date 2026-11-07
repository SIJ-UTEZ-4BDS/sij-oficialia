package mx.edu.utez.sij.controller.admin;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import mx.edu.utez.sij.core.auth.LoginServlet;
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
                    Map.of("codigo", "ADMIN_OFICIALIA", "etiqueta", "Administrador de Oficialía de Partes", "modulo", "OFICIALIA"),
                    Map.of("codigo", "ADMIN_AUDIENCIAS", "etiqueta", "Administrador de Gestión de Audiencias", "modulo", "AUDIENCIAS"),
                    Map.of("codigo", "ADMIN_TOCAS", "etiqueta", "Administrador de Segunda Instancia (Tocas)", "modulo", "TOCAS"),
                    Map.of("codigo", "ADMIN_PERITOS", "etiqueta", "Coordinador / Admin de Peritos", "modulo", "PERITOS")
            );
            case "ADMIN_OFICIALIA" -> List.of(
                    Map.of("codigo", "OPERADOR_OFICIALIA", "etiqueta", "Capturista de Oficialía de Partes", "modulo", "OFICIALIA"),
                    Map.of("codigo", "SECRETARIO_OFICIALIA", "etiqueta", "Oficial Turnador (Oficialía)", "modulo", "OFICIALIA")
            );
            case "ADMIN_AUDIENCIAS" -> List.of(
                    Map.of("codigo", "JUEZ_AUDIENCIA", "etiqueta", "Juez de Audiencia Oral", "modulo", "AUDIENCIAS"),
                    Map.of("codigo", "ENCARGADO_SALA", "etiqueta", "Abogado / Encargado de Audiencia", "modulo", "AUDIENCIAS")
            );
            case "ADMIN_TOCAS" -> List.of(
                    Map.of("codigo", "MAGISTRADO_TOCAS", "etiqueta", "Magistrado de Sala (Tocas)", "modulo", "TOCAS")
            );
            case "ADMIN_PERITOS" -> List.of(
                    Map.of("codigo", "PERITO_JUDICIAL", "etiqueta", "Perito Judicial", "modulo", "PERITOS")
            );
            default -> Collections.emptyList();
        };
    }

    private String resolveModuloFromRole(String rol) {
        if (rol == null) return "GLOBAL";
        if (rol.contains("OFICIALIA")) return "OFICIALIA";
        if (rol.contains("AUDIENCIA") || rol.contains("SALA")) return "AUDIENCIAS";
        if (rol.contains("TOCA") || rol.contains("MAGISTRADO")) return "TOCAS";
        if (rol.contains("PERITO")) return "PERITOS";
        return "GLOBAL";
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
        String accion = req.getParameter("accion");

        try (Connection conn = ConexionMySQL.getConnection()) {
            // Validación en tiempo real de campos únicos (correo, telefono_contacto, curp) al escribir en el input
            if ("validarDuplicado".equals(accion)) {
                String campo = req.getParameter("campo");
                String valor = req.getParameter("valor");
                String idExcluirStr = req.getParameter("idExcluir");
                int idExcluir = (idExcluirStr != null && !idExcluirStr.isBlank()) ? Integer.parseInt(idExcluirStr) : 0;

                String colSql = switch (campo != null ? campo : "") {
                    case "correo" -> "correo";
                    case "telefono" -> "telefono_contacto";
                    case "curp" -> "curp";
                    default -> null;
                };

                if (colSql == null || valor == null || valor.isBlank()) {
                    JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, Map.of("ok", true, "duplicado", false));
                    return;
                }

                String checkSql = "SELECT clave_empleado, nombre_completo FROM usuarios WHERE LOWER(" + colSql + ") = LOWER(?) AND id_usuario != ? LIMIT 1";
                try (PreparedStatement ps = conn.prepareStatement(checkSql)) {
                    ps.setString(1, valor.trim());
                    ps.setInt(2, idExcluir);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, Map.of(
                                    "ok", true,
                                    "duplicado", true,
                                    "mensaje", "Este dato ya está registrado en el servidor público " +
                                            rs.getString("nombre_completo") + " (" + rs.getString("clave_empleado") + ")."
                            ));
                            return;
                        }
                    }
                }
                JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, Map.of("ok", true, "duplicado", false));
                return;
            }

            List<Map<String, String>> allowedRoles = getAllowedTargetRoles(callerRole);
            if (allowedRoles.isEmpty()) {
                JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false, "Sin permisos administrativos");
                return;
            }

            List<String> roleCodes = allowedRoles.stream().map(r -> r.get("codigo")).toList();
            String placeholders = String.join(",", Collections.nCopies(roleCodes.size(), "?"));

            StringBuilder sql = new StringBuilder(
                    "SELECT u.id_usuario, u.clave_empleado, u.nombre_completo, u.correo, " +
                            "COALESCE(u.telefono_contacto, 'Sin registro') AS telefono_contacto, " +
                            "COALESCE(u.curp, 'No registrado') AS curp, " +
                            "COALESCE(u.cargo_especifico, 'Servidor Público') AS cargo_especifico, " +
                            "COALESCE(u.modulo_adscripcion, 'GLOBAL') AS modulo_adscripcion, " +
                            "u.rol, u.id_juzgado, u.activo, u.intentos_fallidos, u.bloqueado_hasta, u.fecha_alta, " +
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
            sql.append("ORDER BY u.activo DESC, u.id_usuario DESC");

            List<Map<String, Object>> usuarios = new ArrayList<>();
            List<Map<String, Object>> juzgados = new ArrayList<>();

            String sqlJuzgados = "SUPER_ADMIN".equals(callerRole)
                    ? "SELECT j.id_juzgado, j.numero_juzgado, j.nombre_juzgado, d.nombre_distrito " +
                    "FROM juzgados j JOIN distritos d ON j.id_distrito = d.id_distrito WHERE j.activo = 1 ORDER BY d.id_distrito, j.id_juzgado"
                    : "SELECT j.id_juzgado, j.numero_juzgado, j.nombre_juzgado, d.nombre_distrito " +
                    "FROM juzgados j JOIN distritos d ON j.id_distrito = d.id_distrito WHERE j.id_juzgado = ?";

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
                        String rolUsr = rs.getString("rol");
                        u.put("idUsuario", rs.getInt("id_usuario"));
                        u.put("claveEmpleado", rs.getString("clave_empleado"));
                        u.put("nombreCompleto", rs.getString("nombre_completo"));
                        u.put("correo", rs.getString("correo"));
                        u.put("telefono", rs.getString("telefono_contacto"));
                        u.put("curp", rs.getString("curp"));
                        u.put("cargo", rs.getString("cargo_especifico"));
                        u.put("modulo", resolveModuloFromRole(rolUsr));
                        u.put("rol", rolUsr);
                        u.put("idJuzgado", rs.getObject("id_juzgado"));
                        u.put("juzgado", rs.getString("nombre_juzgado") +
                                (rs.getString("nombre_distrito").isEmpty() ? "" : " • " + rs.getString("nombre_distrito")));
                        u.put("activo", rs.getBoolean("activo"));
                        u.put("intentosFallidos", rs.getInt("intentos_fallidos"));
                        u.put("bloqueado", rs.getTimestamp("bloqueado_hasta") != null);
                        u.put("fechaAlta", String.valueOf(rs.getTimestamp("fecha_alta")));
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
            response.put("rutaModuloPropio", "../../" + LoginServlet.resolveModuleViewPath(callerRole));
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
                default -> JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                        "Acción no permitida. Los datos históricos de usuarios jamás se eliminan.");
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
        String telefono = req.getParameter("telefono");
        String curp = req.getParameter("curp");
        String cargo = req.getParameter("cargo");
        String password = req.getParameter("password");
        String rolDestino = req.getParameter("rol");
        String idJuzgadoStr = req.getParameter("idJuzgado");

        if (nombre == null || correo == null || telefono == null || password == null || rolDestino == null ||
                nombre.isBlank() || correo.isBlank() || telefono.isBlank() || password.isBlank() || rolDestino.isBlank()) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                    "Nombre, correo, teléfono de contacto, contraseña y rol son obligatorios.");
            return;
        }

        if (!isRoleAllowedForCaller(callerRole, rolDestino)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "Su perfil no está autorizado para dar de alta el rol solicitado.");
            return;
        }

        // Verificar que no exista correo, teléfono o CURP duplicado
        String dupCheck = "SELECT clave_empleado FROM usuarios WHERE LOWER(correo) = LOWER(?) OR telefono_contacto = ? " +
                (curp != null && !curp.isBlank() ? "OR UPPER(curp) = UPPER(?) " : "") + "LIMIT 1";
        try (PreparedStatement psDup = conn.prepareStatement(dupCheck)) {
            psDup.setString(1, correo.trim());
            psDup.setString(2, telefono.trim());
            if (curp != null && !curp.isBlank()) {
                psDup.setString(3, curp.trim());
            }
            try (ResultSet rsD = psDup.executeQuery()) {
                if (rsD.next()) {
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                            "El correo, teléfono de contacto o CURP ya pertenece al registro " + rsD.getString("clave_empleado"));
                    return;
                }
            }
        }

        int idJuzgadoFinal = "SUPER_ADMIN".equals(callerRole)
                ? parsePositiveInt(idJuzgadoStr, "idJuzgado")
                : (callerJuzgado != null ? callerJuzgado : 1);

        String claveGenerada = EmployeeCodeUtil.generateCode(rolDestino, nombre);
        String modulo = resolveModuloFromRole(rolDestino);
        String hashedPass = BCrypt.hashpw(password.trim(), BCrypt.gensalt(12));

        String sql = "INSERT INTO usuarios (clave_empleado, nombre_completo, correo, telefono_contacto, curp, cargo_especifico, modulo_adscripcion, password, rol, id_juzgado, activo) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, claveGenerada);
            ps.setString(2, nombre.trim());
            ps.setString(3, correo.trim().toLowerCase());
            ps.setString(4, telefono.trim());
            ps.setString(5, (curp != null && !curp.isBlank()) ? curp.trim().toUpperCase() : null);
            ps.setString(6, (cargo != null && !cargo.isBlank()) ? cargo.trim() : "Servidor Público Judicial");
            ps.setString(7, modulo);
            ps.setString(8, hashedPass);
            ps.setString(9, rolDestino);
            ps.setInt(10, idJuzgadoFinal);
            ps.executeUpdate();
        }

        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_CREATED, true,
                "Servidor público enrolado exitosamente con ID Único: " + claveGenerada);
    }

    private void handleUpdate(HttpServletRequest req, HttpServletResponse resp, Connection conn,
                              String callerRole, Integer callerJuzgado) throws Exception {
        int idUsuario = parsePositiveInt(req.getParameter("idUsuario"), "idUsuario");
        String nombre = req.getParameter("nombreCompleto");
        String correo = req.getParameter("correo");
        String telefono = req.getParameter("telefono");
        String curp = req.getParameter("curp");
        String cargo = req.getParameter("cargo");
        String rolDestino = req.getParameter("rol");
        String passwordNueva = req.getParameter("password");
        String idJuzgadoStr = req.getParameter("idJuzgado");

        if (nombre == null || nombre.isBlank() || correo == null || correo.isBlank() ||
                telefono == null || telefono.isBlank() || rolDestino == null || rolDestino.isBlank()) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                    "Nombre, correo, teléfono y rol son obligatorios.");
            return;
        }

        if (!canManageTargetUser(conn, idUsuario, callerRole, callerJuzgado)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene permisos sobre este servidor público.");
            return;
        }

        if (!isRoleAllowedForCaller(callerRole, rolDestino)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false, "Rol destino no permitido.");
            return;
        }

        int idJuzgadoFinal = "SUPER_ADMIN".equals(callerRole)
                ? parsePositiveInt(idJuzgadoStr, "idJuzgado")
                : callerJuzgado;
        String modulo = resolveModuloFromRole(rolDestino);

        if (passwordNueva != null && !passwordNueva.isBlank()) {
            String hashed = BCrypt.hashpw(passwordNueva.trim(), BCrypt.gensalt(12));
            String sql = "UPDATE usuarios SET nombre_completo = ?, correo = ?, telefono_contacto = ?, curp = ?, cargo_especifico = ?, modulo_adscripcion = ?, rol = ?, id_juzgado = ?, password = ? WHERE id_usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nombre.trim());
                ps.setString(2, correo.trim().toLowerCase());
                ps.setString(3, telefono.trim());
                ps.setString(4, (curp != null && !curp.isBlank()) ? curp.trim().toUpperCase() : null);
                ps.setString(5, (cargo != null && !cargo.isBlank()) ? cargo.trim() : "Servidor Público Judicial");
                ps.setString(6, modulo);
                ps.setString(7, rolDestino);
                ps.setInt(8, idJuzgadoFinal);
                ps.setString(9, hashed);
                ps.setInt(10, idUsuario);
                ps.executeUpdate();
            }
        } else {
            String sql = "UPDATE usuarios SET nombre_completo = ?, correo = ?, telefono_contacto = ?, curp = ?, cargo_especifico = ?, modulo_adscripcion = ?, rol = ?, id_juzgado = ? WHERE id_usuario = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, nombre.trim());
                ps.setString(2, correo.trim().toLowerCase());
                ps.setString(3, telefono.trim());
                ps.setString(4, (curp != null && !curp.isBlank()) ? curp.trim().toUpperCase() : null);
                ps.setString(5, (cargo != null && !cargo.isBlank()) ? cargo.trim() : "Servidor Público Judicial");
                ps.setString(6, modulo);
                ps.setString(7, rolDestino);
                ps.setInt(8, idJuzgadoFinal);
                ps.setInt(9, idUsuario);
                ps.executeUpdate();
            }
        }

        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true,
                "Información del servidor público actualizada correctamente.");
    }

    private void handleToggle(HttpServletRequest req, HttpServletResponse resp, Connection conn,
                              String callerRole, Integer callerJuzgado) throws Exception {
        int idUsuario = parsePositiveInt(req.getParameter("idUsuario"), "idUsuario");
        if (!canManageTargetUser(conn, idUsuario, callerRole, callerJuzgado)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene permisos sobre este usuario.");
            return;
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE usuarios SET activo = CASE WHEN activo = 1 THEN 0 ELSE 1 END WHERE id_usuario = ?")) {
            ps.setInt(1, idUsuario);
            ps.executeUpdate();
        }
        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true,
                "Estatus de la cuenta actualizado (sus datos se conservan en el histórico).");
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
        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true, "Cuenta desbloqueada exitosamente.");
    }

    private boolean canManageTargetUser(Connection conn, int targetUserId, String callerRole, Integer callerJuzgado) throws Exception {
        String sql = "SELECT rol, id_juzgado FROM usuarios WHERE id_usuario = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, targetUserId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String targetRol = rs.getString("rol");
                int targetJuzgado = rs.getInt("id_juzgado");
                if (!isRoleAllowedForCaller(callerRole, targetRol)) return false;
                if (!"SUPER_ADMIN".equals(callerRole) && (callerJuzgado == null || callerJuzgado != targetJuzgado)) return false;
                return true;
            }
        }
    }

    private int parsePositiveInt(String value, String fieldName) {
        try {
            int parsedValue = Integer.parseInt(value);
            if (parsedValue > 0) return parsedValue;
        } catch (NumberFormatException ignored) {}
        throw new IllegalArgumentException("El campo " + fieldName + " debe ser un entero positivo.");
    }
}