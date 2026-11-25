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
 * Lädt alle Projekte mit Kabeldaten (Angebot = Soll, Real = Ist)
 * direkt aus der Tabelle projects.
 */
public class KabelRepository {

    public record KabelProjekt(
            String schiff,
            String werft,
            String kategorie,
            String schiffstyp,
            Double volumen,
            Double flaeche,
            Double laenge,
            Double breite,
            Double kabelBerechnet,
            Double kabelAngebot,
            Double kabelReal
    ) {
        /** Zugriff über den Spaltennamen der Datenbank. */
        public Double wert(String schluessel) {
            return switch (schluessel) {
                case "volume_gt" -> volumen;
                case "area_sqm" -> flaeche;
                case "length_m" -> laenge;
                case "width_m" -> breite;
                case "cable_calculated_km" -> kabelBerechnet;
                case "cable_offer_km" -> kabelAngebot;
                case "cable_actual_km" -> kabelReal;
                default -> null;
            };
        }
    }

    /**
     * @param kategorie   null = alle Kategorien
     * @param schiffstyp  null = alle Schiffstypen
     * @param nurRelevant true = nur auswertungsrelevante Projekte
     */
    public List<KabelProjekt> lade(String kategorie, String schiffstyp, boolean nurRelevant) {
        String sql = """
                SELECT p.ship_name,
                       sy.name AS shipyard,
                       sc.name AS category,
                       st.name AS ship_type,
                       p.volume_gt,
                       p.area_sqm,
                       p.length_m,
                       p.width_m,
                       p.cable_calculated_km,
                       p.cable_offer_km,
                       p.cable_actual_km
                FROM projects p
                LEFT JOIN shipyards sy       ON sy.id = p.shipyard_id
                LEFT JOIN ship_types st      ON st.id = p.ship_type_id
                LEFT JOIN ship_categories sc ON sc.id = st.category_id
                WHERE (? IS NULL OR sc.name = ? COLLATE NOCASE)
                  AND (? IS NULL OR st.name = ? COLLATE NOCASE)
                  AND (? = 0 OR p.evaluation_relevance = 1)
                  AND (p.cable_offer_km IS NOT NULL OR p.cable_actual_km IS NOT NULL)
                ORDER BY p.ship_name COLLATE NOCASE
                """;

        List<KabelProjekt> projekte = new ArrayList<>();

        try (
                Connection connection = Database.connect();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            setText(statement, 1, kategorie);
            setText(statement, 2, kategorie);
            setText(statement, 3, schiffstyp);
            setText(statement, 4, schiffstyp);
            statement.setInt(5, nurRelevant ? 1 : 0);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    projekte.add(new KabelProjekt(
                            rs.getString("ship_name"),
                            rs.getString("shipyard"),
                            rs.getString("category"),
                            rs.getString("ship_type"),
                            zahl(rs, "volume_gt"),
                            zahl(rs, "area_sqm"),
                            zahl(rs, "length_m"),
                            zahl(rs, "width_m"),
                            zahl(rs, "cable_calculated_km"),
                            zahl(rs, "cable_offer_km"),
                            zahl(rs, "cable_actual_km")
                    ));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Kabeldaten konnten nicht geladen werden", e);
        }

        return projekte;
    }

    private static Double zahl(ResultSet rs, String spalte) throws SQLException {
        Object wert = rs.getObject(spalte);
        return wert instanceof Number n ? n.doubleValue() : null;
    }

    private static void setText(PreparedStatement s, int index, String wert) throws SQLException {
        if (wert == null || wert.isBlank() || wert.toLowerCase().startsWith("alle")) {
            s.setNull(index, Types.VARCHAR);
        } else {
            s.setString(index, wert);
        }
    }
}
