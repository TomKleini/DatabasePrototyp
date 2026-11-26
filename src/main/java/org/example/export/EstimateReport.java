package org.example.export;

import org.example.calculation.LightingSplitCalculator;
import org.example.calculation.LightingSplitCalculator.Result;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Baut aus dem aktuellen Stand der Brennstellen-Schätzung den Inhalt der
 * Excel-Datei auf. Kennt weder JavaFX noch Apache POI, ist also einzeln
 * testbar. Die Datei selbst schreibt EstimateExcelWriter.
 *
 * Blätter:
 *   Zusammenfassung    Eingaben, geschätzte Brennstellen, Aufteilung (historische Anteile)
 *   Berechnung         Formeln, Fehler, Gewichte, Prüfung, Rechenweg der Aufteilung
 *   Ähnliche Schiffe   die ähnlichsten Referenzschiffe zu den eingegebenen Werten
 *   Referenzprojekte   alle Schiffe der aktuellen Filter und wofür sie gezählt wurden
 */
public final class EstimateReport {

    private static final int MIN_RELIABLE_PROJECTS = 6;
    private static final int SUMMARY_COLUMNS = 4;

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");
    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

    private EstimateReport() {
    }

    // ------------------------------------------------------------------
    // Eingabe
    // ------------------------------------------------------------------

    /** Eine Bezugsgröße (Volumen, Fläche, Länge, Kabel ...). */
    public record Size(
            String name,
            String unit,
            boolean used,
            Double value
    ) {
    }

    /** Eine Zeile der Methodentabelle; sum = Zeile "Kombiniert". */
    public record Method(
            String size,
            Integer n,
            String method,
            String formula,
            Double mape,
            Double mdape,
            Double mae,
            Double input,
            Double estimate,
            Double weight,
            Double contribution,
            boolean sum
    ) {
    }

    /**
     * Ein ähnliches Schiff, wie in der Tabelle "Ähnlichste Schiffe".
     * values und deviations haben dieselbe Reihenfolge wie
     * Input.similarSizes; null = für dieses Schiff nicht vorhanden.
     */
    public record Similar(
            int rank,
            String ship,
            List<Double> values,
            List<Double> deviations,
            int compared,
            double similarity,
            Double lightingPoints,
            String source,
            Double extrapolated
    ) {
    }

    /**
     * Ein Referenzprojekt der aktuellen Filter.
     *
     * @param sizeValues     Werte in der Reihenfolge von Input.sizes(), null = unbekannt
     * @param lightingPoints Brennstellen nach der gewählten Basis, null = keine
     * @param source         "Ist" oder "Soll" dazu, leer ohne Brennstellen
     * @param indoor         Innenbeleuchtung (Soll), null = unbekannt
     * @param outdoor        Außenbeleuchtung (Soll), null = unbekannt
     * @param switchSocket   Schalter/Steckdosen (Soll), null = unbekannt
     */
    public record Project(
            String ship,
            List<Double> sizeValues,
            Double lightingPoints,
            String source,
            Double indoor,
            Double outdoor,
            Double switchSocket
    ) {
    }

    /**
     * @param category        null = alle Kategorien
     * @param shipType        null = alle Schiffstypen
     * @param total           geschätzte Brennstellen gesamt
     * @param validation      Text der Prüfung (kann mehrzeilig sein)
     * @param split           Aufteilung nach historischen Anteilen, null = nicht berechenbar
     * @param splitShips      Namen der Schiffe, aus denen die Anteile stammen
     * @param similarSizes    Spaltennamen der verglichenen Bezugsgrößen, z. B. "Volumen (GT)"
     * @param similar         die angezeigten ähnlichsten Schiffe
     * @param similarNote     Hinweistext unter der Tabelle der Oberfläche
     * @param projects        alle Referenzprojekte der aktuellen Filter
     */
    public record Input(
            LocalDateTime created,
            String category,
            String shipType,
            boolean onlyRelevant,
            String basis,
            List<Size> sizes,
            Double total,
            String totalNote,
            List<Method> methods,
            String validation,
            Result split,
            List<String> splitShips,
            List<String> similarSizes,
            List<Similar> similar,
            String similarNote,
            List<Project> projects
    ) {
    }

    // ------------------------------------------------------------------
    // Ausgabe (Tabellenmodell)
    // ------------------------------------------------------------------

    public enum Style {
        TITLE, HEADING, HEADER, LABEL, TEXT, NOTE,
        NUMBER, INTEGER, DECIMAL1, PERCENT,
        TOTAL_TEXT, TOTAL_INTEGER, TOTAL_PERCENT
    }

    /** value: String, Number oder null (leere Zelle); span = verbundene Spalten. */
    public record Cell(Object value, Style style, int span) {
        static Cell of(Object value, Style style) {
            return new Cell(value, style, 1);
        }

        static Cell spanning(Object value, Style style, int span) {
            return new Cell(value, style, span);
        }
    }

    /** height 0 = automatische Höhe. */
    public record Row(List<Cell> cells, float height) {
    }

    /**
     * @param widths     Spaltenbreiten in Zeichen
     * @param freezeRows Zeilen oben, die beim Scrollen stehen bleiben
     * @param filterRow  Zeile mit Überschriften für den Autofilter, -1 = kein Filter
     */
    public record Sheet(
            String name,
            List<Integer> widths,
            List<Row> rows,
            int freezeRows,
            int filterRow
    ) {
    }

    public static String defaultFileName(LocalDateTime time) {
        return "Brennstellen-Schaetzung_" + FILE_STAMP.format(time) + ".xlsx";
    }

    public static List<Sheet> build(Input in) {
        return List.of(summary(in), calculation(in), similar(in), projects(in));
    }

    // ------------------------------------------------------------------
    // Blatt 1: Zusammenfassung
    // ------------------------------------------------------------------

    private static Sheet summary(Input in) {
        List<Integer> widths = List.of(40, 26, 16, 26);
        Rows rows = new Rows(widths);

        rows.add(Cell.spanning("Brennstellen-Schätzung", Style.TITLE, SUMMARY_COLUMNS));
        rows.add(Cell.of("Erstellt am", Style.LABEL),
                Cell.of(DATE_TIME.format(in.created()), Style.TEXT));
        rows.blank();

        rows.add(Cell.spanning("Eingaben", Style.HEADING, SUMMARY_COLUMNS));
        rows.add(header("Bezugsgröße", "Einheit", "Verwendet", "Wert neues Schiff"));
        for (Size size : in.sizes()) {
            rows.add(Cell.of(size.name(), Style.TEXT),
                    Cell.of(size.unit(), Style.TEXT),
                    Cell.of(size.used() ? "Ja" : "Nein", Style.TEXT),
                    Cell.of(size.value(), Style.NUMBER));
        }
        rows.blank();
        rows.add(Cell.of("Brennstellenbasis der Referenzprojekte", Style.LABEL),
                Cell.of(in.basis(), Style.TEXT));
        rows.add(Cell.of("Kategorie", Style.LABEL),
                Cell.of(in.category() == null ? "alle" : in.category(), Style.TEXT));
        rows.add(Cell.of("Schiffstyp", Style.LABEL),
                Cell.of(in.shipType() == null ? "alle" : in.shipType(), Style.TEXT));
        rows.add(Cell.of("Nur auswertungsrelevante Projekte", Style.LABEL),
                Cell.of(in.onlyRelevant() ? "Ja" : "Nein", Style.TEXT));
        rows.add(Cell.of("Referenzprojekte (Anzahl)", Style.LABEL),
                Cell.of(in.projects().size(), Style.INTEGER));
        rows.blank();

        rows.add(Cell.spanning("Ergebnis", Style.HEADING, SUMMARY_COLUMNS));
        rows.add(Cell.of("Geschätzte Brennstellen gesamt", Style.LABEL),
                Cell.of(in.total() == null ? null : (Object) Math.round(in.total()),
                        Style.TOTAL_INTEGER));
        rows.note(in.totalNote(), SUMMARY_COLUMNS);
        rows.blank();

        rows.add(Cell.spanning("Aufteilung der Brennstellen (vorläufig)",
                Style.HEADING, SUMMARY_COLUMNS));
        Result split = in.split();
        rows.add(header("Bereich", "Geschätzt (Stück)", "Anteil"));
        rows.add(Cell.of("Innenbeleuchtung", Style.TEXT),
                Cell.of(split == null ? null : (Object) split.indoor(), Style.INTEGER),
                Cell.of(split == null ? null : (Object) split.indoorShare(), Style.PERCENT));
        rows.add(Cell.of("Außenbeleuchtung", Style.TEXT),
                Cell.of(split == null ? null : (Object) split.outdoor(), Style.INTEGER),
                Cell.of(split == null ? null : (Object) split.outdoorShare(), Style.PERCENT));
        rows.add(Cell.of("Schalter/Steckdosen", Style.TEXT),
                Cell.of(split == null ? null : (Object) split.switchSocket(), Style.INTEGER),
                Cell.of(split == null ? null : (Object) split.switchSocketShare(),
                        Style.PERCENT));
        rows.add(Cell.of("Summe = geschätzte Brennstellen", Style.TOTAL_TEXT),
                Cell.of(split == null
                                ? null
                                : (Object) (split.indoor() + split.outdoor() + split.switchSocket()),
                        Style.TOTAL_INTEGER),
                Cell.of(split == null ? null : (Object) 1.0, Style.TOTAL_PERCENT));

        if (split == null) {
            rows.note("Aufteilung nicht berechenbar. Kein Referenzprojekt hat die "
                    + "vollständige Aufteilung (Innen, Außen, Schalter/Steckdosen) "
                    + "für diese Filter.", SUMMARY_COLUMNS);
        } else {
            rows.note("Methode: " + LightingSplitCalculator.METHOD_NAME + ". Basis: "
                    + split.projectCount()
                    + (split.projectCount() == 1 ? " Projekt" : " Projekte")
                    + " mit vollständiger Aufteilung (Soll-Werte)"
                    + shipList(in.splitShips()) + ".", SUMMARY_COLUMNS);
            if (split.projectCount() < MIN_RELIABLE_PROJECTS) {
                rows.note("Achtung: Weniger als " + MIN_RELIABLE_PROJECTS
                        + " Projekte haben die Aufteilung. Das Ergebnis ist nur eine "
                        + "grobe Richtung.", SUMMARY_COLUMNS);
            }
        }
        rows.note("Rechenweg: siehe Blatt \"Berechnung\". Die ähnlichsten Schiffe: "
                + "siehe Blatt \"Ähnliche Schiffe\". Alle verwendeten Schiffe: "
                + "siehe Blatt \"Referenzprojekte\".", SUMMARY_COLUMNS);

        return new Sheet("Zusammenfassung", widths, rows.list(), 0, -1);
    }

    private static String shipList(List<String> ships) {
        if (ships == null || ships.isEmpty()) {
            return "";
        }
        return ": " + String.join(", ", ships);
    }

    // ------------------------------------------------------------------
    // Blatt 2: Berechnung
    // ------------------------------------------------------------------

    private static Sheet calculation(Input in) {
        List<Integer> widths = List.of(26, 7, 28, 36, 11, 11, 11, 13, 16, 11, 13);
        int columns = widths.size();
        Rows rows = new Rows(widths);

        rows.add(Cell.spanning("Berechnung", Style.TITLE, columns));
        rows.blank();

        rows.add(Cell.spanning("Gesamtschätzung der Brennstellen je Bezugsgröße",
                Style.HEADING, columns));
        rows.note("Für jede Bezugsgröße wird die Methode mit dem kleinsten Prognosefehler "
                + "(MAPE, Leave-one-out) gewählt. Gewicht = (1 / Fehler²) / Summe aller "
                + "(1 / Fehler²), Fehler mindestens 0,5 %. Brennstellen = Σ Gewicht × "
                + "Einzelschätzung.", columns);
        rows.add(header("Bezugsgröße", "n", "Beste Methode", "Formel",
                "MAPE (%)", "MdAPE (%)", "MAE (Brennst.)",
                "Eingabe", "Einzelschätzung", "Gewicht", "Beitrag"));
        for (Method m : in.methods()) {
            if (m.sum()) {
                rows.add(Cell.of(m.size(), Style.TOTAL_TEXT),
                        Cell.of(null, Style.TOTAL_TEXT),
                        Cell.of(null, Style.TOTAL_TEXT),
                        Cell.of(m.formula(), Style.TOTAL_TEXT),
                        Cell.of(null, Style.TOTAL_TEXT),
                        Cell.of(null, Style.TOTAL_TEXT),
                        Cell.of(null, Style.TOTAL_TEXT),
                        Cell.of(null, Style.TOTAL_TEXT),
                        Cell.of(null, Style.TOTAL_TEXT),
                        Cell.of(m.weight(), Style.TOTAL_PERCENT),
                        Cell.of(m.contribution(), Style.TOTAL_INTEGER));
            } else {
                rows.add(Cell.of(m.size(), Style.TEXT),
                        Cell.of(m.n(), Style.INTEGER),
                        Cell.of(m.method(), Style.TEXT),
                        Cell.of(m.formula(), Style.TEXT),
                        Cell.of(m.mape(), Style.DECIMAL1),
                        Cell.of(m.mdape(), Style.DECIMAL1),
                        Cell.of(m.mae(), Style.DECIMAL1),
                        Cell.of(m.input(), Style.NUMBER),
                        Cell.of(m.estimate(), Style.INTEGER),
                        Cell.of(m.weight(), Style.PERCENT),
                        Cell.of(m.contribution(), Style.INTEGER));
            }
        }
        rows.blank();

        rows.add(Cell.spanning("Prüfung der kombinierten Schätzung", Style.HEADING, columns));
        if (in.validation() == null || in.validation().isBlank()) {
            rows.note("Keine Prüfung verfügbar.", columns);
        } else {
            for (String line : in.validation().split("\\R")) {
                if (!line.isBlank()) {
                    rows.note(line.trim(), columns);
                }
            }
        }
        rows.blank();

        rows.add(Cell.spanning("Rechenweg der Aufteilung", Style.HEADING, columns));
        rows.note("Anteil Innen = Summe Innenbeleuchtung / Summe aller drei Bereiche, "
                + "ebenso für Außen und Schalter/Steckdosen. Die Summen laufen über alle "
                + "Referenzschiffe mit vollständiger Aufteilung (Soll-Werte). Große Schiffe "
                + "zählen dadurch stärker. Die geschätzten Brennstellen werden mit diesen "
                + "Anteilen aufgeteilt, ein Rundungsrest geht an Schalter/Steckdosen, "
                + "damit die Summe genau der Schätzung entspricht.", columns);
        rows.note("Hinweis: Die Aufteilung ist vorläufig. Die Datenlage ist dünn, "
                + "vor allem für Außenbeleuchtung und Schalter/Steckdosen.", columns);

        return new Sheet("Berechnung", widths, rows.list(), 0, -1);
    }

    // ------------------------------------------------------------------
    // Blatt 3: Ähnliche Schiffe
    // ------------------------------------------------------------------

    private static Sheet similar(Input in) {
        List<Integer> widths = new ArrayList<>();
        widths.add(7);                              // Rang
        widths.add(32);                             // Schiff
        for (int i = 0; i < in.similarSizes().size(); i++) {
            widths.add(18);                         // Wert
            widths.add(10);                         // Abweichung
        }
        widths.add(14);                             // Ähnlichkeit
        widths.add(16);                             // verglichen
        widths.add(14);                             // Brennstellen
        widths.add(9);                              // Quelle
        widths.add(15);                             // Hochgerechnet
        int columns = widths.size();

        Rows rows = new Rows(widths);
        rows.add(Cell.spanning("Ähnlichste Schiffe zu den eingegebenen Werten",
                Style.TITLE, columns));
        rows.note(in.similarNote(), columns);
        rows.blank();

        List<String> head = new ArrayList<>();
        head.add("Rang");
        head.add("Schiff");
        for (String size : in.similarSizes()) {
            head.add(size);
            head.add("Abw. %");
        }
        head.add("Ähnlichkeit %");
        head.add("Werte verglichen");
        head.add("Brennstellen");
        head.add("Quelle");
        head.add("Hochgerechnet");
        rows.add(header(head.toArray(new String[0])));

        if (in.similar().isEmpty()) {
            rows.note("Keine ähnlichen Schiffe: Es ist keine Bezugsgröße angehakt und "
                    + "eingegeben, oder kein Referenzschiff hat dafür Werte.", columns);
        }
        int sizes = in.similarSizes().size();
        for (Similar s : in.similar()) {
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.of(s.rank(), Style.INTEGER));
            cells.add(Cell.of(s.ship(), Style.TEXT));
            for (int i = 0; i < sizes; i++) {
                cells.add(Cell.of(at(s.values(), i), Style.NUMBER));
                cells.add(Cell.of(at(s.deviations(), i), Style.DECIMAL1));
            }
            cells.add(Cell.of(s.similarity(), Style.DECIMAL1));
            cells.add(Cell.of(s.compared() + " von " + sizes, Style.TEXT));
            cells.add(Cell.of(s.lightingPoints(), Style.INTEGER));
            cells.add(Cell.of(s.source(), Style.TEXT));
            cells.add(Cell.of(s.extrapolated(), Style.INTEGER));
            rows.add(cells);
        }

        return new Sheet("Ähnliche Schiffe", widths, rows.list(), 0, -1);
    }

    private static Double at(List<Double> list, int index) {
        return list != null && index < list.size() ? list.get(index) : null;
    }

    // ------------------------------------------------------------------
    // Blatt 4: Referenzprojekte
    // ------------------------------------------------------------------

    private static Sheet projects(Input in) {
        int sizeCount = in.sizes().size();

        List<Integer> widths = new ArrayList<>();
        widths.add(32);                             // Schiff
        for (int i = 0; i < sizeCount; i++) {
            widths.add(17);                         // Bezugsgrößen
        }
        widths.add(14);                             // Brennstellen
        widths.add(9);                              // Quelle
        widths.add(12);                             // Innen
        widths.add(12);                             // Außen
        widths.add(16);                             // Schalter/Steckdosen
        widths.add(34);                             // In Gesamtschätzung
        int columns = widths.size();

        Rows rows = new Rows(widths);
        rows.add(Cell.spanning("Referenzprojekte (" + in.projects().size() + ")",
                Style.TITLE, columns));
        rows.note("Alle Schiffe der aktuellen Filter. \"In Gesamtschätzung\" nennt die "
                        + "angehakten Bezugsgrößen, für die das Schiff in die Formel eingeflossen "
                        + "ist (Brennstellen und Wert der Bezugsgröße vorhanden). Brennstellen "
                        + "stehen nach der gewählten Basis (Quelle Ist oder Soll). Innen, Außen "
                        + "und Schalter/Steckdosen sind Soll-Werte, die Aufteilung der "
                        + "Brennstellen nutzt nur Schiffe, bei denen alle drei vorhanden sind.",
                columns);

        List<String> head = new ArrayList<>();
        head.add("Schiff");
        for (Size size : in.sizes()) {
            head.add(size.name() + " (" + size.unit() + ")");
        }
        head.add("Brennstellen");
        head.add("Quelle");
        head.add("Innen");
        head.add("Außen");
        head.add("Schalter/ Steckdosen");
        head.add("In Gesamtschätzung");
        int headerRow = rows.list().size();
        rows.add(header(head.toArray(new String[0])));

        for (Project p : in.projects()) {
            List<Cell> cells = new ArrayList<>();
            cells.add(Cell.of(p.ship(), Style.TEXT));
            for (int i = 0; i < sizeCount; i++) {
                cells.add(Cell.of(at(p.sizeValues(), i), Style.NUMBER));
            }
            cells.add(Cell.of(p.lightingPoints(), Style.NUMBER));
            cells.add(Cell.of(p.lightingPoints() == null ? "" : p.source(), Style.TEXT));
            cells.add(Cell.of(p.indoor(), Style.NUMBER));
            cells.add(Cell.of(p.outdoor(), Style.NUMBER));
            cells.add(Cell.of(p.switchSocket(), Style.NUMBER));
            cells.add(Cell.of(usedForTotal(in, p), Style.TEXT));
            rows.add(cells);
        }
        if (in.projects().isEmpty()) {
            rows.note("Keine Referenzprojekte für diese Filter.", columns);
        }

        return new Sheet("Referenzprojekte", widths, rows.list(), headerRow + 1, headerRow);
    }

    private static String usedForTotal(Input in, Project p) {
        if (p.lightingPoints() == null) {
            return "Nein (keine Brennstellen)";
        }
        List<String> names = new ArrayList<>();
        for (int i = 0; i < in.sizes().size(); i++) {
            Size size = in.sizes().get(i);
            Double x = at(p.sizeValues(), i);
            if (size.used() && x != null && x > 0) {
                names.add(size.name());
            }
        }
        return names.isEmpty() ? "Nein" : String.join(", ", names);
    }

    // ------------------------------------------------------------------
    // Hilfen
    // ------------------------------------------------------------------

    private static List<Cell> header(String... titles) {
        List<Cell> cells = new ArrayList<>();
        for (String title : titles) {
            cells.add(Cell.of(title, Style.HEADER));
        }
        return cells;
    }

    /** Sammelt Zeilen und schätzt die Höhe von Textzeilen über mehrere Spalten. */
    private static final class Rows {

        private final List<Integer> widths;
        private final List<Row> rows = new ArrayList<>();

        Rows(List<Integer> widths) {
            this.widths = widths;
        }

        List<Row> list() {
            return rows;
        }

        void add(List<Cell> cells) {
            rows.add(new Row(cells, 0));
        }

        void add(Cell... cells) {
            rows.add(new Row(List.of(cells), 0));
        }

        void blank() {
            rows.add(new Row(List.of(), 0));
        }

        /** Textzeile mit Umbruch über span Spalten; Höhe nach Textlänge. */
        void note(String text, int span) {
            if (text == null || text.isBlank()) {
                return;
            }
            int chars = 0;
            for (int i = 0; i < Math.min(span, widths.size()); i++) {
                chars += widths.get(i);
            }
            int lines = 0;
            for (String part : text.split("\\R", -1)) {
                lines += Math.max(1, (int) Math.ceil(part.length() / (chars * 0.9)));
            }
            rows.add(new Row(
                    List.of(Cell.spanning(text.trim(), Style.NOTE, span)),
                    Math.max(1, lines) * 15f));
        }
    }
}
