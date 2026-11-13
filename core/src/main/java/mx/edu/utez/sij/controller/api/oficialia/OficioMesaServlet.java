
package mx.edu.utez.sij.controller.api.oficialia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import mx.edu.utez.sij.dao.oficialia.OficioMesaDAO;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@WebServlet("/api/oficialia/mesas-oficio")
public class OficioMesaServlet extends HttpServlet {

    private OficioMesaDAO dao;

    private boolean tieneAccesoOficialia(String rol) {
        return "SUPER_ADMIN".equals(rol)
                || (rol != null && rol.endsWith("_OFICIALIA"));
    }

    private boolean puedeConsultarExpedientes(String rol) {
        return "SUPER_ADMIN".equals(rol) || "ADMIN_OFICIALIA".equals(rol);
    }

    @Override
    public void init() throws ServletException {
        try {
            dao = new OficioMesaDAO();
        } catch (SQLException e) {
            throw new ServletException(
                    "No se pudo inicializar el módulo de Oficialía.", e);
        }
    }

    /**
     * Recupera el juzgado de la sesión.
     */
    private Integer obtenerIdJuzgado(HttpServletRequest req) {

        HttpSession session = req.getSession(false);

        if (session == null) {
            return null;
        }

        Object valor = session.getAttribute("id_juzgado");
        if (valor == null) {
            valor = session.getAttribute("idJuzgado");
        }

        if (valor instanceof Number) {
            return ((Number) valor).intValue();
        }

        if (valor instanceof String) {
            try {
                return Integer.parseInt((String) valor);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        return null;
    }

    @Override
    protected void doGet(
            HttpServletRequest req,
            HttpServletResponse resp)
            throws ServletException, IOException {

        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        HttpSession session = req.getSession(false);
        String rol = session == null ? null : (String) session.getAttribute("rol");
        if (session == null || session.getAttribute("idUsuario") == null) {
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_UNAUTHORIZED,
                    false, "Sesión no activa.");
            return;
        }
        if (!tieneAccesoOficialia(rol)) {
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_FORBIDDEN,
                    false, "No tiene acceso al módulo de Oficialía.");
            return;
        }

        Integer idJuzgado = obtenerIdJuzgado(req);

        if (idJuzgado == null || idJuzgado <= 0) {
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_UNAUTHORIZED,
                    false, "La sesión no contiene un juzgado válido.");
            return;
        }

        try {

            String accion = req.getParameter("accion");

            if ("materias".equals(accion)) {
                Map<String, Object> respuesta = new HashMap<>();
                respuesta.put("ok", true);
                respuesta.put("materias", dao.consultarMaterias());

                mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                        resp, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            if ("expedientes".equals(accion)) {
                if (!puedeConsultarExpedientes(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "Sólo el administrador de Oficialía puede consultar expedientes.");
                    return;
                }

                List<Map<String, Object>> expedientes =
                        dao.consultarExpedientes(idJuzgado);

                Map<String, Object> respuesta =
                        new HashMap<String, Object>();

                respuesta.put("ok", true);
                respuesta.put("expedientes", expedientes);

                mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                        resp, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_BAD_REQUEST,
                    false, "Acción de Oficialía no válida.");
            return;

        } catch (SQLException e) {

            log("Error consultando mesas de Oficialía.", e);

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    false, "Error al consultar la información de las mesas.");
        }
    }

    @Override
    protected void doPost(
            HttpServletRequest req,
            HttpServletResponse resp)
            throws ServletException, IOException {

        req.setCharacterEncoding("UTF-8");
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        HttpSession session = req.getSession(false);
        String rol = session == null ? null : (String) session.getAttribute("rol");
        if (session == null || session.getAttribute("idUsuario") == null) {
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_UNAUTHORIZED,
                    false, "Sesión no activa.");
            return;
        }
        if (!tieneAccesoOficialia(rol)) {
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_FORBIDDEN,
                    false, "No tiene acceso al módulo de Oficialía.");
            return;
        }

        Integer idJuzgado = obtenerIdJuzgado(req);

        if (idJuzgado == null || idJuzgado <= 0) {
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_UNAUTHORIZED,
                    false, "La sesión ha expirado o no tiene un juzgado válido.");
            return;
        }

        try {

            String materia = req.getParameter("idMateria");
            String direccion = req.getParameter("direccion");
            String descripcion = req.getParameter("descripcion");
            String partesJson = req.getParameter("partesJson");

            if (materia == null || materia.isBlank()) {
                throw new IllegalArgumentException(
                        "Selecciona una materia.");
            }

            int idMateria = Integer.parseInt(materia);

            if (idMateria <= 0) {
                throw new IllegalArgumentException(
                        "La materia seleccionada no es válida.");
            }

            if (direccion == null || direccion.isBlank()) {
                throw new IllegalArgumentException(
                        "La dirección completa es obligatoria.");
            }

            if (descripcion == null || descripcion.isBlank()) {
                throw new IllegalArgumentException(
                        "La descripción de los hechos es obligatoria.");
            }

            if (partesJson == null || partesJson.isBlank()) {
                throw new IllegalArgumentException(
                        "No se recibieron los datos validados de las partes.");
            }

            validarPartes(partesJson, idMateria);

            Map<String, Object> resultado =
                    dao.registrarOficio(
                            idMateria,
                            idJuzgado,
                            direccion.trim(),
                            descripcion.trim(),
                            partesJson
                    );

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                    resp, HttpServletResponse.SC_CREATED, resultado);

        } catch (IllegalArgumentException e) {

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_BAD_REQUEST,
                    false, e.getMessage());

        } catch (SQLException e) {

            log("Error registrando oficio.", e);

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    false, "No fue posible registrar el oficio.");
        }
    }

    private void validarPartes(String partesJson, int idMateria) {
        JsonObject partes;
        try {
            JsonElement elemento = JsonParser.parseString(partesJson);
            if (!elemento.isJsonObject()) {
                throw new IllegalArgumentException(
                        "Los datos de las partes no tienen un formato válido.");
            }
            partes = elemento.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(
                    "Los datos de las partes no tienen un formato válido.", e);
        }

        JsonElement idMateriaJson = partes.get("idMateria");
        if (idMateriaJson == null || !idMateriaJson.isJsonPrimitive()
                || idMateriaJson.getAsInt() != idMateria) {
            throw new IllegalArgumentException(
                    "La materia no coincide con los datos validados.");
        }

        JsonElement anonimaJson = partes.get("denunciaAnonima");
        if (anonimaJson == null || !anonimaJson.isJsonPrimitive()
                || !anonimaJson.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(
                    "Selecciona si el promovente aparecerá de forma anónima.");
        }

        boolean anonima = anonimaJson.getAsBoolean();
        JsonObject promovente = obtenerObjeto(partes, "promovente");
        JsonObject demandado = obtenerObjeto(partes, "demandado");
        String nombrePromovente = obtenerTexto(promovente, "nombreCompleto");
        String rfcPromovente = obtenerTexto(promovente, "curpRfc");
        String nombreDemandado = obtenerTexto(demandado, "nombreCompleto");

        if (nombreDemandado.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del demandado es obligatorio.");
        }
        if (!anonima && nombrePromovente.isBlank()) {
            throw new IllegalArgumentException(
                    "El nombre del promovente es obligatorio si no se registra anónimamente.");
        }
        if (anonima && (!nombrePromovente.isBlank() || !rfcPromovente.isBlank())) {
            throw new IllegalArgumentException(
                    "Los datos del promovente deben omitirse cuando la denuncia sea anónima.");
        }
    }

    private JsonObject obtenerObjeto(JsonObject padre, String propiedad) {
        JsonElement elemento = padre.get(propiedad);
        if (elemento == null || !elemento.isJsonObject()) {
            throw new IllegalArgumentException(
                    "Faltan datos válidos de la parte " + propiedad + ".");
        }
        return elemento.getAsJsonObject();
    }

    private String obtenerTexto(JsonObject objeto, String propiedad) {
        JsonElement elemento = objeto.get(propiedad);
        if (elemento == null || !elemento.isJsonPrimitive()) {
            return "";
        }
        return elemento.getAsString().trim();
    }
}
