
package mx.edu.utez.sij.controller.api.oficialia;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import mx.edu.utez.sij.dao.oficialia.DuplicidadOficioDAO;
import mx.edu.utez.sij.core.util.JsonResponseUtil;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

@WebServlet("/api/oficialia/duplicidad")
public class DuplicidadOficioServlet extends HttpServlet {

    private DuplicidadOficioDAO dao;

    @Override
    public void init() throws ServletException {
        try {
            dao = new DuplicidadOficioDAO();
        } catch (Exception e) {
            throw new ServletException(
                    "Error al inicializar el DAO de duplicidad", e
            );
        }
    }

    @Override
    protected void doPost(HttpServletRequest request,
                          HttpServletResponse response)
            throws IOException {

        request.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        HttpSession session = request.getSession(false);
        if (session == null || session.getAttribute("idUsuario") == null) {
            JsonResponseUtil.sendMessage(response,
                    HttpServletResponse.SC_UNAUTHORIZED,
                    false, "Sesión no activa.");
            return;
        }

        String rol = (String) session.getAttribute("rol");
        if (!"SUPER_ADMIN".equals(rol)
                && (rol == null || !rol.endsWith("_OFICIALIA"))) {
            JsonResponseUtil.sendMessage(response,
                    HttpServletResponse.SC_FORBIDDEN,
                    false, "No tiene acceso a la consulta de duplicidad de Oficialía.");
            return;
        }

        Object idJuzgado = session.getAttribute("idJuzgado");
        Object nombreJuzgado = session.getAttribute("nombreJuzgado");
        if (!(idJuzgado instanceof Number numero)
                || numero.intValue() <= 0
                || nombreJuzgado == null
                || nombreJuzgado.toString().isBlank()) {
            JsonResponseUtil.sendMessage(response,
                    HttpServletResponse.SC_FORBIDDEN,
                    false, "Su cuenta no tiene un juzgado asignado.");
            return;
        }

        String curpRfc = limpiar(request.getParameter("curpRfc"));
        String nombre = limpiar(request.getParameter("nombre"));
        String telefono = limpiar(request.getParameter("telefono"));
        String materia = limpiar(request.getParameter("materia"));

        if (curpRfc.isEmpty() && nombre.isEmpty()
                && telefono.isEmpty()) {
            responderError(response, 400,
                    "Ingresa al menos un dato para buscar");
            return;
        }

        try {
            List<Map<String, Object>> coincidencias =
                    dao.buscarCoincidencias(
                            curpRfc,
                            nombre,
                            telefono,
                            materia,
                            nombreJuzgado.toString().trim()
                    );

            boolean duplicado = !coincidencias.isEmpty();
            JsonResponseUtil.sendJson(response,
                    HttpServletResponse.SC_OK,
                    Map.of("ok", true, "duplicado", duplicado));

        } catch (SQLException e) {

            getServletContext().log(
                    "Error al consultar duplicidad", e
            );

            responderError(response, 500,
                    "Error al consultar la base de datos");
        }
    }

    private String limpiar(String texto) {
        return texto == null ? "" : texto.trim();
    }

    private void responderError(HttpServletResponse response,
                                int codigo,
                                String mensaje) throws IOException {
        JsonResponseUtil.sendMessage(response, codigo, false, mensaje);
    }
}
