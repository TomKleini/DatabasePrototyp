package org.example;

import javafx.application.Application;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
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

        Button overviewButton = new Button("Projekte");
        overviewButton.setOnAction(event -> {
            root.setCenter(overviewView);
            root.setRight(null);
        });

        Button kpiButton = new Button("Kalkulation");
        Button powerBiButton = new Button("Power BI");

        KalkulationView kalkulationView =
        new KalkulationView(new KpiRepository());

        kpiButton.setOnAction(event -> {
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
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}