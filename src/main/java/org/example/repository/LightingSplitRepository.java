package org.example.repository;

import org.example.database.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * Lädt die historische Aufteilung der Brennstellen (Soll-Werte):
 * LIGHTING_INDOOR, LIGHTING_OUTDOOR, LIGHTING_SWITCH_SOCKET.
 * Es zählen nur Projekte, die ALLE DREI Werte haben.
 */
public class LightingSplitRepository {

    public record Reference(
            String shipName,
            double indoor,
            double outdoor,
            double switchSocket
    ) {
        public double total() {
            return indoor + outdoor + switchSocket;
        }
    }

    /**
     * @param categoryName  null = alle Kategorien
     * @param shipTypeName  null = alle Schiffstypen
     * @param onlyRelevant  true = nur auswertungsrelevante Projekte
     */
    public List<Reference> findReferences(
            String categoryName,
            String shipTypeName,
            boolean onlyRelevant
    ) {
        String sql = """
                SELECT p.ship_name,
                       MAX(CASE WHEN mt.code = 'LIGHTING_INDOOR'
                                THEN pm.target_value END) AS indoor,
                       MAX(CASE WHEN mt.code = 'LIGHTING_OUTDOOR'
                                THEN pm.target_value END) AS outdoor,
                       MAX(CASE WHEN mt.code = 'LIGHTING_SWITCH_SOCKET'
                                THEN pm.target_value END) AS switch_socket
                FROM projects p
                JOIN project_metrics pm ON pm.project_id = p.id
                JOIN metric_types mt    ON mt.id = pm.metric_type_id
                LEFT JOIN ship_types st      ON st.id = p.ship_type_id
                LEFT JOIN ship_categories sc ON sc.id = st.category_id
                WHERE mt.code IN ('LIGHTING_INDOOR',
                                  'LIGHTING_OUTDOOR',
                                  'LIGHTING_SWITCH_SOCKET')
                  AND (? IS NULL OR sc.name = ? COLLATE NOCASE)
                  AND (? IS NULL OR st.name = ? COLLATE NOCASE)
                  AND (? = 0 OR p.evaluation_relevance = 1)
                GROUP BY p.id
                HAVING indoor IS NOT NULL
                   AND outdoor IS NOT NULL
                   AND switch_socket IS NOT NULL
                   AND (indoor + outdoor + switch_socket) > 0
                """;

        List<Reference> references = new ArrayList<>();
        try (
                Connection connection = Database.connect();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            setNullableText(statement, 1, categoryName);
            setNullableText(statement, 2, categoryName);
            setNullableText(statement, 3, shipTypeName);
            setNullableText(statement, 4, shipTypeName);
            statement.setInt(5, onlyRelevant ? 1 : 0);

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    references.add(new Reference(
                            result.getString("ship_name"),
                            result.getDouble("indoor"),
                            result.getDouble("outdoor"),
                            result.getDouble("switch_socket")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException(
                    "Brennstellen-Aufteilung konnte nicht geladen werden", e);
        }
        return references;
    }

    private static void setNullableText(
            PreparedStatement statement,
            int index,
            String value
    ) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value);
        }
    }
}
