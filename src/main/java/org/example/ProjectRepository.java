package org.example;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

//project repository erweitern mit den werten die in unsere Projektdetails aufgenommen werden sollten

public class ProjectRepository {

    public List<Project> findAll(){
        String sql= """
                SELECT 
                    p.id,
                    p.ship_name,
                    sy.name AS shipyards,
                    sc.name AS ship_categories,
                    st.name AS ship_types,
                    p.project_status,
                    p.evaluation_relevance,

                    p.length_m,
                    p.width_m,
                    p.volume_gt,
                    p.area_sqm,

                    p.cable_calculated_km,
                    p.cable_offer_km,
                    p.cable_actual_km,

                    p.ring_cable_included,

                    p.offer_date,
                    p.order_date,

                    p.construction_start,
                    p.construction_end_planned,
                    p.construction_end_actual
                FROM projects p
                LEFT JOIN shipyards sy
                   ON sy.id = p.shipyard_id
                LEFT JOIN ship_types st
                    ON st.id = p.ship_type_id
                LEFT JOIN ship_categories sc
                    ON sc.id = st.category_id
                ORDER BY p.ship_name COLLATE NOCASE
                    
                """;

        List<Project> projects = new ArrayList<>();

        try(
                Connection connection = Database.connect();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery();
                )
        {
            while(result.next()){
                Project project = new Project(
                        result.getInt("id"),
                        result.getString("ship_name"),
                        result.getString("shipyards"),
                        result.getString("ship_categories"),
                        result.getString("ship_types"),
                        result.getString("project_status"),
                        result.getInt("evaluation_relevance") == 1,

                        (Double) result.getObject("length_m"),
                        (Double) result.getObject("width_m"),
                        (Double) result.getObject("volume_gt"),
                        (Double) result.getObject("area_sqm"),

                        (Double) result.getObject("cable_calculated_km"),
                        (Double) result.getObject("cable_offer_km"),
                        (Double) result.getObject("cable_actual_km"),

                        result.getInt("ring_cable_included") == 1,

                        result.getString("offer_date"),
                        result.getString("order_date"),

                        result.getString("construction_start"),
                        result.getString("construction_end_planned"),
                        result.getString("construction_end_actual")
                );

                projects.add(project);
            }

        } catch (SQLException e) {
            throw new RuntimeException("Projekte konnten nicht geladen werden",e);
        }
        return projects;
    }

    public List<LookupItem> findShipyards() {
        return findLookupItems("""
            SELECT id, name
            FROM shipyards
            ORDER BY name COLLATE NOCASE
            """);
    }

    public List<LookupItem> findCategories() {
        return findLookupItems("""
            SELECT id, name
            FROM ship_categories
            ORDER BY name COLLATE NOCASE
            """);
    }

    public List<LookupItem> findShipTypes(int categoryId) {
        String sql = """
            SELECT id, name
            FROM ship_types
            WHERE category_id = ?
            ORDER BY name COLLATE NOCASE
            """;

        List<LookupItem> items = new ArrayList<>();

        try (
                Connection connection = Database.connect();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setInt(1, categoryId);

            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    items.add(new LookupItem(
                            result.getInt("id"),
                            result.getString("name")
                    ));
                }
            }

            return items;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Schiffstypen konnten nicht geladen werden", e
            );
        }
    }

    private List<LookupItem> findLookupItems(String sql) {
        List<LookupItem> items = new ArrayList<>();

        try (
                Connection connection = Database.connect();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                items.add(new LookupItem(
                        result.getInt("id"),
                        result.getString("name")
                ));
            }

            return items;

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Stammdaten konnten nicht geladen werden", e
            );
        }
    }

    public int insert(NewProjectData project) {
        String sql = """
            INSERT INTO projects (
                shipyard_id, ship_type_id, ship_name, project_status,
                length_m, width_m, volume_gt, area_sqm,
                cable_calculated_km, cable_offer_km, cable_actual_km,
                ring_cable_included,
                offer_date, order_date, construction_start,
                construction_end_planned, construction_end_actual
            )
            VALUES (
                ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                ?,
                ?, ?, ?,
                ?, ?
            )
            """;

        try (
                Connection connection = Database.connect();
                PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            if (project.shipyardId() == null) {
                statement.setNull(1, java.sql.Types.INTEGER);
            } else {
                statement.setInt(1, project.shipyardId());
            }

            statement.setInt(2, project.shipTypeId());
            statement.setString(3, project.shipName());
            statement.setString(4, project.status());

            setNullableDouble(statement, 5, project.lengthM());
            setNullableDouble(statement, 6, project.widthM());
            setNullableDouble(statement, 7, project.volumeGt());
            setNullableDouble(statement, 8, project.areaSqm());

            setNullableDouble(statement, 9, project.cableCalculatedKm());
            setNullableDouble(statement, 10, project.cableOfferKm());
            setNullableDouble(statement, 11, project.cableActualKm());

            statement.setInt(12, project.ringCableIncluded() ? 1 : 0);

            setDate(statement, 13, project.offerDate());
            setDate(statement, 14, project.orderDate());
            setDate(statement, 15, project.constructionStart());
            setDate(statement, 16, project.constructionEndPlanned());
            setDate(statement, 17, project.constructionEndActual());

            statement.executeUpdate();

            try (
                    PreparedStatement idStatement =
                            connection.prepareStatement(
                                    "SELECT last_insert_rowid()"
                            );
                    ResultSet result = idStatement.executeQuery()
            ) {
                return result.getInt(1);
            }

        } catch (SQLException e) {
            throw new RuntimeException(
                    "Projekt konnte nicht gespeichert werden", e
            );
        }
    }

    private void setNullableDouble(
            PreparedStatement statement,
            int index,
            Double value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.REAL);
        } else {
            statement.setDouble(index, value);
        }
    }

    private void setDate(
            PreparedStatement statement,
            int index,
            java.time.LocalDate value
    ) throws SQLException {
        statement.setString(
                index,
                value == null ? null : value.toString()
        );
    }
}
