package org.example.export;

import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Schreibt die Blätter aus EstimateReport als xlsx-Datei (Apache POI).
 *
 * Die Datei wird zuerst im Speicher erzeugt und erst dann geschrieben.
 * Schlägt die Erzeugung fehl, bleibt keine halbe Datei zurück.
 */
public final class EstimateExcelWriter {

    private static final int MAX_COLUMN_WIDTH_CHARS = 100;

    private EstimateExcelWriter() {
    }

    public static void write(List<EstimateReport.Sheet> sheets, Path target)
            throws IOException {
        byte[] bytes;
        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {

            Map<EstimateReport.Style, CellStyle> styles = createStyles(workbook);
            for (EstimateReport.Sheet sheet : sheets) {
                writeSheet(workbook, sheet, styles);
            }
            workbook.write(buffer);
            bytes = buffer.toByteArray();
        }
        Files.write(target, bytes);
    }

    private static void writeSheet(
            Workbook workbook,
            EstimateReport.Sheet model,
            Map<EstimateReport.Style, CellStyle> styles
    ) {
        org.apache.poi.ss.usermodel.Sheet sheet = workbook.createSheet(model.name());

        for (int i = 0; i < model.widths().size(); i++) {
            int chars = Math.min(MAX_COLUMN_WIDTH_CHARS, model.widths().get(i));
            sheet.setColumnWidth(i, chars * 256);
        }

        int rowIndex = 0;
        int maxColumn = 0;
        for (EstimateReport.Row rowModel : model.rows()) {
            org.apache.poi.ss.usermodel.Row row = sheet.createRow(rowIndex);
            if (rowModel.height() > 0) {
                row.setHeightInPoints(rowModel.height());
            }

            int column = 0;
            for (EstimateReport.Cell cellModel : rowModel.cells()) {
                int span = Math.max(1, cellModel.span());
                CellStyle style = styles.get(cellModel.style());

                // Alle verbundenen Zellen bekommen den Stil, damit Rahmen und Füllung durchgehen
                for (int k = 0; k < span; k++) {
                    org.apache.poi.ss.usermodel.Cell cell = row.createCell(column + k);
                    cell.setCellStyle(style);
                    if (k == 0) {
                        setValue(cell, cellModel.value());
                    }
                }
                if (span > 1) {
                    sheet.addMergedRegion(new CellRangeAddress(
                            rowIndex, rowIndex, column, column + span - 1));
                }
                column += span;
            }
            maxColumn = Math.max(maxColumn, column);
            rowIndex++;
        }

        if (model.freezeRows() > 0) {
            sheet.createFreezePane(0, model.freezeRows());
        }
        if (model.filterRow() >= 0 && maxColumn > 0) {
            sheet.setAutoFilter(new CellRangeAddress(
                    model.filterRow(), Math.max(model.filterRow(), rowIndex - 1),
                    0, maxColumn - 1));
        }

        sheet.getPrintSetup().setLandscape(true);
        sheet.setFitToPage(true);
        sheet.getPrintSetup().setFitWidth((short) 1);
        sheet.getPrintSetup().setFitHeight((short) 0);
    }

    private static void setValue(org.apache.poi.ss.usermodel.Cell cell, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (Double.isFinite(d)) {
                cell.setCellValue(d);
            }
        } else {
            cell.setCellValue(String.valueOf(value));
        }
    }

    // ------------------------------------------------------------------
    // Stile
    // ------------------------------------------------------------------

    private static Map<EstimateReport.Style, CellStyle> createStyles(Workbook workbook) {
        short integerFormat = workbook.createDataFormat().getFormat("#,##0");
        short decimalFormat = workbook.createDataFormat().getFormat("#,##0.0");
        short percentFormat = workbook.createDataFormat().getFormat("0.0%");

        Font normal = workbook.createFont();

        Font bold = workbook.createFont();
        bold.setBold(true);

        Font title = workbook.createFont();
        title.setBold(true);
        title.setFontHeightInPoints((short) 16);

        Font heading = workbook.createFont();
        heading.setBold(true);
        heading.setFontHeightInPoints((short) 12);
        heading.setColor(IndexedColors.DARK_BLUE.getIndex());

        Map<EstimateReport.Style, CellStyle> map = new EnumMap<>(EstimateReport.Style.class);

        map.put(EstimateReport.Style.TITLE, style(workbook, title, null, 0, false, false));
        map.put(EstimateReport.Style.HEADING, style(workbook, heading, null, 0, false, false));

        CellStyle header = style(workbook, bold, IndexedColors.GREY_25_PERCENT, 0, true, false);
        header.setVerticalAlignment(VerticalAlignment.CENTER);
        header.setAlignment(HorizontalAlignment.CENTER);
        header.setBorderBottom(BorderStyle.THIN);
        map.put(EstimateReport.Style.HEADER, header);

        map.put(EstimateReport.Style.LABEL, style(workbook, bold, null, 0, false, false));
        map.put(EstimateReport.Style.TEXT, style(workbook, normal, null, 0, false, false));

        CellStyle note = style(workbook, normal, null, 0, true, false);
        note.setVerticalAlignment(VerticalAlignment.TOP);
        map.put(EstimateReport.Style.NOTE, note);

        map.put(EstimateReport.Style.NUMBER, style(workbook, normal, null, 0, false, false));
        map.put(EstimateReport.Style.INTEGER,
                style(workbook, normal, null, integerFormat, false, false));
        map.put(EstimateReport.Style.DECIMAL1,
                style(workbook, normal, null, decimalFormat, false, false));
        map.put(EstimateReport.Style.PERCENT,
                style(workbook, normal, null, percentFormat, false, false));

        map.put(EstimateReport.Style.TOTAL_TEXT, style(workbook, bold, null, 0, false, true));
        map.put(EstimateReport.Style.TOTAL_INTEGER,
                style(workbook, bold, null, integerFormat, false, true));
        map.put(EstimateReport.Style.TOTAL_PERCENT,
                style(workbook, bold, null, percentFormat, false, true));
        return map;
    }

    private static CellStyle style(
            Workbook workbook,
            Font font,
            IndexedColors fill,
            int dataFormat,
            boolean wrap,
            boolean topBorder
    ) {
        CellStyle style = workbook.createCellStyle();
        style.setFont(font);
        if (fill != null) {
            style.setFillForegroundColor(fill.getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        }
        if (dataFormat != 0) {
            style.setDataFormat((short) dataFormat);
        }
        style.setWrapText(wrap);
        if (topBorder) {
            style.setBorderTop(BorderStyle.THIN);
        }
        return style;
    }
}
