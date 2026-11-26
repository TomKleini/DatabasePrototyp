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
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFTable;
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
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Excel-Import für ein Blatt der Schiffsdatenbank.
 *
 * Blattname                 -> Schiffskategorie
 * Spalte "Schiffstyp"       -> Schiffsuntertyp
 * Zeile 1                   -> Überschriften
 * Datenzeilen               -> nur innerhalb der Excel-Tabelle (Notizen darunter werden ignoriert)
 *
 * Die Spalten werden über ihre ÜBERSCHRIFT gefunden, nicht über die Spaltennummer.
 * Dadurch bricht der Import nicht, wenn Spalten eingefügt, gelöscht oder verschoben
 * werden. Welche Überschrift welchem Feld zugeordnet wurde, steht beim Import
 * in der Konsole.
 *
 * Beleuchtung:
 *   Gesamt                          -> LIGHTING_POINTS (Soll)
 *   (Die Spalte "Brennstellen Soll" wird ignoriert.)
 *   Brennstellen Ist                -> LIGHTING_POINTS (Ist)
 *   Innenbeleuchtung                -> LIGHTING_INDOOR (Soll)
 *   Außenbeleuchtung                -> LIGHTING_OUTDOOR (Soll)
 *   Schalter/Steckdosen             -> LIGHTING_SWITCH_SOCKET (Soll)
 */
public final class ExcelImporter {

    private static final DataFormatter FORMATTER =
            new DataFormatter(Locale.GERMANY);

    private static final DateTimeFormatter ISO_DATE =
            DateTimeFormatter.ISO_LOCAL_DATE;

    private static final DateTimeFormatter MONTH_YEAR =
            DateTimeFormatter.ofPattern("M/uuuu");

    private static final int HEADER_ROW = 0;
    private static final int FIRST_DATA_ROW = 1;
    private static final int MAX_NAME_LENGTH = 120;
    private static final int MAX_PRINTED_WARNINGS = 200;

    /** Kennzahl-Typen, die in der Tabelle metric_types vorhanden sein müssen. */
    private static final String[] REQUIRED_METRIC_CODES = {
            "IO_COUNT", "LIGHTING_POINTS", "CABLE_RECORDS",
            "CABLE_ENGINEERING_TIME", "CABLE_ROUTING_TIME",
            "LIGHTING_POINT_TIME", "IO_ENGINEERING_TIME", "IO_DELIVERY_TIME",
            "FIRE_DETECTORS",
            "LIGHTING_INDOOR", "LIGHTING_OUTDOOR", "LIGHTING_SWITCH_SOCKET"
    };

    /** Hinweise zu Zellen, die nicht gelesen werden konnten (pro Importlauf). */
    private static final List<String> WARNINGS = new ArrayList<>();

    /** Aktuelles Schiff, nur für aussagekräftige Hinweise. */
    private static String currentShip = "";

    private ExcelImporter() {
    }

    /**
     * Importiert ein Blatt anhand seines Namens.
     *
     * @param excelPath Pfad zur xlsx-Datei
     * @param sheetName Name des zu importierenden Blatts
     */
    public static void importSheet(String excelPath, String sheetName) {
        WARNINGS.clear();
        currentShip = "";
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
            evaluator.setIgnoreMissingWorkbooks(true);
            connection.setAutoCommit(false);
            try {
                int[] result = importRows(connection, sheet, evaluator);
                connection.commit();
                System.out.println(
                        result[0] + " Projekte aus dem Blatt '"
                                + sheet.getSheetName()
                                + "' wurden importiert, "
                                + result[1] + " übersprungen."
                );
                printWarnings();
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

    // ------------------------------------------------------------------
    // Zeilen
    // ------------------------------------------------------------------

    /** @return {importiert, übersprungen} */
    private static int[] importRows(
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

        Columns columns = readColumns(sheet, evaluator);
        columns.printMapping(sheet.getSheetName());
        columns.requireMandatory();
        ensureMetricTypes(connection);

        int lastRow = lastDataRow(sheet);
        System.out.println(
                "Datenbereich: Excel-Zeilen " + (FIRST_DATA_ROW + 1)
                        + " bis " + (lastRow + 1)
        );

        int categoryId = findOrCreateCategory(connection, categoryName);
        int imported = 0;
        int skipped = 0;

        for (int rowIndex = FIRST_DATA_ROW; rowIndex <= lastRow; rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || isEmptyProjectRow(row, evaluator, columns)) {
                continue;
            }
            int excelRow = rowIndex + 1;

            String shipyardName = normalizeName(
                    getCellText(cell(row, columns.shipyard), evaluator));
            String shipName = normalizeName(
                    getCellText(cell(row, columns.shipName), evaluator));
            String shipTypeName = normalizeName(
                    getCellText(cell(row, columns.shipType), evaluator));
            currentShip = shipName;

            if (shipName.length() > MAX_NAME_LENGTH) {
                System.out.println(
                        "Excel-Zeile " + excelRow
                                + " übersprungen: Schiffsname ist ein Fließtext "
                                + "(Notiz?), kein Projekt."
                );
                skipped++;
                continue;
            }
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

            if (projectExists(connection, shipyardId, shipName)) {
                System.out.println(
                        "Excel-Zeile " + excelRow
                                + " übersprungen: Projekt '"
                                + shipName + "' existiert bereits."
                );
                skipped++;
                continue;
            }

            long projectId = insertProject(
                    connection,
                    columns,
                    row,
                    evaluator,
                    shipyardId,
                    shipTypeId,
                    shipName,
                    excelRow
            );
            insertDepartments(connection, columns, row, evaluator, projectId);
            insertMetrics(connection, columns, row, evaluator, projectId, excelRow);
            imported++;
        }
        currentShip = "";
        return new int[]{imported, skipped};
    }

    /**
     * Letzte Datenzeile: Ende der Excel-Tabelle, die in der Kopfzeile beginnt.
     * Notizen, Durchschnittswerte oder Diagramme unter der Tabelle werden so
     * nicht als Projekte gelesen.
     */
    private static int lastDataRow(Sheet sheet) {
        if (sheet instanceof XSSFSheet xssfSheet) {
            for (XSSFTable table : xssfSheet.getTables()) {
                if (table.getStartCellReference().getRow() == HEADER_ROW) {
                    return table.getEndCellReference().getRow();
                }
            }
        }
        System.out.println(
                "Hinweis: Keine Excel-Tabelle in der Kopfzeile gefunden. "
                        + "Es wird bis zur letzten belegten Zeile gelesen."
        );
        return sheet.getLastRowNum();
    }

    private static Columns readColumns(Sheet sheet, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(HEADER_ROW);
        if (header == null) {
            throw new IllegalArgumentException(
                    "Die Kopfzeile (Zeile 1) ist leer."
            );
        }
        List<String> headers = new ArrayList<>();
        int last = Math.max(header.getLastCellNum(), 0);
        for (int i = 0; i < last; i++) {
            headers.add(getCellText(header.getCell(i), evaluator));
        }
        return new Columns(headers);
    }

    /** Prüft, dass alle Kennzahl-Typen in der Datenbank angelegt sind. */
    private static void ensureMetricTypes(Connection connection)
            throws SQLException {

        String createSql = """
                INSERT INTO metric_types (code, name, unit)
                SELECT ?, ?, ?
                WHERE NOT EXISTS (SELECT 1 FROM metric_types WHERE code = ?)
                """;
        String[][] lightingTypes = {
                {"LIGHTING_INDOOR", "Brennstellen Innenbeleuchtung", "Stk."},
                {"LIGHTING_OUTDOOR", "Brennstellen Außenbeleuchtung", "Stk."},
                {"LIGHTING_SWITCH_SOCKET", "Schalter/Steckdosen", "Stk."}
        };
        try (PreparedStatement create = connection.prepareStatement(createSql)) {
            for (String[] type : lightingTypes) {
                create.setString(1, type[0]);
                create.setString(2, type[1]);
                create.setString(3, type[2]);
                create.setString(4, type[0]);
                create.addBatch();
            }
            create.executeBatch();
        }
        Set<String> existing = new HashSet<>();
        try (
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT code FROM metric_types"
                );
                ResultSet result = statement.executeQuery()
        ) {
            while (result.next()) {
                existing.add(result.getString("code"));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String code : REQUIRED_METRIC_CODES) {
            if (!existing.contains(code)) {
                missing.add(code);
            }
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "In der Tabelle metric_types fehlen Kennzahlen: " + missing
                            + ". Bitte Database.insertMasterData() mit den neuen "
                            + "Kennzahlen ausführen (Database.java aktualisieren)."
            );
        }
    }

    // ------------------------------------------------------------------
    // Projekt
    // ------------------------------------------------------------------

    private static long insertProject(
            Connection connection,
            Columns c,
            Row row,
            FormulaEvaluator evaluator,
            int shipyardId,
            int shipTypeId,
            String shipName,
            int excelRow
    ) throws SQLException {
        String status = firstNonBlank(
                statusText(cell(row, c.status), evaluator),
                statusText(cell(row, c.offerOrOrder), evaluator)
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
            statement.setString(3, shipName);
            statement.setString(4, status);
            setNullableDouble(statement, 5, number(row, c.length, evaluator));
            setNullableDouble(statement, 6, number(row, c.width, evaluator));
            setNullableDouble(statement, 7, number(row, c.volume, evaluator));
            setNullableDouble(statement, 8, number(row, c.area, evaluator));
            setNullableDouble(statement, 9, number(row, c.cableCalculated, evaluator));
            setNullableDouble(statement, 10, number(row, c.cableOffer, evaluator));
            setNullableDouble(statement, 11, number(row, c.cableActual, evaluator));
            statement.setInt(
                    12,
                    getBooleanValue(cell(row, c.ringCable), evaluator) ? 1 : 0
            );
            setNullableDate(statement, 13, getCellDate(cell(row, c.offerDate), evaluator));
            setNullableDate(statement, 14, getCellDate(cell(row, c.orderDate), evaluator));
            setNullableDate(statement, 15, getCellDate(cell(row, c.constructionStart), evaluator));
            setNullableDate(statement, 16, getCellDate(cell(row, c.constructionEndPlanned), evaluator));
            setNullableDate(statement, 17, getCellDate(cell(row, c.constructionEndActual), evaluator));
            setNullableText(statement, 18, getCellText(cell(row, c.editor), evaluator));
            setNullableText(statement, 19, getCellText(cell(row, c.evaluation), evaluator));
            setNullableText(statement, 20, getCellText(cell(row, c.link), evaluator));
            setNullableText(statement, 21, getCellText(cell(row, c.comments), evaluator));
            statement.setInt(
                    22,
                    getRelevance(cell(row, c.relevance), evaluator)
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
            Columns c,
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
            for (int i = 0; i < Columns.DEPARTMENT_CODES.length; i++) {
                String value = getCellText(
                        cell(row, c.department[i]),
                        evaluator
                );
                if (isMissingText(value)) {
                    continue;
                }
                statement.setLong(1, projectId);
                statement.setString(2, value);
                statement.setString(3, Columns.DEPARTMENT_CODES[i]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    // ------------------------------------------------------------------
    // Kennzahlen
    // ------------------------------------------------------------------

    private static void insertMetrics(
            Connection connection,
            Columns c,
            Row row,
            FormulaEvaluator evaluator,
            long projectId,
            int excelRow
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

            // Kennzahlen mit eigener Soll-/Ist-Spalte
            for (int i = 0; i < Columns.METRIC_CODES.length; i++) {
                addMetric(
                        statement,
                        projectId,
                        Columns.METRIC_CODES[i],
                        number(row, c.metricTarget[i], evaluator),
                        number(row, c.metricActual[i], evaluator)
                );
            }

            // Beleuchtung
            Lighting lighting = readLighting(row, c, evaluator, excelRow);
            addMetric(statement, projectId, "LIGHTING_POINTS",
                    lighting.total(), lighting.actual());
            addMetric(statement, projectId, "LIGHTING_INDOOR",
                    lighting.indoor(), null);
            addMetric(statement, projectId, "LIGHTING_OUTDOOR",
                    lighting.outdoor(), null);
            addMetric(statement, projectId, "LIGHTING_SWITCH_SOCKET",
                    lighting.socketsSwitches(), null);

            // Feuermelder: eine Spalte "Plan/Ist"
            Double[] fireDetectors = getTargetAndActual(
                    cell(row, c.fireDetectors),
                    evaluator
            );
            addMetric(statement, projectId, "FIRE_DETECTORS",
                    fireDetectors[0], fireDetectors[1]);

            statement.executeBatch();
        }
    }

    private static void addMetric(
            PreparedStatement statement,
            long projectId,
            String metricCode,
            Double target,
            Double actual
    ) throws SQLException {
        if (target == null && actual == null) {
            return;
        }
        statement.setLong(1, projectId);
        setNullableDouble(statement, 2, target);
        setNullableDouble(statement, 3, actual);
        statement.setString(4, metricCode);
        statement.addBatch();
    }

    /**
     * Beleuchtungswerte eines Projekts.
     *
     * total   = Spalte "Gesamt". Ein Wert von 0 gilt als "nicht bekannt"
     *           (die Excel-Formel liefert 0, wenn nichts eingetragen ist).
     * indoor, outdoor, socketsSwitches = Einzelwerte, nur Soll.
     * actual  = "Brennstellen Ist".
     */
    private record Lighting(
            Double total,
            Double indoor,
            Double outdoor,
            Double socketsSwitches,
            Double actual
    ) {
    }

    private static Lighting readLighting(
            Row row,
            Columns c,
            FormulaEvaluator evaluator,
            int excelRow
    ) {
        // Nur "Gesamt" zählt. Die Spalte "Brennstellen Soll" (Freitext) wird ignoriert.
        Double total = positiveOrNull(numberQuiet(row, c.lightingTotal, evaluator));
        Double indoor = number(row, c.lightingIndoor, evaluator);
        Double outdoor = number(row, c.lightingOutdoor, evaluator);
        Double sockets = number(row, c.socketsSwitches, evaluator);
        Double actual = number(row, c.lightingActual, evaluator);

        if (total == null) {
            String text = getCellText(cell(row, c.lightingTotal), evaluator);
            if (!text.isBlank() && !isNoNumberMarker(text)
                    && parsePlainNumber(text) == null) {
                warn("Zelle " + address(cell(row, c.lightingTotal))
                        + " (" + currentShip + "): Gesamt '" + shorten(text)
                        + "' ist keine Zahl, daher leer gespeichert.");
            }
        }
        return new Lighting(total, indoor, outdoor, sockets, actual);
    }

    private static Double positiveOrNull(Double value) {
        return value != null && value > 0 ? value : null;
    }

    // ------------------------------------------------------------------
    // Stammdaten (Kategorie, Werft, Schiffstyp)
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Zellen lesen
    // ------------------------------------------------------------------

    private static Cell cell(Row row, int columnIndex) {
        return columnIndex < 0 ? null : row.getCell(columnIndex);
    }

    private static Double number(Row row, int columnIndex, FormulaEvaluator evaluator) {
        return getCellNumber(cell(row, columnIndex), evaluator, true);
    }

    private static Double numberQuiet(Row row, int columnIndex, FormulaEvaluator evaluator) {
        return getCellNumber(cell(row, columnIndex), evaluator, false);
    }

    /**
     * Zelltyp, bei Formeln mit Auswertung. Lässt sich eine Formel nicht
     * auswerten (z. B. externe Verknüpfung), gilt der von Excel gespeicherte Wert.
     */
    private static CellType resolveType(Cell cell, FormulaEvaluator evaluator) {
        CellType type = cell.getCellType();
        if (type != CellType.FORMULA) {
            return type;
        }
        try {
            return evaluator.evaluateFormulaCell(cell);
        } catch (RuntimeException exception) {
            return cell.getCachedFormulaResultType();
        }
    }

    private static String getCellText(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        if (cell == null) {
            return "";
        }
        try {
            return FORMATTER.formatCellValue(cell, evaluator).trim();
        } catch (RuntimeException exception) {
            if (cell.getCellType() != CellType.FORMULA) {
                throw exception;
            }
            // Formel nicht auswertbar: gespeicherten Excel-Wert verwenden.
            return switch (cell.getCachedFormulaResultType()) {
                case NUMERIC -> FORMATTER.formatRawCellContents(
                        cell.getNumericCellValue(),
                        cell.getCellStyle().getDataFormat(),
                        cell.getCellStyle().getDataFormatString()
                ).trim();
                case STRING -> cell.getStringCellValue().trim();
                case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
                default -> "";
            };
        }
    }

    private static Double getCellNumber(
            Cell cell,
            FormulaEvaluator evaluator,
            boolean warnIfUnreadable
    ) {
        if (cell == null) {
            return null;
        }
        CellType type = resolveType(cell, evaluator);
        if (type == CellType.NUMERIC && !DateUtil.isCellDateFormatted(cell)) {
            return cell.getNumericCellValue();
        }
        String text = getCellText(cell, evaluator);
        if (isMissingText(text)) {
            return null;
        }
        String original = text;
        // Werte wie "50000/" werden als 50000 übernommen.
        if (text.endsWith("/")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        Double parsed = parsePlainNumber(text);
        if (parsed == null && warnIfUnreadable && !isNoNumberMarker(original)) {
            // Komplexe Angaben wie "900 (Wettbewerb 1100 km)" werden bewusst
            // nicht automatisch geraten. Sie werden gemeldet.
            warn("Zelle " + address(cell) + " (" + currentShip + "): '"
                    + shorten(original) + "' ist keine Zahl, daher leer gespeichert.");
        }
        return parsed;
    }

    private static LocalDate getCellDate(
            Cell cell,
            FormulaEvaluator evaluator
    ) {
        if (cell == null) {
            return null;
        }
        CellType type = resolveType(cell, evaluator);
        if (type == CellType.NUMERIC) {
            double value = cell.getNumericCellValue();
            // Reine Jahreszahl wie 2022 (kein Datum): als 01.01. des Jahres.
            if (isYear(value)) {
                warn("Zelle " + address(cell) + " (" + currentShip
                        + "): nur das Jahr " + (int) value
                        + " angegeben, gespeichert als 01.01." + (int) value + ".");
                return LocalDate.of((int) value, 1, 1);
            }
            return DateUtil.getJavaDate(value)
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
            if (normalized.matches("\\d{4}")) {
                warn("Zelle " + address(cell) + " (" + currentShip
                        + "): nur das Jahr " + normalized
                        + " angegeben, gespeichert als 01.01." + normalized + ".");
                return LocalDate.of(Integer.parseInt(normalized), 1, 1);
            }
            if (normalized.matches("\\d{1,2}/\\d{4}")) {
                return YearMonth.parse(normalized, MONTH_YEAR).atDay(1);
            }
            if (normalized.matches("\\d{1,2}/\\d{1,2}/\\d{4}")) {
                DateTimeFormatter formatter =
                        DateTimeFormatter.ofPattern("d/M/uuuu");
                return LocalDate.parse(normalized, formatter);
            }
        } catch (DateTimeParseException ignored) {
            // wird unten gemeldet
        }
        if (!isNoNumberMarker(text)) {
            warn("Zelle " + address(cell) + " (" + currentShip + "): Datum '"
                    + shorten(text) + "' nicht lesbar, daher leer gespeichert.");
        }
        return null;
    }

    private static boolean isYear(double value) {
        return value >= 1900 && value <= 2100 && value == Math.rint(value);
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
            // "3.400" oder "11.845": Punkt ist Tausendertrennzeichen
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
        Double number = getCellNumber(cell, evaluator, false);
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

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

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
            FormulaEvaluator evaluator,
            Columns columns
    ) {
        return getCellText(cell(row, columns.shipyard), evaluator).isBlank()
                && getCellText(cell(row, columns.shipName), evaluator).isBlank()
                && getCellText(cell(row, columns.shipType), evaluator).isBlank();
    }

    /** Fehlt ein Wert? (leer, n.b., ./., -) */
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

    /**
     * Platzhalter, bei denen kein Hinweis nötig ist ("x" = nicht bekannt,
     * "tbd", "?"). In Fachbereichsspalten bleibt "x" ein gültiger Eintrag.
     */
    private static boolean isNoNumberMarker(String value) {
        if (isMissingText(value)) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.GERMAN);
        return normalized.equals("x")
                || normalized.equals("?")
                || normalized.equals("tbd")
                || normalized.equals("na")
                || normalized.equals("n.a.");
    }

    /** Statusspalte: "n.b." und Ähnliches zählt als nicht ausgefüllt. */
    private static String statusText(Cell cell, FormulaEvaluator evaluator) {
        String text = getCellText(cell, evaluator);
        return isMissingText(text) ? "" : text;
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    /** Zeilenumbrüche und mehrfache Leerzeichen in Namen zu einem Leerzeichen. */
    private static String normalizeName(String value) {
        return value == null
                ? ""
                : value.replaceAll("[\\s\\u00a0]+", " ").trim();
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

    private static String address(Cell cell) {
        return cell == null
                ? "?"
                : CellReference.convertNumToColString(cell.getColumnIndex())
                + (cell.getRowIndex() + 1);
    }

    private static String shorten(String text) {
        String single = text.replaceAll("\\s+", " ");
        return single.length() > 60 ? single.substring(0, 57) + "..." : single;
    }

    private static void warn(String message) {
        WARNINGS.add(message);
    }

    private static void printWarnings() {
        if (WARNINGS.isEmpty()) {
            return;
        }
        System.out.println();
        System.out.println(
                WARNINGS.size() + " Hinweise zu Zellen, die nicht (vollständig) "
                        + "gelesen werden konnten:"
        );
        int shown = Math.min(WARNINGS.size(), MAX_PRINTED_WARNINGS);
        for (int i = 0; i < shown; i++) {
            System.out.println(" - " + WARNINGS.get(i));
        }
        if (WARNINGS.size() > shown) {
            System.out.println(" ... und " + (WARNINGS.size() - shown) + " weitere.");
        }
    }

    // ------------------------------------------------------------------
    // Spaltenzuordnung über die Überschriften (ohne POI, daher einzeln testbar)
    // ------------------------------------------------------------------

    static final class Columns {

        static final String[] DEPARTMENT_CODES = {
                "SES", "SNE2", "SNE4", "SOB", "SNE3",
                "SNE1", "SNA", "ANCS", "DP", "SPA"
        };

        /** Kennzahlen mit eigener Soll- und Ist-Spalte. */
        static final String[] METRIC_CODES = {
                "IO_COUNT", "CABLE_RECORDS",
                "CABLE_ENGINEERING_TIME", "CABLE_ROUTING_TIME",
                "LIGHTING_POINT_TIME", "IO_ENGINEERING_TIME",
                "IO_DELIVERY_TIME"
        };

        /** Überschriftanfang (normalisiert) zu den Kennzahlen oben. */
        private static final String[] METRIC_PREFIXES = {
                "ios", "kabeldatensaetze",
                "engineeringkabeldatensaetze", "routingkabeldatensatz",
                "minutenprobrennstellen", "minutenproioengineering",
                "minutenproioabgabe"
        };

        private final List<String> rawHeaders;
        private final List<String> headers = new ArrayList<>();
        private final List<String> found = new ArrayList<>();
        private final List<String> notFound = new ArrayList<>();

        final int shipyard;
        final int shipName;
        final int shipType;
        final int offerOrOrder;
        final int length;
        final int width;
        final int volume;
        final int area;
        final int cableCalculated;
        final int cableOffer;
        final int cableActual;
        final int ringCable;
        final int offerDate;
        final int orderDate;
        final int constructionStart;
        final int constructionEndPlanned;
        final int constructionEndActual;
        final int editor;
        final int status;
        final int evaluation;
        final int link;
        final int comments;
        final int relevance;
        final int fireDetectors;
        final int lightingTotal;
        final int lightingActual;
        final int lightingIndoor;
        final int lightingOutdoor;
        final int socketsSwitches;
        final int[] department = new int[DEPARTMENT_CODES.length];
        final int[] metricTarget = new int[METRIC_CODES.length];
        final int[] metricActual = new int[METRIC_CODES.length];

        Columns(List<String> rawHeaders) {
            this.rawHeaders = List.copyOf(rawHeaders);
            for (String header : rawHeaders) {
                headers.add(normalize(header));
            }

            shipyard = one("Werft", true, h -> h.equals("werft"));
            shipName = one("Schiffsname", true, h -> h.equals("schiffsname"));
            shipType = one("Schiffstyp", false, h -> h.equals("schiffstyp"));
            offerOrOrder = one("Auftrag oder Angebot", false,
                    h -> h.startsWith("auftragoderangebot"));
            length = one("Länge", false, h -> h.startsWith("laenge[m]"));
            width = one("Breite", false, h -> h.startsWith("breite[m]"));
            volume = one("Volumen BRZ/GT", false, h -> h.startsWith("volumen"));
            area = one("Fläche", false,
                    h -> h.startsWith("flaeche") && h.contains("qm"));
            cableCalculated = one("Kabellänge Kalkulationstool", false,
                    h -> h.startsWith("kabellaenge") && h.contains("kalkulationstool"));
            cableOffer = one("Kabellänge Angebot", false,
                    h -> h.startsWith("kabellaenge") && h.contains("angebot"));
            cableActual = one("Kabellänge Real", false,
                    h -> h.startsWith("kabellaenge") && h.contains("real"));
            ringCable = one("Ringkabel", false, h -> h.startsWith("ringkabel"));
            offerDate = one("Angebotsdatum", false, h -> h.equals("angebotsdatum"));
            orderDate = one("Auftragsdatum", false, h -> h.equals("auftragsdatum"));
            constructionStart = one("Baubeginn", false,
                    h -> h.startsWith("baubeginn") || h.startsWith("baugebeginn"));
            constructionEndPlanned = one("Bauende Soll", false,
                    h -> h.startsWith("bauendesoll"));
            constructionEndActual = one("Bauende Ist", false,
                    h -> h.startsWith("bauendeist"));

            for (int i = 0; i < DEPARTMENT_CODES.length; i++) {
                department[i] = one("Fachbereich " + DEPARTMENT_CODES[i], false,
                        departmentMatcher(DEPARTMENT_CODES[i]));
            }

            editor = one("Bearbeiter", false, h -> h.equals("bearbeiter"));
            status = one("Auftrag/Angebot/Budgetangebot/Indikation", false,
                    h -> h.startsWith("auftrag/angebot"));
            evaluation = one("Auswertung/Quelle", false,
                    h -> h.equals("auswertung") || h.equals("quelle"));
            link = one("Link", false, h -> h.equals("link"));
            comments = one("Bemerkungen", false,
                    h -> h.equals("bemerkungen") || h.startsWith("unterscheidungin"));
            relevance = one("Relevanz für Auswertung", false,
                    h -> h.startsWith("relevanzfuerauswertung"));

            for (int i = 0; i < METRIC_CODES.length; i++) {
                String prefix = METRIC_PREFIXES[i];
                metricTarget[i] = one(METRIC_CODES[i] + " Soll", false,
                        h -> h.startsWith(prefix) && isTarget(h));
                metricActual[i] = one(METRIC_CODES[i] + " Ist", false,
                        h -> h.startsWith(prefix) && isActual(h));
            }

            lightingTotal = one("Brennstellen Gesamt", false, h -> h.equals("gesamt"));
            lightingActual = one("Brennstellen Ist", false,
                    h -> h.startsWith("brennstellen") && isActual(h));
            lightingIndoor = one("Innenbeleuchtung", false,
                    h -> h.equals("innenbeleuchtung"));
            lightingOutdoor = one("Außenbeleuchtung", false,
                    h -> h.equals("aussenbeleuchtung"));
            socketsSwitches = one("Schalter/Steckdosen", false,
                    h -> h.startsWith("schalter"));
            fireDetectors = one("Anzahl Feuermelder", false,
                    h -> h.startsWith("anzahlfeuermelder"));
        }

        /** Kleinschreibung, ohne Leerzeichen/Umbrüche/Apostrophe, Umlaute als ae/oe/ue/ss. */
        static String normalize(String value) {
            if (value == null) {
                return "";
            }
            return value.toLowerCase(Locale.GERMAN)
                    .replace("\u00e4", "ae")
                    .replace("\u00f6", "oe")
                    .replace("\u00fc", "ue")
                    .replace("\u00df", "ss")
                    .replaceAll("[\\s\\u00a0\\u202f'\\u2019`\\u00b4]+", "");
        }

        static String columnLetter(int index) {
            StringBuilder letters = new StringBuilder();
            int n = index + 1;
            while (n > 0) {
                int rest = (n - 1) % 26;
                letters.insert(0, (char) ('A' + rest));
                n = (n - 1) / 26;
            }
            return letters.toString();
        }

        private static boolean isTarget(String header) {
            return header.contains("soll");
        }

        private static boolean isActual(String header) {
            return header.endsWith("ist") || header.endsWith("ist]");
        }

        private static Predicate<String> departmentMatcher(String code) {
            return switch (code) {
                case "SES" -> h -> h.startsWith("ses") || h.contains("drives");
                case "SNE2" -> h -> h.contains("powerdistribution");
                case "SNE4" -> h -> h.contains("cablesystems");
                case "SOB" -> h -> h.startsWith("sob") || h.contains("baustelle");
                case "SNE3" -> h -> h.contains("lighting");
                case "SNE1" -> h -> h.contains("safety");
                case "SNA" -> h -> h.startsWith("sna") || h.contains("automation");
                case "ANCS" -> h -> h.startsWith("ancs") || h.contains("navcom");
                case "DP" -> h -> h.startsWith("dpsystem") || h.startsWith("dp-system");
                case "SPA" -> h -> h.contains("systemsarchitecture");
                default -> h -> false;
            };
        }

        /** Ganz linke passende Spalte oder -1. */
        private int one(String label, boolean mandatory, Predicate<String> matcher) {
            for (int i = 0; i < headers.size(); i++) {
                if (matcher.test(headers.get(i))) {
                    found.add("  " + columnLetter(i) + "  "
                            + oneLine(rawHeaders.get(i)) + "  ->  " + label);
                    return i;
                }
            }
            notFound.add((mandatory ? "  PFLICHT: " : "  ") + label);
            return -1;
        }

        private static String oneLine(String text) {
            String single = text.replaceAll("\\s+", " ").trim();
            return single.length() > 45 ? single.substring(0, 42) + "..." : single;
        }

        void requireMandatory() {
            if (shipyard < 0 || shipName < 0) {
                throw new IllegalArgumentException(
                        "Pflichtspalte fehlt in Zeile 1: "
                                + (shipyard < 0 ? "'Werft' " : "")
                                + (shipName < 0 ? "'Schiffsname'" : "")
                );
            }
        }

        void printMapping(String sheetName) {
            System.out.println("Spaltenzuordnung für Blatt '" + sheetName + "':");
            found.forEach(System.out::println);
            if (!notFound.isEmpty()) {
                System.out.println("Nicht gefunden (bleibt leer):");
                notFound.forEach(System.out::println);
            }
        }

        List<String> foundLines() {
            return found;
        }

        List<String> notFoundLines() {
            return notFound;
        }
    }
}
