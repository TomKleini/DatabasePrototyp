package org.example.importer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.database.Database;

import java.io.FileInputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * Excel-Import für die Blattstruktur der Schiffsdatenbank.
 *
 * Blattname                 -> Schiffskategorie
 * Spalte C                  -> Schiffsuntertyp
 * Spalten A bis Q           -> Projektstammdaten
 * Spalten R bis AA          -> beteiligte Fachbereiche
 * Spalten AF bis AV         -> Soll-/Ist-Kennzahlen
 * Spalten AY bis BH         -> werden nicht importiert (berechnete Werte)
 *
 * NEU: Beleuchtungsaufteilung (Innenbeleuchtung, Außenbeleuchtung,
 * Schalter/Steckdosen, Gesamt) wird über die Überschrift gefunden.
 * Alle Spalten dahinter werden automatisch verschoben.
 * Alte Excel-Dateien ohne diese Spalten funktionieren weiterhin.
 *
 * Bereits vorhandene Projekte werden nicht neu angelegt. Bei ihnen wird
 * nur die Beleuchtungsaufteilung ergänzt bzw. aktualisiert.
 */
public final class ExcelImporter {

    private static final DataFormatter FORMATTER =
            new DataFormatter(Locale.GERMANY);

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE;

    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("M/uuuu");

    private static final int FIRST_DATA_ROW = 1;

    private static final DepartmentColumn[] DEPARTMENT_COLUMNS = {
            new DepartmentColumn("SES", 17),
            new DepartmentColumn("SNE2", 18),
            new DepartmentColumn("SNE4", 19),
            new DepartmentColumn("SOB", 20),
            new DepartmentColumn("SNE3", 21),
            new DepartmentColumn("SNE1", 22),
            new DepartmentColumn("SNA", 23),
            new DepartmentColumn("ANCS", 24),
            new DepartmentColumn("DP", 25),
            new DepartmentColumn("SPA", 26)
    };

    private static final MetricColumns[] METRIC_COLUMNS = {
            new MetricColumns("IO_COUNT", 31, 32),
            new MetricColumns("LIGHTING_POINTS", 33, 34),
            new MetricColumns("CABLE_RECORDS", 35, 36),
            new MetricColumns("CABLE_ENGINEERING_TIME", 37, 38),
            new MetricColumns("CABLE_ROUTING_TIME", 39, 40),
            new MetricColumns("LIGHTING_POINT_TIME", 41, 42),
            new MetricColumns("IO_ENGINEERING_TIME", 43, 44),
            new MetricColumns("IO_DELIVERY_TIME", 45, 46)
    };

    /** Neue Beleuchtungsaufteilung: Code, Name, Einheit. */
    private static final String[][] LIGHTING_SPLIT_TYPES = {
            {"LIGHTING_INDOOR", "Brennstellen Innenbeleuchtung", "Stk."},
            {"LIGHTING_OUTDOOR", "Brennstellen Außenbeleuchtung", "Stk."},
            {"LIGHTING_SWITCH_SOCKET", "Schalter/Steckdosen", "Stk."},
            {"LIGHTING_TOTAL", "Brennstellen Gesamt", "Stk."}
    };

    // Werden pro Import aus der Kopfzeile ermittelt
    private static int insertPosition = Integer.MAX_VALUE;
    private static int insertedColumns = 0;
    private static int[] lightingColumns = null;

    private ExcelImporter() {
    }

    /**
     * Importiert ein Blatt anhand seines Namens.
     *
     * @param excelPath Pfad zur xlsx-Datei
     * @param sheetName Name des zu importierenden Blatts
     */
    public static void importSheet(String excelPath, String sheetName) {
        try (
                FileInputStream input = new FileInputStream(excelPath);
                Workbook workbook = WorkbookFactory.create(input);
                Connection connection = Database.connect()
        ) {
            Sheet sheet = workbook.getSheet(sheetName);

            if (sheet == null) {
                throw new IllegalArgumentException(
                        "Excel-Blatt nicht gefunden: " + sheetName
                );
            }

            FormulaEvaluator evaluator =
                    workbook.getCreationHelper().createFormulaEvaluator();

            connection.setAutoCommit(false);

            try {
                detectLightingColumns(sheet, evaluator);
                ensureLightingMetricTypes(connection);

                int imported = importRows(connection, sheet, evaluator);
                connection.commit();

                System.out.println(
                        imported + " neue Projekte aus dem Blatt '"
                                + sheet.getSheetName()
                                + "' wurden importiert."
                );
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (IOException | SQLException | RuntimeException exception) {
            System.err.println(
                    "Excel-Import fehlgeschlagen: "
                            + exception.getMessage()
            );
            exception.printStackTrace();
        }
    }

    private static int importRows(
            Connection connection,
            Sheet sheet,
            FormulaEvaluator evaluator
    ) throws SQLException {

        String categoryName = sheet.getSheetName().trim();

        if (categoryName.isBlank()) {
            throw new IllegalArgumentException(
                    "Der Blattname darf nicht leer sein."
            );
        }

        int categoryId = findOrCreateCategory(connection, categoryName);
        int imported = 0;

        for (int rowIndex = FIRST_DATA_ROW;
             rowIndex <= sheet.getLastRowNum();
             rowIndex++) {

            Row row = sheet.getRow(rowIndex);

            if (row == null || isEmptyProjectRow(row, evaluator)) {
                continue;
            }

            int excelRow = rowIndex + 1;
            String shipyardName = getCellText(cell(row, 0), evaluator);
            String shipName = getCellText(cell(row, 1), evaluator);
            String shipTypeName = getCellText(cell(row, 2), evaluator);

            if (shipyardName.isBlank()) {
                shipyardName = "n.b.";

                System.out.println(
                        "Hinweis: In Excel-Zeile " + excelRow
                                + " fehlt die Werft. Gespeichert als 'n.b.'."
                );
            }

            requireValue(shipName, "Schiffsname", excelRow);

            if (shipTypeName.isBlank()) {
                shipTypeName = "n.b.";

                System.out.println(
                        "Hinweis: In Excel-Zeile " + excelRow
                                + " fehlt der Schiffstyp. Gespeichert als 'n.b.'."
                );
            }

            int shipyardId = findOrCreateShipyard(
                    connection,
                    shipyardName
            );

            int shipTypeId = findOrCreateShipType(
                    connection,
                    categoryId,
                    shipTypeName
            );

            Long existingProjectId =
                    findProjectId(connection, shipyardId, shipName);

            if (existingProjectId != null) {
                int written = upsertLightingSplit(
                        connection, row, evaluator, existingProjectId
                );
                System.out.println(
                        "Excel-Zeile " + excelRow + ": Projekt '"
                                + shipName + "' existiert bereits, "
                                + written + " Beleuchtungswerte ergänzt."
                );
                continue;
            }

            long projectId = insertProject(
                    connection,
                    row,
                    evaluator,
                    shipyardId,
                    shipTypeId,
                    excelRow
            );

            insertDepartments(
                    connection,
                    row,
                    evaluator,
                    projectId
            );

            insertMetrics(
                    connection,
                    row,
                    evaluator,
                    projectId
            );

            upsertLightingSplit(connection, row, evaluator, projectId);

            imported++;
        }

        return imported;
    }

    private static long insertProject(
            Connection connection,
            Row row,
            FormulaEvaluator evaluator,
            int shipyardId,
            int shipTypeId,
            int excelRow
    ) throws SQLException {

        String status = firstNonBlank(
                getCellText(cell(row, 28), evaluator),
                getCellText(cell(row, 3), evaluator)
        );

        if (status.isBlank()) {
            status = "Unbekannt";
            System.out.println(
                    "Hinweis: In Excel-Zeile " + excelRow
                            + " fehlt der Projektstatus."
            );
        }

        String sql = """
                INSERT INTO projects (
                    shipyard_id,
                    ship_type_id,
                    ship_name,
                    project_status,
                    length_m,
                    width_m,
                    volume_gt,
                    area_sqm,
                    cable_calculated_km,
                    cable_offer_km,
                    cable_actual_km,
                    ring_cable_included,
                    offer_date,
                    order_date,
                    construction_start,
                    construction_end_planned,
                    construction_end_actual,
                    editor,
                    evaluation,
                    link,
                    comments,
                    evaluation_relevance
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """;

        try (PreparedStatement statement = connection.prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setInt(1, shipyardId);
            statement.setInt(2, shipTypeId);
            statement.setString(
                    3,
                    getCellText(cell(row, 1), evaluator)
            );
            statement.setString(4, status);

            setNullableDouble(statement, 5, getCellNumber(cell(row, 4), evaluator));
            setNullableDouble(statement, 6, getCellNumber(cell(row, 5), evaluator));
            setNullableDouble(statement, 7, getCellNumber(cell(row, 6), evaluator));
            setNullableDouble(statement, 8, getCellNumber(cell(row, 7), evaluator));
            setNullableDouble(statement, 9, getCellNumber(cell(row, 8), evaluator));
            setNullableDouble(statement, 10, getCellNumber(cell(row, 9), evaluator));
            setNullableDouble(statement, 11, getCellNumber(cell(row, 10), evaluator));

            statement.setInt(
                    12,
                    getBooleanValue(cell(row, 11), evaluator) ? 1 : 0
            );

            setNullableDate(statement, 13, getCellDate(cell(row, 12), evaluator));
            setNullableDate(statement, 14, getCellDate(cell(row, 13), evaluator));
            setNullableDate(statement, 15, getCellDate(cell(row, 14), evaluator));
            setNullableDate(statement, 16, getCellDate(cell(row, 15), evaluator));
            setNullableDate(statement, 17, getCellDate(cell(row, 16), evaluator));

            setNullableText(statement, 18, getCellText(cell(row, 27), evaluator));
            setNullableText(statement, 19, getCellText(cell(row, 29), evaluator));
            setNullableText(statement, 20, getCellText(cell(row, 30), evaluator));
            setNullableText(statement, 21, getCellText(cell(row, 48), evaluator));

            statement.setInt(
                    22,
                    getRelevance(cell(row, 49), evaluator)
            );

            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }

        throw new SQLException(
                "Für Excel-Zeile " + excelRow
                        + " wurde keine Projekt-ID erzeugt."
        );
    }

    private static void insertDepartments(
            Connection connection,
            Row row,
            FormulaEvaluator evaluator,
            long projectId
    ) throws SQLException {

        String sql = """
                INSERT INTO project_departments (
                    project_id,
                    department_id,
                    value
                )
                SELECT ?, id, ?
                FROM departments
                WHERE code = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            for (DepartmentColumn mapping : DEPARTMENT_COLUMNS) {
                String value = getCellText(
                        cell(row, mapping.columnIndex()),
                        evaluator
                );

                if (isMissingText(value)) {
                    continue;
                }

                statement.setLong(1, projectId);
                statement.setString(2, value);
                statement.setString(3, mapping.departmentCode());
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    private static void insertMetrics(
            Connection connection,
            Row row,
            FormulaEvaluator evaluator,
            long projectId
    ) throws SQLException {

        String sql = """
                INSERT INTO project_metrics (
                    project_id,
                    metric_type_id,
                    target_value,
                    actual_value
                )
                SELECT ?, id, ?, ?
                FROM metric_types
                WHERE code = ?
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {

            for (MetricColumns mapping : METRIC_COLUMNS) {
                Double target = getCellNumber(
                        cell(row, mapping.targetColumn()),
                        evaluator
                );
                Double actual = getCellNumber(
                        cell(row, mapping.actualColumn()),
                        evaluator
                );

                if (target == null && actual == null) {
                    continue;
                }

                statement.setLong(1, projectId);
                setNullableDouble(statement, 2, target);
                setNullableDouble(statement, 3, actual);
                statement.setString(4, mapping.metricCode());
                statement.addBatch();
            }

            Double[] fireDetectors = getTargetAndActual(
                    cell(row, 47),
                    evaluator
            );

            if (fireDetectors[0] != null || fireDetectors[1] != null) {
                statement.setLong(1, projectId);
                setNullableDouble(statement, 2, fireDetectors[0]);
                setNullableDouble(statement, 3, fireDetectors[1]);
                statement.setString(4, "FIRE_DETECTORS");
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    // ------------------------------------------------------------
    // NEU: Beleuchtungsaufteilung
    // ------------------------------------------------------------

    /** Liest eine alte Spaltennummer und verschiebt sie bei Bedarf. */
    private static Cell cell(Row row, int oldIndex) {
        int index = oldIndex >= insertPosition
                ? oldIndex + insertedColumns
                : oldIndex;
        return row.getCell(index);
    }

    /** Sucht die neuen Beleuchtungsspalten über die Überschrift. */
    private static void detectLightingColumns(
            Sheet sheet,
            FormulaEvaluator evaluator
    ) {
        insertPosition = Integer.MAX_VALUE;
        insertedColumns = 0;
        lightingColumns = null;

        Row header = sheet.getRow(0);
        if (header == null) {
            return;
        }

        int indoor = findHeader(header, evaluator, 0, "innenbeleuchtung");

        if (indoor < 0) {
            System.out.println(
                    "Keine Spalte 'Innenbeleuchtung' gefunden: "
                            + "Import im alten Spaltenformat."
            );
            return;
        }

        int outdoor = findHeader(header, evaluator, indoor,
                "außenbeleuchtung", "aussenbeleuchtung");
        int switchSocket = findHeader(header, evaluator, indoor, "schalter");
        int total = findHeader(header, evaluator, indoor, "gesamt");

        if (outdoor < 0 || switchSocket < 0 || total < 0) {
            throw new IllegalArgumentException(
                    "Beleuchtungsspalten unvollständig. Gefunden: Innen="
                            + indoor + ", Außen=" + outdoor
                            + ", Schalter/Steckdosen=" + switchSocket
                            + ", Gesamt=" + total
            );
        }

        lightingColumns = new int[]{indoor, outdoor, switchSocket, total};
        insertPosition = indoor;
        insertedColumns =
                Math.max(Math.max(outdoor, switchSocket), total) - indoor + 1;

        System.out.println(
                "Beleuchtungsaufteilung erkannt ab Spalte " + (indoor + 1)
                        + ", " + insertedColumns
                        + " neue Spalten. Alle späteren Spalten werden verschoben."
        );

        // Kontrolle: Stimmen die verschobenen Spalten?
        int[] check = {33, 34, 47, 48, 49};
        String[] meaning = {
                "Brennstellen Soll", "Brennstellen Ist",
                "Brandmelder", "Kommentare", "Relevanz"
        };
        for (int i = 0; i < check.length; i++) {
            System.out.println(
                    "  Kontrolle " + meaning[i] + " -> Überschrift: '"
                            + getCellText(cell(header, check[i]), evaluator)
                                    .replace("\n", " ")
                            + "'"
            );
        }
    }

    private static int findHeader(
            Row header,
            FormulaEvaluator evaluator,
            int startIndex,
            String... keywords
    ) {
        int last = header.getLastCellNum();
        int end = startIndex == 0 ? last : Math.min(last, startIndex + 10);

        for (int i = startIndex; i < end; i++) {
            String text = getCellText(header.getCell(i), evaluator)
                    .toLowerCase(Locale.GERMAN)
                    .replaceAll("\\s+", "")
                    .replace("-", "");

            for (String keyword : keywords) {
                if (text.contains(keyword)) {
                    return i;
                }
            }
        }
        return -1;
    }

    /** Legt die neuen Kennzahltypen an, falls sie fehlen. */
    private static void ensureLightingMetricTypes(Connection connection)
            throws SQLException {

        String sql = """
                INSERT INTO metric_types (code, name, unit)
                SELECT ?, ?, ?
                WHERE NOT EXISTS (
                    SELECT 1 FROM metric_types WHERE code = ?
                )
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String[] type : LIGHTING_SPLIT_TYPES) {
                statement.setString(1, type[0]);
                statement.setString(2, type[1]);
                statement.setString(3, type[2]);
                statement.setString(4, type[0]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    /** Schreibt Innen/Außen/Schalter/Gesamt als Soll-Werte. */
    private static int upsertLightingSplit(
            Connection connection,
            Row row,
            FormulaEvaluator evaluator,
            long projectId
    ) throws SQLException {

        if (lightingColumns == null) {
            return 0;
        }

        String deleteSql = """
                DELETE FROM project_metrics
                WHERE project_id = ?
                  AND metric_type_id = (
                      SELECT id FROM metric_types WHERE code = ?
                  )
                """;

        String insertSql = """
                INSERT INTO project_metrics (
                    project_id,
                    metric_type_id,
                    target_value,
                    actual_value
                )
                SELECT ?, id, ?, NULL
                FROM metric_types
                WHERE code = ?
                """;

        int written = 0;

        try (
                PreparedStatement delete = connection.prepareStatement(deleteSql);
                PreparedStatement insert = connection.prepareStatement(insertSql)
        ) {
            for (int i = 0; i < lightingColumns.length; i++) {
                Double value = getCellNumber(
                        row.getCell(lightingColumns[i]),
                        evaluator
                );

                if (value == null) {
                    continue;
                }

                String code = LIGHTING_SPLIT_TYPES[i][0];

                delete.setLong(1, projectId);
                delete.setString(2, code);
                delete.executeUpdate();

                insert.setLong(1, projectId);
                insert.setDouble(2, value);
                insert.setString(3, code);
                insert.executeUpdate();

                written++;
            }
        }

        return written;
    }

    private static Long findProjectId(
            Connection connection,
            int shipyardId,
            String shipName
    ) throws SQLException {

        String sql = """
                SELECT id
                FROM projects
                WHERE shipyard_id = ?
                  AND ship_name = ? COLLATE NOCASE
                LIMIT 1
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, shipyardId);
            statement.setString(2, shipName);

            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getLong("id") : null;
            }
        }
    }

    // ------------------------------------------------------------
    // Stammdaten anlegen / finden
    // ------------------------------------------------------------

    private static int findOrCreateCategory(
            Connection connection,
            String categoryName
    ) throws SQLException {

        String selectSql = """
                SELECT id
                FROM ship_categories
                WHERE name = ? COLLATE NOCASE
                """;

        Integer existingId = findId(connection, selectSql, categoryName);

        if (existingId != null) {
            return existingId;
        }

        String insertSql = """
                INSERT INTO ship_categories (name)
                VALUES (?)
                """;

        return insertAndGetId(connection, insertSql, categoryName);
    }

    private static int findOrCreateShipyard(
            Connection connection,
            String shipyardName
    ) throws SQLException {

        String selectSql = """
                SELECT id
                FROM shipyards
                WHERE name = ? COLLATE NOCASE
                """;

        Integer existingId = findId(connection, selectSql, shipyardName);

        if (existingId != null) {
            return existingId;
        }

        String insertSql = """
                INSERT INTO shipyards (name)
                VALUES (?)
                """;

        return insertAndGetId(connection, insertSql, shipyardName);
    }

    private static int findOrCreateShipType(
            Connection connection,
            int categoryId,
            String shipTypeName
    ) throws SQLException {

        String selectSql = """
                SELECT id
                FROM ship_types
                WHERE category_id = ?
                  AND name = ? COLLATE NOCASE
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(selectSql)) {
            statement.setInt(1, categoryId);
            statement.setString(2, shipTypeName);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getInt("id");
                }
            }
        }

        String insertSql = """
                INSERT INTO ship_types (category_id, name)
                VALUES (?, ?)
                """;

        try (PreparedStatement statement = connection.prepareStatement(
                insertSql,
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setInt(1, categoryId);
            statement.setString(2, shipTypeName);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }

        throw new SQLException(
                "Schiffstyp konnte nicht angelegt werden: "
                        + shipTypeName
        );
    }

    private static Integer findId(
            Connection connection,
            String sql,
            String value
    ) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setString(1, value);

            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return result.getInt("id");
                }
            }
        }

        return null;
    }

    private static int insertAndGetId(
            Connection connection,
            String sql,
            String value
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                sql,
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setString(1, value);
            statement.executeUpdate();

            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getInt(1);
                }
            }
        }

        throw new SQLException("Datensatz konnte nicht angelegt werden: " + value);
    }

    // ------------------------------------------------------------
    // Zellen lesen
    // ------------------------------------------------------------

    private static String getCellText(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        if (cell == null) {
            return "";
        }

        return FORMATTER.formatCellValue(cell, evaluator).trim();
    }

    private static Double getCellNumber(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        if (cell == null) {
            return null;
        }

        CellType type = cell.getCellType();

        if (type == CellType.FORMULA) {
            type = evaluator.evaluateFormulaCell(cell);
        }

        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return cell.getNumericCellValue();
        }

        String text = getCellText(cell, evaluator);

        if (isMissingText(text)) {
            return null;
        }

        // Werte wie "50000/" werden als 50000 übernommen.
        if (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1).trim();
        }

        // Komplexe Angaben wie "900 (Wettbewerb 1100 km)" werden bewusst
        // nicht automatisch geraten.
        if (!text.matches("[-+]?\\d{1,3}(?:[. ]\\d{3})*(?:,\\d+)?"
                + "|[-+]?\\d+(?:[.,]\\d+)?")) {
            return null;
        }

        String normalized = text.replace(" ", "");

        if (normalized.contains(",")) {
            normalized = normalized.replace(".", "").replace(',', '.');
        } else if (normalized.matches("[-+]?[1-9]\\d{0,2}(?:\\.\\d{3})+")) {
            // "3.400" oder "11.845": Punkt ist Tausendertrennzeichen
            normalized = normalized.replace(".", "");
        }

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static LocalDate getCellDate(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        if (cell == null) {
            return null;
        }

        CellType type = cell.getCellType();

        if (type == CellType.FORMULA) {
            type = evaluator.evaluateFormulaCell(cell);
        }

        if (type == CellType.NUMERIC) {
            return DateUtil.getJavaDate(cell.getNumericCellValue())
                    .toInstant()
                    .atZone(ZoneId.systemDefault())
                    .toLocalDate();
        }

        String text = getCellText(cell, evaluator);

        if (isMissingText(text)) {
            return null;
        }

        String normalized = text.replace('.', '/').replace('-', '/');

        try {
            if (normalized.matches("\\d{1,2}/\\d{4}")) {
                return LocalDate.parse(normalized, MONTH_YEAR)
                        .withDayOfMonth(1);
            }

            if (normalized.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                DateTimeFormatter formatter =
                        DateTimeFormatter.ofPattern("d/M/uuuu");
                return LocalDate.parse(normalized, formatter);
            }
        } catch (DateTimeParseException ignored) {
            // Nicht erkennbare Texte werden als fehlendes Datum behandelt.
        }

        return null;
    }

    private static Double[] getTargetAndActual(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        if (cell == null) {
            return new Double[]{null, null};
        }

        if (cell.getCellType() == CellType.NUMERIC) {
            return new Double[]{cell.getNumericCellValue(), null};
        }

        String text = getCellText(cell, evaluator);

        if (isMissingText(text)) {
            return new Double[]{null, null};
        }

        String[] parts = text.split("/", -1);
        Double target = parsePlainNumber(parts[0]);
        Double actual = parts.length > 1
                ? parsePlainNumber(parts[1])
                : null;

        return new Double[]{target, actual};
    }

    private static Double parsePlainNumber(String text) {
        if (text == null) {
            return null;
        }

        String normalized = text.trim().replace(" ", "");

        if (!normalized.matches("[-+]?\\d{1,3}(?:\\.\\d{3})*(?:,\\d+)?"
                + "|[-+]?\\d+(?:[.,]\\d+)?")) {
            return null;
        }

        if (normalized.contains(",")) {
            normalized = normalized.replace(".", "").replace(',', '.');
        } else if (normalized.matches("[-+]?[1-9]\\d{0,2}(?:\\.\\d{3})+")) {
            normalized = normalized.replace(".", "");
        }

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean getBooleanValue(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        String value = getCellText(cell, evaluator)
                .trim()
                .toLowerCase(Locale.GERMAN);

        return value.equals("ja")
                || value.equals("x")
                || value.equals("1")
                || value.equals("true");
    }

    private static int getRelevance(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        Double number = getCellNumber(cell, evaluator);

        if (number != null) {
            return number.intValue() == 0 ? 0 : 1;
        }

        String text = getCellText(cell, evaluator)
                .toLowerCase(Locale.GERMAN);

        if (text.equals("nein") || text.equals("false")) {
            return 0;
        }

        return 1;
    }

    // ------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------

    private static void setNullableDouble(
            PreparedStatement statement,
            int parameter,
            Double value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.REAL);
        } else {
            statement.setDouble(parameter, value);
        }
    }

    private static void setNullableDate(
            PreparedStatement statement,
            int parameter,
            LocalDate value
    ) throws SQLException {
        if (value == null) {
            statement.setNull(parameter, Types.DATE);
        } else {
            statement.setString(parameter, ISO_DATE.format(value));
        }
    }

    private static void setNullableText(
            PreparedStatement statement,
            int parameter,
            String value
    ) throws SQLException {
        if (value == null || value.isBlank()) {
            statement.setNull(parameter, Types.VARCHAR);
        } else {
            statement.setString(parameter, value);
        }
    }

    private static boolean isEmptyProjectRow(
            Row row,
            FormulaEvaluator evaluator
    ) {
        return getCellText(cell(row, 0), evaluator).isBlank()
                && getCellText(cell(row, 1), evaluator).isBlank()
                && getCellText(cell(row, 2), evaluator).isBlank();
    }

    private static boolean isMissingText(String value) {
        if (value == null || value.isBlank()) {
            return true;
        }

        String normalized = value
                .trim()
                .toLowerCase(Locale.GERMAN);

        return normalized.equals("n.b.")
                || normalized.equals("n.b")
                || normalized.equals("./.")
                || normalized.equals("-");
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }

        return second == null ? "" : second.trim();
    }

    private static void requireValue(
            String value,
            String columnName,
            int excelRow
    ) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Excel-Zeile " + excelRow + ": Pflichtfeld '"
                            + columnName + "' ist leer."
            );
        }
    }

    private record DepartmentColumn(
            String departmentCode,
            int columnIndex
    ) {
    }

    private record MetricColumns(
            String metricCode,
            int targetColumn,
            int actualColumn
    ) {
    }
}
