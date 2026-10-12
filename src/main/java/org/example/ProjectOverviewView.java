package org.example;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;

public class ProjectOverviewView extends VBox {

    private static final String ALL_CATEGORIES = "Alle Kategorien";

    private final TableView<Project> projectTable = new TableView<>();
    private Consumer<Project> onProjectOpen = project -> {};
    private Runnable onNewProject = () -> {};
    private final ProjectRepository repository;
    private final ObservableList<Project> allProjects =
            FXCollections.observableArrayList();

    public ProjectOverviewView(ProjectRepository repository) {
        this.repository = repository;
        getStyleClass().add("content");

        Label pageTitle = new Label("Projektübersicht");
        pageTitle.getStyleClass().add("page-title");

        Label description = new Label(
                "Schiffsprojekte anzeigen, pflegen und auswerten"
        );
        description.getStyleClass().add("page-description");

        VBox heading = new VBox(4, pageTitle, description);

        Button detailsButton = new Button("Details anzeigen");
        detailsButton.getStyleClass().add("primary-button");
        detailsButton.setOnAction(event -> openSelectedProject());

        Button newProjectButton = new Button("Neues Projekt");
        newProjectButton.getStyleClass().add("primary-button");
        newProjectButton.setOnAction(event -> onNewProject.run());

        HBox buttons = new HBox(10, detailsButton, newProjectButton);
        HBox header = new HBox(20, heading, buttons);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(heading, Priority.ALWAYS);

        addColumn("Schiffsname", Project::getShipName);
        addColumn("Werft", Project::getShipyard);
        addColumn("Kategorie", Project::getCategory);
        addColumn("Schiffstyp", Project::getShipType);
        addColumn("Status", Project::getStatus);

        List<Project> projects = repository.findAll();

        allProjects.setAll(projects);

        FilteredList<Project> filteredProjects = new FilteredList<>(
                allProjects,
                project -> true
        );

        projectTable.setItems(filteredProjects);
        projectTable.setPlaceholder(
                new Label("Keine passenden Projekte gefunden.")
        );

        // Startbreiten passend zu Überschrift und Inhalt,
        // danach frei per Maus verstellbar.
        TableAutoSize.autoSize(projectTable, allProjects);

        ComboBox<String> categoryFilter = new ComboBox<>();
        categoryFilter.setPrefWidth(210);
        categoryFilter.getItems().add(ALL_CATEGORIES);

        projects.stream()
                .map(Project::getCategory)
                .filter(category ->
                        category != null && !category.isBlank()
                )
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .forEach(categoryFilter.getItems()::add);

        categoryFilter.setValue(ALL_CATEGORIES);

        TextField searchField = new TextField();
        searchField.setPromptText(
                "Schiff, Werft, Typ oder Status suchen ..."
        );
        searchField.setPrefWidth(300);

        // Bei jeder Änderung beide Bedingungen gemeinsam prüfen.
        categoryFilter.valueProperty().addListener(
                (observable, oldValue, newValue) ->
                        updateFilter(
                                filteredProjects,
                                categoryFilter,
                                searchField
                        )
        );

        searchField.textProperty().addListener(
                (observable, oldValue, newValue) ->
                        updateFilter(
                                filteredProjects,
                                categoryFilter,
                                searchField
                        )
        );

        HBox filters = new HBox(
                10,
                new Label("Kategorie:"),
                categoryFilter,
                searchField
        );
        filters.setAlignment(Pos.CENTER_LEFT);

        projectTable.setRowFactory(table -> {
            TableRow<Project> row = new TableRow<>();

            row.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY
                        && event.getClickCount() == 2
                        && !row.isEmpty()) {
                    onProjectOpen.accept(row.getItem());
                }
            });

            return row;
        });

        VBox tableCard = new VBox(projectTable);
        tableCard.getStyleClass().add("content-card");

        VBox.setVgrow(projectTable, Priority.ALWAYS);
        VBox.setVgrow(tableCard, Priority.ALWAYS);

        getChildren().addAll(header, filters, tableCard);
    }

    private void updateFilter(
            FilteredList<Project> filteredProjects,
            ComboBox<String> categoryFilter,
            TextField searchField
    ) {
        String category = categoryFilter.getValue();
        String searchText = searchField.getText()
                .trim()
                .toLowerCase(Locale.ROOT);

        filteredProjects.setPredicate(project -> {
            boolean categoryMatches =
                    category == null
                            || ALL_CATEGORIES.equals(category)
                            || category.equals(project.getCategory());

            boolean searchMatches =
                    searchText.isEmpty()
                            || contains(project.getShipName(), searchText)
                            || contains(project.getShipyard(), searchText)
                            || contains(project.getCategory(), searchText)
                            || contains(project.getShipType(), searchText)
                            || contains(project.getStatus(), searchText);

            return categoryMatches && searchMatches;
        });

        projectTable.getSelectionModel().clearSelection();
    }

    private boolean contains(String value, String searchText) {
        return value != null
                && value.toLowerCase(Locale.ROOT).contains(searchText);
    }

    private void addColumn(
            String title,
            Function<Project, String> value
    ) {
        TableColumn<Project, String> column = new TableColumn<>(title);

        column.setCellValueFactory(cell ->
                new SimpleStringProperty(
                        value.apply(cell.getValue())
                )
        );

        // Zeilenumbruch: Wird die Spalte schmaler gezogen, bricht der
        // Text um und die Zeile wird automatisch höher. Nichts wird
        // abgeschnitten.
        column.setCellFactory(col -> new WrappingCell(col));

        projectTable.getColumns().add(column);
    }

    /** Tabellenzelle, deren Text an die Spaltenbreite umbricht. */
    private static final class WrappingCell extends TableCell<Project, String> {

        private final Text text = new Text();

        WrappingCell(TableColumn<Project, String> column) {
            text.wrappingWidthProperty().bind(
                    column.widthProperty().subtract(12)
            );
            text.fontProperty().bind(fontProperty());
            text.fillProperty().bind(textFillProperty());

            setGraphic(text);
            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            text.setText(empty || item == null ? null : item);
        }
    }

    private void openSelectedProject() {
        Project selected =
                projectTable.getSelectionModel().getSelectedItem();

        if (selected != null) {
            onProjectOpen.accept(selected);
        }
    }

    public void setOnProjectOpen(Consumer<Project> onProjectOpen) {
        this.onProjectOpen = onProjectOpen;
    }

    public void setOnNewProject(Runnable onNewProject) {
        this.onNewProject = onNewProject;
    }

    public void reloadProjects() {
        allProjects.setAll(repository.findAll());
        TableAutoSize.autoSize(projectTable, allProjects);
    }
}