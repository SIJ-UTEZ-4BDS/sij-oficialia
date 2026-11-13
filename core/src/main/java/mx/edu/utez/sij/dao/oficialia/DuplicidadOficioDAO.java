package mx.edu.utez.sij.dao.oficialia;

import mx.edu.utez.sij.core.config.ConexionMySQL;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DuplicidadOficioDAO {

    public List<Map<String, Object>> buscarCoincidencias(
            String curpRfc,
            String nombre,
            int idMateria,
            int idJuzgado) throws SQLException {

        List<Map<String, Object>> coincidencias = new ArrayList<>();
        String rfcNormalizado = valor(curpRfc).toUpperCase();
        String nombreNormalizado = valor(nombre).toUpperCase();

        try (Connection conn = ConexionMySQL.getConnection()) {
            agregarCoincidenciasJson(
                    conn, "oficialia_registros", rfcNormalizado,
                    nombreNormalizado, idMateria, idJuzgado, coincidencias);

            if (tieneColumna(conn, "oficialia_expedientes", "partes_json")) {
                agregarCoincidenciasJson(
                        conn, "oficialia_expedientes", rfcNormalizado,
                        nombreNormalizado, idMateria, idJuzgado, coincidencias);
            } else if (tieneColumna(
                    conn, "oficialia_expedientes", "partes_resumen")) {
                agregarCoincidenciasLegadas(
                        conn, nombreNormalizado, idMateria, idJuzgado,
                        coincidencias);
            }
        }

        return coincidencias;
    }

    private void agregarCoincidenciasJson(
            Connection conn,
            String tabla,
            String curpRfc,
            String nombre,
            int idMateria,
            int idJuzgado,
            List<Map<String, Object>> coincidencias) throws SQLException {

        String sql = """
            SELECT j.numero_juzgado, j.nombre_juzgado,
                   d.nombre_distrito, m.nombre_materia,
                   e.fecha_registro
            FROM %s e
            INNER JOIN juzgados j ON j.id_juzgado = e.id_juzgado
            INNER JOIN distritos d ON d.id_distrito = j.id_distrito
            INNER JOIN materias m ON m.id_materia = e.id_materia
            WHERE e.id_juzgado <> ?
              AND e.id_materia = ?
              AND (
                  (? <> '' AND (
                      UPPER(JSON_UNQUOTE(JSON_EXTRACT(
                          CAST(e.partes_json AS JSON),
                          '$.promovente.curpRfc'))) = ?
                      OR UPPER(JSON_UNQUOTE(JSON_EXTRACT(
                          CAST(e.partes_json AS JSON),
                          '$.demandado.curpRfc'))) = ?
                  ))
                  OR (? <> '' AND (
                      UPPER(JSON_UNQUOTE(JSON_EXTRACT(
                          CAST(e.partes_json AS JSON),
                          '$.promovente.nombreCompleto'))) = ?
                      OR UPPER(JSON_UNQUOTE(JSON_EXTRACT(
                          CAST(e.partes_json AS JSON),
                          '$.demandado.nombreCompleto'))) = ?
                  ))
              )
            ORDER BY e.fecha_registro DESC
            LIMIT 10
            """.formatted(tabla);

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            ps.setInt(2, idMateria);
            ps.setString(3, curpRfc);
            ps.setString(4, curpRfc);
            ps.setString(5, curpRfc);
            ps.setString(6, nombre);
            ps.setString(7, nombre);
            ps.setString(8, nombre);
            agregarFilas(ps, coincidencias);
        }
    }

    private void agregarCoincidenciasLegadas(
            Connection conn,
            String nombre,
            int idMateria,
            int idJuzgado,
            List<Map<String, Object>> coincidencias) throws SQLException {

        if (nombre.isEmpty()) {
            return;
        }

        String sql = """
            SELECT j.numero_juzgado, j.nombre_juzgado,
                   d.nombre_distrito, m.nombre_materia,
                   e.fecha_registro
            FROM oficialia_expedientes e
            INNER JOIN juzgados j ON j.id_juzgado = e.id_juzgado
            INNER JOIN distritos d ON d.id_distrito = j.id_distrito
            INNER JOIN materias m ON m.id_materia = e.id_materia
            WHERE e.id_juzgado <> ?
              AND e.id_materia = ?
              AND UPPER(TRIM(e.partes_resumen)) = ?
            ORDER BY e.fecha_registro DESC
            LIMIT 10
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            ps.setInt(2, idMateria);
            ps.setString(3, nombre);
            agregarFilas(ps, coincidencias);
        }
    }

    private void agregarFilas(
            PreparedStatement ps,
            List<Map<String, Object>> coincidencias) throws SQLException {
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Map<String, Object> fila = new HashMap<>();
                fila.put("juzgado",
                        rs.getString("numero_juzgado") + " - "
                                + rs.getString("nombre_juzgado"));
                fila.put("distrito", rs.getString("nombre_distrito"));
                fila.put("materia", rs.getString("nombre_materia"));
                Timestamp fecha = rs.getTimestamp("fecha_registro");
                fila.put("fecha", fecha == null ? "" : fecha.toString());
                coincidencias.add(fila);
            }
        }
    }

    private boolean tieneColumna(
            Connection conn,
            String tabla,
            String columna) throws SQLException {
        DatabaseMetaData metadata = conn.getMetaData();
        try (ResultSet columnas = metadata.getColumns(
                conn.getCatalog(), null, tabla, "%")) {
            while (columnas.next()) {
                if (tabla.equalsIgnoreCase(columnas.getString("TABLE_NAME"))
                        && columna.equalsIgnoreCase(
                                columnas.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private String valor(String texto) {
        return texto == null ? "" : texto.trim();
    }
}
