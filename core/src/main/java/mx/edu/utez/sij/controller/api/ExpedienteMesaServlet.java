package mx.edu.utez.sij.controller.api;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import mx.edu.utez.sij.core.config.ConexionMySQL;
import mx.edu.utez.sij.core.util.JsonResponseUtil;

import java.io.IOException;
import java.sql.*;
import java.time.Year;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

@WebServlet("/api/expedientes")
public class ExpedienteMesaServlet extends HttpServlet {

    private boolean tieneAccesoOficialia(String rol) {
        return "SUPER_ADMIN".equals(rol)
                || (rol != null && rol.endsWith("_OFICIALIA"));
    }

    private Integer obtenerIdJuzgado(HttpSession session, String rol) {
        Object valor = session.getAttribute("idJuzgado");
        if (valor instanceof Number numero && numero.intValue() > 0) {
            return numero.intValue();
        }
        if ("SUPER_ADMIN".equals(rol)) {
            return 1;
        }
        return null;
    }

    private void ensureTablesExist(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS oficialia_mesas (
                    id_mesa INT AUTO_INCREMENT PRIMARY KEY,
                    clave_mesa VARCHAR(20) NOT NULL UNIQUE,
                    nombre_mesa VARCHAR(100) NOT NULL,
                    id_materia INT NOT NULL,
                    id_juzgado INT NOT NULL,
                    carga_actual INT NOT NULL DEFAULT 0,
                    activo TINYINT(1) NOT NULL DEFAULT 1
                )
            """);

            st.execute("""
                CREATE TABLE IF NOT EXISTS oficialia_expedientes (
                    id_expediente INT AUTO_INCREMENT PRIMARY KEY,
                    numero_expediente VARCHAR(40) NOT NULL UNIQUE,
                    id_materia INT NOT NULL,
                    id_juzgado INT NOT NULL,
                    id_mesa INT NOT NULL,
                    partes_resumen VARCHAR(255) NOT NULL,
                    tipo_parte VARCHAR(60) NOT NULL,
                    estatus VARCHAR(40) NOT NULL DEFAULT 'RADICADO',
                    fecha_registro DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
                )
            """);

            st.execute("""
                INSERT IGNORE INTO oficialia_mesas (id_mesa, clave_mesa, nombre_mesa, id_materia, id_juzgado, carga_actual) VALUES
                (1, 'M-FAM-01', 'Mesa Primera Familiar', 1, 1, 1),
                (2, 'M-FAM-02', 'Mesa Segunda Familiar', 1, 1, 0),
                (3, 'M-VIO-01', 'Mesa Violencia Familiar', 2, 2, 0),
                (4, 'M-CIV-01', 'Mesa Primera Civil', 3, 1, 0),
                (5, 'M-PEN-01', 'Mesa Primera Penal', 4, 1, 0)
            """);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("idUsuario") == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "Sesión no activa");
            return;
        }

        String rol = (String) session.getAttribute("rol");
        if (!tieneAccesoOficialia(rol)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene acceso al módulo de Oficialía.");
            return;
        }
        Integer idJuzgadoSesion = obtenerIdJuzgado(session, rol);
        if (idJuzgadoSesion == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "Su cuenta no tiene un juzgado asignado.");
            return;
        }
        int idJuzgado = idJuzgadoSesion;
        String idMateriaParam = req.getParameter("idMateria");

        try (Connection conn = ConexionMySQL.getConnection()) {
            ensureTablesExist(conn);

            // Si envía la materia como parámetro, devuelve las mesas disponibles y la asignada automáticamente
            if (idMateriaParam != null && !idMateriaParam.isBlank()) {
                int idMateria;
                try {
                    idMateria = Integer.parseInt(idMateriaParam);
                } catch (NumberFormatException e) {
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                            "La materia seleccionada no es válida.");
                    return;
                }
                if (idMateria <= 0) {
                    JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                            "La materia seleccionada no es válida.");
                    return;
                }

                List<Map<String, Object>> mesas = new ArrayList<>();
                String sqlM = "SELECT id_mesa, clave_mesa, nombre_mesa, carga_actual FROM oficialia_mesas " +
                        "WHERE id_materia = ? AND id_juzgado = ? AND activo = 1 ORDER BY carga_actual ASC, id_mesa ASC";
                try (PreparedStatement ps = conn.prepareStatement(sqlM)) {
                    ps.setInt(1, idMateria);
                    ps.setInt(2, idJuzgado);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            mesas.add(Map.of(
                                    "idMesa", rs.getInt("id_mesa"),
                                    "claveMesa", rs.getString("clave_mesa"),
                                    "nombreMesa", rs.getString("nombre_mesa"),
                                    "cargaActual", rs.getInt("carga_actual")
                            ));
                        }
                    }
                }
                Map<String, Object> response = new HashMap<>();
                response.put("ok", true);
                response.put("mesas", mesas);
                response.put("mesaAutomatica", mesas.isEmpty() ? null : mesas.get(0));
                JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, response);
                return;
            }

            // Cargar catálogo de materias y expedientes exclusivos del juzgado del usuario
            List<Map<String, Object>> materias = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT id_materia, nombre_materia FROM materias WHERE activo = 1 ORDER BY id_materia")) {
                while (rs.next()) {
                    materias.add(Map.of("idMateria", rs.getInt("id_materia"), "nombreMateria", rs.getString("nombre_materia")));
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("ok", true);
            response.put("materias", materias);

            if ("ADMIN_OFICIALIA".equals(rol) || "SUPER_ADMIN".equals(rol)) {
                List<Map<String, Object>> expedientes = new ArrayList<>();
                String sqlExp = "SELECT e.id_expediente, e.numero_expediente, m.nombre_materia, " +
                        "CONCAT(me.clave_mesa, ' - ', me.nombre_mesa) AS mesa, " +
                        "e.partes_resumen, e.tipo_parte, e.estatus " +
                        "FROM oficialia_expedientes e " +
                        "JOIN materias m ON e.id_materia = m.id_materia " +
                        "JOIN oficialia_mesas me ON e.id_mesa = me.id_mesa " +
                        "WHERE e.id_juzgado = ? ORDER BY e.id_expediente DESC";
                try (PreparedStatement ps = conn.prepareStatement(sqlExp)) {
                    ps.setInt(1, idJuzgado);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            expedientes.add(Map.of(
                                    "idExpediente", rs.getInt("id_expediente"),
                                    "numeroExpediente", rs.getString("numero_expediente"),
                                    "materia", rs.getString("nombre_materia"),
                                    "mesa", rs.getString("mesa"),
                                    "partes", rs.getString("partes_resumen"),
                                    "tipoParte", rs.getString("tipo_parte"),
                                    "estatus", rs.getString("estatus")
                            ));
                        }
                    }
                }
                response.put("expedientes", expedientes);
            }

            JsonResponseUtil.sendJson(resp, HttpServletResponse.SC_OK, response);
        } catch (Exception e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false,
                    "Error al consultar expedientes: " + e.getMessage());
        }
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        HttpSession session = req.getSession(false);
        if (session == null || session.getAttribute("idUsuario") == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_UNAUTHORIZED, false, "Sesión no activa");
            return;
        }

        String rol = (String) session.getAttribute("rol");
        if (!tieneAccesoOficialia(rol)) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "No tiene acceso al módulo de Oficialía.");
            return;
        }
        Integer idJuzgadoSesion = obtenerIdJuzgado(session, rol);
        if (idJuzgadoSesion == null) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_FORBIDDEN, false,
                    "Su cuenta no tiene un juzgado asignado.");
            return;
        }
        int idJuzgado = idJuzgadoSesion;

        String idMateriaStr = req.getParameter("idMateria");
        String partes = req.getParameter("partesResumen");
        String tipoParte = req.getParameter("tipoParte");

        if (idMateriaStr == null || partes == null || partes.isBlank() || tipoParte == null || tipoParte.isBlank()) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                    "Materia, nombre de la parte y carácter son obligatorios.");
            return;
        }

        int idMateria;
        try {
            idMateria = Integer.parseInt(idMateriaStr);
        } catch (NumberFormatException e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                    "La materia seleccionada no es válida.");
            return;
        }
        if (idMateria <= 0) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_BAD_REQUEST, false,
                    "La materia seleccionada no es válida.");
            return;
        }

        try (Connection conn = ConexionMySQL.getConnection()) {
            ensureTablesExist(conn);

            int idMesa = 1;
            String descMesa = "M-GEN-01";
            String sqlMesa = "SELECT id_mesa, clave_mesa, nombre_mesa FROM oficialia_mesas " +
                    "WHERE id_materia = ? AND id_juzgado = ? AND activo = 1 ORDER BY carga_actual ASC, id_mesa ASC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(sqlMesa)) {
                ps.setInt(1, idMateria);
                ps.setInt(2, idJuzgado);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        idMesa = rs.getInt("id_mesa");
                        descMesa = rs.getString("clave_mesa") + " - " + rs.getString("nombre_mesa");
                    } else {
                        JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_CONFLICT, false,
                                "No hay mesas disponibles para esta materia en su juzgado.");
                        return;
                    }
                }
            }

            String folio = "EXP-J0" + idJuzgado + "-" + Year.now().getValue() + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);

            String sqlIns = "INSERT INTO oficialia_expedientes (numero_expediente, id_materia, id_juzgado, id_mesa, partes_resumen, tipo_parte, estatus) " +
                    "VALUES (?, ?, ?, ?, ?, ?, 'RADICADO')";
            try (PreparedStatement ps = conn.prepareStatement(sqlIns)) {
                ps.setString(1, folio);
                ps.setInt(2, idMateria);
                ps.setInt(3, idJuzgado);
                ps.setInt(4, idMesa);
                ps.setString(5, partes.trim());
                ps.setString(6, tipoParte.trim());
                ps.executeUpdate();
            }

            try (PreparedStatement psU = conn.prepareStatement("UPDATE oficialia_mesas SET carga_actual = carga_actual + 1 WHERE id_mesa = ? AND id_juzgado = ?")) {
                psU.setInt(1, idMesa);
                psU.setInt(2, idJuzgado);
                if (psU.executeUpdate() != 1) {
                    throw new SQLException("No fue posible actualizar la carga de la mesa.");
                }
            }

            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_CREATED, true,
                    "Expediente generado: " + folio + " (" + descMesa + ")");
        } catch (Exception e) {
            JsonResponseUtil.sendMessage(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, false,
                    "Error al guardar expediente: " + e.getMessage());
        }
    }
}