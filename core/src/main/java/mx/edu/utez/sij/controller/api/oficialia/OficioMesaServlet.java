
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

    // Esta API aún está acoplada a roles/sesiones de Oficialía; Magistrado y
    // Tocas necesitan un contrato de datos y permisos propio antes de consumirla.
    private boolean tieneAccesoOficialia(String rol) {
        return "SUPER_ADMIN".equals(rol)
                || "ADMIN_OFICIALIA".equals(rol)
                || "OPERADOR_OFICIALIA".equals(rol);
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
                if (!"ADMIN_OFICIALIA".equals(rol)
                        && !"OPERADOR_OFICIALIA".equals(rol)
                        && !"SUPER_ADMIN".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "No tiene permiso para consultar expedientes de Oficialía.");
                    return;
                }

                List<Map<String, Object>> expedientes =
                        "OPERADOR_OFICIALIA".equals(rol)
                                ? dao.consultarBandejaCapturista(idJuzgado)
                                : "SUPER_ADMIN".equals(rol)
                                ? dao.consultarExpedientesSuperAdmin(idJuzgado)
                                : dao.consultarExpedientes(
                                        idJuzgado,
                                        true);

                Map<String, Object> respuesta =
                        new HashMap<String, Object>();

                respuesta.put("ok", true);
                respuesta.put("expedientes", expedientes);

                mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                        resp, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            if ("contacto".equals(accion)) {
                if (!"OPERADOR_OFICIALIA".equals(rol)
                        && !"ADMIN_OFICIALIA".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "No tiene permiso para corregir estos datos.");
                    return;
                }
                long idRegistro = obtenerIdRegistro(req);
                Map<String, Object> datos = dao.consultarContactoDomicilio(
                        idJuzgado, idRegistro);
                if (datos == null) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_NOT_FOUND,
                            false, "El expediente no existe en su juzgado.");
                    return;
                }
                Map<String, Object> respuesta = new HashMap<>();
                respuesta.put("ok", true);
                respuesta.put("datos", datos);
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                        resp, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            if ("mesas".equals(accion)) {
                if (!"ADMIN_OFICIALIA".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "Sólo el Administrador de Oficialía puede consultar mesas.");
                    return;
                }
                int idMateria = obtenerEntero(req.getParameter("idMateria"));
                Map<String, Object> respuesta = new HashMap<>();
                respuesta.put("ok", true);
                respuesta.put("mesas", dao.consultarMesas(idJuzgado, idMateria));
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                        resp, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            if ("auditoria".equals(accion)) {
                if (!"ADMIN_OFICIALIA".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "Sólo el Administrador de Oficialía puede consultar la auditoría.");
                    return;
                }
                Map<String, Object> respuesta = new HashMap<>();
                respuesta.put("ok", true);
                respuesta.put("eventos", dao.consultarAuditoria(idJuzgado));
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                        resp, HttpServletResponse.SC_OK, respuesta);
                return;
            }

            if ("historial".equals(accion)) {
                if (!"ADMIN_OFICIALIA".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "Sólo el administrador de Oficialía puede consultar el historial del juzgado.");
                    return;
                }
                Map<String, Object> historial =
                        dao.consultarHistorial(idJuzgado);
                historial.put("ok", true);
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                        resp, HttpServletResponse.SC_OK, historial);
                return;
            }

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_BAD_REQUEST,
                    false, "Acción de Oficialía no válida.");
            return;

        } catch (IllegalArgumentException e) {
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_BAD_REQUEST,
                    false, e.getMessage());
        } catch (SQLException e) {

            log("Error consultando mesas de Oficialía.", e);

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                    resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    false, "Error al consultar la información de Oficialía.");
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

            String accion = req.getParameter("accion");
            Integer idUsuarioSesion = obtenerNumeroSesion(
                    session.getAttribute("idUsuario"));
            if (idUsuarioSesion == null || idUsuarioSesion <= 0) {
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                        resp, HttpServletResponse.SC_UNAUTHORIZED,
                        false, "La sesión no contiene un usuario válido.");
                return;
            }
            if ("actualizar-contacto".equals(accion)) {
                if (!"OPERADOR_OFICIALIA".equals(rol)
                        && !"ADMIN_OFICIALIA".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "No tiene permiso para corregir estos datos.");
                    return;
                }
                dao.actualizarContactoDomicilio(
                        idJuzgado, idUsuarioSesion,
                        obtenerIdRegistro(req),
                        req.getParameter("telefono"),
                        req.getParameter("correoElectronico"),
                        req.getParameter("direccionComponentes"));
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                        resp, HttpServletResponse.SC_OK, true,
                        "Se actualizaron los datos de contacto y domicilio.");
                return;
            }
            if ("actualizar-expediente".equals(accion)) {
                if (!"ADMIN_OFICIALIA".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "Sólo el Administrador de Oficialía puede editar expedientes.");
                    return;
                }
                dao.actualizarExpedienteAdmin(
                        idJuzgado, idUsuarioSesion,
                        req.getParameter("fuente"),
                        obtenerIdRegistro(req),
                        obtenerEntero(req.getParameter("idMateria")),
                        obtenerEntero(req.getParameter("idMesa")),
                        req.getParameter("estatus"),
                        req.getParameter("direccion"),
                        req.getParameter("descripcion"),
                        req.getParameter("partesJson"),
                        req.getParameter("partesResumen"),
                        req.getParameter("tipoParte"));
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                        resp, HttpServletResponse.SC_OK, true,
                        "El expediente se actualizó correctamente.");
                return;
            }
            if ("archivar".equals(accion)) {
                if (!"ADMIN_OFICIALIA".equals(rol)) {
                    mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                            resp, HttpServletResponse.SC_FORBIDDEN,
                            false, "Sólo el Administrador de Oficialía puede archivar expedientes.");
                    return;
                }
                dao.archivarExpedienteAdmin(
                        idJuzgado, idUsuarioSesion,
                        req.getParameter("fuente"),
                        obtenerIdRegistro(req));
                mx.edu.utez.sij.core.util.JsonResponseUtil.sendMessage(
                        resp, HttpServletResponse.SC_OK, true,
                        "El expediente se archivó correctamente.");
                return;
            }

            String materia = req.getParameter("idMateria");
            String direccion = req.getParameter("direccion");
            String descripcion = req.getParameter("descripcion");
            String partesJson = req.getParameter("partesJson");
            String nombrePromoventeBusqueda =
                    req.getParameter("nombrePromoventeBusqueda");
            String curpRfcPromoventeBusqueda =
                    req.getParameter("curpRfcPromoventeBusqueda");
            String aceptarDuplicidadParametro =
                    req.getParameter("aceptarDuplicidad");
            Integer idUsuario = idUsuarioSesion;

            if (idUsuario == null || idUsuario <= 0) {
                throw new IllegalArgumentException(
                        "La sesión no contiene un usuario válido.");
            }
            if (aceptarDuplicidadParametro != null
                    && !aceptarDuplicidadParametro.isBlank()
                    && !"true".equalsIgnoreCase(aceptarDuplicidadParametro)
                    && !"false".equalsIgnoreCase(aceptarDuplicidadParametro)) {
                throw new IllegalArgumentException(
                        "La confirmación de duplicidad no es válida.");
            }

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
                            idUsuario,
                            direccion.trim(),
                            descripcion.trim(),
                            partesJson,
                            nombrePromoventeBusqueda,
                            curpRfcPromoventeBusqueda,
                            "true".equalsIgnoreCase(
                                    aceptarDuplicidadParametro)
                    );

            mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                    resp, HttpServletResponse.SC_CREATED, resultado);

        } catch (OficioMesaDAO.CoincidenciasException e) {
            Map<String, Object> respuesta = new HashMap<>();
            respuesta.put("ok", false);
            respuesta.put("duplicado", true);
            respuesta.put("mensaje", e.getMessage());
            respuesta.put("coincidencias", e.getCoincidencias());
            mx.edu.utez.sij.core.util.JsonResponseUtil.sendJson(
                    resp, HttpServletResponse.SC_CONFLICT, respuesta);

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

    private Integer obtenerNumeroSesion(Object valor) {
        if (valor instanceof Number numero) {
            return numero.intValue();
        }
        if (valor instanceof String texto) {
            try {
                return Integer.parseInt(texto);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private int obtenerEntero(String valor) {
        try {
            int numero = Integer.parseInt(valor == null ? "" : valor);
            if (numero <= 0) {
                throw new NumberFormatException();
            }
            return numero;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El identificador recibido no es válido.", e);
        }
    }

    private long obtenerIdRegistro(HttpServletRequest req) {
        String valor = req.getParameter("idRegistro");
        if (valor == null || valor.isBlank()) {
            valor = req.getParameter("idExpediente");
        }
        try {
            long id = Long.parseLong(valor == null ? "" : valor);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El identificador del expediente no es válido.", e);
        }
    }
}
