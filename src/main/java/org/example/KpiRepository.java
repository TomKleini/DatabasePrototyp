package org.example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Liest die Kennzahlen aus project_metrics und dreht sie
 * pro Projekt in eine Zeile (Pivot über metric_types.code).
 */
public class KpiRepository {

    public List<LightingKpiRow> findLightingData(boolean onlyRelevant) {
        String sql = """
                SELECT
                    p.id,
                    p.ship_name,
                    sy.name AS shipyard,
                    sc.name AS category,
                    st.name AS ship_type,
                    p.project_status,
                    p.length_m,
                    p.volume_gt,
                    p.area_sqm,

                    MAX(CASE WHEN mt.code = 'LIGHTING_POINTS'
                        THEN pm.target_value END) AS bs_soll,
                    MAX(CASE WHEN mt.code = 'LIGHTING_POINTS'
                        THEN pm.actual_value END) AS bs_ist,

                    MAX(CASE WHEN mt.code = 'IO_COUNT'
                        THEN pm.target_value END) AS io_soll,
                    MAX(CASE WHEN mt.code = 'IO_COUNT'
                        THEN pm.actual_value END) AS io_ist,

                    MAX(CASE WHEN mt.code = 'LIGHTING_POINT_TIME'
                        THEN pm.target_value END) AS min_bs_soll,
                    MAX(CASE WHEN mt.code = 'LIGHTING_POINT_TIME'
                        THEN pm.actual_value END) AS min_bs_ist,

                    MAX(CASE WHEN mt.code = 'IO_ENGINEERING_TIME'
                        THEN pm.target_value END) AS min_io_eng_soll,
                    MAX(CASE WHEN mt.code = 'IO_ENGINEERING_TIME'
                        THEN pm.actual_value END) AS min_io_eng_ist,

                    MAX(CASE WHEN mt.code = 'IO_DELIVERY_TIME'
                        THEN pm.target_value END) AS min_io_abg_soll,
                    MAX(CASE WHEN mt.code = 'IO_DELIVERY_TIME'
                        THEN pm.actual_value END) AS min_io_abg_ist

                FROM projects p
                JOIN project_metrics pm
                    ON pm.project_id = p.id
                JOIN metric_types mt
                    ON mt.id = pm.metric_type_id
                LEFT JOIN shipyards sy
                    ON sy.id = p.shipyard_id
                LEFT JOIN ship_types st
                    ON st.id = p.ship_type_id
                LEFT JOIN ship_categories sc
                    ON sc.id = st.category_id
                WHERE mt.code IN (
                        'LIGHTING_POINTS',
                        'IO_COUNT',
                        'LIGHTING_POINT_TIME',
                        'IO_ENGINEERING_TIME',
                        'IO_DELIVERY_TIME'
                    )
                  AND (? = 0 OR p.evaluation_relevance = 1)
                GROUP BY p.id
                ORDER BY p.ship_name COLLATE NOCASE
                """;

        List<LightingKpiRow> rows = new ArrayList<>();

        try (
                Connection connection = Database.connect();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setInt(1, onlyRelevant ? 1 : 0);

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.add(new LightingKpiRow(
                            result.getInt("id"),
                            result.getString("ship_name"),
                            result.getString("shipyard"),
                            result.getString("category"),
                            result.getString("ship_type"),
                            result.getString("project_status"),

                            getDouble(result, "length_m"),
                            getDouble(result, "volume_gt"),
                            getDouble(result, "area_sqm"),

                            getDouble(result, "bs_soll"),
                            getDouble(result, "bs_ist"),
                            getDouble(result, "io_soll"),
                            getDouble(result, "io_ist"),

                            getDouble(result, "min_bs_soll"),
                            getDouble(result, "min_bs_ist"),
                            getDouble(result, "min_io_eng_soll"),
                            getDouble(result, "min_io_eng_ist"),
                            getDouble(result, "min_io_abg_soll"),
                            getDouble(result, "min_io_abg_ist")
                    ));
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Kennzahlen konnten nicht geladen werden", e
            );
        }

        return rows;
    }

    /** Liefert null statt 0, wenn die Spalte leer ist. */
    private static Double getDouble(ResultSet result, String column)
            throws SQLException {
        Object value = result.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
    }
}

