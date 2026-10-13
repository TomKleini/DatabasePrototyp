package org.example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lädt alle Projekte mit Stammdaten und Kennzahlen für die
 * Korrelationsmatrix. Eigenständig, damit KpiRepository unverändert bleibt.
 * Anders als dort werden auch Projekte ohne Beleuchtungskennzahlen geladen
 * (z. B. Schiffe, die nur Kabelwerte haben).
 */
public class MatrixRepository {

    private static final String[] STAMMDATEN = {
            "length_m",
            "width_m",
            "volume_gt",
            "area_sqm",
            "cable_calculated_km",
            "cable_offer_km",
            "cable_actual_km"
    };

    /**
     * @param kategorie   null = alle Kategorien
     * @param schiffstyp  null = alle Schiffstypen
     * @param nurRelevant nur Projekte mit evaluation_relevance = 1
     */
    public List<MatrixProjekt> lade(
            String kategorie,
            String schiffstyp,
            boolean nurRelevant
    ) {
        String projekteSql = """
                SELECT
                    p.id,
                    p.ship_name,
                    sy.name AS shipyard,
                    p.length_m,
                    p.width_m,
                    p.volume_gt,
                    p.area_sqm,
                    p.cable_calculated_km,
                    p.cable_offer_km,
                    p.cable_actual_km
                FROM projects p
                LEFT JOIN shipyards sy
                    ON sy.id = p.shipyard_id
                LEFT JOIN ship_types st
                    ON st.id = p.ship_type_id
                LEFT JOIN ship_categories sc
                    ON sc.id = st.category_id
                WHERE (? IS NULL OR sc.name = ?)
                  AND (? IS NULL OR st.name = ?)
                  AND (? = 0 OR p.evaluation_relevance = 1)
                ORDER BY p.ship_name COLLATE NOCASE
                """;

        String kennzahlenSql = """
                SELECT
                    pm.project_id,
                    mt.code,
                    pm.target_value,
                    pm.actual_value
                FROM project_metrics pm
                JOIN metric_types mt
                    ON mt.id = pm.metric_type_id
                """;

        Map<Integer, MatrixProjekt> projekte = new LinkedHashMap<>();

        try (Connection connection = Database.connect()) {

            try (PreparedStatement statement =
                         connection.prepareStatement(projekteSql)) {
                statement.setString(1, kategorie);
                statement.setString(2, kategorie);
                statement.setString(3, schiffstyp);
                statement.setString(4, schiffstyp);
                statement.setInt(5, nurRelevant ? 1 : 0);

                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        Map<String, Double> werte = new HashMap<>();
                        for (String spalte : STAMMDATEN) {
                            Double wert = zahl(result, spalte);
                            if (wert != null) {
                                werte.put(spalte, wert);
                            }
                        }

                        int id = result.getInt("id");
                        projekte.put(id, new MatrixProjekt(
                                id,
                                result.getString("ship_name"),
                                result.getString("shipyard"),
                                werte));
                    }
                }
            }

            try (PreparedStatement statement =
                         connection.prepareStatement(kennzahlenSql);
                 ResultSet result = statement.executeQuery()) {

                while (result.next()) {
                    MatrixProjekt projekt = projekte.get(result.getInt("project_id"));
                    if (projekt == null) {
                        continue;   // durch Filter ausgeschlossen
                    }

                    String code = result.getString("code");
                    Double soll = zahl(result, "target_value");
                    Double ist = zahl(result, "actual_value");

                    if (soll != null) {
                        projekt.werte().put(code + ":soll", soll);
                    }
                    if (ist != null) {
                        projekt.werte().put(code + ":ist", ist);
                    }
                }
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Daten für die Korrelationsmatrix konnten nicht geladen werden", e);
        }

        return new ArrayList<>(projekte.values());
    }

    /** Liefert null statt 0, wenn die Spalte leer ist. */
    private static Double zahl(ResultSet result, String spalte) throws SQLException {
        Object wert = result.getObject(spalte);
        return wert instanceof Number zahl ? zahl.doubleValue() : null;
    }
}