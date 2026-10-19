package org.example.ui.project;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.util.StringConverter;
import org.example.model.LookupItem;
import org.example.model.ProjectEditData;
import org.example.repository.ProjectRepository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.*;
import java.util.function.IntConsumer;

/** Bearbeitet genau ein bestehendes Projekt. Bis zum Speichern nur lokale Eingaben. */
public class ProjectEditView extends BorderPane {
    private final ProjectRepository repository;
    private final ProjectEditData original;
    private final TabPane tabs = new TabPane();
    private final Label errorLabel = new Label();
    private final Map<String, TextField> numbers = new LinkedHashMap<>();
    private final Map<String, DatePicker> dates = new LinkedHashMap<>();
    private final Map<Node, Tab> fieldTabs = new IdentityHashMap<>();
    private final Map<Integer, TextField[]> metricFields = new LinkedHashMap<>();
    private final Map<Integer, TextField> departmentFields = new LinkedHashMap<>();
    private final TextField name = new TextField();
    private final TextField status = new TextField();
    private final ComboBox<LookupItem> shipyard = new ComboBox<>();
    private final ComboBox<LookupItem> category = new ComboBox<>();
    private final ComboBox<LookupItem> shipType = new ComboBox<>();
    private final CheckBox relevant = new CheckBox("Für Auswertungen berücksichtigen");
    private final CheckBox ringCable = new CheckBox("Ringkabel enthalten");
    private final TextField editor = new TextField();
    private final TextField link = new TextField();
    private final TextArea evaluation = new TextArea();
    private final TextArea comments = new TextArea();
    private List<Object> savedState;
    private boolean saving;
    private Runnable onCancel = () -> {};
    private IntConsumer onSaved = id -> {};
    private static final DateTimeFormatter GERMAN_DATE =
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT);

    public ProjectEditView(ProjectRepository repository, int projectId) {
        this.repository = repository;
        this.original = repository.findForEditing(projectId);
        getStyleClass().addAll("content", "project-edit");
        Label title = new Label("Projekt bearbeiten");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label(original.shipName() + " · Projekt " + original.id());
        subtitle.getStyleClass().add("page-description");
        Label help = new Label("Änderungen werden erst mit „Änderungen speichern“ übernommen. "
                + "Leere Zahlenfelder bedeuten unbekannt; 0 bleibt ein eigener Wert.");
        help.setWrapText(true);
        VBox header = new VBox(6, title, subtitle, help);
        header.setPadding(new Insets(0, 0, 16, 0));
        setTop(header);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getStyleClass().add("project-edit-tabs");
        createGeneralTab();
        createTechnicalTab();
        createDatesTab();
        createMetricsTab();
        createDepartmentsTab();
        createNotesTab();
        setCenter(tabs);

        Button cancel = new Button("Abbrechen");
        cancel.setId("edit-cancel");
        cancel.setOnAction(event -> { if (confirmDiscardChanges()) onCancel.run(); });
        Button save = new Button("Änderungen speichern");
        save.setId("edit-save");
        save.getStyleClass().add("primary-button");
        save.setOnAction(event -> save());
        errorLabel.getStyleClass().add("project-edit-error");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        HBox actions = new HBox(10, cancel, save);
        actions.setAlignment(Pos.CENTER_RIGHT);
        VBox footer = new VBox(8, errorLabel, actions);
        footer.setPadding(new Insets(12, 0, 0, 0));
        setBottom(footer);
        savedState = currentState();
    }

    private void createGeneralTab() {
        Tab tab = tab("Stammdaten");
        GridPane grid = grid();
        name.setText(original.shipName()); name.setId("edit-name");
        status.setText(original.status()); status.setPromptText("z. B. Angebot oder Auftrag");
        status.setId("edit-status");
        shipyard.getItems().setAll(repository.findShipyards());
        shipyard.setPromptText("Keine Werft angegeben");
        select(shipyard, original.shipyardId());
        Button clearShipyard = new Button("Leeren");
        clearShipyard.setOnAction(e -> shipyard.getSelectionModel().clearSelection());
        HBox yardRow = new HBox(8, shipyard, clearShipyard);
        HBox.setHgrow(shipyard, Priority.ALWAYS);
        category.getItems().setAll(repository.findCategories());
        category.valueProperty().addListener((o, old, selected) -> {
            shipType.getItems().setAll(selected == null ? List.of()
                    : repository.findShipTypes(selected.id()));
            shipType.getSelectionModel().clearSelection();
        });
        select(category, original.categoryId());
        select(shipType, original.shipTypeId());
        relevant.setSelected(original.relevant());
        add(grid, tab, 0, "Schiffsname *", name);
        add(grid, tab, 1, "Status *", status);
        add(grid, tab, 2, "Werft", yardRow); track(shipyard, tab);
        add(grid, tab, 3, "Kategorie *", category);
        add(grid, tab, 4, "Schiffstyp *", shipType);
        add(grid, tab, 5, "Auswertung", relevant);
        tab.setContent(scroll(new VBox(16, hint("* Pflichtfeld. Kategorie und Schiffstyp werden aus den Stammdaten gewählt."), grid)));
    }

    private void createTechnicalTab() {
        Tab tab = tab("Technik");
        GridPane grid = grid();
        number(grid, tab, 0, "length", "Schiffslänge [m]", original.lengthM());
        number(grid, tab, 1, "width", "Breite [m]", original.widthM());
        number(grid, tab, 2, "gt", "BRZ / GT", original.volumeGt());
        number(grid, tab, 3, "area", "Fläche [m²]", original.areaSqm());
        number(grid, tab, 4, "cableCalculated", "Kabel berechnet [km]", original.cableCalculatedKm());
        number(grid, tab, 5, "cableOffer", "Kabel Angebot [km]", original.cableOfferKm());
        number(grid, tab, 6, "cableActual", "Kabel Ist [km]", original.cableActualKm());
        ringCable.setSelected(original.ringCableIncluded());
        add(grid, tab, 7, "Kabelumfang", ringCable);
        tab.setContent(scroll(new VBox(16, hint("Fläche bitte nach eurer vereinbarten Flächendefinition eintragen. "
                + "Sie wird hier nicht automatisch aus Länge × Breite berechnet."), grid)));
    }

    private void createDatesTab() {
        Tab tab = tab("Termine");
        GridPane grid = grid();
        date(grid, tab, 0, "offer", "Angebotsdatum", original.offerDate());
        date(grid, tab, 1, "order", "Auftragsdatum", original.orderDate());
        date(grid, tab, 2, "start", "Baubeginn", original.constructionStart());
        date(grid, tab, 3, "endPlanned", "Bauende Soll", original.constructionEndPlanned());
        date(grid, tab, 4, "endActual", "Bauende Ist", original.constructionEndActual());
        tab.setContent(scroll(new VBox(16, hint("Datum über den Kalender wählen oder als TT.MM.JJJJ eingeben. Leer bedeutet unbekannt."), grid)));
    }

    private void createMetricsTab() {
        Tab tab = tab("Kennzahlen");
        GridPane grid = grid();
        grid.addRow(0, heading("Kennzahl / Einheit"), heading("Soll"), heading("Ist"));
        int row = 1;
        for (ProjectEditData.Metric m : original.metrics()) {
            TextField target = new TextField(format(m.target()));
            TextField actual = new TextField(format(m.actual()));
            target.setId("edit-metric-" + m.code() + "-target");
            actual.setId("edit-metric-" + m.code() + "-actual");
            target.setPromptText("n. b."); actual.setPromptText("n. b.");
            Label label = new Label(m.name() + (m.unit() == null ? "" : " [" + m.unit() + "]"));
            label.setWrapText(true);
            grid.addRow(row++, label, target, actual);
            track(target, tab); track(actual, tab);
            metricFields.put(m.id(), new TextField[]{target, actual});
        }
        tab.setContent(scroll(new VBox(16, hint("Nur bekannte Werte ergänzen. Soll und Ist bleiben getrennt; "
                + "geschätzte Werte bitte nicht als Ist ausweisen. Minutenwerte stehen in der angegebenen Einheit."), grid)));
    }

    private void createDepartmentsTab() {
        Tab tab = tab("Fachbereiche");
        GridPane grid = grid(); int row = 0;
        for (ProjectEditData.Department d : original.departments()) {
            TextField value = new TextField(d.value());
            value.setId("edit-department-" + d.id());
            add(grid, tab, row++, d.code() + " · " + d.name(), value);
            departmentFields.put(d.id(), value);
        }
        tab.setContent(scroll(new VBox(16, hint("Die Fachbereichswerte werden wie bisher als Freitext gepflegt. "
                + "Bestehende Inhalte bleiben erhalten, bis du sie änderst."), grid)));
    }

    private void createNotesTab() {
        Tab tab = tab("Notizen"); GridPane grid = grid();
        editor.setText(original.editor()); link.setText(original.link());
        evaluation.setText(original.evaluation()); comments.setText(original.comments());
        evaluation.setPrefRowCount(3); comments.setPrefRowCount(5);
        evaluation.setWrapText(true); comments.setWrapText(true);
        add(grid, tab, 0, "Bearbeiter", editor);
        add(grid, tab, 1, "Bewertung", evaluation);
        add(grid, tab, 2, "Link / Ablageort", link);
        add(grid, tab, 3, "Kommentare", comments);
        tab.setContent(scroll(grid));
    }

    private void save() {
        if (saving) return;
        errorLabel.setText("");
        try {
            if (name.getText().isBlank()) invalid(name, "Bitte einen Schiffsnamen eingeben.");
            if (status.getText().isBlank()) invalid(status, "Bitte einen Status eingeben.");
            if (category.getValue() == null) invalid(category, "Bitte eine Kategorie wählen.");
            if (shipType.getValue() == null) invalid(shipType, "Bitte einen Schiffstyp wählen.");
            List<ProjectEditData.Metric> metrics = new ArrayList<>();
            for (ProjectEditData.Metric m : original.metrics()) {
                TextField[] fields = metricFields.get(m.id());
                metrics.add(new ProjectEditData.Metric(m.id(), m.code(), m.name(), m.unit(),
                        numeric(fields[0], m.name() + " Soll"), numeric(fields[1], m.name() + " Ist")));
            }
            List<ProjectEditData.Department> departments = original.departments().stream()
                    .map(d -> new ProjectEditData.Department(d.id(), d.code(), d.name(),
                            departmentFields.get(d.id()).getText())).toList();
            ProjectEditData data = new ProjectEditData(original.id(),
                    shipyard.getValue() == null ? null : shipyard.getValue().id(),
                    category.getValue().id(), shipType.getValue().id(), name.getText(), status.getText(),
                    relevant.isSelected(), number("length"), number("width"), number("gt"), number("area"),
                    number("cableCalculated"), number("cableOffer"), number("cableActual"), ringCable.isSelected(),
                    date("offer"), date("order"), date("start"), date("endPlanned"), date("endActual"),
                    editor.getText(), evaluation.getText(), link.getText(), comments.getText(), metrics, departments);
            saving = true;
            repository.update(data);
            savedState = currentState();
        } catch (RuntimeException e) {
            errorLabel.setText(e.getMessage() == null ? "Änderungen konnten nicht gespeichert werden." : e.getMessage());
            return;
        } finally { saving = false; }
        // Die Datenbank ist bereits gespeichert. Fehler beim anschließenden
        // Anzeigen dürfen nicht als fehlgeschlagenes Speichern gemeldet werden.
        onSaved.accept(original.id());
    }

    private Double number(String key) { return numeric(numbers.get(key), numbers.get(key).getPromptText()); }
    private Double numeric(TextField field, String label) {
        try { return parseNumber(field.getText()); }
        catch (IllegalArgumentException e) { invalid(field, label + ": " + e.getMessage()); return null; }
    }

    /** Deutsche Tausender-/Dezimalschreibweise, alternativ Dezimalpunkt. */
    static Double parseNumber(String text) {
        if (text == null || text.isBlank()) return null;
        String value = text.trim().replace(" ", "").replace("\u00a0", "").replace("\u202f", "");
        if (value.contains(",")) {
            if (!value.matches("[+]?([0-9]+|[0-9]{1,3}(\\.[0-9]{3})+)(,[0-9]+)?"))
                throw new IllegalArgumentException("Bitte eine gültige Zahl ab 0 eingeben (z. B. 1250,5).");
            value = value.replace(".", "").replace(',', '.');
        } else if (value.matches("[+]?[0-9]{1,3}(\\.[0-9]{3})+")) {
            value = value.replace(".", "");
        }
        if (!value.matches("[+]?[0-9]+(\\.[0-9]+)?"))
            throw new IllegalArgumentException("Bitte eine gültige Zahl ab 0 eingeben (z. B. 1250,5).");
        double number = Double.parseDouble(value);
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Die Zahl ist zu groß.");
        return number;
    }

    private String date(String key) {
        DatePicker picker = dates.get(key);
        String text = picker.getEditor().getText();
        if (text == null || text.isBlank()) return null;
        try { return parseDate(text).toString(); }
        catch (DateTimeParseException e) {
            invalid(picker, "Bitte ein gültiges Datum als TT.MM.JJJJ eingeben."); return null;
        }
    }

    private static LocalDate parseDate(String value) {
        try { return LocalDate.parse(value.trim(), GERMAN_DATE); }
        catch (DateTimeParseException e) { return LocalDate.parse(value.trim()); }
    }

    private void date(GridPane grid, Tab tab, int row, String key, String label, String value) {
        DatePicker picker = new DatePicker(); picker.setId("edit-date-" + key);
        picker.setPromptText("TT.MM.JJJJ");
        picker.setConverter(new StringConverter<>() {
            @Override public String toString(LocalDate d) {
                return d == null ? "" : d.format(DateTimeFormatter.ofPattern("dd.MM.uuuu"));
            }
            @Override public LocalDate fromString(String s) {
                if (s == null || s.isBlank()) return null;
                try { return parseDate(s); }
                catch (DateTimeParseException e) { return picker.getValue(); }
            }
        });
        if (value != null && !value.isBlank()) {
            try { picker.setValue(parseDate(value)); }
            catch (DateTimeParseException e) { picker.getEditor().setText(value); }
        }
        dates.put(key, picker); add(grid, tab, row, label, picker);
    }

    private void number(GridPane grid, Tab tab, int row, String key, String label, Double value) {
        TextField field = new TextField(format(value));
        field.setPromptText(label); field.setId("edit-" + key);
        numbers.put(key, field); add(grid, tab, row, label, field);
    }

    private static String format(Double value) {
        return value == null ? "" : BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().replace('.', ',');
    }

    private Tab tab(String title) { Tab t = new Tab(title); tabs.getTabs().add(t); return t; }
    private GridPane grid() { GridPane g = new GridPane(); g.setHgap(20); g.setVgap(14); return g; }
    private ScrollPane scroll(Node content) {
        VBox box = new VBox(content); box.setPadding(new Insets(22));
        box.getStyleClass().add("project-edit-body");
        ScrollPane scroll = new ScrollPane(box); scroll.setFitToWidth(true);
        scroll.getStyleClass().add("project-edit-scroll"); return scroll;
    }
    private Label hint(String text) { Label l = new Label(text); l.setWrapText(true); l.setMaxWidth(800); return l; }
    private Label heading(String text) { Label l = new Label(text); l.getStyleClass().add("detail-card-title"); return l; }
    private void add(GridPane grid, Tab tab, int row, String label, Node field) {
        Label title = new Label(label); title.setWrapText(true); title.setMaxWidth(240);
        grid.addRow(row, title, field); GridPane.setHgrow(field, Priority.ALWAYS);
        if (field instanceof Control control) control.setMaxWidth(Double.MAX_VALUE);
        track(field, tab);
    }
    private void track(Node node, Tab tab) {
        fieldTabs.put(node, tab);
    }
    private void invalid(Node field, String message) {
        tabs.getSelectionModel().select(fieldTabs.get(field)); field.requestFocus();
        throw new IllegalArgumentException(message);
    }
    private static void select(ComboBox<LookupItem> combo, Integer id) {
        if (id != null) combo.getItems().stream().filter(i -> i.id() == id).findFirst().ifPresent(combo::setValue);
    }
    private List<Object> currentState() {
        List<Object> state = new ArrayList<>();
        for (Node node : fieldTabs.keySet()) {
            if (node instanceof TextInputControl input) state.add(Objects.toString(input.getText(), ""));
            else if (node instanceof ComboBox<?> combo) state.add(combo.getValue());
            else if (node instanceof CheckBox check) state.add(check.isSelected());
            else if (node instanceof DatePicker picker) {
                String text = Objects.toString(picker.getEditor().getText(), "").trim();
                try { state.add(text.isEmpty() ? "" : parseDate(text).toString()); }
                catch (DateTimeParseException e) { state.add(text); }
            }
        }
        return state;
    }
    public boolean isDirty() { return !currentState().equals(savedState); }
    public boolean confirmDiscardChanges() {
        if (!isDirty()) return true;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        if (getScene() != null) alert.initOwner(getScene().getWindow());
        alert.setTitle("Änderungen verwerfen?");
        alert.setHeaderText("Es gibt ungespeicherte Änderungen.");
        alert.setContentText("Möchtest du die Bearbeitung ohne Speichern verlassen?");
        ButtonType discard = new ButtonType("Änderungen verwerfen", ButtonBar.ButtonData.OK_DONE);
        ButtonType stay = new ButtonType("Weiter bearbeiten", ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(stay, discard);
        return alert.showAndWait().orElse(stay) == discard;
    }
    public void setOnCancel(Runnable onCancel) { this.onCancel = Objects.requireNonNull(onCancel); }
    public void setOnSaved(IntConsumer onSaved) { this.onSaved = Objects.requireNonNull(onSaved); }
}
