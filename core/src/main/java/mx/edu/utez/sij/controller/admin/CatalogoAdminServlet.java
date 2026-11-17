package mx.edu.utez.sij.controller.admin;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import mx.edu.utez.sij.core.config.ConexionMySQL;
import mx.edu.utez.sij.core.util.JsonResponseUtil;

import java.io.IOException;
import java.sql.*;
import java.util.*;

@WebServlet("/api/admin/catalogos")
public class CatalogoAdminServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("rol") == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "No autenticado");
            return;
        }

        String accion = req.getParameter("accion");
        try (Connection conn = ConexionMySQL.getConnection()) {
            // Validación en tiempo real para evitar repetir nombres de Materia, Distrito o clave de Juzgado
            if ("validarDuplicado".equals(accion)) {
                String tipo = req.getParameter("tipo");
                String valor = req.getParameter("valor");
                if (valor == null || valor.isBlank()) {
                    JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, Map.of("ok", true, "duplicado", false));
                    return;
                }
                String sql = switch (tipo != null ? tipo : "") {
                    case "materia" -> "SELECT COUNT(*) FROM materias WHERE LOWER(nombre_materia) = LOWER(?)";
                    case "distrito" -> "SELECT COUNT(*) FROM distritos WHERE LOWER(nombre_distrito) = LOWER(?)";
                    case "juzgado" -> "SELECT COUNT(*) FROM juzgados WHERE LOWER(numero_juzgado) = LOWER(?)";
                    default -> null;
                };
                if (sql != null) {
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, valor.trim());
                        try (ResultSet rs = ps.executeQuery()) {
                            boolean dup = rs.next() && rs.getInt(1) > 0;
                            JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, Map.of(
                                    "ok", true,
                                    "duplicado", dup,
                                    "mensaje", dup ? "Este nombre/clave ya existe en el catálogo oficial." : ""
                            ));
                            return;
                        }
                    }
                }
            }

            List<Map<String, Object>> materias = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id_materia, nombre_materia, activo FROM materias ORDER BY id_materia")) {
                while (rs.next()) {
                    materias.add(Map.of(
                            "idMateria", rs.getInt("id_materia"),
                            "nombreMateria", rs.getString("nombre_materia"),
                            "activo", rs.getBoolean("activo")
                    ));
                }
            }

            List<Map<String, Object>> distritos = new ArrayList<>();
            String sqlDist = "SELECT d.id_distrito, d.nombre_distrito, COALESCE(d.municipio_cabecera, 'Morelos') AS municipio, " +
                    "d.id_distrito_cercano, COALESCE(dc.nombre_distrito, 'Sede Principal / Propia') AS nombre_cercano, d.activo " +
                    "FROM distritos d LEFT JOIN distritos dc ON d.id_distrito_cercano = dc.id_distrito ORDER BY d.id_distrito";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sqlDist)) {
                while (rs.next()) {
                    Map<String, Object> d = new HashMap<>();
                    d.put("idDistrito", rs.getInt("id_distrito"));
                    d.put("nombreDistrito", rs.getString("nombre_distrito"));
                    d.put("municipio", rs.getString("municipio"));
                    d.put("idDistritoCercano", rs.getObject("id_distrito_cercano"));
                    d.put("nombreCercano", rs.getString("nombre_cercano"));
                    d.put("activo", rs.getBoolean("activo"));
                    distritos.add(d);
                }
            }

            List<Map<String, Object>> juzgados = new ArrayList<>();
            String sqlJuz = "SELECT j.id_juzgado, j.numero_juzgado, j.nombre_juzgado, d.nombre_distrito, m.nombre_materia, j.activo " +
                    "FROM juzgados j JOIN distritos d ON j.id_distrito = d.id_distrito " +
                    "JOIN materias m ON j.id_materia = m.id_materia ORDER BY j.id_juzgado DESC";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sqlJuz)) {
                while (rs.next()) {
                    juzgados.add(Map.of(
                            "idJuzgado", rs.getInt("id_juzgado"),
                            "numeroJuzgado", rs.getString("numero_juzgado"),
                            "nombreJuzgado", rs.getString("nombre_juzgado"),
                            "distrito", rs.getString("nombre_distrito"),
                            "materia", rs.getString("nombre_materia"),
                            "activo", rs.getBoolean("activo")
                    ));
                }
            }

            JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, Map.of(
                    "ok", true,
                    "materias", materias,
                    "distritos", distritos,
                    "juzgados", juzgados
            ));
        } catch (Exception e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        String rol = (session != null) ? (String) session.getAttribute("rol") : null;
        if (rol == null || (!rol.equals("SUPER_ADMIN") && !rol.startsWith("ADMIN_"))) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false, "Requiere permisos de Administrador.");
            return;
        }

        String accion = req.getParameter("accion");
        try (Connection conn = ConexionMySQL.getConnection()) {
            switch (accion != null ? accion : "") {
                case "crearMateria" -> {
                    String nombre = req.getParameter("nombreMateria");
                    try (PreparedStatement ps = conn.prepareStatement("INSERT INTO materias (nombre_materia, activo) VALUES (?, 1)")) {
                        ps.setString(1, nombre.trim());
                        ps.executeUpdate();
                    }
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_CREATED, true, "Materia registrada correctamente.");
                }
                case "editarMateria" -> {
                    int id = Integer.parseInt(req.getParameter("idMateria"));
                    String nombre = req.getParameter("nombreMateria");
                    try (PreparedStatement ps = conn.prepareStatement("UPDATE materias SET nombre_materia = ? WHERE id_materia = ?")) {
                        ps.setString(1, nombre.trim());
                        ps.setInt(2, id);
                        ps.executeUpdate();
                    }
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true, "Materia modificada correctamente.");
                }
                case "eliminarMateria" -> {
                    int id = Integer.parseInt(req.getParameter("idMateria"));
                    try (PreparedStatement ps = conn.prepareStatement("DELETE FROM materias WHERE id_materia = ?")) {
                        ps.setInt(1, id);
                        ps.executeUpdate();
                        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true, "Materia eliminada del catálogo.");
                    } catch (SQLException ex) {
                        // Si ya tiene juzgados vinculados, se desactiva para no romper llaves foráneas
                        try (PreparedStatement psOff = conn.prepareStatement("UPDATE materias SET activo = CASE WHEN activo = 1 THEN 0 ELSE 1 END WHERE id_materia = ?")) {
                            psOff.setInt(1, id);
                            psOff.executeUpdate();
                        }
                        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_OK, true, "La materia tiene juzgados vinculados; se cambió su estatus Activo/Inactivo.");
                    }
                }
                case "crearDistrito" -> {
                    String nombre = req.getParameter("nombreDistrito");
                    String municipio = req.getParameter("municipio");
                    String idCercanoStr = req.getParameter("idDistritoCercano");
                    String sql = "INSERT INTO distritos (nombre_distrito, municipio_cabecera, id_distrito_cercano, activo) VALUES (?, ?, ?, 1)";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, nombre.trim());
                        ps.setString(2, (municipio != null && !municipio.isBlank()) ? municipio.trim() : "Cuernavaca");
                        if (idCercanoStr != null && !idCercanoStr.isBlank()) {
                            ps.setInt(3, Integer.parseInt(idCercanoStr));
                        } else {
                            ps.setNull(3, Types.INTEGER);
                        }
                        ps.executeUpdate();
                    }
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_CREATED, true, "Nuevo Distrito Judicial registrado.");
                }
                case "crearJuzgado" -> {
                    String clave = req.getParameter("numeroJuzgado");
                    String nombre = req.getParameter("nombreJuzgado");
                    int idDistrito = Integer.parseInt(req.getParameter("idDistrito"));
                    String sql = "INSERT INTO juzgados (numero_juzgado, nombre_juzgado, id_distrito, id_materia, activo) VALUES (?, ?, ?, 1, 1)";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        ps.setString(1, clave.trim().toUpperCase());
                        ps.setString(2, nombre.trim());
                        ps.setInt(3, idDistrito);
                        ps.executeUpdate();
                    }
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_CREATED, true, "Juzgado registrado correctamente en el distrito.");
                }
                default -> JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false, "Acción de catálogo no reconocida.");
            }
        } catch (Exception e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false, "Error en catálogo: " + e.getMessage());
        }
    }
}