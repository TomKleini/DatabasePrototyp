package org.example;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Alert;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import org.example.database.Database;
import org.example.model.Project;
import org.example.repository.KpiRepository;
import org.example.repository.ProjectRepository;
import org.example.ui.calculation.KalkulationView;
import org.example.ui.project.ProjectDetailsView;
import org.example.ui.project.ProjectEditView;
import org.example.ui.project.ProjectFormView;
import org.example.ui.project.ProjectOverviewView;

import java.util.Objects;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        Database.createTables();
        Database.insertMasterData();

        Label appTitle = new Label("Schiffdatensammlung");
        appTitle.getStyleClass().add("app-title");

        HBox topBar = new HBox(appTitle);
        topBar.getStyleClass().add("top-bar");
        topBar.setAlignment(Pos.CENTER_LEFT);

        BorderPane root = new BorderPane();

        ProjectRepository repository = new ProjectRepository();
        ProjectOverviewView overviewView =
                new ProjectOverviewView(repository);
        ProjectDetailsView detailsView =
                new ProjectDetailsView();

        overviewView.setOnNewProject(() -> {
            ProjectFormView formView =
                    new ProjectFormView(repository);

            formView.setOnCancel(() ->
                    root.setCenter(overviewView)
            );

            formView.setOnSaved(newProjectId -> {
                overviewView.reloadProjects();
                root.setCenter(overviewView);
            });

            root.setRight(null);
            root.setCenter(formView);
        });

        overviewView.setOnProjectOpen(project -> {
            detailsView.showProject(project);
            root.setRight(detailsView);
        });

        detailsView.setOnClose(() -> root.setRight(null));

        java.util.function.Consumer<Project> editProject = project -> {
            try {
                ProjectEditView editView = new ProjectEditView(repository, project.getId());
                editView.setOnCancel(() -> {
                    root.setCenter(overviewView);
                    detailsView.showProject(project);
                    root.setRight(detailsView);
                });
                editView.setOnSaved(id -> {
                    root.setCenter(overviewView);
                    root.setRight(null);
                    try {
                        overviewView.reloadProjects();
                        Project saved = overviewView.showSavedProject(id);
                        detailsView.showProject(saved);
                        root.setRight(detailsView);
                    } catch (RuntimeException e) {
                        showError(stage, "Projekt gespeichert", "Die Änderungen sind gespeichert. "
                                + "Die Übersicht konnte jedoch nicht neu geladen werden.", e);
                    }
                });
                root.setRight(null);
                root.setCenter(editView);
            } catch (RuntimeException e) {
                showError(stage, "Bearbeiten nicht möglich", "Das Projekt konnte nicht geladen werden.", e);
            }
        };
        overviewView.setOnProjectEdit(editProject);
        detailsView.setOnProjectEdit(editProject);

        Button overviewButton = new Button("Projekte");
        overviewButton.setOnAction(event -> {
            if (!allowNavigation(root)) return;
            root.setCenter(overviewView);
            root.setRight(null);
        });

        Button kpiButton = new Button("Kalkulation");
        Button powerBiButton = new Button("Power BI");

        KalkulationView kalkulationView =
                new KalkulationView(new KpiRepository());

        kpiButton.setOnAction(event -> {
            if (!allowNavigation(root)) return;
            kalkulationView.reload();
            root.setCenter(kalkulationView);
            root.setRight(null);
        });

        // Power BI kommt später.
        powerBiButton.setDisable(true);

        VBox navigation = new VBox(
                10,
                overviewButton,
                kpiButton,
                powerBiButton
        );
        navigation.getStyleClass().add("navigation");

        root.setTop(topBar);
        root.setLeft(navigation);
        root.setCenter(overviewView);

        Scene scene = new Scene(root, 1100, 650);

        scene.getStylesheets().add(
                Objects.requireNonNull(
                        Main.class.getResource("/org/example/app.css")
                ).toExternalForm()
        );

        stage.setTitle("Schiffdatensammlung");
        stage.setScene(scene);
        stage.setMinWidth(950);
        stage.setMinHeight(500);
        stage.setOnCloseRequest(event -> {
            if (!allowNavigation(root)) event.consume();
        });
        stage.show();
    }

    private boolean allowNavigation(BorderPane root) {
        return !(root.getCenter() instanceof ProjectEditView editView)
                || editView.confirmDiscardChanges();
    }

    private void showError(Stage owner, String title, String message, RuntimeException error) {
        error.printStackTrace();
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(message);
        alert.setContentText(error.getMessage() == null ? "Bitte die Konsolenausgabe prüfen." : error.getMessage());
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
