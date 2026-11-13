
package mx.edu.utez.sij.dao.oficialia;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import mx.edu.utez.sij.core.config.ConexionMySQL;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;

import java.util.ArrayList;
import java.util.Comparator;
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
            int idUsuario,
            String direccion,
            String descripcion,
            String partesJson,
            String nombrePromoventeBusqueda,
            String curpRfcPromoventeBusqueda,
            boolean aceptarDuplicidad)
            throws SQLException, CoincidenciasException {

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
                JsonObject partes = parsearPartes(partesJson);
                boolean denunciaAnonima = partes.has("denunciaAnonima")
                        && partes.get("denunciaAnonima").isJsonPrimitive()
                        && partes.get("denunciaAnonima").getAsBoolean();
                String nombrePromovente = denunciaAnonima
                        ? valor(nombrePromoventeBusqueda)
                        : textoParte(partes, "promovente", "nombreCompleto");
                String curpRfcPromovente = denunciaAnonima
                        ? valor(curpRfcPromoventeBusqueda)
                        : textoParte(partes, "promovente", "curpRfc");
                String nombreDemandado = textoParte(partes, "demandado",
                        "nombreCompleto");
                String curpRfcDemandado = textoParte(partes, "demandado",
                        "curpRfc");
                List<Map<String, Object>> coincidencias =
                        new DuplicidadOficioDAO().buscarCoincidencias(
                                conn,
                                nombrePromovente,
                                curpRfcPromovente,
                                nombreDemandado,
                                curpRfcDemandado,
                                idMateria,
                                idJuzgado,
                                descripcion);
                if (!coincidencias.isEmpty() && !aceptarDuplicidad) {
                    throw new CoincidenciasException(coincidencias);
                }

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

                if (!coincidencias.isEmpty()) {
                    DuplicidadOficioDAO.registrarEvento(
                            conn,
                            idUsuario,
                            idJuzgado,
                            "DUPLICIDAD_ADMITIDA",
                            coincidencias,
                            folio,
                            idExpediente);
                }

                conn.commit();

                resultado.put("ok", true);
                resultado.put("folio", folio);
                resultado.put("idExpediente", idExpediente);
                resultado.put("mensaje",
                        "Oficio registrado correctamente.");

                return resultado;

            } catch (CoincidenciasException | IllegalArgumentException e) {
                conn.rollback();
                throw e;
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
                            fecha(rs.getTimestamp("fecha_registro")));

                    expedientes.add(fila);
                }
            }
            Map<String, Integer> admitidos =
                    DuplicidadOficioDAO.consultarRegistrosAdmitidos(
                            conn, idJuzgado);
            for (Map<String, Object> expediente : expedientes) {
                expediente.put(
                        "cantidadSimilitudes",
                        admitidos.getOrDefault(
                                String.valueOf(expediente.get("folio")), 0));
            }
        }

        return expedientes;
    }

    public Map<String, Object> consultarHistorial(int idJuzgado)
            throws SQLException {
        List<Map<String, Object>> expedientes = new ArrayList<>();
        List<Map<String, Object>> intentos;

        try (Connection conn = ConexionMySQL.getConnection()) {
            String sqlNuevos = """
                SELECT e.id_registro, e.folio, m.nombre_materia,
                       e.direccion_completa, e.descripcion_hechos,
                       e.partes_json, e.fecha_registro
                FROM oficialia_registros e
                INNER JOIN materias m ON m.id_materia = e.id_materia
                WHERE e.id_juzgado = ?
                ORDER BY e.fecha_registro DESC
                """;
            try (PreparedStatement ps = conn.prepareStatement(sqlNuevos)) {
                ps.setInt(1, idJuzgado);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> fila = new HashMap<>();
                        fila.put("tipo", "EXPEDIENTE");
                        fila.put("fuente", "REGISTRO");
                        fila.put("folio", rs.getString("folio"));
                        fila.put("materia", rs.getString("nombre_materia"));
                        fila.put("direccion",
                                rs.getString("direccion_completa"));
                        fila.put("descripcion",
                                rs.getString("descripcion_hechos"));
                        fila.put("partesJson", rs.getString("partes_json"));
                        fila.put("fecha",
                                fecha(rs.getTimestamp("fecha_registro")));
                        fila.put("estatus", "REGISTRADO");
                        expedientes.add(fila);
                    }
                }
            }

            if (tieneTabla(conn, "oficialia_expedientes")) {
                consultarExpedientesLegados(conn, idJuzgado, expedientes);
            }

            Map<String, Integer> admitidos =
                    DuplicidadOficioDAO.consultarRegistrosAdmitidos(
                            conn, idJuzgado);
            for (Map<String, Object> expediente : expedientes) {
                expediente.put(
                        "cantidadSimilitudes",
                        admitidos.getOrDefault(
                                String.valueOf(expediente.get("folio")), 0));
            }
            intentos = DuplicidadOficioDAO.consultarIntentos(conn, idJuzgado);
        }

        expedientes.sort(Comparator.comparing(
                expediente -> String.valueOf(expediente.get("fecha")),
                Comparator.reverseOrder()));
        Map<String, Object> historial = new HashMap<>();
        historial.put("expedientes", expedientes);
        historial.put("intentosDuplicidad", intentos);
        return historial;
    }

    private void consultarExpedientesLegados(
            Connection conn,
            int idJuzgado,
            List<Map<String, Object>> expedientes) throws SQLException {

        String sql = """
            SELECT e.numero_expediente, m.nombre_materia,
                   e.partes_resumen, e.tipo_parte, e.estatus,
                   e.fecha_registro
            FROM oficialia_expedientes e
            INNER JOIN materias m ON m.id_materia = e.id_materia
            WHERE e.id_juzgado = ?
            ORDER BY e.fecha_registro DESC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> fila = new HashMap<>();
                    fila.put("tipo", "EXPEDIENTE");
                    fila.put("fuente", "LEGADO");
                    fila.put("folio", rs.getString("numero_expediente"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("partesJson", rs.getString("partes_resumen"));
                    fila.put("tipoParte", rs.getString("tipo_parte"));
                    fila.put("estatus", rs.getString("estatus"));
                    fila.put("fecha",
                            fecha(rs.getTimestamp("fecha_registro")));
                    fila.put("direccion", "");
                    fila.put("descripcion", "");
                    expedientes.add(fila);
                }
            }
        }
    }

    private boolean tieneTabla(Connection conn, String tabla)
            throws SQLException {
        DatabaseMetaData metadata = conn.getMetaData();
        try (ResultSet tablas = metadata.getTables(
                conn.getCatalog(), null, tabla, new String[]{"TABLE"})) {
            while (tablas.next()) {
                if (tabla.equalsIgnoreCase(tablas.getString("TABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonObject parsearPartes(String partesJson) {
        try {
            JsonElement partes = JsonParser.parseString(partesJson);
            if (partes.isJsonObject()) {
                return partes.getAsJsonObject();
            }
        } catch (JsonParseException e) {
            throw new IllegalArgumentException(
                    "Los datos de las partes no tienen un formato válido.", e);
        }
        throw new IllegalArgumentException(
                "Los datos de las partes no tienen un formato válido.");
    }

    private String textoParte(
            JsonObject partes,
            String nombreParte,
            String campo) {
        JsonElement elemento = partes.get(nombreParte);
        if (elemento == null || !elemento.isJsonObject()) {
            return "";
        }
        JsonElement valor = elemento.getAsJsonObject().get(campo);
        return valor == null || !valor.isJsonPrimitive()
                ? "" : valor.getAsString().trim();
    }

    private String fecha(Timestamp fecha) {
        return fecha == null ? "" : fecha.toString();
    }

    private String valor(String texto) {
        return texto == null ? "" : texto.trim();
    }

    public static final class CoincidenciasException extends Exception {
        private final List<Map<String, Object>> coincidencias;

        public CoincidenciasException(
                List<Map<String, Object>> coincidencias) {
            super("Se detectaron posibles duplicidades antes de guardar.");
            this.coincidencias = coincidencias;
        }

        public List<Map<String, Object>> getCoincidencias() {
            return coincidencias;
        }
    }
}
