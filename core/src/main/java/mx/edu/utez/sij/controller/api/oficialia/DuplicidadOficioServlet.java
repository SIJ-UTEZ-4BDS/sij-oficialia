package mx.edu.utez.sij.controller.api.oficialia;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import mx.edu.utez.sij.core.util.JsonResponseUtil;
import mx.edu.utez.sij.dao.oficialia.DuplicidadOficioDAO;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/oficialia/duplicidad")
public class DuplicidadOficioServlet extends HttpServlet {

    // Endpoint específico de Oficialía; Magistrado/Tocas aún no tienen aquí
    // contrato de consulta ni autorización definida para leer estos registros.
    private DuplicidadOficioDAO dao;

    @Override
    public void init() throws ServletException {
        dao = new DuplicidadOficioDAO();
    }

    @Override
    protected void doPost(
            HttpServletRequest request,
            HttpServletResponse response) throws IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("idUsuario") == null) {
            JsonResponseUtil.sendMessage(
                    response, HttpServletResponse.SC_UNAUTHORIZED,
                    false, "Sesión no activa.");
            return;
        }

        String rol = (String) session.getAttribute("rol");
        if (!"SUPER_ADMIN".equals(rol)
                && (rol == null || !rol.endsWith("_OFICIALIA"))) {
            JsonResponseUtil.sendMessage(
                    response, HttpServletResponse.SC_FORBIDDEN,
                    false, "No tiene acceso a la consulta de duplicidad de Oficialía.");
            return;
        }

        Integer idUsuario = numeroSesion(session.getAttribute("idUsuario"));
        Integer idJuzgado = numeroSesion(session.getAttribute("idJuzgado"));
        if (idUsuario == null || idJuzgado == null || idJuzgado <= 0) {
            JsonResponseUtil.sendMessage(
                    response, HttpServletResponse.SC_FORBIDDEN,
                    false, "La sesión no contiene un usuario y juzgado válidos.");
            return;
        }

        try {
            Consulta consulta = leerConsulta(request);
            String accion = valor(request.getParameter("accion"));
            if (accion.isBlank() || "buscar".equals(accion)) {
                List<Map<String, Object>> coincidencias =
                        buscar(consulta, idJuzgado);
                Map<String, Object> respuesta = new HashMap<>();
                respuesta.put("ok", true);
                respuesta.put("duplicado", !coincidencias.isEmpty());
                respuesta.put("coincidencias", coincidencias);
                JsonResponseUtil.sendJson(
                        response, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            if ("detalle".equals(accion)) {
                if (!"OPERADOR_OFICIALIA".equals(rol)) {
                    JsonResponseUtil.sendMessage(
                            response, HttpServletResponse.SC_FORBIDDEN,
                            false, "El detalle de posibles duplicidades sólo está disponible para capturistas durante el registro.");
                    return;
                }
                List<Map<String, Object>> detalles = dao.buscarDetalles(
                        consulta.nombrePromovente,
                        consulta.curpRfcPromovente,
                        consulta.nombreDemandado,
                        consulta.curpRfcDemandado,
                        consulta.idMateria,
                        idJuzgado,
                        consulta.descripcion);
                Map<String, Object> respuesta = new HashMap<>();
                respuesta.put("ok", true);
                respuesta.put("detalles", detalles);
                JsonResponseUtil.sendJson(
                        response, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            if ("decision".equals(accion)) {
                dao.registrarDecision(
                        idUsuario,
                        idJuzgado,
                        valor(request.getParameter("decision")),
                        consulta.nombrePromovente,
                        consulta.curpRfcPromovente,
                        consulta.nombreDemandado,
                        consulta.curpRfcDemandado,
                        consulta.idMateria,
                        consulta.descripcion);
                JsonResponseUtil.sendJson(
                        response, HttpServletResponse.SC_OK,
                        Map.of("ok", true, "registrado", true));
                return;
            }

            JsonResponseUtil.sendMessage(
                    response, HttpServletResponse.SC_BAD_REQUEST,
                    false, "La acción de duplicidad no es válida.");
        } catch (IllegalArgumentException e) {
            JsonResponseUtil.sendMessage(
                    response, HttpServletResponse.SC_BAD_REQUEST,
                    false, e.getMessage());
        } catch (SQLException e) {
            getServletContext().log(
                    "Error procesando coincidencias de Oficialía.", e);
            JsonResponseUtil.sendMessage(
                    response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    false, mensajeErrorSql(e));
        }
    }

    private String mensajeErrorSql(SQLException e) {
        String mensaje = e.getMessage();
        if (mensaje != null
                && (mensaje.startsWith(
                        "No se puede registrar el historial de duplicidad:")
                || mensaje.startsWith(
                        "El detalle de duplicidad excede el espacio disponible"))) {
            return mensaje;
        }
        return "Error al consultar o registrar la duplicidad.";
    }

    private List<Map<String, Object>> buscar(
            Consulta consulta,
            int idJuzgado) throws SQLException {
        return dao.buscarCoincidencias(
                consulta.nombrePromovente,
                consulta.curpRfcPromovente,
                consulta.nombreDemandado,
                consulta.curpRfcDemandado,
                consulta.idMateria,
                idJuzgado,
                consulta.descripcion);
    }

    private Consulta leerConsulta(HttpServletRequest request) {
        String nombrePromovente =
                valor(request.getParameter("nombrePromovente"));
        String curpRfcPromovente =
                valor(request.getParameter("curpRfcPromovente"));
        String nombreDemandado =
                valor(request.getParameter("nombreDemandado"));
        String curpRfcDemandado =
                valor(request.getParameter("curpRfcDemandado"));
        String descripcion = valor(request.getParameter("descripcion"));
        int idMateria;
        try {
            idMateria = Integer.parseInt(
                    valor(request.getParameter("idMateria")));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Selecciona una materia válida.", e);
        }
        if (idMateria <= 0) {
            throw new IllegalArgumentException(
                    "Selecciona una materia válida.");
        }
        if (nombreDemandado.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del demandado es obligatorio para verificar coincidencias.");
        }
        return new Consulta(
                nombrePromovente, curpRfcPromovente, nombreDemandado,
                curpRfcDemandado, idMateria, descripcion);
    }

    private Integer numeroSesion(Object valor) {
        if (valor instanceof Number numero) {
            return numero.intValue();
        }
        if (valor instanceof String texto) {
            try {
                return Integer.parseInt(texto);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String valor(String texto) {
        return texto == null ? "" : texto.trim();
    }

    private static final class Consulta {
        private final String nombrePromovente;
        private final String curpRfcPromovente;
        private final String nombreDemandado;
        private final String curpRfcDemandado;
        private final int idMateria;
        private final String descripcion;

        private Consulta(
                String nombrePromovente,
                String curpRfcPromovente,
                String nombreDemandado,
                String curpRfcDemandado,
                int idMateria,
                String descripcion) {
            this.nombrePromovente = nombrePromovente;
            this.curpRfcPromovente = curpRfcPromovente;
            this.nombreDemandado = nombreDemandado;
            this.curpRfcDemandado = curpRfcDemandado;
            this.idMateria = idMateria;
            this.descripcion = descripcion;
        }
    }
}
