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
import java.sql.Timestamp;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DuplicidadOficioDAO {

    private static final Gson GSON = new Gson();
    private static final Set<String> PALABRAS_COMUNES = Set.of(
            "A", "AL", "CON", "DE", "DEL", "EL", "EN", "LA", "LAS",
            "LOS", "O", "PARA", "POR", "SA", "SC", "SAPI", "Y"
    );

    public List<Map<String, Object>> buscarCoincidencias(
            String nombrePromovente,
            String curpRfcPromovente,
            String nombreDemandado,
            String curpRfcDemandado,
            int idMateria,
            int idJuzgado,
            String descripcion) throws SQLException {

        try (Connection conn = ConexionMySQL.getConnection()) {
            return buscarCoincidencias(
                    conn, nombrePromovente, curpRfcPromovente,
                    nombreDemandado, curpRfcDemandado, idMateria,
                    idJuzgado, descripcion);
        }
    }

    List<Map<String, Object>> buscarCoincidencias(
            Connection conn,
            String nombrePromovente,
            String curpRfcPromovente,
            String nombreDemandado,
            String curpRfcDemandado,
            int idMateria,
            int idJuzgado,
            String descripcion) throws SQLException {

        Consulta consulta = new Consulta(
                nombrePromovente, curpRfcPromovente, nombreDemandado,
                curpRfcDemandado, idMateria, descripcion);
        List<Map<String, Object>> coincidencias = new ArrayList<>();

        for (Candidato candidato : consultarCandidatos(conn, idJuzgado)) {
            List<String> criterios = evaluar(consulta, candidato);
            if (!criterios.isEmpty()) {
                coincidencias.add(resumen(candidato, criterios));
            }
        }

        coincidencias.sort(Comparator
                .comparingInt((Map<String, Object> match) ->
                        (Integer) match.get("cantidadSimilitudes"))
                .reversed()
                .thenComparing(match -> String.valueOf(match.get("fecha")),
                        Comparator.reverseOrder()));
        return coincidencias;
    }

    public List<Map<String, Object>> buscarDetalles(
            String nombrePromovente,
            String curpRfcPromovente,
            String nombreDemandado,
            String curpRfcDemandado,
            int idMateria,
            int idJuzgado,
            String descripcion) throws SQLException {

        Consulta consulta = new Consulta(
                nombrePromovente, curpRfcPromovente, nombreDemandado,
                curpRfcDemandado, idMateria, descripcion);
        List<Map<String, Object>> detalles = new ArrayList<>();

        try (Connection conn = ConexionMySQL.getConnection()) {
            for (Candidato candidato : consultarCandidatos(conn, idJuzgado)) {
                List<String> criterios = evaluar(consulta, candidato);
                if (!criterios.isEmpty()) {
                    detalles.add(detalle(candidato, criterios));
                }
            }
        }
        return detalles;
    }

    public void registrarDecision(
            int idUsuario,
            int idJuzgado,
            String decision,
            String nombrePromovente,
            String curpRfcPromovente,
            String nombreDemandado,
            String curpRfcDemandado,
            int idMateria,
            String descripcion) throws SQLException {

        String accion = switch (decision) {
            case "CANCELAR" -> "DUPLICIDAD_CANCELADA";
            case "CONTINUAR" -> "DUPLICIDAD_CONTINUAR";
            default -> throw new IllegalArgumentException(
                    "La decisión de duplicidad no es válida.");
        };

        try (Connection conn = ConexionMySQL.getConnection()) {
            conn.setAutoCommit(false);
            try {
                List<Map<String, Object>> coincidencias =
                        buscarCoincidencias(
                                conn, nombrePromovente, curpRfcPromovente,
                                nombreDemandado, curpRfcDemandado,
                                idMateria, idJuzgado, descripcion);
                if (coincidencias.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Las coincidencias cambiaron; vuelva a verificar antes de continuar.");
                }
                registrarEvento(
                        conn, idUsuario, idJuzgado, accion,
                        coincidencias, null, null);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof SQLException sqlException) {
                    throw sqlException;
                }
                if (e instanceof IllegalArgumentException argumentException) {
                    throw argumentException;
                }
                throw new SQLException(
                        "No fue posible registrar la decisión de duplicidad.", e);
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    static void registrarEvento(
            Connection conn,
            int idUsuario,
            int idJuzgado,
            String accion,
            List<Map<String, Object>> coincidencias,
            String folioRegistrado,
            Long idRegistro) throws SQLException {

        validarTablaAuditoria(conn);

        Set<String> criterios = new LinkedHashSet<>();
        int cantidadMaxima = 0;
        for (Map<String, Object> coincidencia : coincidencias) {
            Object valores = coincidencia.get("similitudes");
            if (valores instanceof List<?> lista) {
                if (lista.size() > cantidadMaxima) {
                    criterios.clear();
                    cantidadMaxima = lista.size();
                    for (Object criterio : lista) {
                        criterios.add(String.valueOf(criterio));
                    }
                }
            }
        }

        Map<String, Object> evento = new HashMap<>();
        if (folioRegistrado != null) {
            evento.put("folioRegistrado", folioRegistrado);
        }
        evento.put("cantidadCoincidencias", coincidencias.size());
        evento.put("cantidadSimilitudes", cantidadMaxima);
        evento.put("criterios", criterios);
        String valorNuevo = GSON.toJson(evento);
        if (valorNuevo.length() > 255) {
            throw new SQLException(
                    "El detalle de duplicidad excede el espacio disponible en auditoria_historial.valor_nuevo.");
        }

        String sql = """
            INSERT INTO auditoria_historial
                (id_referencia, usuario_responsable, modulo_origen,
                 campo_modificado, valor_anterior, valor_nuevo)
            VALUES (?, ?, ?, ?, NULL, ?)
            """;

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (idRegistro != null && idRegistro <= Integer.MAX_VALUE) {
                ps.setInt(1, idRegistro.intValue());
            } else {
                ps.setNull(1, java.sql.Types.INTEGER);
            }
            ps.setString(2, Integer.toString(idUsuario));
            ps.setString(3, moduloJuzgado(idJuzgado));
            ps.setString(4, accion);
            ps.setString(5, valorNuevo);
            ps.executeUpdate();
        }
    }

    static Map<String, Integer> consultarRegistrosAdmitidos(
            Connection conn,
            int idJuzgado) throws SQLException {

        validarTablaAuditoria(conn);
        Map<String, Integer> cantidades = new HashMap<>();
        String sql = """
            SELECT valor_nuevo
            FROM auditoria_historial
            WHERE modulo_origen = ?
              AND campo_modificado = 'DUPLICIDAD_ADMITIDA'
            ORDER BY fecha_hora DESC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, moduloJuzgado(idJuzgado));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject evento = parsearEvento(rs.getString("valor_nuevo"));
                    String folio = texto(evento, "folioRegistrado");
                    if (!folio.isBlank() && !cantidades.containsKey(folio)) {
                        cantidades.put(
                                folio, numero(evento, "cantidadSimilitudes"));
                    }
                }
            }
        }
        return cantidades;
    }

    static List<Map<String, Object>> consultarIntentos(
            Connection conn,
            int idJuzgado) throws SQLException {

        validarTablaAuditoria(conn);
        List<Map<String, Object>> eventos = new ArrayList<>();
        String sql = """
            SELECT a.campo_modificado, a.valor_nuevo, a.fecha_hora,
                   u.nombre_completo
            FROM auditoria_historial a
            LEFT JOIN usuarios u
                ON CAST(u.id_usuario AS CHAR) = a.usuario_responsable
            WHERE a.modulo_origen = ?
              AND a.campo_modificado LIKE 'DUPLICIDAD_%'
            ORDER BY a.fecha_hora DESC
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, moduloJuzgado(idJuzgado));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject detalle = parsearEvento(rs.getString("valor_nuevo"));
                    Map<String, Object> evento = new HashMap<>();
                    evento.put("accion", rs.getString("campo_modificado"));
                    evento.put("folioRegistrado",
                            texto(detalle, "folioRegistrado"));
                    evento.put("cantidadCoincidencias",
                            numero(detalle, "cantidadCoincidencias"));
                    evento.put("cantidadSimilitudes",
                            numero(detalle, "cantidadSimilitudes"));
                    evento.put("criterios", textoCriterios(detalle));
                    evento.put("responsable", rs.getString("nombre_completo"));
                    Timestamp fecha = rs.getTimestamp("fecha_hora");
                    evento.put("fecha", fecha == null ? "" : fecha.toString());
                    eventos.add(evento);
                }
            }
        }
        return eventos;
    }

    private List<Candidato> consultarCandidatos(
            Connection conn,
            int idJuzgado) throws SQLException {

        List<Candidato> candidatos = new ArrayList<>();
        if (tieneColumna(conn, "oficialia_registros", "partes_json")) {
            consultarRegistrosNuevos(conn, idJuzgado, candidatos);
        }

        if (tieneColumna(conn, "oficialia_expedientes", "partes_resumen")) {
            consultarRegistrosLegados(conn, idJuzgado, candidatos);
        } else if (tieneColumna(conn, "oficialia_expedientes", "partes_json")) {
            consultarRegistrosJsonLegados(conn, idJuzgado, candidatos);
        }
        return candidatos;
    }

    private void consultarRegistrosNuevos(
            Connection conn,
            int idJuzgado,
            List<Candidato> candidatos) throws SQLException {

        String sql = """
            SELECT e.id_registro, e.folio, e.id_juzgado,
                   j.numero_juzgado, j.nombre_juzgado,
                   d.nombre_distrito, m.id_materia, m.nombre_materia,
                   e.direccion_completa, e.descripcion_hechos,
                   e.partes_json, e.fecha_registro
            FROM oficialia_registros e
            INNER JOIN juzgados j ON j.id_juzgado = e.id_juzgado
            INNER JOIN distritos d ON d.id_distrito = j.id_distrito
            INNER JOIN materias m ON m.id_materia = e.id_materia
            WHERE e.id_juzgado <> ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Candidato candidato = new Candidato();
                    candidato.fuente = "REGISTRO";
                    candidato.id = rs.getLong("id_registro");
                    candidato.folio = rs.getString("folio");
                    candidato.idMateria = rs.getInt("id_materia");
                    candidato.materia = rs.getString("nombre_materia");
                    candidato.juzgado = rs.getString("numero_juzgado")
                            + " - " + rs.getString("nombre_juzgado");
                    candidato.distrito = rs.getString("nombre_distrito");
                    candidato.direccion = rs.getString("direccion_completa");
                    candidato.descripcion = rs.getString("descripcion_hechos");
                    candidato.partesJson = rs.getString("partes_json");
                    candidato.fecha = fecha(rs.getTimestamp("fecha_registro"));
                    cargarPartes(candidato);
                    candidatos.add(candidato);
                }
            }
        }
    }

    private void consultarRegistrosLegados(
            Connection conn,
            int idJuzgado,
            List<Candidato> candidatos) throws SQLException {

        String sql = """
            SELECT e.id_expediente, e.numero_expediente, e.id_materia,
                   e.partes_resumen, e.tipo_parte, e.estatus,
                   e.fecha_registro, j.numero_juzgado, j.nombre_juzgado,
                   d.nombre_distrito, m.nombre_materia
            FROM oficialia_expedientes e
            INNER JOIN juzgados j ON j.id_juzgado = e.id_juzgado
            INNER JOIN distritos d ON d.id_distrito = j.id_distrito
            INNER JOIN materias m ON m.id_materia = e.id_materia
            WHERE e.id_juzgado <> ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Candidato candidato = new Candidato();
                    candidato.fuente = "LEGADO";
                    candidato.id = rs.getLong("id_expediente");
                    candidato.folio = rs.getString("numero_expediente");
                    candidato.idMateria = rs.getInt("id_materia");
                    candidato.materia = rs.getString("nombre_materia");
                    candidato.juzgado = rs.getString("numero_juzgado")
                            + " - " + rs.getString("nombre_juzgado");
                    candidato.distrito = rs.getString("nombre_distrito");
                    candidato.partesTexto = rs.getString("partes_resumen");
                    candidato.estatus = rs.getString("estatus");
                    candidato.fecha = fecha(rs.getTimestamp("fecha_registro"));
                    if (candidato.partesTexto != null
                            && !candidato.partesTexto.isBlank()) {
                        candidato.nombres.add(candidato.partesTexto);
                    }
                    candidatos.add(candidato);
                }
            }
        }
    }

    private void consultarRegistrosJsonLegados(
            Connection conn,
            int idJuzgado,
            List<Candidato> candidatos) throws SQLException {

        String sql = """
            SELECT e.id_expediente, e.numero_expediente, e.id_materia,
                   e.partes_json, e.fecha_registro,
                   j.numero_juzgado, j.nombre_juzgado,
                   d.nombre_distrito, m.nombre_materia
            FROM oficialia_expedientes e
            INNER JOIN juzgados j ON j.id_juzgado = e.id_juzgado
            INNER JOIN distritos d ON d.id_distrito = j.id_distrito
            INNER JOIN materias m ON m.id_materia = e.id_materia
            WHERE e.id_juzgado <> ?
            """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, idJuzgado);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Candidato candidato = new Candidato();
                    candidato.fuente = "LEGADO";
                    candidato.id = rs.getLong("id_expediente");
                    candidato.folio = rs.getString("numero_expediente");
                    candidato.idMateria = rs.getInt("id_materia");
                    candidato.materia = rs.getString("nombre_materia");
                    candidato.juzgado = rs.getString("numero_juzgado")
                            + " - " + rs.getString("nombre_juzgado");
                    candidato.distrito = rs.getString("nombre_distrito");
                    candidato.partesJson = rs.getString("partes_json");
                    candidato.fecha = fecha(rs.getTimestamp("fecha_registro"));
                    cargarPartes(candidato);
                    candidatos.add(candidato);
                }
            }
        }
    }

    private void cargarPartes(Candidato candidato) throws SQLException {
        if (candidato.partesJson == null || candidato.partesJson.isBlank()) {
            return;
        }
        try {
            JsonElement partes = JsonParser.parseString(candidato.partesJson);
            if (!partes.isJsonObject()) {
                throw new SQLException(
                        "Las partes del expediente " + candidato.folio
                                + " tienen un formato JSON no válido.");
            }
            JsonObject objeto = partes.getAsJsonObject();
            cargarParte(objeto, "promovente", candidato);
            cargarParte(objeto, "demandado", candidato);
        } catch (JsonParseException | IllegalStateException e) {
            throw new SQLException(
                    "No fue posible leer las partes del expediente "
                            + candidato.folio + ".", e);
        }
    }

    private void cargarParte(
            JsonObject objeto,
            String nombrePropiedad,
            Candidato candidato) {
        JsonElement elemento = objeto.get(nombrePropiedad);
        if (elemento == null || !elemento.isJsonObject()) {
            return;
        }
        JsonObject parte = elemento.getAsJsonObject();
        String nombre = texto(parte, "nombreCompleto");
        String rfc = texto(parte, "curpRfc");
        if (!nombre.isBlank()) {
            candidato.nombres.add(nombre);
        }
        if (!rfc.isBlank()) {
            candidato.rfc.add(normalizarRfc(rfc));
        }
    }

    private List<String> evaluar(Consulta consulta, Candidato candidato) {
        List<String> criterios = new ArrayList<>();
        if (coincideNombre(consulta.nombrePromovente, candidato.nombres)
                || coincideTextoLegado(
                        consulta.nombrePromovente, candidato.partesTexto)) {
            criterios.add("PROMOVENTE");
        }
        if (coincideNombre(consulta.nombreDemandado, candidato.nombres)
                || coincideTextoLegado(
                        consulta.nombreDemandado, candidato.partesTexto)) {
            criterios.add("DEMANDADO");
        }
        if (coincideRfc(consulta.curpRfcPromovente, candidato.rfc)
                || coincideRfc(consulta.curpRfcDemandado, candidato.rfc)) {
            criterios.add("RFC");
        }
        if (consulta.idMateria == candidato.idMateria) {
            criterios.add("MATERIA");
        }
        if (similitudDescripcion(
                consulta.descripcion, candidato.descripcion)) {
            criterios.add("DESCRIPCION");
        }

        if (!criterios.contains("PROMOVENTE")
                && !criterios.contains("DEMANDADO")
                && !criterios.contains("RFC")) {
            return Collections.emptyList();
        }
        return criterios;
    }

    private boolean coincideNombre(String nombre, List<String> nombres) {
        Set<String> buscados = tokens(nombre);
        if (buscados.isEmpty()) {
            return false;
        }
        for (String nombreCandidato : nombres) {
            Set<String> candidatos = tokens(nombreCandidato);
            for (String buscado : buscados) {
                for (String candidato : candidatos) {
                    if (tokensSimilares(buscado, candidato)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean coincideTextoLegado(String nombre, String partesTexto) {
        return partesTexto != null
                && coincideNombre(nombre, List.of(partesTexto));
    }

    private boolean coincideRfc(String rfc, List<String> rfcs) {
        String normalizado = normalizarRfc(rfc);
        return !normalizado.isBlank() && rfcs.contains(normalizado);
    }

    private boolean similitudDescripcion(String a, String b) {
        Set<String> tokensA = tokens(a);
        Set<String> tokensB = tokens(b);
        if (tokensA.size() < 3 || tokensB.size() < 3) {
            return false;
        }
        Set<String> interseccion = new HashSet<>(tokensA);
        interseccion.retainAll(tokensB);
        Set<String> union = new HashSet<>(tokensA);
        union.addAll(tokensB);
        return interseccion.size() >= 3
                && (double) interseccion.size() / union.size() >= 0.18;
    }

    private boolean tokensSimilares(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (Math.min(a.length(), b.length()) < 4
                || Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        int distancia = distanciaEdicion(a, b);
        return distancia <= 1 || transposicionAdyacente(a, b);
    }

    private boolean transposicionAdyacente(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int primeraDiferencia = -1;
        int segundaDiferencia = -1;
        for (int i = 0; i < a.length(); i++) {
            if (a.charAt(i) != b.charAt(i)) {
                if (primeraDiferencia < 0) {
                    primeraDiferencia = i;
                } else if (segundaDiferencia < 0) {
                    segundaDiferencia = i;
                } else {
                    return false;
                }
            }
        }
        return segundaDiferencia == primeraDiferencia + 1
                && a.charAt(primeraDiferencia) == b.charAt(segundaDiferencia)
                && a.charAt(segundaDiferencia) == b.charAt(primeraDiferencia);
    }

    private int distanciaEdicion(String a, String b) {
        int[] anterior = new int[b.length() + 1];
        int[] actual = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            anterior[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            actual[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int costo = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                actual[j] = Math.min(
                        Math.min(actual[j - 1] + 1, anterior[j] + 1),
                        anterior[j - 1] + costo);
            }
            int[] intercambio = anterior;
            anterior = actual;
            actual = intercambio;
        }
        return anterior[b.length()];
    }

    private Set<String> tokens(String texto) {
        String normalizado = Normalizer.normalize(
                valor(texto), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", " ");
        Set<String> tokens = new LinkedHashSet<>();
        Arrays.stream(normalizado.trim().split("\\s+"))
                .filter(token -> token.length() >= 3)
                .filter(token -> !PALABRAS_COMUNES.contains(token))
                .forEach(tokens::add);
        return tokens;
    }

    private Map<String, Object> resumen(
            Candidato candidato,
            List<String> criterios) {
        Map<String, Object> fila = new HashMap<>();
        fila.put("fuente", candidato.fuente);
        fila.put("idCoincidencia", candidato.id);
        fila.put("folio", valor(candidato.folio));
        fila.put("juzgado", valor(candidato.juzgado));
        fila.put("distrito", valor(candidato.distrito));
        fila.put("materia", valor(candidato.materia));
        fila.put("fecha", valor(candidato.fecha));
        fila.put("similitudes", criterios);
        fila.put("cantidadSimilitudes", criterios.size());
        fila.put("nivel", nivel(criterios.size()));
        return fila;
    }

    private Map<String, Object> detalle(
            Candidato candidato,
            List<String> criterios) {
        Map<String, Object> fila = resumen(candidato, criterios);
        fila.put("direccion", valor(candidato.direccion));
        fila.put("descripcion", valor(candidato.descripcion));
        fila.put("estatus", valor(candidato.estatus));
        if (candidato.partesJson != null
                && !candidato.partesJson.isBlank()) {
            fila.put("partes", JsonParser.parseString(candidato.partesJson));
        } else {
            fila.put("partes", valor(candidato.partesTexto));
        }
        return fila;
    }

    private static String nivel(int cantidad) {
        if (cantidad >= 3) {
            return "ALTA";
        }
        return cantidad == 2 ? "MEDIA" : "INICIAL";
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

    private static void validarTablaAuditoria(Connection conn)
            throws SQLException {
        Set<String> requeridas = Set.of(
                "id_referencia", "usuario_responsable", "modulo_origen",
                "campo_modificado", "valor_anterior", "valor_nuevo",
                "fecha_hora");
        Set<String> existentes = new HashSet<>();
        try (ResultSet columnas = conn.getMetaData().getColumns(
                conn.getCatalog(), null, "auditoria_historial", "%")) {
            while (columnas.next()) {
                if ("auditoria_historial".equalsIgnoreCase(
                        columnas.getString("TABLE_NAME"))) {
                    existentes.add(columnas.getString("COLUMN_NAME")
                            .toLowerCase(Locale.ROOT));
                }
            }
        }
        if (!existentes.containsAll(requeridas)) {
            throw new SQLException(
                    "No se puede registrar el historial de duplicidad: "
                            + "auditoria_historial no existe o no contiene "
                            + "las columnas requeridas "
                            + String.join(", ", requeridas) + ".");
        }
    }

    private static String moduloJuzgado(int idJuzgado) {
        // The existing audit schema has no court column; this keeps entries scoped.
        return "OFICIALIA_JUZGADO_" + idJuzgado;
    }

    private static JsonObject parsearEvento(String json) throws SQLException {
        try {
            JsonElement elemento = JsonParser.parseString(json);
            if (elemento.isJsonObject()) {
                return elemento.getAsJsonObject();
            }
            throw new SQLException(
                    "El detalle de auditoría de duplicidad no es válido.");
        } catch (JsonParseException e) {
            throw new SQLException(
                    "No fue posible leer un evento de auditoría de duplicidad.",
                    e);
        }
    }

    private static String texto(JsonObject objeto, String propiedad) {
        JsonElement elemento = objeto.get(propiedad);
        return elemento == null || !elemento.isJsonPrimitive()
                ? "" : elemento.getAsString();
    }

    private static int numero(JsonObject objeto, String propiedad) {
        JsonElement elemento = objeto.get(propiedad);
        return elemento == null || !elemento.isJsonPrimitive()
                ? 0 : elemento.getAsInt();
    }

    private static String textoCriterios(JsonObject objeto) {
        JsonElement elemento = objeto.get("criterios");
        if (elemento == null || !elemento.isJsonArray()) {
            return "";
        }
        List<String> criterios = new ArrayList<>();
        elemento.getAsJsonArray().forEach(item ->
                criterios.add(item.getAsString()));
        return String.join(", ", criterios);
    }

    private static String normalizarRfc(String rfc) {
        return valor(rfc).replaceAll("[^A-Za-z0-9]", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String fecha(Timestamp fecha) {
        return fecha == null ? "" : fecha.toString();
    }

    private static String valor(String texto) {
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
            this.nombrePromovente = valor(nombrePromovente);
            this.curpRfcPromovente = normalizarRfc(curpRfcPromovente);
            this.nombreDemandado = valor(nombreDemandado);
            this.curpRfcDemandado = normalizarRfc(curpRfcDemandado);
            this.idMateria = idMateria;
            this.descripcion = valor(descripcion);
        }
    }

    private static final class Candidato {
        private String fuente;
        private long id;
        private String folio;
        private int idMateria;
        private String materia;
        private String juzgado;
        private String distrito;
        private String direccion;
        private String descripcion;
        private String partesJson;
        private String partesTexto;
        private String estatus;
        private String fecha;
        private final List<String> nombres = new ArrayList<>();
        private final List<String> rfc = new ArrayList<>();
    }
}
