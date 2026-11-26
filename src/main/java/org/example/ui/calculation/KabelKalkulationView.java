package org.example.ui.calculation;

import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.example.repository.KabelRepository;
import org.example.repository.KabelRepository.KabelProjekt;
import org.example.ui.common.TableAutoSize;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Kalkulation Kabel: gleicher Aufbau wie die Beleuchtungsseite
 * (Kopf, gemeinsame Filter, Reiter). Rechnet die Kabellänge
 * Angebot (Soll) und Real (Ist) für ein neues Schiff.
 */
public class KabelKalkulationView extends ScrollPane {

    private static final String ALL_CATEGORIES = "Alle Kategorien";
    private static final String ALL_SHIP_TYPES = "Alle Schiffstypen";

    private static final NumberFormat NUMBER =
            NumberFormat.getNumberInstance(Locale.GERMANY);

    static {
        NUMBER.setMaximumFractionDigits(1);
    }

    private final KabelRepository repository = new KabelRepository();
    private List<KabelProjekt> allRows = List.of();
    private List<KabelProjekt> rows = List.of();

    private final ComboBox<String> categoryFilter = new ComboBox<>();
    private final ComboBox<String> shipTypeFilter = new ComboBox<>();
    private final CheckBox onlyRelevant =
            new CheckBox("Nur auswertungsrelevante Projekte");
    private final Label projectCount = new Label();
    private boolean updatingFilters = false;

    private final KabelSchaetzungCard schaetzung = new KabelSchaetzungCard();
    private final TableView<KabelProjekt> dataTable = new TableView<>();

    public KabelKalkulationView() {
        setFitToWidth(true);
        setFitToHeight(true);
        setHbarPolicy(ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollBarPolicy.NEVER);
        getStyleClass().add("kalkulation-scroll");

        Label pageTitle = new Label("Kalkulation Kabel");
        pageTitle.getStyleClass().add("page-title");

        Label description = new Label(
                "Kabellänge für neue Projekte aus bisherigen Angeboten "
                        + "und real verlegten Kabeln schätzen");
        description.getStyleClass().add("page-description");
        description.setWrapText(true);

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

        Button refreshButton = new Button("Aktualisieren");
        refreshButton.getStyleClass().add("calculation-refresh-button");
        refreshButton.setOnAction(event -> reload());

        HBox categoryGroup = new HBox(8, new Label("Kategorie:"), categoryFilter);
        categoryGroup.setAlignment(Pos.CENTER_LEFT);
        HBox shipTypeGroup = new HBox(8, new Label("Schiffstyp:"), shipTypeFilter);
        shipTypeGroup.setAlignment(Pos.CENTER_LEFT);

        FlowPane filters = new FlowPane(16, 10, categoryGroup, shipTypeGroup,
                onlyRelevant, refreshButton);
        filters.setAlignment(Pos.CENTER_LEFT);

        projectCount.setWrapText(true);
        projectCount.getStyleClass().add("hint");
        Label filterHint = new Label("Die Filter gelten für alle Reiter.");
        filterHint.setWrapText(true);
        filterHint.getStyleClass().add("hint");

        VBox filterCard = new VBox(8, filters, projectCount, filterHint);
        filterCard.getStyleClass().add("calculation-filter-card");

        TabPane tabs = new TabPane();
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("calculation-tabs");
        tabs.setMinWidth(0);
        tabs.setMinHeight(0);
        tabs.getTabs().addAll(
                calculationTab("Schätzung",
                        "Kabellänge aus Schiffsgrößen schätzen, getrennt nach "
                                + "Angebot (Soll) und real verlegt (Ist).",
                        card("Kabellänge schätzen und ähnliche Schiffe vergleichen", schaetzung)),
                calculationTab("Datengrundlage",
                        "Die geladenen Projekte mit Kabellänge Angebot und/oder Real prüfen.",
                        buildDataCard())
        );

        VBox content = new VBox(16, heading, filterCard, tabs);
        content.getStyleClass().addAll("content", "calculation-content");
        content.setMinHeight(0);
        content.setMinWidth(0);
        VBox.setVgrow(tabs, Priority.ALWAYS);

        setContent(content);
        reload();
    }

    /** Daten neu aus der Datenbank laden. */
    public void reload() {
        allRows = repository.lade(null, null, onlyRelevant.isSelected());

        String previous = categoryFilter.getValue();
        List<String> categories = allRows.stream()
                .map(KabelProjekt::kategorie)
                .filter(c -> c != null && !c.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        updatingFilters = true;
        categoryFilter.getItems().setAll(ALL_CATEGORIES);
        categoryFilter.getItems().addAll(categories);
        categoryFilter.setValue(categories.contains(previous) ? previous : ALL_CATEGORIES);
        updatingFilters = false;

        updateShipTypeOptions();
        applyFilter();
    }

    private void updateShipTypeOptions() {
        String previous = shipTypeFilter.getValue();
        List<String> shipTypes = allRows.stream()
                .filter(this::matchesCategory)
                .map(KabelProjekt::schiffstyp)
                .filter(t -> t != null && !t.isBlank())
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();

        updatingFilters = true;
        shipTypeFilter.getItems().setAll(ALL_SHIP_TYPES);
        shipTypeFilter.getItems().addAll(shipTypes);
        shipTypeFilter.setValue(shipTypes.contains(previous) ? previous : ALL_SHIP_TYPES);
        updatingFilters = false;
    }

    private boolean matchesCategory(KabelProjekt p) {
        String c = categoryFilter.getValue();
        return c == null || ALL_CATEGORIES.equals(c) || c.equals(p.kategorie());
    }

    private boolean matchesShipType(KabelProjekt p) {
        String t = shipTypeFilter.getValue();
        return t == null || ALL_SHIP_TYPES.equals(t) || t.equals(p.schiffstyp());
    }

    private void applyFilter() {
        rows = allRows.stream()
                .filter(this::matchesCategory)
                .filter(this::matchesShipType)
                .toList();

        projectCount.setText(rows.size() + " Projekte mit Kabeldaten");
        dataTable.getItems().setAll(rows);

        schaetzung.setFilter(
                ALL_CATEGORIES.equals(categoryFilter.getValue()) ? null : categoryFilter.getValue(),
                ALL_SHIP_TYPES.equals(shipTypeFilter.getValue()) ? null : shipTypeFilter.getValue(),
                onlyRelevant.isSelected()
        );
    }

    // ------------------------------------------------------------------

    private VBox buildDataCard() {
        addColumn(dataTable, "Schiffsname", KabelProjekt::schiff);
        addColumn(dataTable, "Werft", KabelProjekt::werft);
        addColumn(dataTable, "Schiffstyp", KabelProjekt::schiffstyp);
        addColumn(dataTable, "Länge (m)", p -> format(p.laenge()));
        addColumn(dataTable, "Breite (m)", p -> format(p.breite()));
        addColumn(dataTable, "Volumen (GT)", p -> format(p.volumen()));
        addColumn(dataTable, "Fläche (m²)", p -> format(p.flaeche()));
        addColumn(dataTable, "Kabel berechnet (km)", p -> format(p.kabelBerechnet()));
        addColumn(dataTable, "Angebot (km)", p -> format(p.kabelAngebot()));
        addColumn(dataTable, "Real (km)", p -> format(p.kabelReal()));
        addColumn(dataTable, "Real ggü. Angebot", KabelKalkulationView::abweichung);
        addColumn(dataTable, "Bezugsjahr", p -> p.hatJahr() ? p.bezugsjahr() + " (" + p.jahrQuelle() + ")" : "⚠ ohne Jahr");

        dataTable.setPrefHeight(350);
        TableAutoSize.install(dataTable);
        dataTable.setPlaceholder(new Label("Keine Projekte mit Kabeldaten gefunden."));

        return card("Datengrundlage", dataTable);
    }

    private static String abweichung(KabelProjekt p) {
        Double a = p.kabelAngebot();
        Double r = p.kabelReal();
        if (a == null || r == null || a <= 0) {
            return "–";
        }
        double prozent = (r / a - 1) * 100;
        return (prozent >= 0 ? "+" : "") + String.format(Locale.GERMANY, "%.1f %%", prozent);
    }

    private Tab calculationTab(String title, String description, Node... cards) {
        Label intro = new Label(description);
        intro.setWrapText(true);
        intro.getStyleClass().add("calculation-tab-intro");

        VBox body = new VBox(16, intro);
        body.getChildren().addAll(cards);
        body.getStyleClass().add("calculation-tab-body");
        body.setMinWidth(0);

        ScrollPane scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("calculation-tab-scroll");

        Tab tab = new Tab(title, scroll);
        tab.setClosable(false);
        return tab;
    }

    private static VBox card(String title, Node... children) {
        Label label = new Label(title);
        label.getStyleClass().add("section-title");

        VBox box = new VBox(10);
        box.getChildren().add(label);
        box.getChildren().addAll(children);
        box.getStyleClass().add("content-card");
        box.setMinWidth(0);
        return box;
    }

    private static String format(Double value) {
        return value == null ? "–" : NUMBER.format(value);
    }

    private static <T> void addColumn(TableView<T> table, String title, Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        table.getColumns().add(column);
    }
}
