
package mx.edu.utez.sij.dao.oficialia;

import mx.edu.utez.sij.core.config.ConexionMySQL;
import mx.edu.utez.sij.model.oficialia.MesaOficialia;

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
     * Crea las tablas necesarias e inserta las mesas iniciales.
     */
    public void crearTablas() throws SQLException {

        String sqlMesas = """
            CREATE TABLE IF NOT EXISTS oficialia_mesas (
                id_mesa INT AUTO_INCREMENT PRIMARY KEY,
                id_materia INT NOT NULL,
                id_juzgado INT NOT NULL,
                nombre_mesa VARCHAR(100) NOT NULL,
                carga_actual INT NOT NULL DEFAULT 0,
                disponible BOOLEAN NOT NULL DEFAULT TRUE,
                UNIQUE KEY uk_mesa_materia_juzgado
                    (id_materia, id_juzgado, nombre_mesa),
                INDEX idx_mesas_asignacion
                    (id_juzgado, id_materia, disponible, carga_actual)
            ) ENGINE=InnoDB
            """;

        String sqlExpedientes = """
            CREATE TABLE IF NOT EXISTS oficialia_expedientes (
                id_expediente BIGINT AUTO_INCREMENT PRIMARY KEY,
                folio VARCHAR(40) UNIQUE,
                id_juzgado INT NOT NULL,
                id_mesa INT NOT NULL,
                id_materia INT NOT NULL,
                direccion_completa TEXT NOT NULL,
                descripcion_hechos TEXT NOT NULL,
                partes_json LONGTEXT NOT NULL,
                fecha_registro TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (id_mesa)
                    REFERENCES oficialia_mesas(id_mesa),
                INDEX idx_expediente_juzgado
                    (id_juzgado, fecha_registro)
            ) ENGINE=InnoDB
            """;

        try (Connection conn =
                     ConexionMySQL.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.execute(sqlMesas);
            stmt.execute(sqlExpedientes);
        }

        insertarMesasSemilla();
    }

    /**
     * Mesas iniciales.
     * Los identificadores de materia son ejemplos:
     * 1 Civil, 2 Penal, 3 Familiar, 4 Mercantil, 5 Laboral.
     */
    private void insertarMesasSemilla() throws SQLException {

        String sql = """
            INSERT IGNORE INTO oficialia_mesas
            (id_materia, id_juzgado, nombre_mesa, carga_actual, disponible)
            VALUES (?, ?, ?, 0, TRUE)
            """;

        String[][] materias = {
                {"1", "Mesa Civil 1"},
                {"1", "Mesa Civil 2"},
                {"2", "Mesa Penal 1"},
                {"2", "Mesa Penal 2"},
                {"3", "Mesa Familiar 1"},
                {"3", "Mesa Familiar 2"},
                {"4", "Mesa Mercantil 1"},
                {"4", "Mesa Mercantil 2"},
                {"5", "Mesa Laboral 1"},
                {"5", "Mesa Laboral 2"}
        };

        try (Connection conn =
                     ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            for (String[] materia : materias) {
                ps.setInt(1, Integer.parseInt(materia[0]));
                ps.setInt(2, 1);
                ps.setString(3, materia[1]);
                ps.addBatch();
            }

            ps.executeBatch();
        }
    }

    /**
     * Consulta las mesas de una materia y un juzgado.
     */
    public List<MesaOficialia> consultarMesas(
            int idMateria, int idJuzgado) throws SQLException {

        List<MesaOficialia> mesas = new ArrayList<>();

        String sql = """
            SELECT id_mesa, id_materia, id_juzgado,
                   nombre_mesa, carga_actual, disponible
            FROM oficialia_mesas
            WHERE id_materia = ?
              AND id_juzgado = ?
              AND disponible = TRUE
            ORDER BY carga_actual ASC, id_mesa ASC
            """;

        try (Connection conn =
                     ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, idMateria);
            ps.setInt(2, idJuzgado);

            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {
                    MesaOficialia mesa = new MesaOficialia();

                    mesa.setIdMesa(rs.getInt("id_mesa"));
                    mesa.setIdMateria(rs.getInt("id_materia"));
                    mesa.setIdJuzgado(rs.getInt("id_juzgado"));
                    mesa.setNombreMesa(rs.getString("nombre_mesa"));
                    mesa.setCargaActual(rs.getInt("carga_actual"));
                    mesa.setDisponible(rs.getBoolean("disponible"));

                    mesas.add(mesa);
                }
            }
        }

        return mesas;
    }

    /**
     * Asigna automáticamente una mesa y registra el expediente.
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

        String sqlMesas = """
            SELECT id_mesa, nombre_mesa, carga_actual
            FROM oficialia_mesas
            WHERE id_materia = ?
              AND id_juzgado = ?
              AND disponible = TRUE
            ORDER BY carga_actual ASC, id_mesa ASC
            LIMIT 1
            FOR UPDATE
            """;

        String sqlInsertar = """
            INSERT INTO oficialia_expedientes
            (id_juzgado, id_mesa, id_materia,
             direccion_completa, descripcion_hechos, partes_json)
            VALUES (?, ?, ?, ?, ?, ?)
            """;

        String sqlActualizar = """
            UPDATE oficialia_mesas
            SET carga_actual = carga_actual + 1
            WHERE id_mesa = ?
              AND id_juzgado = ?
            """;

        String sqlFolio = """
            UPDATE oficialia_expedientes
            SET folio = ?
            WHERE id_expediente = ?
              AND id_juzgado = ?
            """;

        try (Connection conn =
                     ConexionMySQL.getConnection()) {

            conn.setAutoCommit(false);

            try {

                int idMesa;
                String nombreMesa;

                // 1. Bloquear y seleccionar la mesa menos ocupada.
                try (PreparedStatement ps =
                             conn.prepareStatement(sqlMesas)) {

                    ps.setInt(1, idMateria);
                    ps.setInt(2, idJuzgado);

                    try (ResultSet rs = ps.executeQuery()) {

                        if (!rs.next()) {
                            throw new SQLException(
                                    "No existen mesas disponibles para esta materia.");
                        }

                        idMesa = rs.getInt("id_mesa");
                        nombreMesa = rs.getString("nombre_mesa");
                    }
                }

                // 2. Insertar expediente.
                long idExpediente;

                try (PreparedStatement ps =
                             conn.prepareStatement(
                                     sqlInsertar,
                                     Statement.RETURN_GENERATED_KEYS)) {

                    ps.setInt(1, idJuzgado);
                    ps.setInt(2, idMesa);
                    ps.setInt(3, idMateria);
                    ps.setString(4, direccion);
                    ps.setString(5, descripcion);
                    ps.setString(6, partesJson);

                    ps.executeUpdate();

                    try (ResultSet rs = ps.getGeneratedKeys()) {

                        if (!rs.next()) {
                            throw new SQLException(
                                    "No fue posible generar el expediente.");
                        }

                        idExpediente = rs.getLong(1);
                    }
                }

                // 3. Generar folio.
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

                // 4. Incrementar carga de la mesa.
                try (PreparedStatement ps =
                             conn.prepareStatement(sqlActualizar)) {

                    ps.setInt(1, idMesa);
                    ps.setInt(2, idJuzgado);

                    if (ps.executeUpdate() != 1) {
                        throw new SQLException(
                                "No fue posible actualizar la carga de la mesa.");
                    }
                }

                conn.commit();

                resultado.put("ok", true);
                resultado.put("folio", folio);
                resultado.put("idExpediente", idExpediente);
                resultado.put("idMesa", idMesa);
                resultado.put("nombreMesa", nombreMesa);
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
            SELECT e.folio, e.id_materia, e.id_mesa,
                   m.nombre_mesa, m.carga_actual,
                   e.direccion_completa,
                   e.fecha_registro
            FROM oficialia_expedientes e
            INNER JOIN oficialia_mesas m
                ON e.id_mesa = m.id_mesa
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
                    fila.put("idMesa", rs.getInt("id_mesa"));
                    fila.put("nombreMesa", rs.getString("nombre_mesa"));
                    fila.put("cargaActual", rs.getInt("carga_actual"));
                    fila.put("direccion",
                            rs.getString("direccion_completa"));
                    fila.put("fecha",
                            rs.getTimestamp("fecha_registro").toString());

                    expedientes.add(fila);
                }
            }
        }

        return expedientes;
    }
}
