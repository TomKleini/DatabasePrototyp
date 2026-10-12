package org.example;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Kalkulation Beleuchtung:
 * 1. Aufwandsschätzung aus Brennstellen / IOs
 * 2. Kennzahlen (Median, Spanne, Soll/Ist-Faktor)
 * 3. Zusammenhänge (Regression, Pearson r)
 * 4. Datengrundlage pro Projekt
 */
public class KalkulationView extends ScrollPane {

    private static final String ALL_CATEGORIES = "Alle Kategorien";
    private static final String ALL_SHIP_TYPES = "Alle Schiffstypen";

    /** Ab so vielen Ist-Werten wird mit Ist statt Soll gerechnet. */
    private static final int MIN_N = 3;

    private static final NumberFormat NUMBER =
            NumberFormat.getNumberInstance(Locale.GERMANY);

    static {
        NUMBER.setMaximumFractionDigits(1);
    }

    private record Kpi(
            String name,
            Function<LightingKpiRow, Double> target,
            Function<LightingKpiRow, Double> actual
    ) {
    }

    private record KpiLine(
            String name,
            KpiStatistics.Summary target,
            KpiStatistics.Summary actual,
            KpiStatistics.Summary ratio
    ) {
    }

    private record CorrelationLine(
            String name,
            int n,
            KpiStatistics.Regression regression
    ) {
    }

    private record EstimateLine(
            String activity,
            Double quantity,
            Double minutesPerUnit,
            String basis,
            double hours,
            double hoursLow,
            double hoursHigh,
            Double cost
    ) {
    }

    private static final Kpi LIGHTING_POINTS = new Kpi(
            "Brennstellen",
            LightingKpiRow::lightingPointsTarget,
            LightingKpiRow::lightingPointsActual
    );

    private static final Kpi IOS = new Kpi(
            "IOs",
            LightingKpiRow::ioTarget,
            LightingKpiRow::ioActual
    );

    private static final Kpi TIME_LIGHTING_POINT = new Kpi(
            "Minuten pro Brennstelle",
            LightingKpiRow::minPerLightingPointTarget,
            LightingKpiRow::minPerLightingPointActual
    );

    private static final Kpi TIME_IO_ENGINEERING = new Kpi(
            "Minuten pro IO Engineering",
            LightingKpiRow::minPerIoEngineeringTarget,
            LightingKpiRow::minPerIoEngineeringActual
    );

    private static final Kpi TIME_IO_DELIVERY = new Kpi(
            "Minuten pro IO Abgabe",
            LightingKpiRow::minPerIoDeliveryTarget,
            LightingKpiRow::minPerIoDeliveryActual
    );

    private final KpiRepository repository;
    private List<LightingKpiRow> allRows = List.of();
    private List<LightingKpiRow> rows = List.of();

    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final ComboBox<String> shipTypeFilter = new ComboBox<>();
    private final Label correlationBasis = new Label();

    /** Verhindert, dass beim Befüllen der Filter mehrfach gerechnet wird. */
    private boolean updatingFilters = false;
    private final CheckBox onlyRelevant =
            new CheckBox("Nur auswertungsrelevante Projekte");
    private final Label projectCount = new Label();

    private final TextField lightingPointsInput = new TextField();
    private final TextField ioInput = new TextField();
    private final TextField hourlyRateInput = new TextField();
    private final Label estimateHint = new Label();

    private final TableView<EstimateLine> estimateTable = new TableView<>();
    private final TableView<KpiLine> kpiTable = new TableView<>();
    private final TableView<CorrelationLine> correlationTable =
            new TableView<>();
    private final TableView<LightingKpiRow> dataTable = new TableView<>();

    public KalkulationView(KpiRepository repository) {
        this.repository = repository;

        setFitToWidth(true);
        getStyleClass().add("kalkulation-scroll");

        Label pageTitle = new Label("Kalkulation Beleuchtung");
        pageTitle.getStyleClass().add("page-title");

        Label description = new Label(
                "Erfahrungswerte aus bisherigen Projekten und "
                        + "Aufwandsschätzung für neue Projekte"
        );
        description.getStyleClass().add("page-description");

        VBox heading = new VBox(4, pageTitle, description);

        categoryFilter.setPrefWidth(210);
        categoryFilter.setOnAction(event -> {
            if (!updatingFilters) {
                updateShipTypeOptions();
                applyFilter();
            }
        });

        shipTypeFilter.setPrefWidth(210);
        shipTypeFilter.setOnAction(event -> {
            if (!updatingFilters) {
                applyFilter();
            }
        });

        onlyRelevant.setSelected(true);
        onlyRelevant.setOnAction(event -> reload());

        HBox filters = new HBox(
                10,
                new Label("Kategorie:"),
                categoryFilter,
                new Label("Schiffstyp:"),
                shipTypeFilter,
                onlyRelevant,
                projectCount
        );
        filters.setAlignment(Pos.CENTER_LEFT);

        VBox content = new VBox(
                16,
                heading,
                filters,
                buildEstimateCard(),
                buildKpiCard(),
                buildCorrelationCard(),
                buildDataCard()
        );
        content.getStyleClass().add("content");

        setContent(content);
        reload();
    }

    /** Daten neu aus der Datenbank laden (z. B. nach neuem Projekt). */
    public void reload() {
        allRows = repository.findLightingData(onlyRelevant.isSelected());

        String previous = categoryFilter.getValue();

        List<String> categories = allRows.stream()
                .map(LightingKpiRow::category)
                .filter(category -> category != null && !category.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        updatingFilters = true;
        categoryFilter.getItems().setAll(ALL_CATEGORIES);
        categoryFilter.getItems().addAll(categories);
        categoryFilter.setValue(
                categories.contains(previous) ? previous : ALL_CATEGORIES
        );
        updatingFilters = false;

        updateShipTypeOptions();
        applyFilter();
    }

    /** Schiffstypen passend zur gewählten Kategorie anbieten. */
    private void updateShipTypeOptions() {
        String previous = shipTypeFilter.getValue();

        List<String> shipTypes = allRows.stream()
                .filter(this::matchesCategory)
                .map(LightingKpiRow::shipType)
                .filter(type -> type != null && !type.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        updatingFilters = true;
        shipTypeFilter.getItems().setAll(ALL_SHIP_TYPES);
        shipTypeFilter.getItems().addAll(shipTypes);
        shipTypeFilter.setValue(
                shipTypes.contains(previous) ? previous : ALL_SHIP_TYPES
        );
        updatingFilters = false;
    }

    private boolean matchesCategory(LightingKpiRow row) {
        String category = categoryFilter.getValue();
        return category == null
                || ALL_CATEGORIES.equals(category)
                || category.equals(row.category());
    }

    private boolean matchesShipType(LightingKpiRow row) {
        String shipType = shipTypeFilter.getValue();
        return shipType == null
                || ALL_SHIP_TYPES.equals(shipType)
                || shipType.equals(row.shipType());
    }

    private void applyFilter() {
        rows = allRows.stream()
                .filter(this::matchesCategory)
                .filter(this::matchesShipType)
                .toList();

        projectCount.setText(rows.size() + " Projekte mit Beleuchtungsdaten");
        correlationBasis.setText(describeBasis());

        dataTable.getItems().setAll(rows);
        updateKpis();
        updateCorrelations();
        calculate();
    }

    // ------------------------------------------------------------------
    // Aufbau der Karten
    // ------------------------------------------------------------------

    private VBox buildEstimateCard() {
        lightingPointsInput.setPromptText("z. B. 850");
        ioInput.setPromptText("leer lassen = aus Brennstellen schätzen");
        hourlyRateInput.setPromptText("optional, z. B. 85");

        lightingPointsInput.setOnAction(event -> calculate());
        ioInput.setOnAction(event -> calculate());
        hourlyRateInput.setOnAction(event -> calculate());

        Button calculateButton = new Button("Berechnen");
        calculateButton.getStyleClass().add("primary-button");
        calculateButton.setOnAction(event -> calculate());

        GridPane inputs = new GridPane();
        inputs.setHgap(10);
        inputs.setVgap(8);
        inputs.addRow(0, new Label("Brennstellen:"), lightingPointsInput);
        inputs.addRow(1, new Label("IOs:"), ioInput);
        inputs.addRow(2, new Label("Stundensatz (€/h):"), hourlyRateInput);
        inputs.add(calculateButton, 1, 3);
        lightingPointsInput.setPrefWidth(260);

        addColumn(estimateTable, "Tätigkeit", EstimateLine::activity);
        addColumn(estimateTable, "Menge", line -> format(line.quantity()));
        addColumn(estimateTable, "Min. pro Einheit",
                line -> format(line.minutesPerUnit()));
        addColumn(estimateTable, "Basis", EstimateLine::basis);
        addColumn(estimateTable, "Stunden", line -> format(line.hours()));
        addColumn(estimateTable, "Spanne (h)",
                line -> format(line.hoursLow()) + " – "
                        + format(line.hoursHigh()));
        addColumn(estimateTable, "Kosten (€)", line -> format(line.cost()));

        estimateTable.setPrefHeight(170);
        TableAutoSize.install(estimateTable);
        estimateTable.setPlaceholder(
                new Label("Brennstellen und/oder IOs eingeben und berechnen.")
        );

        estimateHint.getStyleClass().add("hint");
        estimateHint.setWrapText(true);

        return card("Aufwandsschätzung", inputs, estimateTable, estimateHint);
    }

    private VBox buildKpiCard() {
        addColumn(kpiTable, "Kennzahl", KpiLine::name);
        addColumn(kpiTable, "n Soll", line -> String.valueOf(line.target().n()));
        addColumn(kpiTable, "Median Soll", line -> format(line.target().median()));
        addColumn(kpiTable, "n Ist", line -> String.valueOf(line.actual().n()));
        addColumn(kpiTable, "Median Ist", line -> format(line.actual().median()));
        addColumn(kpiTable, "Spanne Ist (Q1 – Q3)",
                line -> range(line.actual()));
        addColumn(kpiTable, "Ist / Soll (Median)",
                line -> line.ratio().isEmpty()
                        ? "–"
                        : String.format(Locale.GERMANY, "%.2f",
                                line.ratio().median()));

        kpiTable.setPrefHeight(190);
        TableAutoSize.install(kpiTable);

        Label hint = new Label(
                "Ist / Soll > 1 bedeutet: Es wurde mehr gebraucht als geplant."
        );
        hint.getStyleClass().add("hint");

        return card("Kennzahlen", kpiTable, hint);
    }

    private VBox buildCorrelationCard() {
        addColumn(correlationTable, "Zusammenhang", CorrelationLine::name);
        addColumn(correlationTable, "n", line -> String.valueOf(line.n()));
        addColumn(correlationTable, "r", line -> line.regression() == null
                ? "–"
                : String.format(Locale.GERMANY, "%.2f", line.regression().r()));
        addColumn(correlationTable, "Stärke",
                line -> strength(line.regression()));
        addColumn(correlationTable, "Formel (y = a + b · x)",
                line -> line.regression() == null
                        ? "–"
                        : "y = " + format(line.regression().intercept())
                        + " + " + String.format(Locale.GERMANY, "%.3f",
                        line.regression().slope()) + " · x");

        correlationTable.setPrefHeight(220);
        TableAutoSize.install(correlationTable);

        Label hint = new Label(
                "r nahe 1 oder −1 = starker Zusammenhang, nahe 0 = keiner. "
                        + "Bei kleinem n nur als Tendenz lesen."
        );
        hint.getStyleClass().add("hint");

        correlationBasis.getStyleClass().add("hint");
        correlationBasis.setWrapText(true);

        return card("Zusammenhänge", correlationBasis, correlationTable, hint);
    }

    private VBox buildDataCard() {
        addColumn(dataTable, "Schiffsname", LightingKpiRow::shipName);
        addColumn(dataTable, "Werft", LightingKpiRow::shipyard);
        addColumn(dataTable, "Schiffstyp", LightingKpiRow::shipType);
        addColumn(dataTable, "Status", LightingKpiRow::status);
        addColumn(dataTable, "Brennst. Soll",
                row -> format(row.lightingPointsTarget()));
        addColumn(dataTable, "Brennst. Ist",
                row -> format(row.lightingPointsActual()));
        addColumn(dataTable, "IOs Soll", row -> format(row.ioTarget()));
        addColumn(dataTable, "IOs Ist", row -> format(row.ioActual()));
        addColumn(dataTable, "Min/BS Soll",
                row -> format(row.minPerLightingPointTarget()));
        addColumn(dataTable, "Min/BS Ist",
                row -> format(row.minPerLightingPointActual()));
        addColumn(dataTable, "Min/IO Eng. Soll",
                row -> format(row.minPerIoEngineeringTarget()));
        addColumn(dataTable, "Min/IO Eng. Ist",
                row -> format(row.minPerIoEngineeringActual()));
        addColumn(dataTable, "Min/IO Abg. Soll",
                row -> format(row.minPerIoDeliveryTarget()));
        addColumn(dataTable, "Min/IO Abg. Ist",
                row -> format(row.minPerIoDeliveryActual()));

        dataTable.setPrefHeight(350);
        TableAutoSize.install(dataTable);
        dataTable.setPlaceholder(
                new Label("Keine Projekte mit Beleuchtungsdaten gefunden.")
        );

        return card("Datengrundlage", dataTable);
    }

    // ------------------------------------------------------------------
    // Berechnungen
    // ------------------------------------------------------------------

    private void updateKpis() {
        List<KpiLine> lines = new ArrayList<>();

        Stream.of(LIGHTING_POINTS, IOS, TIME_LIGHTING_POINT,
                        TIME_IO_ENGINEERING, TIME_IO_DELIVERY)
                .forEach(kpi -> lines.add(new KpiLine(
                        kpi.name(),
                        summarize(kpi.target()),
                        summarize(kpi.actual()),
                        KpiStatistics.summarize(rows.stream()
                                .map(row -> ratio(
                                        kpi.actual().apply(row),
                                        kpi.target().apply(row)))
                                .toList())
                )));

        kpiTable.getItems().setAll(lines);
    }

    private void updateCorrelations() {
        List<CorrelationLine> lines = new ArrayList<>();

        addCorrelation(lines, "Brennstellen aus Länge (m)",
                LightingKpiRow::lengthM, LightingKpiRow::lightingPoints);
        addCorrelation(lines, "Brennstellen aus GT/BRZ",
                LightingKpiRow::volumeGt, LightingKpiRow::lightingPoints);
        addCorrelation(lines, "Brennstellen aus Fläche (m²)",
                LightingKpiRow::areaSqm, LightingKpiRow::lightingPoints);
        addCorrelation(lines, "IOs aus Brennstellen",
                LightingKpiRow::lightingPoints, LightingKpiRow::ioCount);
        addCorrelation(lines, "Min/Brennstelle (Ist) aus Brennstellen (Ist)",
                LightingKpiRow::lightingPointsActual,
                LightingKpiRow::minPerLightingPointActual);
        addCorrelation(lines, "Min/IO Engineering (Ist) aus IOs (Ist)",
                LightingKpiRow::ioActual,
                LightingKpiRow::minPerIoEngineeringActual);

        correlationTable.getItems().setAll(lines);
    }

    private void addCorrelation(
            List<CorrelationLine> lines,
            String name,
            Function<LightingKpiRow, Double> x,
            Function<LightingKpiRow, Double> y
    ) {
        List<Double> xs = rows.stream().map(x).toList();
        List<Double> ys = rows.stream().map(y).toList();

        lines.add(new CorrelationLine(
                name,
                KpiStatistics.countPairs(xs, ys),
                KpiStatistics.regression(xs, ys)
        ));
    }

    private void calculate() {
        Double lightingPoints = parseInput(lightingPointsInput);
        Double ios = parseInput(ioInput);
        Double hourlyRate = parseInput(hourlyRateInput);

        estimateTable.getItems().clear();

        if (lightingPoints == null && ios == null) {
            estimateHint.setText("");
            return;
        }

        List<String> hints = new ArrayList<>();
        String ioLabel = "";

        // IOs fehlen -> über Regression aus den Brennstellen schätzen
        if (ios == null && lightingPoints != null) {
            KpiStatistics.Regression regression = KpiStatistics.regression(
                    rows.stream().map(LightingKpiRow::lightingPoints).toList(),
                    rows.stream().map(LightingKpiRow::ioCount).toList()
            );

            if (regression != null && regression.n() >= MIN_N) {
                ios = Math.max(0, regression.predict(lightingPoints));
                ioLabel = " (IOs geschätzt)";
                hints.add("IOs wurden aus den Brennstellen geschätzt (r = "
                        + String.format(Locale.GERMANY, "%.2f", regression.r())
                        + ", n = " + regression.n() + ").");
            } else {
                hints.add("IOs konnten nicht geschätzt werden: "
                        + "zu wenige Projekte mit Brennstellen und IOs.");
            }
        }

        List<EstimateLine> lines = new ArrayList<>();

        if (lightingPoints != null) {
            addEstimate(lines, hints, "Brennstellen",
                    lightingPoints, TIME_LIGHTING_POINT, hourlyRate);
        }

        if (ios != null) {
            addEstimate(lines, hints, "IO Engineering" + ioLabel,
                    ios, TIME_IO_ENGINEERING, hourlyRate);
            addEstimate(lines, hints, "IO Abgabe" + ioLabel,
                    ios, TIME_IO_DELIVERY, hourlyRate);
        }

        // Bewusst keine Gesamtsumme: Die Kennzahlen beschreiben
        // unterschiedliche Größen und werden nicht addiert.

        hints.add("Spanne = mittlere 50 % der bisherigen Projekte (Q1 – Q3).");

        estimateTable.getItems().setAll(lines);
        estimateHint.setText(String.join("\n", hints));
    }

    private void addEstimate(
            List<EstimateLine> lines,
            List<String> hints,
            String activity,
            double quantity,
            Kpi kpi,
            Double hourlyRate
    ) {
        KpiStatistics.Summary actual = summarize(kpi.actual());
        KpiStatistics.Summary target = summarize(kpi.target());

        KpiStatistics.Summary basis;
        String basisText;

        if (actual.n() >= MIN_N) {
            basis = actual;
            basisText = "Ist, n = " + actual.n();
        } else if (!target.isEmpty()) {
            basis = target;
            basisText = "Soll, n = " + target.n();
            hints.add(kpi.name() + ": weniger als " + MIN_N
                    + " Ist-Werte, daher Schätzung über Soll-Werte.");
        } else if (!actual.isEmpty()) {
            basis = actual;
            basisText = "Ist, n = " + actual.n();
        } else {
            hints.add(kpi.name() + ": keine Daten vorhanden.");
            return;
        }

        double hours = quantity * basis.median() / 60;
        double low = quantity * basis.q1() / 60;
        double high = quantity * basis.q3() / 60;

        lines.add(new EstimateLine(
                activity,
                quantity,
                basis.median(),
                basisText,
                hours,
                low,
                high,
                hourlyRate == null ? null : hours * hourlyRate
        ));
    }

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

    /**
     * Beschreibt, auf welchen Schiffen die Zusammenhänge beruhen.
     * Wird aus den tatsächlich gefilterten Daten erzeugt und bleibt
     * daher korrekt, wenn später weitere Kategorien importiert werden.
     */
    private String describeBasis() {
        if (rows.isEmpty()) {
            return "Datenbasis: keine Projekte.";
        }

        String categories = String.join(", ", rows.stream()
                .map(LightingKpiRow::category)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList());

        String shipTypes = ALL_SHIP_TYPES.equals(shipTypeFilter.getValue())
                ? "alle Schiffstypen"
                : "Schiffstyp " + shipTypeFilter.getValue();

        return "Datenbasis: " + categories + ", " + shipTypes + ", "
                + rows.size() + " Projekte. Die Zusammenhänge gelten nur "
                + "für diese Schiffe und lassen sich nicht auf andere "
                + "Schiffstypen übertragen.";
    }

    private KpiStatistics.Summary summarize(Function<LightingKpiRow, Double> value) {
        return KpiStatistics.summarize(rows.stream().map(value).toList());
    }

    private static Double ratio(Double actual, Double target) {
        if (actual == null || target == null || target == 0) {
            return null;
        }
        return actual / target;
    }

    private static String strength(KpiStatistics.Regression regression) {
        if (regression == null) {
            return "zu wenig Daten";
        }

        double r = Math.abs(regression.r());

        if (r >= 0.7) {
            return "stark";
        }
        if (r >= 0.3) {
            return "mittel";
        }
        return "schwach";
    }

    private static String range(KpiStatistics.Summary summary) {
        if (summary.isEmpty()) {
            return "–";
        }
        return format(summary.q1()) + " – " + format(summary.q3());
    }

    private static String format(Double value) {
        return value == null ? "–" : NUMBER.format(value);
    }

    /** Akzeptiert deutsche Eingaben wie "1.200" oder "85,5". */
    private static Double parseInput(TextField field) {
        String text = field.getText() == null ? "" : field.getText().trim();

        if (text.isEmpty()) {
            return null;
        }

        if (text.contains(",")) {
            text = text.replace(".", "").replace(',', '.');
        } else if (text.matches("\\d{1,3}(\\.\\d{3})+")) {
            text = text.replace(".", "");
        }

        try {
            double value = Double.parseDouble(text);
            return value < 0 ? null : value;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static VBox card(String title, Node... children) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");

        VBox box = new VBox(10);
        box.getChildren().add(label);
        box.getChildren().addAll(children);
        box.getStyleClass().add("content-card");

        return box;
    }

    private static <T> void addColumn(
            TableView<T> table,
            String title,
            Function<T, String> value
    ) {
        TableColumn<T, String> column = new TableColumn<>(title);

        column.setCellValueFactory(cell ->
                new SimpleStringProperty(value.apply(cell.getValue()))
        );

        table.getColumns().add(column);
    }
}