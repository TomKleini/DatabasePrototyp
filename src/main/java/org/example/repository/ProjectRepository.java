package org.example.repository;

import org.example.database.Database;
import org.example.model.LookupItem;
import org.example.model.NewProjectData;
import org.example.model.Project;
import org.example.model.ProjectEditData;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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

                        readDouble(result, "length_m"),
                        readDouble(result, "width_m"),
                        readDouble(result, "volume_gt"),
                        readDouble(result, "area_sqm"),

                        readDouble(result, "cable_calculated_km"),
                        readDouble(result, "cable_offer_km"),
                        readDouble(result, "cable_actual_km"),

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

    /** Lädt nach ID, einschließlich noch leerer Kennzahlen und Fachbereiche. */
    public ProjectEditData findForEditing(int projectId) {
        String sql = """
                SELECT p.*, st.category_id
                FROM projects p
                JOIN ship_types st ON st.id = p.ship_type_id
                WHERE p.id = ?
                """;
        try (Connection connection = Database.connect();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, projectId);
            try (ResultSet r = statement.executeQuery()) {
                if (!r.next()) {
                    throw new IllegalArgumentException("Das Projekt existiert nicht mehr.");
                }
                List<ProjectEditData.Metric> metrics = new ArrayList<>();
                try (PreparedStatement metricStatement = connection.prepareStatement("""
                        SELECT mt.id, mt.code, mt.name, mt.unit,
                               pm.target_value, pm.actual_value
                        FROM metric_types mt
                        LEFT JOIN project_metrics pm
                            ON pm.metric_type_id = mt.id AND pm.project_id = ?
                        ORDER BY mt.id
                        """)) {
                    metricStatement.setInt(1, projectId);
                    try (ResultSet m = metricStatement.executeQuery()) {
                        while (m.next()) {
                            metrics.add(new ProjectEditData.Metric(m.getInt("id"),
                                    m.getString("code"), m.getString("name"), m.getString("unit"),
                                    readDouble(m, "target_value"), readDouble(m, "actual_value")));
                        }
                    }
                }
                List<ProjectEditData.Department> departments = new ArrayList<>();
                try (PreparedStatement departmentStatement = connection.prepareStatement("""
                        SELECT d.id, d.code, d.name, pd.value
                        FROM departments d
                        LEFT JOIN project_departments pd
                            ON pd.department_id = d.id AND pd.project_id = ?
                        ORDER BY d.code COLLATE NOCASE
                        """)) {
                    departmentStatement.setInt(1, projectId);
                    try (ResultSet d = departmentStatement.executeQuery()) {
                        while (d.next()) {
                            departments.add(new ProjectEditData.Department(d.getInt("id"),
                                    d.getString("code"), d.getString("name"), d.getString("value")));
                        }
                    }
                }
                Number shipyard = (Number) r.getObject("shipyard_id");
                return new ProjectEditData(r.getInt("id"),
                        shipyard == null ? null : shipyard.intValue(),
                        r.getInt("category_id"), r.getInt("ship_type_id"),
                        r.getString("ship_name"), r.getString("project_status"),
                        r.getInt("evaluation_relevance") == 1,
                        readDouble(r, "length_m"), readDouble(r, "width_m"),
                        readDouble(r, "volume_gt"), readDouble(r, "area_sqm"),
                        readDouble(r, "cable_calculated_km"), readDouble(r, "cable_offer_km"),
                        readDouble(r, "cable_actual_km"), r.getInt("ring_cable_included") == 1,
                        r.getString("offer_date"), r.getString("order_date"),
                        r.getString("construction_start"), r.getString("construction_end_planned"),
                        r.getString("construction_end_actual"), r.getString("editor"),
                        r.getString("evaluation"), r.getString("link"), r.getString("comments"),
                        metrics, departments);
            }
        } catch (SQLException e) {
            throw new RuntimeException("Projekt konnte nicht zum Bearbeiten geladen werden.", e);
        }
    }

    /** Projekt und zugehörige Werte werden gemeinsam gespeichert oder zurückgerollt.
     * Es wird ausschließlich die vorhandene Projekt-ID aktualisiert.
     */
    public void update(ProjectEditData project) {
        validateEdit(project);
        String sql = """
                UPDATE projects SET
                    shipyard_id = ?, ship_type_id = ?, ship_name = ?, project_status = ?,
                    length_m = ?, width_m = ?, volume_gt = ?, area_sqm = ?,
                    cable_calculated_km = ?, cable_offer_km = ?, cable_actual_km = ?,
                    ring_cable_included = ?, offer_date = ?, order_date = ?,
                    construction_start = ?, construction_end_planned = ?, construction_end_actual = ?,
                    evaluation_relevance = ?, editor = ?, evaluation = ?, link = ?, comments = ?,
                    updated_at = CURRENT_TIMESTAMP
                WHERE id = ?
                """;
        try (Connection connection = Database.connect()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement check = connection.prepareStatement(
                        "SELECT 1 FROM ship_types WHERE id = ? AND category_id = ?")) {
                    check.setInt(1, project.shipTypeId());
                    check.setInt(2, project.categoryId());
                    try (ResultSet r = check.executeQuery()) {
                        if (!r.next()) {
                            throw new IllegalArgumentException("Schiffstyp und Kategorie passen nicht zusammen.");
                        }
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    if (project.shipyardId() == null) statement.setNull(1, java.sql.Types.INTEGER);
                    else statement.setInt(1, project.shipyardId());
                    statement.setInt(2, project.shipTypeId());
                    statement.setString(3, project.shipName().trim());
                    statement.setString(4, project.status().trim());
                    setNullableDouble(statement, 5, project.lengthM());
                    setNullableDouble(statement, 6, project.widthM());
                    setNullableDouble(statement, 7, project.volumeGt());
                    setNullableDouble(statement, 8, project.areaSqm());
                    setNullableDouble(statement, 9, project.cableCalculatedKm());
                    setNullableDouble(statement, 10, project.cableOfferKm());
                    setNullableDouble(statement, 11, project.cableActualKm());
                    statement.setInt(12, project.ringCableIncluded() ? 1 : 0);
                    statement.setString(13, project.offerDate());
                    statement.setString(14, project.orderDate());
                    statement.setString(15, project.constructionStart());
                    statement.setString(16, project.constructionEndPlanned());
                    statement.setString(17, project.constructionEndActual());
                    statement.setInt(18, project.relevant() ? 1 : 0);
                    statement.setString(19, optionalText(project.editor()));
                    statement.setString(20, optionalText(project.evaluation()));
                    statement.setString(21, optionalText(project.link()));
                    statement.setString(22, optionalText(project.comments()));
                    statement.setInt(23, project.id());
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalArgumentException("Das Projekt existiert nicht mehr. Es wurde nichts gespeichert.");
                    }
                }
                writeMetrics(connection, project);
                writeDepartments(connection, project);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                try { connection.rollback(); }
                catch (SQLException rollbackError) { e.addSuppressed(rollbackError); }
                throw e;
            }
        } catch (SQLException e) {
            throw new RuntimeException("Änderungen konnten nicht gespeichert werden. "
                    + "Die Datenbankänderungen wurden zurückgerollt.", e);
        }
    }

    private void writeMetrics(Connection connection, ProjectEditData project) throws SQLException {
        String upsert = """
                INSERT INTO project_metrics (project_id, metric_type_id, target_value, actual_value)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(project_id, metric_type_id) DO UPDATE SET
                    target_value = excluded.target_value, actual_value = excluded.actual_value
                """;
        try (PreparedStatement statement = connection.prepareStatement(upsert);
             PreparedStatement clear = connection.prepareStatement("""
                     UPDATE project_metrics SET target_value = NULL, actual_value = NULL
                     WHERE project_id = ? AND metric_type_id = ?
                     """)) {
            for (ProjectEditData.Metric metric : project.metrics()) {
                if (metric.target() == null && metric.actual() == null) {
                    clear.setInt(1, project.id()); clear.setInt(2, metric.id());
                    clear.executeUpdate();
                } else {
                    statement.setInt(1, project.id()); statement.setInt(2, metric.id());
                    setNullableDouble(statement, 3, metric.target());
                    setNullableDouble(statement, 4, metric.actual());
                    statement.executeUpdate();
                }
            }
        }
    }

    private void writeDepartments(Connection connection, ProjectEditData project) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO project_departments (project_id, department_id, value)
                VALUES (?, ?, ?)
                ON CONFLICT(project_id, department_id) DO UPDATE SET value = excluded.value
                """);
             PreparedStatement clear = connection.prepareStatement("""
                     UPDATE project_departments SET value = NULL
                     WHERE project_id = ? AND department_id = ?
                     """)) {
            for (ProjectEditData.Department department : project.departments()) {
                String value = optionalText(department.value());
                if (value == null) {
                    clear.setInt(1, project.id()); clear.setInt(2, department.id());
                    clear.executeUpdate();
                } else {
                    statement.setInt(1, project.id()); statement.setInt(2, department.id());
                    statement.setString(3, value); statement.executeUpdate();
                }
            }
        }
    }

    private static void validateEdit(ProjectEditData p) {
        if (p.id() <= 0 || p.shipTypeId() <= 0 || p.categoryId() <= 0
                || p.shipName() == null || p.shipName().isBlank()
                || p.status() == null || p.status().isBlank()) {
            throw new IllegalArgumentException("Schiffsname, Status, Kategorie und Schiffstyp sind Pflichtfelder.");
        }
        Double[] values = {p.lengthM(), p.widthM(), p.volumeGt(), p.areaSqm(),
                p.cableCalculatedKm(), p.cableOfferKm(), p.cableActualKm()};
        for (Double value : values) validateNumber(value);
        Set<Integer> metricIds = new HashSet<>();
        for (ProjectEditData.Metric m : p.metrics()) {
            if (m.id() <= 0 || !metricIds.add(m.id())) {
                throw new IllegalArgumentException("Eine Kennzahl ist ungültig oder doppelt vorhanden.");
            }
            validateNumber(m.target()); validateNumber(m.actual());
        }
        Set<Integer> departmentIds = new HashSet<>();
        for (ProjectEditData.Department d : p.departments()) {
            if (d.id() <= 0 || !departmentIds.add(d.id())) {
                throw new IllegalArgumentException("Ein Fachbereich ist ungültig oder doppelt vorhanden.");
            }
        }
        for (String date : new String[]{p.offerDate(), p.orderDate(), p.constructionStart(),
                p.constructionEndPlanned(), p.constructionEndActual()}) {
            if (date != null) {
                try { LocalDate.parse(date); }
                catch (DateTimeParseException e) {
                    throw new IllegalArgumentException("Datumswerte müssen gültige ISO-Daten sein (JJJJ-MM-TT).", e);
                }
            }
        }
    }

    private static void validateNumber(Double value) {
        if (value != null && (!Double.isFinite(value) || value < 0)) {
            throw new IllegalArgumentException("Zahlen müssen endlich und mindestens 0 sein.");
        }
    }

    private static String optionalText(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Double readDouble(ResultSet result, String column) throws SQLException {
        Object value = result.getObject(column);
        return value instanceof Number number ? number.doubleValue() : null;
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
