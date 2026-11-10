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

public class DuplicidadOficioDAO {

    public DuplicidadOficioDAO() {
        inicializarTabla();
    }

    private void inicializarTabla() {

        String sql = """
            CREATE TABLE IF NOT EXISTS oficialia_partes_evaluacion (
                id BIGINT AUTO_INCREMENT PRIMARY KEY,
                folio VARCHAR(50) NOT NULL,
                fiscalia VARCHAR(150) NOT NULL,
                materia VARCHAR(100) NOT NULL,
                estatus VARCHAR(50) NOT NULL,
                nombre_promovente VARCHAR(200),
                curp_rfc VARCHAR(18),
                telefono VARCHAR(10),
                INDEX idx_curp_rfc (curp_rfc),
                INDEX idx_nombre_materia (nombre_promovente, materia),
                INDEX idx_telefono (telefono)
            )
            """;

        try (Connection conn = ConexionMySQL.getConnection();
             Statement stmt = conn.createStatement()) {

            stmt.executeUpdate(sql);

        } catch (SQLException e) {
            throw new IllegalStateException(
                    "No se pudo inicializar la tabla de duplicidad", e
            );
        }
    }

    public List<Map<String, Object>> buscarCoincidencias(
            String curpRfc,
            String nombre,
            String telefono,
            String materia,
            String fiscaliaActual) throws SQLException {

        List<Map<String, Object>> coincidencias = new ArrayList<>();

        String sql = """
            SELECT folio, fiscalia, materia, estatus
            FROM oficialia_partes_evaluacion
            WHERE fiscalia <> ?
              AND (
                  (curp_rfc = ? AND ? <> '')
                  OR (telefono = ? AND ? <> '')
                  OR (
                      UPPER(TRIM(nombre_promovente)) = UPPER(TRIM(?))
                      AND materia = ?
                      AND ? <> ''
                  )
              )
            ORDER BY id DESC
            LIMIT 20
            """;

        try (Connection conn = ConexionMySQL.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, valor(fiscaliaActual));
            ps.setString(2, valor(curpRfc));
            ps.setString(3, valor(curpRfc));
            ps.setString(4, valor(telefono));
            ps.setString(5, valor(telefono));
            ps.setString(6, valor(nombre));
            ps.setString(7, valor(materia));
            ps.setString(8, valor(nombre));

            try (ResultSet rs = ps.executeQuery()) {

                while (rs.next()) {

                    Map<String, Object> fila = new HashMap<>();

                    fila.put(
                            "folio",
                            enmascararFolio(rs.getString("folio"))
                    );

                    fila.put(
                            "fiscalia",
                            rs.getString("fiscalia")
                    );

                    fila.put(
                            "materia",
                            rs.getString("materia")
                    );

                    fila.put(
                            "estatus",
                            rs.getString("estatus")
                    );

                    coincidencias.add(fila);
                }
            }
        }

        return coincidencias;
    }

    private String valor(String texto) {
        return texto == null ? "" : texto.trim();
    }

    private String enmascararFolio(String folio) {

        if (folio == null || folio.length() <= 4) {
            return "****";
        }

        return "****" + folio.substring(folio.length() - 4);
    }
}
