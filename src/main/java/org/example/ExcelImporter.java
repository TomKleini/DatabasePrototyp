package org.example;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

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
 * Einmaliger Excel-Import für die bisher betrachtete Blattstruktur.
 *
 * Blattname                 -> Schiffskategorie
 * Spalte C                  -> Schiffsuntertyp
 * Spalten A bis Q           -> Projektstammdaten
 * Spalten R bis AA          -> beteiligte Fachbereiche
 * Spalten AF bis AV         -> Soll-/Ist-Kennzahlen
 * Spalten AY bis BH         -> werden nicht importiert (berechnete Werte)
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
                int imported = importRows(connection, sheet, evaluator);
                connection.commit();

                System.out.println(
                        imported + " Projekte aus dem Blatt '"
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
            String shipyardName = getCellText(row.getCell(0), evaluator);
            String shipName = getCellText(row.getCell(1), evaluator);
            String shipTypeName = getCellText(row.getCell(2), evaluator);

            if (shipyardName.isBlank()) {
                shipyardName = "n.b.";

                System.out.println(
                        "Hinweis: In Excel-Zeile " + excelRow
                                + " fehlt der Schiffstyp. Gespeichert als 'n.b.'."
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

            if (projectExists(connection, shipyardId, shipName)) {
                System.out.println(
                        "Excel-Zeile " + excelRow
                                + " übersprungen: Projekt '"
                                + shipName + "' existiert bereits."
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
                getCellText(row.getCell(28), evaluator),
                getCellText(row.getCell(3), evaluator)
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
                    getCellText(row.getCell(1), evaluator)
            );
            statement.setString(4, status);

            setNullableDouble(
                    statement, 5,
                    getCellNumber(row.getCell(4), evaluator)
            );
            setNullableDouble(
                    statement, 6,
                    getCellNumber(row.getCell(5), evaluator)
            );
            setNullableDouble(
                    statement, 7,
                    getCellNumber(row.getCell(6), evaluator)
            );
            setNullableDouble(
                    statement, 8,
                    getCellNumber(row.getCell(7), evaluator)
            );
            setNullableDouble(
                    statement, 9,
                    getCellNumber(row.getCell(8), evaluator)
            );
            setNullableDouble(
                    statement, 10,
                    getCellNumber(row.getCell(9), evaluator)
            );
            setNullableDouble(
                    statement, 11,
                    getCellNumber(row.getCell(10), evaluator)
            );

            statement.setInt(
                    12,
                    getBooleanValue(row.getCell(11), evaluator) ? 1 : 0
            );

            setNullableDate(
                    statement, 13,
                    getCellDate(row.getCell(12), evaluator)
            );
            setNullableDate(
                    statement, 14,
                    getCellDate(row.getCell(13), evaluator)
            );
            setNullableDate(
                    statement, 15,
                    getCellDate(row.getCell(14), evaluator)
            );
            setNullableDate(
                    statement, 16,
                    getCellDate(row.getCell(15), evaluator)
            );
            setNullableDate(
                    statement, 17,
                    getCellDate(row.getCell(16), evaluator)
            );

            setNullableText(
                    statement, 18,
                    getCellText(row.getCell(27), evaluator)
            );
            setNullableText(
                    statement, 19,
                    getCellText(row.getCell(29), evaluator)
            );
            setNullableText(
                    statement, 20,
                    getCellText(row.getCell(30), evaluator)
            );
            setNullableText(
                    statement, 21,
                    getCellText(row.getCell(48), evaluator)
            );

            statement.setInt(
                    22,
                    getRelevance(row.getCell(49), evaluator)
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
                        row.getCell(mapping.columnIndex()),
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
                        row.getCell(mapping.targetColumn()),
                        evaluator
                );
                Double actual = getCellNumber(
                        row.getCell(mapping.actualColumn()),
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
                    row.getCell(47),
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

    private static int findOrCreateCategory(
            Connection connection,
            String categoryName
    ) throws SQLException {

        String selectSql = """
                SELECT id
                FROM ship_categories
                WHERE name = ? COLLATE NOCASE
                """;

        Integer existingId = findId(
                connection,
                selectSql,
                categoryName
        );

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

        Integer existingId = findId(
                connection,
                selectSql,
                shipyardName
        );

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

    private static boolean projectExists(
            Connection connection,
            int shipyardId,
            String shipName
    ) throws SQLException {

        String sql = """
                SELECT 1
                FROM projects
                WHERE shipyard_id = ?
                  AND ship_name = ? COLLATE NOCASE
                LIMIT 1
                """;

        try (PreparedStatement statement =
                     connection.prepareStatement(sql)) {
            statement.setInt(1, shipyardId);
            statement.setString(2, shipName);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
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
        return getCellText(row.getCell(0), evaluator).isBlank()
                && getCellText(row.getCell(1), evaluator).isBlank()
                && getCellText(row.getCell(2), evaluator).isBlank();
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
