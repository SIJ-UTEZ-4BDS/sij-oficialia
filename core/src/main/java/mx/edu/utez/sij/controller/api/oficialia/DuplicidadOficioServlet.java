
package mx.edu.utez.sij.controller.api.oficialia;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import mx.edu.utez.sij.dao.oficialia.DuplicidadOficioDAO;

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

        String curpRfc = limpiar(request.getParameter("curpRfc"));
        String nombre = limpiar(request.getParameter("nombre"));
        String telefono = limpiar(request.getParameter("telefono"));
        String materia = limpiar(request.getParameter("materia"));
        String fiscaliaActual = limpiar(
                request.getParameter("fiscaliaActual")
        );

        if (fiscaliaActual.isEmpty()) {
            responderError(response, 400,
                    "La fiscalia actual es obligatoria");
            return;
        }

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
                            fiscaliaActual
                    );

            boolean duplicado = !coincidencias.isEmpty();

            StringBuilder json = new StringBuilder();

            json.append("{");
            json.append("\"duplicado\":").append(duplicado);
            json.append(",\"total\":").append(coincidencias.size());
            json.append(",\"coincidencias\":[");

            for (int i = 0; i < coincidencias.size(); i++) {

                Map<String, Object> fila = coincidencias.get(i);

                if (i > 0) {
                    json.append(",");
                }

                json.append("{");
                json.append("\"folio\":\"")
                        .append(escapar(fila.get("folio")))
                        .append("\",");

                json.append("\"fiscalia\":\"")
                        .append(escapar(fila.get("fiscalia")))
                        .append("\",");

                json.append("\"materia\":\"")
                        .append(escapar(fila.get("materia")))
                        .append("\",");

                json.append("\"estatus\":\"")
                        .append(escapar(fila.get("estatus")))
                        .append("\"");

                json.append("}");
            }

            json.append("]}");

            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(json.toString());

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

    private String escapar(Object valor) {

        if (valor == null) {
            return "";
        }

        String texto = valor.toString();
        StringBuilder resultado = new StringBuilder();

        for (char c : texto.toCharArray()) {
            switch (c) {
                case '"' -> resultado.append("\\\"");
                case '\\' -> resultado.append("\\\\");
                case '\n' -> resultado.append("\\n");
                case '\r' -> resultado.append("\\r");
                case '\t' -> resultado.append("\\t");
                default -> {
                    if (c < 32) {
                        resultado.append(
                                String.format("\\u%04x", (int) c)
                        );
                    } else {
                        resultado.append(c);
                    }
                }
            }
        }

        return resultado.toString();
    }

    private void responderError(HttpServletResponse response,
                                int codigo,
                                String mensaje) throws IOException {

        response.setStatus(codigo);
        response.getWriter().write(
                "{\"error\":\"" + escapar(mensaje) + "\"}"
        );
    }
}
