
package mx.edu.utez.sij.dao.oficialia;

import com.google.gson.Gson;
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

    private static final Gson GSON = new Gson();

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
                id_usuario INT NULL,
                id_mesa INT NULL,
                estatus VARCHAR(40) NOT NULL DEFAULT 'REGISTRADO',
                activo TINYINT(1) NOT NULL DEFAULT 1,
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
     * Registra el expediente y lo asigna a la mesa activa con menor carga.
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
             direccion_completa, descripcion_hechos, partes_json,
             id_usuario, id_mesa, estatus)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'REGISTRADO')
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
                ValidacionDatosOficialia.validarCaptura(
                        partes, direccion, descripcion);
                String partesJsonValidado = GSON.toJson(partes);
                int idMesa = obtenerMesaMenorCarga(
                        conn, idMateria, idJuzgado);
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
                    ps.setString(5, partesJsonValidado);
                    ps.setInt(6, idUsuario);
                    ps.setInt(7, idMesa);

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

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE oficialia_mesas "
                                + "SET carga_actual = carga_actual + 1 "
                                + "WHERE id_mesa = ? AND id_juzgado = ?")) {
                    ps.setInt(1, idMesa);
                    ps.setInt(2, idJuzgado);
                    if (ps.executeUpdate() != 1) {
                        throw new SQLException(
                                "No se pudo actualizar la carga de la mesa asignada.");
                    }
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

    private int obtenerMesaMenorCarga(
            Connection conn,
            int idMateria,
            int idJuzgado) throws SQLException {
        String sql = """
            SELECT id_mesa
            FROM oficialia_mesas
            WHERE id_materia = ? AND id_juzgado = ? AND activo = 1
            ORDER BY carga_actual ASC, id_mesa ASC
            LIMIT 1
            FOR UPDATE
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idMateria);
            ps.setInt(2, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                            "No hay mesas activas para la materia y juzgado seleccionados.");
                }
                return rs.getInt("id_mesa");
            }
        }
    }

    /**
     * Consulta expedientes del juzgado de la sesión.
     */
    public List<Map<String, Object>> consultarExpedientes(
            int idJuzgado,
            boolean incluirContacto) throws SQLException {
        return consultarExpedientes(idJuzgado, incluirContacto, false);
    }

    public List<Map<String, Object>> consultarExpedientes(
            int idJuzgado,
            boolean incluirContacto,
            boolean incluirArchivados) throws SQLException {

        List<Map<String, Object>> expedientes = new ArrayList<>();

        String sql = """
            SELECT e.id_registro, e.id_mesa, e.id_usuario, e.folio,
                   e.id_materia, ma.nombre_materia,
                   e.direccion_completa, e.descripcion_hechos,
                   e.partes_json, e.fecha_registro, e.estatus,
                   CONCAT(me.clave_mesa, ' - ', me.nombre_mesa) AS mesa,
                   u.nombre_completo AS capturista, e.activo
            FROM oficialia_registros e
            INNER JOIN materias ma
                ON e.id_materia = ma.id_materia
            LEFT JOIN oficialia_mesas me ON me.id_mesa = e.id_mesa
            LEFT JOIN usuarios u ON u.id_usuario = e.id_usuario
            WHERE e.id_juzgado = ? AND (? = 1 OR e.activo = 1)
            ORDER BY e.fecha_registro DESC
            """;

        try (Connection conn =
                     ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setInt(1, idJuzgado);
            ps.setBoolean(2, incluirArchivados);

            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    Map<String, Object> fila =
                            new HashMap<String, Object>();

                    fila.put("fuente", "REGISTRO");
                    fila.put("idRegistro", rs.getLong("id_registro"));
                    fila.put("idMesa", rs.getObject("id_mesa"));
                    fila.put("idCapturista", rs.getObject("id_usuario"));
                    fila.put("capturista", rs.getString("capturista"));
                    fila.put("folio", rs.getString("folio"));
                    fila.put("idMateria", rs.getInt("id_materia"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("mesa", rs.getString("mesa"));
                    fila.put("direccion",
                            rs.getString("direccion_completa"));
                    fila.put("descripcion",
                            rs.getString("descripcion_hechos"));
                    String partesJson = rs.getString("partes_json");
                    fila.put("partesJson", incluirContacto
                            ? partesJson : ocultarContacto(partesJson));
                    fila.put("fecha",
                            fecha(rs.getTimestamp("fecha_registro")));
                    fila.put("estatus", rs.getString("estatus"));
                    fila.put("activo", rs.getBoolean("activo"));

                    expedientes.add(fila);
                }
            }
            consultarExpedientesLegadosAdmin(
                    conn, idJuzgado, expedientes, incluirArchivados);
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

    public List<Map<String, Object>> consultarExpedientesSuperAdmin(
            int idJuzgado) throws SQLException {
        List<Map<String, Object>> expedientes = new ArrayList<>();
        String sql = """
            SELECT e.folio, e.id_materia, ma.nombre_materia,
                   e.direccion_completa, e.descripcion_hechos,
                   e.partes_json, e.fecha_registro
            FROM oficialia_registros e
            INNER JOIN materias ma ON ma.id_materia = e.id_materia
            WHERE e.id_juzgado = ?
            ORDER BY e.fecha_registro DESC
            """;
        try (Connection conn = ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> fila = new HashMap<>();
                    fila.put("folio", rs.getString("folio"));
                    fila.put("idMateria", rs.getInt("id_materia"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("direccion", rs.getString("direccion_completa"));
                    fila.put("descripcion", rs.getString("descripcion_hechos"));
                    fila.put("partesJson",
                            ocultarContacto(rs.getString("partes_json")));
                    fila.put("fecha", fecha(rs.getTimestamp("fecha_registro")));
                    expedientes.add(fila);
                }
            }
            Map<String, Integer> admitidos =
                    DuplicidadOficioDAO.consultarRegistrosAdmitidos(
                            conn, idJuzgado);
            for (Map<String, Object> expediente : expedientes) {
                expediente.put("cantidadSimilitudes", admitidos.getOrDefault(
                        String.valueOf(expediente.get("folio")), 0));
            }
        }
        return expedientes;
    }

    public List<Map<String, Object>> consultarBandejaCapturista(
            int idJuzgado) throws SQLException {
        List<Map<String, Object>> expedientes = new ArrayList<>();
        String sqlNuevos = """
            SELECT e.id_registro, e.folio, ma.nombre_materia,
                   CONCAT(me.clave_mesa, ' - ', me.nombre_mesa) AS mesa,
                   e.fecha_registro, e.estatus
            FROM oficialia_registros e
            INNER JOIN materias ma ON ma.id_materia = e.id_materia
            LEFT JOIN oficialia_mesas me ON me.id_mesa = e.id_mesa
            WHERE e.id_juzgado = ? AND e.activo = 1
            ORDER BY e.fecha_registro DESC
            """;
        try (Connection conn = ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sqlNuevos)) {
            ps.setInt(1, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> fila = new HashMap<>();
                    fila.put("fuente", "REGISTRO");
                    fila.put("idRegistro", rs.getLong("id_registro"));
                    fila.put("folio", rs.getString("folio"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("mesa", rs.getString("mesa"));
                    fila.put("fecha", fecha(rs.getTimestamp("fecha_registro")));
                    fila.put("estatus", rs.getString("estatus"));
                    expedientes.add(fila);
                }
            }
            consultarExpedientesLegadosBandeja(conn, idJuzgado, expedientes);
        }
        expedientes.sort(Comparator.comparing(
                expediente -> String.valueOf(expediente.get("fecha")),
                Comparator.reverseOrder()));
        return expedientes;
    }

    private void consultarExpedientesLegadosBandeja(
            Connection conn,
            int idJuzgado,
            List<Map<String, Object>> expedientes) throws SQLException {
        if (!tieneTabla(conn, "oficialia_expedientes")) {
            return;
        }
        String sql = """
            SELECT e.id_expediente, e.numero_expediente, m.nombre_materia,
                   CONCAT(me.clave_mesa, ' - ', me.nombre_mesa) AS mesa,
                   e.fecha_registro, e.estatus
            FROM oficialia_expedientes e
            INNER JOIN materias m ON m.id_materia = e.id_materia
            LEFT JOIN oficialia_mesas me ON me.id_mesa = e.id_mesa
            WHERE e.id_juzgado = ? AND e.activo = 1
            ORDER BY e.fecha_registro DESC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> fila = new HashMap<>();
                    fila.put("fuente", "LEGADO");
                    fila.put("idExpediente", rs.getInt("id_expediente"));
                    fila.put("folio", rs.getString("numero_expediente"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("mesa", rs.getString("mesa"));
                    fila.put("fecha", fecha(rs.getTimestamp("fecha_registro")));
                    fila.put("estatus", rs.getString("estatus"));
                    expedientes.add(fila);
                }
            }
        }
    }

    private void consultarExpedientesLegadosAdmin(
            Connection conn,
            int idJuzgado,
            List<Map<String, Object>> expedientes,
            boolean incluirArchivados) throws SQLException {
        if (!tieneTabla(conn, "oficialia_expedientes")) {
            return;
        }
        String sql = """
            SELECT e.id_expediente, e.numero_expediente, e.id_materia,
                   m.nombre_materia, e.id_mesa, e.partes_resumen,
                   e.tipo_parte, e.estatus, e.fecha_registro,
                   CONCAT(me.clave_mesa, ' - ', me.nombre_mesa) AS mesa,
                   e.activo
            FROM oficialia_expedientes e
            INNER JOIN materias m ON m.id_materia = e.id_materia
            LEFT JOIN oficialia_mesas me ON me.id_mesa = e.id_mesa
            WHERE e.id_juzgado = ? AND (? = 1 OR e.activo = 1)
            ORDER BY e.fecha_registro DESC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            ps.setBoolean(2, incluirArchivados);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> fila = new HashMap<>();
                    fila.put("fuente", "LEGADO");
                    fila.put("idExpediente", rs.getInt("id_expediente"));
                    fila.put("folio", rs.getString("numero_expediente"));
                    fila.put("idMateria", rs.getInt("id_materia"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("idMesa", rs.getInt("id_mesa"));
                    fila.put("mesa", rs.getString("mesa"));
                    fila.put("partesJson", rs.getString("partes_resumen"));
                    fila.put("tipoParte", rs.getString("tipo_parte"));
                    fila.put("estatus", rs.getString("estatus"));
                    fila.put("activo", rs.getBoolean("activo"));
                    fila.put("fecha", fecha(rs.getTimestamp("fecha_registro")));
                    fila.put("direccion", "");
                    fila.put("descripcion", "");
                    expedientes.add(fila);
                }
            }
        }
    }

        public List<Map<String, Object>> consultarMesas(
                int idJuzgado,
                int idMateria) throws SQLException {
            List<Map<String, Object>> mesas = new ArrayList<>();
            String sql = """
                SELECT id_mesa, clave_mesa, nombre_mesa
                FROM oficialia_mesas
                WHERE id_juzgado = ? AND id_materia = ? AND activo = 1
                ORDER BY carga_actual, id_mesa
                """;
            try (Connection conn = ConexionMySQL.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, idJuzgado);
                ps.setInt(2, idMateria);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> mesa = new HashMap<>();
                        mesa.put("idMesa", rs.getInt("id_mesa"));
                        mesa.put("nombre", rs.getString("clave_mesa") + " - "
                                + rs.getString("nombre_mesa"));
                        mesas.add(mesa);
                    }
                }
            }
            return mesas;
        }

        public Map<String, Object> consultarContactoDomicilio(
                int idJuzgado,
                long idRegistro) throws SQLException {
            String sql = """
                SELECT e.partes_json
                FROM oficialia_registros e
                WHERE e.id_registro = ? AND e.id_juzgado = ? AND e.activo = 1
                """;
            try (Connection conn = ConexionMySQL.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, idRegistro);
                ps.setInt(2, idJuzgado);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return null;
                    }
                    JsonObject partes = parsearPartes(rs.getString("partes_json"));
                    JsonObject promovente = objeto(partes, "promovente");
                    JsonObject contacto = objeto(promovente, "contacto");
                    JsonObject direccion = objeto(partes, "direccionComponentes");
                    Map<String, Object> datos = new HashMap<>();
                    datos.put("telefono", texto(contacto, "telefono"));
                    datos.put("correoElectronico",
                            texto(contacto, "correoElectronico"));
                    datos.put("direccionComponentes", direccion.deepCopy());
                    return datos;
                }
            }
        }

        public void actualizarContactoDomicilio(
                int idJuzgado,
                int idUsuario,
                long idRegistro,
                String telefono,
                String correo,
                String direccionComponentesJson) throws SQLException {
            if (idRegistro <= 0) {
                throw new IllegalArgumentException("El expediente no es válido.");
            }
            JsonObject componentes = parsearPartes(direccionComponentesJson);
            String direccion = formatearDireccion(componentes);
            String telefonoNormalizado =
                    ValidacionDatosOficialia.normalizarTelefono(telefono);
            String correoNormalizado = correo == null ? ""
                    : correo.trim().toLowerCase(java.util.Locale.ROOT);
            if ((!valor(telefono).isBlank() && telefonoNormalizado.isBlank())
                    || (!correoNormalizado.isBlank()
                    && !ValidacionDatosOficialia.correoValido(correoNormalizado))
                    || (telefonoNormalizado.isBlank()
                    && correoNormalizado.isBlank())) {
                throw new IllegalArgumentException(
                        "Proporciona un teléfono válido o un correo electrónico válido.");
            }

            try (Connection conn = ConexionMySQL.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String select = """
                        SELECT direccion_completa, descripcion_hechos, partes_json
                        FROM oficialia_registros
                        WHERE id_registro = ? AND id_juzgado = ? AND activo = 1
                        FOR UPDATE
                        """;
                    String direccionAnterior;
                    String descripcion;
                    JsonObject partesAnterior;
                    try (PreparedStatement ps = conn.prepareStatement(select)) {
                        ps.setLong(1, idRegistro);
                        ps.setInt(2, idJuzgado);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException(
                                        "El expediente no existe en su juzgado o está archivado.");
                            }
                            direccionAnterior =
                                    rs.getString("direccion_completa");
                            descripcion = rs.getString("descripcion_hechos");
                            partesAnterior =
                                    parsearPartes(rs.getString("partes_json"));
                        }
                    }
                    JsonObject partes = partesAnterior.deepCopy();
                    JsonObject promovente = objeto(partes, "promovente");
                    JsonObject contacto = objeto(promovente, "contacto");
                    contacto.addProperty("telefono", telefonoNormalizado);
                    contacto.addProperty("correoElectronico", correoNormalizado);
                    partes.add("direccionComponentes", componentes);
                    ValidacionDatosOficialia.validarCaptura(
                            partes, direccion, descripcion);

                    try (PreparedStatement ps = conn.prepareStatement("""
                            UPDATE oficialia_registros
                            SET direccion_completa = ?, partes_json = ?
                            WHERE id_registro = ? AND id_juzgado = ? AND activo = 1
                            """)) {
                        ps.setString(1, direccion);
                        ps.setString(2, GSON.toJson(partes));
                        ps.setLong(3, idRegistro);
                        ps.setInt(4, idJuzgado);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException(
                                    "No se pudo actualizar el expediente.");
                        }
                    }

                    JsonObject contactoAnterior =
                            objeto(objeto(partesAnterior, "promovente"), "contacto");
                    registrarAuditoria(conn, idRegistro, idUsuario,
                            "OFICIALIA_REGISTRO", "telefono",
                            texto(contactoAnterior, "telefono"),
                            telefonoNormalizado);
                    registrarAuditoria(conn, idRegistro, idUsuario,
                            "OFICIALIA_REGISTRO", "correoElectronico",
                            texto(contactoAnterior, "correoElectronico"),
                            correoNormalizado);
                    auditarComponentesDireccion(conn, idRegistro, idUsuario,
                            "OFICIALIA_REGISTRO",
                            objeto(partesAnterior, "direccionComponentes"),
                            componentes);
                    conn.commit();
                } catch (SQLException | IllegalArgumentException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }

        public void actualizarExpedienteAdmin(
                int idJuzgado,
                int idUsuario,
                String fuente,
                long id,
                int idMateria,
                int idMesa,
                String estatus,
                String direccion,
                String descripcion,
                String partesJson,
                String partesResumen,
                String tipoParte) throws SQLException {
            if (id <= 0 || idMateria <= 0 || idMesa <= 0
                    || estatus == null || estatus.isBlank()
                    || !estatus.trim().matches(
                    "(?U)[\\p{L}\\p{M}0-9 _-]{1,40}")) {
                throw new IllegalArgumentException(
                        "Revisa la materia, mesa y estatus del expediente.");
            }
            try (Connection conn = ConexionMySQL.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    validarMesa(conn, idJuzgado, idMateria, idMesa);
                    if ("REGISTRO".equals(fuente)) {
                        actualizarRegistroNuevo(conn, idJuzgado, idUsuario, id,
                                idMateria, idMesa, estatus, direccion, descripcion,
                                partesJson);
                    } else if ("LEGADO".equals(fuente)) {
                        actualizarRegistroLegado(conn, idJuzgado, idUsuario, id,
                                idMateria, idMesa, estatus, partesResumen, tipoParte);
                    } else {
                        throw new IllegalArgumentException(
                                "La fuente del expediente no es válida.");
                    }
                    conn.commit();
                } catch (SQLException | IllegalArgumentException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }

        private void actualizarRegistroNuevo(
                Connection conn,
                int idJuzgado,
                int idUsuario,
                long id,
                int idMateria,
                int idMesa,
                String estatus,
                String direccion,
                String descripcion,
                String partesJson) throws SQLException {
            String oldFolio;
            int oldMateria;
            Integer oldMesa;
            String oldEstatus;
            String oldDireccion;
            String oldDescripcion;
            String oldPartes;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT folio, id_materia, id_mesa, estatus,
                           direccion_completa, descripcion_hechos, partes_json
                    FROM oficialia_registros
                    WHERE id_registro = ? AND id_juzgado = ? AND activo = 1
                    FOR UPDATE
                    """)) {
                ps.setLong(1, id);
                ps.setInt(2, idJuzgado);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException(
                                "El expediente no existe en su juzgado o está archivado.");
                    }
                    oldFolio = rs.getString("folio");
                    oldMateria = rs.getInt("id_materia");
                    oldMesa = (Integer) rs.getObject("id_mesa");
                    oldEstatus = rs.getString("estatus");
                    oldDireccion = rs.getString("direccion_completa");
                    oldDescripcion = rs.getString("descripcion_hechos");
                    oldPartes = rs.getString("partes_json");
                }
            }
            JsonObject partes = parsearPartes(partesJson);
            validarMateriaPartes(partes, idMateria);
            ValidacionDatosOficialia.validarCaptura(
                    partes, direccion, descripcion);
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE oficialia_registros
                    SET id_materia = ?, id_mesa = ?, estatus = ?,
                        direccion_completa = ?, descripcion_hechos = ?,
                        partes_json = ?
                    WHERE id_registro = ? AND id_juzgado = ? AND activo = 1
                    """)) {
                ps.setInt(1, idMateria);
                ps.setInt(2, idMesa);
                ps.setString(3, estatus.trim());
                ps.setString(4, direccion.trim());
                ps.setString(5, descripcion.trim());
                ps.setString(6, GSON.toJson(partes));
                ps.setLong(7, id);
                ps.setInt(8, idJuzgado);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("No se pudo actualizar el expediente.");
                }
            }
            ajustarCargasMesas(conn, idJuzgado, oldMesa, idMesa);
            auditarCambiosAdmin(conn, id, idUsuario, "OFICIALIA_REGISTRO",
                    oldFolio, oldMateria, oldMesa, oldEstatus,
                    oldDireccion, oldDescripcion, oldPartes,
                    oldFolio, idMateria, idMesa, estatus,
                    direccion, descripcion, GSON.toJson(partes));
        }

        private void actualizarRegistroLegado(
                Connection conn,
                int idJuzgado,
                int idUsuario,
                long id,
                int idMateria,
                int idMesa,
                String estatus,
                String partesResumen,
                String tipoParte) throws SQLException {
            String folio;
            int oldMateria;
            int oldMesa;
            String oldEstatus;
            String oldPartes;
            String oldTipo;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT numero_expediente, id_materia, id_mesa, estatus,
                           partes_resumen, tipo_parte
                    FROM oficialia_expedientes
                    WHERE id_expediente = ? AND id_juzgado = ? AND activo = 1
                    FOR UPDATE
                    """)) {
                ps.setLong(1, id);
                ps.setInt(2, idJuzgado);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new IllegalArgumentException(
                                "El expediente legado no existe en su juzgado o está archivado.");
                    }
                    folio = rs.getString("numero_expediente");
                    oldMateria = rs.getInt("id_materia");
                    oldMesa = rs.getInt("id_mesa");
                    oldEstatus = rs.getString("estatus");
                    oldPartes = rs.getString("partes_resumen");
                    oldTipo = rs.getString("tipo_parte");
                }
            }
            if (partesResumen == null || partesResumen.isBlank()
                    || partesResumen.length() > 255 || tipoParte == null
                    || tipoParte.isBlank() || tipoParte.length() > 60) {
                throw new IllegalArgumentException(
                        "Los datos del expediente legado no son válidos.");
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE oficialia_expedientes
                    SET id_materia = ?, id_mesa = ?, estatus = ?,
                        partes_resumen = ?, tipo_parte = ?
                    WHERE id_expediente = ? AND id_juzgado = ? AND activo = 1
                    """)) {
                ps.setInt(1, idMateria);
                ps.setInt(2, idMesa);
                ps.setString(3, estatus.trim());
                ps.setString(4, partesResumen.trim());
                ps.setString(5, tipoParte.trim());
                ps.setLong(6, id);
                ps.setInt(7, idJuzgado);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException("No se pudo actualizar el expediente legado.");
                }
            }
            ajustarCargasMesas(conn, idJuzgado, oldMesa, idMesa);
            registrarAuditoria(conn, id, idUsuario, "OFICIALIA_LEGADO",
                    "materia", String.valueOf(oldMateria), String.valueOf(idMateria));
            registrarAuditoria(conn, id, idUsuario, "OFICIALIA_LEGADO",
                    "mesa", String.valueOf(oldMesa), String.valueOf(idMesa));
            registrarAuditoria(conn, id, idUsuario, "OFICIALIA_LEGADO",
                    "estatus", oldEstatus, estatus.trim());
            registrarAuditoria(conn, id, idUsuario, "OFICIALIA_LEGADO",
                    "partes_resumen", oldPartes, partesResumen.trim());
            registrarAuditoria(conn, id, idUsuario, "OFICIALIA_LEGADO",
                    "tipo_parte", oldTipo, tipoParte.trim());
        }

        private void auditarCambiosAdmin(
                Connection conn,
                long id,
                int idUsuario,
                String modulo,
                String oldFolio,
                int oldMateria,
                Integer oldMesa,
                String oldEstatus,
                String oldDireccion,
                String oldDescripcion,
                String oldPartes,
                String folio,
                int materia,
                int mesa,
                String estatus,
                String direccion,
                String descripcion,
                String partes) throws SQLException {
            registrarAuditoria(conn, id, idUsuario, modulo, "materia",
                    String.valueOf(oldMateria), String.valueOf(materia));
            registrarAuditoria(conn, id, idUsuario, modulo, "mesa",
                    String.valueOf(oldMesa), String.valueOf(mesa));
            registrarAuditoria(conn, id, idUsuario, modulo, "estatus",
                    oldEstatus, estatus.trim());
            registrarAuditoria(conn, id, idUsuario, modulo, "direccion",
                    oldDireccion, direccion.trim());
            registrarAuditoria(conn, id, idUsuario, modulo, "descripcion",
                    oldDescripcion, descripcion.trim());
            registrarAuditoria(conn, id, idUsuario, modulo, "partes",
                    oldPartes, partes);
        }

        public void archivarExpedienteAdmin(
                int idJuzgado,
                int idUsuario,
                String fuente,
                long id) throws SQLException {
            String tabla;
            String idColumna;
            String modulo;
            if ("REGISTRO".equals(fuente)) {
                tabla = "oficialia_registros";
                idColumna = "id_registro";
                modulo = "OFICIALIA_REGISTRO";
            } else if ("LEGADO".equals(fuente)) {
                tabla = "oficialia_expedientes";
                idColumna = "id_expediente";
                modulo = "OFICIALIA_LEGADO";
            } else {
                throw new IllegalArgumentException(
                        "La fuente del expediente no es válida.");
            }
            try (Connection conn = ConexionMySQL.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String select = "SELECT "
                            + ("REGISTRO".equals(fuente) ? "folio" : "numero_expediente")
                            + ", id_mesa FROM " + tabla + " WHERE " + idColumna
                            + " = ? AND id_juzgado = ? AND activo = 1 FOR UPDATE";
                    String folio;
                    int idMesa;
                    try (PreparedStatement ps = conn.prepareStatement(select)) {
                        ps.setLong(1, id);
                        ps.setInt(2, idJuzgado);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException(
                                        "El expediente no existe en su juzgado o ya está archivado.");
                            }
                            folio = rs.getString(1);
                            idMesa = rs.getInt(2);
                        }
                    }
                    String update = "UPDATE " + tabla
                            + " SET activo = 0 WHERE " + idColumna
                            + " = ? AND id_juzgado = ? AND activo = 1";
                    try (PreparedStatement ps = conn.prepareStatement(update)) {
                        ps.setLong(1, id);
                        ps.setInt(2, idJuzgado);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException(
                                    "No se pudo archivar el expediente.");
                        }
                    }
                    ajustarCargasMesas(conn, idJuzgado, idMesa, null);
                    registrarAuditoria(conn, id, idUsuario, modulo,
                            "archivo", "activo", "archivado");
                    conn.commit();
                } catch (SQLException | IllegalArgumentException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }

        public void desarchivarExpedienteAdmin(
                int idJuzgado,
                int idUsuario,
                String fuente,
                long id) throws SQLException {
            String tabla;
            String idColumna;
            String modulo;
            if ("REGISTRO".equals(fuente)) {
                tabla = "oficialia_registros";
                idColumna = "id_registro";
                modulo = "OFICIALIA_REGISTRO";
            } else if ("LEGADO".equals(fuente)) {
                tabla = "oficialia_expedientes";
                idColumna = "id_expediente";
                modulo = "OFICIALIA_LEGADO";
            } else {
                throw new IllegalArgumentException(
                        "La fuente del expediente no es válida.");
            }
            if (id <= 0) {
                throw new IllegalArgumentException(
                        "El expediente no es válido.");
            }

            try (Connection conn = ConexionMySQL.getConnection()) {
                conn.setAutoCommit(false);
                try {
                    String select = "SELECT id_mesa FROM " + tabla
                            + " WHERE " + idColumna
                            + " = ? AND id_juzgado = ? AND activo = 0 FOR UPDATE";
                    Integer idMesa;
                    try (PreparedStatement ps = conn.prepareStatement(select)) {
                        ps.setLong(1, id);
                        ps.setInt(2, idJuzgado);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (!rs.next()) {
                                throw new IllegalArgumentException(
                                        "El expediente no está archivado o no pertenece a su juzgado.");
                            }
                            int mesa = rs.getInt("id_mesa");
                            idMesa = rs.wasNull() ? null : mesa;
                        }
                    }

                    String update = "UPDATE " + tabla + " SET activo = 1 WHERE "
                            + idColumna
                            + " = ? AND id_juzgado = ? AND activo = 0";
                    try (PreparedStatement ps = conn.prepareStatement(update)) {
                        ps.setLong(1, id);
                        ps.setInt(2, idJuzgado);
                        if (ps.executeUpdate() != 1) {
                            throw new SQLException(
                                    "No se pudo desarchivar el expediente.");
                        }
                    }

                    ajustarCargasMesas(conn, idJuzgado, null, idMesa);
                    registrarAuditoria(conn, id, idUsuario, modulo,
                            "archivo", "archivado", "activo");
                    conn.commit();
                } catch (SQLException | IllegalArgumentException e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(true);
                }
            }
        }

        public List<Map<String, Object>> consultarAuditoria(int idJuzgado)
                throws SQLException {
            List<Map<String, Object>> eventos = new ArrayList<>();
            String sql = """
                SELECT a.id_referencia, a.modulo_origen, a.campo_modificado,
                       a.valor_anterior, a.valor_nuevo, a.fecha_hora,
                       u.nombre_completo AS responsable,
                       COALESCE(r.folio, l.numero_expediente) AS folio
                FROM auditoria_historial a
                LEFT JOIN oficialia_registros r
                  ON a.modulo_origen = 'OFICIALIA_REGISTRO'
                 AND a.id_referencia = r.id_registro
                LEFT JOIN oficialia_expedientes l
                  ON a.modulo_origen = 'OFICIALIA_LEGADO'
                 AND a.id_referencia = l.id_expediente
                LEFT JOIN usuarios u
                  ON CAST(u.id_usuario AS CHAR) = a.usuario_responsable
                WHERE a.modulo_origen IN ('OFICIALIA_REGISTRO', 'OFICIALIA_LEGADO')
                  AND (r.id_juzgado = ? OR l.id_juzgado = ?)
                ORDER BY a.fecha_hora DESC, a.id_auditoria DESC
                """;
            try (Connection conn = ConexionMySQL.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, idJuzgado);
                ps.setInt(2, idJuzgado);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Map<String, Object> evento = new HashMap<>();
                        evento.put("folio", rs.getString("folio"));
                        evento.put("fuente", rs.getString("modulo_origen"));
                        evento.put("campo", rs.getString("campo_modificado"));
                        evento.put("valorAnterior", rs.getString("valor_anterior"));
                        evento.put("valorNuevo", rs.getString("valor_nuevo"));
                        evento.put("fecha", fecha(rs.getTimestamp("fecha_hora")));
                        evento.put("responsable", rs.getString("responsable"));
                        eventos.add(evento);
                    }
                }
            }
            return eventos;
        }

    private void validarMesa(
            Connection conn,
            int idJuzgado,
            int idMateria,
            int idMesa) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT 1 FROM oficialia_mesas
                WHERE id_mesa = ? AND id_juzgado = ?
                  AND id_materia = ? AND activo = 1
                """)) {
            ps.setInt(1, idMesa);
            ps.setInt(2, idJuzgado);
            ps.setInt(3, idMateria);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalArgumentException(
                            "La mesa debe estar activa y pertenecer al mismo juzgado y materia.");
                }
            }
        }
    }

    private void validarMateriaPartes(
            JsonObject partes,
            int idMateria) {
        JsonElement idMateriaJson = partes.get("idMateria");
        try {
            if (idMateriaJson == null || !idMateriaJson.isJsonPrimitive()
                    || idMateriaJson.getAsInt() != idMateria) {
                throw new IllegalArgumentException(
                        "La materia no coincide con los datos del expediente.");
            }
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "La materia no coincide con los datos del expediente.", e);
        }
    }

    private void ajustarCargasMesas(
            Connection conn,
            int idJuzgado,
            Integer mesaAnterior,
            Integer mesaNueva) throws SQLException {
        if (mesaAnterior != null && mesaAnterior.equals(mesaNueva)) {
            return;
        }
        if (mesaAnterior != null) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE oficialia_mesas
                    SET carga_actual = CASE WHEN carga_actual > 0
                        THEN carga_actual - 1 ELSE 0 END
                    WHERE id_mesa = ? AND id_juzgado = ?
                    """)) {
                ps.setInt(1, mesaAnterior);
                ps.setInt(2, idJuzgado);
                ps.executeUpdate();
            }
        }
        if (mesaNueva != null) {
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE oficialia_mesas
                    SET carga_actual = carga_actual + 1
                    WHERE id_mesa = ? AND id_juzgado = ?
                    """)) {
                ps.setInt(1, mesaNueva);
                ps.setInt(2, idJuzgado);
                if (ps.executeUpdate() != 1) {
                    throw new SQLException(
                            "No se pudo actualizar la carga de la mesa asignada.");
                }
            }
        }
    }

    private void auditarComponentesDireccion(
                Connection conn,
                long id,
                int idUsuario,
                String modulo,
                JsonObject anterior,
                JsonObject nuevo) throws SQLException {
            String[] campos = {"calle", "numeroExterior", "numeroInterior",
                    "colonia", "municipio", "estado", "codigoPostal"};
            for (String campo : campos) {
                registrarAuditoria(conn, id, idUsuario, modulo,
                        "direccion." + campo, texto(anterior, campo),
                        texto(nuevo, campo));
            }
        }

        private void registrarAuditoria(
                Connection conn,
                long id,
                int idUsuario,
                String modulo,
                String campo,
                String anterior,
                String nuevo) throws SQLException {
            String valorAnterior = anterior == null ? "" : anterior;
            String valorNuevo = nuevo == null ? "" : nuevo;
            if (valorAnterior.equals(valorNuevo)) {
                return;
            }
            try (PreparedStatement ps = conn.prepareStatement("""
                    INSERT INTO auditoria_historial
                        (id_referencia, usuario_responsable, modulo_origen,
                         campo_modificado, valor_anterior, valor_nuevo)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setLong(1, id);
                ps.setString(2, String.valueOf(idUsuario));
                ps.setString(3, modulo);
                ps.setString(4, campo);
                ps.setString(5, valorAnterior);
                ps.setString(6, valorNuevo);
                ps.executeUpdate();
            }
        }

        private String formatearDireccion(JsonObject direccion) {
            StringBuilder texto = new StringBuilder();
            agregarDireccion(texto, "Calle", texto(direccion, "calle"));
            agregarDireccion(texto, "No. exterior",
                    texto(direccion, "numeroExterior"));
            agregarDireccion(texto, "Interior",
                    texto(direccion, "numeroInterior"));
            agregarDireccion(texto, "Colonia", texto(direccion, "colonia"));
            agregarDireccion(texto, "Municipio", texto(direccion, "municipio"));
            agregarDireccion(texto, "Estado", texto(direccion, "estado"));
            agregarDireccion(texto, "C.P.", texto(direccion, "codigoPostal"));
            return texto.toString();
        }

        private void agregarDireccion(
                StringBuilder destino,
                String etiqueta,
                String valor) {
            if (valor == null || valor.isBlank()) {
                return;
            }
            if (!destino.isEmpty()) {
                destino.append(", ");
            }
            destino.append(etiqueta).append(' ').append(valor.trim());
        }

        private JsonObject objeto(JsonObject padre, String campo) {
            JsonElement elemento = padre.get(campo);
            if (elemento != null && elemento.isJsonObject()) {
                return elemento.getAsJsonObject();
            }
            JsonObject objeto = new JsonObject();
            padre.add(campo, objeto);
            return objeto;
        }

        private String texto(JsonObject padre, String campo) {
            JsonElement elemento = padre.get(campo);
            return elemento == null || !elemento.isJsonPrimitive()
                    ? "" : elemento.getAsString().trim();
    }

    private String ocultarContacto(String partesJson) throws SQLException {
        if (partesJson == null || partesJson.isBlank()) {
            return partesJson;
        }
        try {
            JsonElement elemento = JsonParser.parseString(partesJson);
            if (!elemento.isJsonObject()) {
                return partesJson;
            }
            JsonObject partes = elemento.getAsJsonObject().deepCopy();
            JsonElement promovente = partes.get("promovente");
            if (promovente != null && promovente.isJsonObject()) {
                JsonObject datosPromovente =
                        promovente.getAsJsonObject().deepCopy();
                datosPromovente.remove("contacto");
                partes.add("promovente", datosPromovente);
            }
            return GSON.toJson(partes);
        } catch (JsonParseException e) {
            throw new SQLException(
                    "No se pudieron proteger los datos de contacto.", e);
        }
    }

    public Map<String, Object> consultarHistorial(int idJuzgado)
            throws SQLException {
        List<Map<String, Object>> expedientes = new ArrayList<>();
        List<Map<String, Object>> intentos;

        try (Connection conn = ConexionMySQL.getConnection()) {
            String sqlNuevos = """
                SELECT e.id_registro, e.folio, m.nombre_materia,
                       e.direccion_completa, e.descripcion_hechos,
                       e.partes_json, e.fecha_registro, e.estatus,
                       e.id_mesa, e.id_usuario,
                       CONCAT(me.clave_mesa, ' - ', me.nombre_mesa) AS mesa,
                       u.nombre_completo AS capturista, e.activo
                FROM oficialia_registros e
                INNER JOIN materias m ON m.id_materia = e.id_materia
                LEFT JOIN oficialia_mesas me ON me.id_mesa = e.id_mesa
                LEFT JOIN usuarios u ON u.id_usuario = e.id_usuario
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
                        fila.put("idRegistro", rs.getLong("id_registro"));
                        fila.put("folio", rs.getString("folio"));
                        fila.put("materia", rs.getString("nombre_materia"));
                        fila.put("mesa", rs.getString("mesa"));
                        fila.put("idMesa", rs.getObject("id_mesa"));
                        fila.put("idCapturista", rs.getObject("id_usuario"));
                        fila.put("capturista", rs.getString("capturista"));
                        fila.put("direccion",
                                rs.getString("direccion_completa"));
                        fila.put("descripcion",
                                rs.getString("descripcion_hechos"));
                        fila.put("partesJson", rs.getString("partes_json"));
                        fila.put("fecha",
                                fecha(rs.getTimestamp("fecha_registro")));
                        fila.put("estatus", rs.getString("estatus"));
                        fila.put("activo", rs.getBoolean("activo"));
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
            SELECT e.id_expediente, e.numero_expediente, m.nombre_materia,
                   e.id_mesa, me.clave_mesa, me.nombre_mesa,
                   e.partes_resumen, e.tipo_parte, e.estatus,
                   e.fecha_registro, e.activo
            FROM oficialia_expedientes e
            INNER JOIN materias m ON m.id_materia = e.id_materia
            LEFT JOIN oficialia_mesas me ON me.id_mesa = e.id_mesa
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
                    fila.put("idExpediente", rs.getInt("id_expediente"));
                    fila.put("folio", rs.getString("numero_expediente"));
                    fila.put("materia", rs.getString("nombre_materia"));
                    fila.put("idMesa", rs.getInt("id_mesa"));
                    fila.put("mesa", rs.getString("clave_mesa") == null
                            ? "" : rs.getString("clave_mesa") + " - "
                            + rs.getString("nombre_mesa"));
                    fila.put("partesJson", rs.getString("partes_resumen"));
                    fila.put("partes", rs.getString("partes_resumen"));
                    fila.put("tipoParte", rs.getString("tipo_parte"));
                    fila.put("estatus", rs.getString("estatus"));
                    fila.put("activo", rs.getBoolean("activo"));
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
