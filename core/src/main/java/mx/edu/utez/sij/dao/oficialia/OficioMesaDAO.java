
package mx.edu.utez.sij.dao.oficialia;

import mx.edu.utez.sij.core.config.ConexionMySQL;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class OficioMesaDAO {

    public OficioMesaDAO() throws SQLException {
        crearTablas();
    }

    /**
     * Crea el almacén de expedientes de la captura nueva.
     */
    public void crearTablas() throws SQLException {

        String sqlRegistros = """
            CREATE TABLE IF NOT EXISTS oficialia_registros (
                id_registro BIGINT AUTO_INCREMENT PRIMARY KEY,
                folio VARCHAR(40) UNIQUE,
                id_juzgado INT NOT NULL,
                id_materia INT NOT NULL,
                direccion_completa TEXT NOT NULL,
                descripcion_hechos TEXT NOT NULL,
                partes_json LONGTEXT NOT NULL,
                fecha_registro TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                INDEX idx_registro_juzgado
                    (id_juzgado, fecha_registro)
            ) ENGINE=InnoDB
            """;

        try (Connection conn =
                     ConexionMySQL.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sqlRegistros);
        }

    }

    public List<Map<String, Object>> consultarMaterias() throws SQLException {
        List<Map<String, Object>> materias = new ArrayList<>();

        String sql = """
            SELECT id_materia, nombre_materia
            FROM materias
            WHERE activo = 1
            ORDER BY nombre_materia
            """;

        try (Connection conn = ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {

            while (rs.next()) {
                Map<String, Object> materia = new HashMap<>();
                materia.put("idMateria", rs.getInt("id_materia"));
                materia.put("nombreMateria", rs.getString("nombre_materia"));
                materias.add(materia);
            }
        }

        return materias;
    }

    /**
     * Registra el expediente sin asignarlo a una mesa.
     */
    public Map<String, Object> registrarOficio(
            int idMateria,
            int idJuzgado,
            String direccion,
            String descripcion,
            String partesJson) throws SQLException {

        Map<String, Object> resultado =
                new HashMap<String, Object>();

        if (idMateria <= 0 || idJuzgado <= 0
                || direccion == null || direccion.isBlank()
                || descripcion == null || descripcion.isBlank()
                || partesJson == null || partesJson.isBlank()) {

            throw new IllegalArgumentException(
                    "Todos los campos obligatorios deben estar completos.");
        }

        String sqlInsertar = """
            INSERT INTO oficialia_registros
            (id_juzgado, id_materia,
             direccion_completa, descripcion_hechos, partes_json)
            VALUES (?, ?, ?, ?, ?)
            """;

        String sqlFolio = """
            UPDATE oficialia_registros
            SET folio = ?
            WHERE id_registro = ?
              AND id_juzgado = ?
            """;

        try (Connection conn =
                     ConexionMySQL.getConnection()) {

            conn.setAutoCommit(false);

            try {

                long idExpediente;

                try (PreparedStatement ps =
                             conn.prepareStatement(
                                     sqlInsertar,
                                     Statement.RETURN_GENERATED_KEYS)) {

                    ps.setInt(1, idJuzgado);
                    ps.setInt(2, idMateria);
                    ps.setString(3, direccion);
                    ps.setString(4, descripcion);
                    ps.setString(5, partesJson);

                    ps.executeUpdate();

                    try (ResultSet rs = ps.getGeneratedKeys()) {

                        if (!rs.next()) {
                            throw new SQLException(
                                    "No fue posible generar el expediente.");
                        }

                        idExpediente = rs.getLong(1);
                    }
                }

                String folio = String.format(
                        java.util.Locale.ROOT,
                        "EXP-J%02d-2026-%04d",
                        idJuzgado,
                        idExpediente
                );

                try (PreparedStatement ps =
                             conn.prepareStatement(sqlFolio)) {

                    ps.setString(1, folio);
                    ps.setLong(2, idExpediente);
                    ps.setInt(3, idJuzgado);

                    ps.executeUpdate();
                }

                conn.commit();

                resultado.put("ok", true);
                resultado.put("folio", folio);
                resultado.put("idExpediente", idExpediente);
                resultado.put("mensaje",
                        "Oficio registrado correctamente.");

                return resultado;

            } catch (Exception e) {

                conn.rollback();

                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }

                throw new SQLException(
                        "Error al registrar el oficio.", e);

            } finally {
                conn.setAutoCommit(true);
            }

        }

    }

    /**
     * Consulta expedientes del juzgado de la sesión.
     */
    public List<Map<String, Object>> consultarExpedientes(
            int idJuzgado) throws SQLException {

        List<Map<String, Object>> expedientes = new ArrayList<>();

        String sql = """
            SELECT e.folio, e.id_materia, ma.nombre_materia,
                   e.direccion_completa, e.descripcion_hechos,
                   e.partes_json, e.fecha_registro
            FROM oficialia_registros e
            INNER JOIN materias ma
                ON e.id_materia = ma.id_materia
            WHERE e.id_juzgado = ?
            ORDER BY e.fecha_registro DESC
            """;

        try (Connection conn =
                     ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, idJuzgado);

            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    Map<String, Object> fila =
                            new HashMap<String, Object>();

                    fila.put("folio", rs.getString("folio"));
                    fila.put("idMateria", rs.getInt("id_materia"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("direccion",
                            rs.getString("direccion_completa"));
                    fila.put("descripcion",
                            rs.getString("descripcion_hechos"));
                    fila.put("partesJson",
                            rs.getString("partes_json"));
                    fila.put("fecha",
                            rs.getTimestamp("fecha_registro").toString());

                    expedientes.add(fila);
                }
            }
        }

        return expedientes;
    }
}
