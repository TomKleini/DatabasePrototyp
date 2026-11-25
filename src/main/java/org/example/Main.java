package org.example;

import org.example.importer.ExcelImporter;
import org.example.model.Project;
import org.example.database.Database;
import org.example.repository.ProjectRepository;
import org.example.repository.KpiRepository;
import org.example.ui.project.ProjectOverviewView;
import org.example.ui.project.ProjectDetailsView;
import org.example.ui.project.ProjectFormView;
import org.example.ui.project.ProjectEditView;
import org.example.ui.calculation.KalkulationAuswahlView;

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

import java.util.Objects;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        Database.createTables();
        Database.insertMasterData();

        /* Excel Importer zum testing. Uncomment to import data from an Excel file.
        ExcelImporter.importSheet(
            "C:\\Users\\tom.kleinikauf\\OneDrive - VINCI Energies\\Desktop\\Datenbank 2025-09-25 - Kopie.xlsx",
            "Forschung & Spezial"
        ); 
        */

        Label appTitle = new Label("Schiffdatensammlung");
        appTitle.getStyleClass().add("app-title");

        Button menuToggle = new Button("☰");
        menuToggle.getStyleClass().add("menu-toggle");
        HBox topBar = new HBox(12, menuToggle, appTitle);
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
                    UiAnimations.showPage(root, overviewView)
            );

            formView.setOnSaved(newProjectId -> {
                overviewView.reloadProjects();
                UiAnimations.showPage(root, overviewView);
            });

            root.setRight(null);
            UiAnimations.showPage(root, formView);
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
                    UiAnimations.showPage(root, overviewView);
                    detailsView.showProject(project);
                    root.setRight(detailsView);
                });
                editView.setOnSaved(id -> {
                    UiAnimations.showPage(root, overviewView);
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
                UiAnimations.showPage(root, editView);
            } catch (RuntimeException e) {
                showError(stage, "Bearbeiten nicht möglich", "Das Projekt konnte nicht geladen werden.", e);
            }
        };
        overviewView.setOnProjectEdit(editProject);
        detailsView.setOnProjectEdit(editProject);

        Button overviewButton = new Button("Projekte");
        overviewButton.setOnAction(event -> {
            if (!allowNavigation(root)) return;
            UiAnimations.showPage(root, overviewView);
            root.setRight(null);
        });

        Button kpiButton = new Button("Kalkulation");
        Button powerBiButton = new Button("Power BI");

        KalkulationAuswahlView kalkulationAuswahlView =
                new KalkulationAuswahlView(new KpiRepository());

        kpiButton.setOnAction(event -> {
            if (!allowNavigation(root)) return;
            kalkulationAuswahlView.showStart();
            UiAnimations.showPage(root, kalkulationAuswahlView);
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

        Runnable toggleSidebar = UiAnimations.makeCollapsible(navigation, 175);
        menuToggle.setOnAction(e -> toggleSidebar.run());

        root.setTop(topBar);
        root.setLeft(navigation);
        root.setCenter(overviewView);   // Start ohne Animation, bleibt so

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