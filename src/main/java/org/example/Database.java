package org.example;

import java.sql.*;

public class Database {

    private static final String URL =
            "jdbc:sqlite:database.db";

    public static Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection(URL);

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
        }

        return connection;
    }

    public static void createTables() {

        String createShipyards = """
                CREATE TABLE IF NOT EXISTS shipyards (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT NOT NULL UNIQUE
                );
                """;

        String createShipTypes = """
                CREATE TABLE IF NOT EXISTS ship_types (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category_id INTEGER NOT NULL,
                    name TEXT NOT NULL,
        
                    FOREIGN KEY (category_id)
                        REFERENCES ship_categories(id),
        
                    UNIQUE (category_id, name)
                );
                """;

        String createProjects = """
                CREATE TABLE IF NOT EXISTS projects (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    shipyard_id INTEGER,
                    ship_type_id INTEGER NOT NULL,
                    ship_name TEXT NOT NULL,
                    project_status TEXT NOT NULL,

                    length_m REAL,
                    width_m REAL,
                    volume_gt REAL,
                    area_sqm REAL,

                    cable_calculated_km REAL,
                    cable_offer_km REAL,
                    cable_actual_km REAL,
                    ring_cable_included INTEGER NOT NULL DEFAULT 0,

                    offer_date DATE,
                    order_date DATE,
                    construction_start DATE,
                    construction_end_planned DATE,
                    construction_end_actual DATE,

                    editor TEXT,
                    evaluation TEXT,
                    link TEXT,
                    comments TEXT,
                    evaluation_relevance INTEGER NOT NULL DEFAULT 1,

                    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,

                    FOREIGN KEY (shipyard_id)
                        REFERENCES shipyards(id),

                    FOREIGN KEY (ship_type_id)
                        REFERENCES ship_types(id)
                );
                """;

        String createDepartments = """
                CREATE TABLE IF NOT EXISTS departments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL
                );
                """;

        String createProjectDepartments = """
                CREATE TABLE IF NOT EXISTS project_departments (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL,
                    department_id INTEGER NOT NULL,
                    value TEXT,

                    FOREIGN KEY (project_id)
                        REFERENCES projects(id)
                        ON DELETE CASCADE,

                    FOREIGN KEY (department_id)
                        REFERENCES departments(id),

                    UNIQUE (project_id, department_id)
                );
                """;

        String createMetricTypes = """
                CREATE TABLE IF NOT EXISTS metric_types (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    code TEXT NOT NULL UNIQUE,
                    name TEXT NOT NULL,
                    unit TEXT
                );
                """;

        String createProjectMetrics = """
                CREATE TABLE IF NOT EXISTS project_metrics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    project_id INTEGER NOT NULL,
                    metric_type_id INTEGER NOT NULL,
                    target_value REAL,
                    actual_value REAL,

                    FOREIGN KEY (project_id)
                        REFERENCES projects(id)
                        ON DELETE CASCADE,

                    FOREIGN KEY (metric_type_id)
                        REFERENCES metric_types(id),

                    UNIQUE (project_id, metric_type_id)
                );
                """;

        String createShipCategories = """
        CREATE TABLE IF NOT EXISTS ship_categories (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL UNIQUE
        );
        """;

        try (
                Connection connection = connect();
                Statement statement = connection.createStatement()
        ) {
            statement.execute(createShipyards);
            statement.execute(createShipCategories);
            statement.execute(createShipTypes);
            statement.execute(createProjects);
            statement.execute(createDepartments);
            statement.execute(createProjectDepartments);
            statement.execute(createMetricTypes);
            statement.execute(createProjectMetrics);


            System.out.println("Alle Datenbanktabellen wurden erstellt.");

            java.io.File databaseFile =
                    new java.io.File("database.db");

            System.out.println(
                    "Datenbankpfad: " + databaseFile.getAbsolutePath()
            );

            System.out.println(
                    "Datenbank existiert jetzt: " + databaseFile.exists()
            );

            System.out.println(
                    "Dateigröße: " + databaseFile.length() + " Bytes"
            );

        } catch (SQLException exception) {
            System.err.println(
                    "Fehler beim Erstellen der Tabellen: "
                            + exception.getMessage()
            );
        }

        System.out.println(
                "Verwendete Datenbank: "
                        + new java.io.File("database.db").getAbsolutePath()
        );
    }

    public static void insertMasterData() {

        String departmentSql = """
            INSERT OR IGNORE INTO departments (code, name)
            VALUES (?, ?)
            """;

        String metricSql = """
            INSERT OR IGNORE INTO metric_types (code, name, unit)
            VALUES (?, ?, ?)
            """;

        String shipCategoriesSql = """
            INSERT OR IGNORE INTO ship_categories (name)
            VALUES (?)
            """;

        String shipyardSql = """
            INSERT OR IGNORE INTO shipyards (name)
            VALUES (?)
            """;

        String shipTypeSql = """
            INSERT OR IGNORE INTO ship_types (category_id, name)
            SELECT id, ?
            FROM ship_categories
            WHERE name = ?
            """;


        String[][] departments = {
                {"SES", "Drives"},
                {"SNE2", "Power Distribution"},
                {"SNE4", "Cable Systems"},
                {"SOB", "Baustelle"},
                {"SNE3", "Lighting"},
                {"SNE1", "Safety, Security, Entertainment"},
                {"SNA", "Automation"},
                {"ANCS", "NavCom"},
                {"DP", "DP System"},
                {"SPA", "Systems Architecture"}
        };

        String[][] metrics = {
                {"IO_COUNT", "IOs", "Stück"},
                {"LIGHTING_POINTS", "Brennstellen", "Stück"},
                {"CABLE_RECORDS", "Kabeldatensätze", "Stück"},
                {"CABLE_ENGINEERING_TIME",
                        "Engineering pro Kabeldatensatz", "Minuten"},
                {"CABLE_ROUTING_TIME",
                        "Routing pro Kabeldatensatz", "Minuten"},
                {"LIGHTING_POINT_TIME",
                        "Zeit pro Brennstelle", "Minuten"},
                {"IO_ENGINEERING_TIME",
                        "IO Engineering", "Minuten"},
                {"IO_DELIVERY_TIME",
                        "IO Abgabe", "Minuten"},
                {"FIRE_DETECTORS",
                        "Feuermelder", "Stück"}
        };

        String[] shipCategories = {
                "Yachten","Fähren & RoRo", "Container","Cruise","Forschung & Spezial", "Militär","Conversion","Wind"
        };

        String[] shipyards = {
                "A+R","Crist","Damen","Fassmer","FSG","Kröger","LWB","Meyer","MPC","Neptun","P+S","Peene","Sietas","TKMS","Western Shipyard"
        };

        String[][] shipTypes = {
                {"Forschung & Spezial", "Bohrschiff"},
                {"Forschung & Spezial","Eisbrecher"},
                {"Forschung & Spezial","Errichterschiff"},
                {"Forschung & Spezial","Fischerei-Schutzboot"},
                {"Forschung & Spezial","Forschungsschiff"},
                {"Forschung & Spezial","Kabelleger"},
                {"Forschung & Spezial", "LNG Carrier"},
                {"Forschung & Spezial", "Polizei"},
                {"Forschung & Spezial", "Sailing Vessel"},
                {"Forschung & Spezial", "Saugbagger"},
                {"Forschung & Spezial", "Schadstoff-bekämpfer"},
                {"Forschung & Spezial", "Schwergut-/Dockschiff"},
                {"Forschung & Spezial", "Seeversuch Küste"},
                {"Forschung & Spezial", "Tanker"},
                {"Forschung & Spezial", "Unfallbekämpfungsschiff"},
                {"Forschung & Spezial", "VWFS"},
                {"Forschung & Spezial", "Zoll Einsatzschiff"},
                {"Forschung & Spezial", "Zollboot"}

        };

        try (Connection connection = connect()) {

            connection.setAutoCommit(false);

            try (PreparedStatement statement =
                         connection.prepareStatement(departmentSql)) {

                for (String[] department : departments) {
                    statement.setString(1, department[0]);
                    statement.setString(2, department[1]);
                    statement.addBatch();
                }

                statement.executeBatch();
            }

            try (PreparedStatement statement =
                         connection.prepareStatement(metricSql)) {

                for (String[] metric : metrics) {
                    statement.setString(1, metric[0]);
                    statement.setString(2, metric[1]);
                    statement.setString(3, metric[2]);
                    statement.addBatch();
                }

                statement.executeBatch();
            }

            try (PreparedStatement statement =
                         connection.prepareStatement(shipCategoriesSql)) {

                for (String shipCategorie : shipCategories) {
                    statement.setString(1, shipCategorie);
                    statement.addBatch();
                }

                statement.executeBatch();
            }

            try (PreparedStatement statement =
                         connection.prepareStatement(shipyardSql)) {

                for (String shipyard : shipyards) {
                    statement.setString(1, shipyard);
                    statement.addBatch();
                }

                statement.executeBatch();
            }

            try (PreparedStatement statement =
                         connection.prepareStatement(shipTypeSql)) {

                for (String[] shipType : shipTypes) {

                    // Untertyp für den ersten Platzhalter
                    statement.setString(1, shipType[1]);

                    // Kategorie für den zweiten Platzhalter
                    statement.setString(2, shipType[0]);

                    statement.addBatch();
                }

                statement.executeBatch();
            }

            connection.commit();
            System.out.println("Stammdaten wurden eingefügt.");

        } catch (SQLException exception) {
            System.err.println(
                    "Fehler bei den Stammdaten: "
                            + exception.getMessage()
            );
        }
    }

}