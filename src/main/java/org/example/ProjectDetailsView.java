package org.example;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

public class ProjectDetailsView extends VBox {

    private final Label projectNameLabel = new Label();

    private final Label lengthLabel = new Label();
    private final Label widthLabel = new Label();
    private final Label volumeLabel = new Label();
    private final Label areaLabel = new Label();

    private final Label offerDateLabel = new Label();
    private final Label orderDateLabel = new Label();
    private final Label constructionStartLabel = new Label();
    private final Label constructionEndPlannedLabel = new Label();
    private final Label constructionEndActualLabel = new Label();

    private final Label cableCalculatedLabel = new Label();
    private final Label cableOfferLabel = new Label();
    private final Label cableActualLabel = new Label();

    private Runnable onClose = () -> {};

    public ProjectDetailsView() {
        getStyleClass().add("details-pane");
        setPrefWidth(340);
        setMinWidth(300);

        Label title = new Label("Projektdetails");
        title.getStyleClass().add("page-title");

        Button closeButton = new Button("×");
        closeButton.getStyleClass().add("details-close-button");
        closeButton.setOnAction(event -> onClose.run());

        HBox header = new HBox(10, title, closeButton);
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(title, Priority.ALWAYS);

        projectNameLabel.getStyleClass().add("details-project-name");

        VBox technicalCard = createCard(
                "Technische Daten",
                lengthLabel, widthLabel, volumeLabel, areaLabel
        );

        VBox scheduleCard = createCard(
                "Projektablauf",
                offerDateLabel, orderDateLabel,
                constructionStartLabel, constructionEndPlannedLabel,
                constructionEndActualLabel
        );

        VBox cableCard = createCard(
                "Kabeldaten",
                cableCalculatedLabel, cableOfferLabel, cableActualLabel
        );

        VBox body = new VBox(
                16,
                projectNameLabel,
                technicalCard,
                scheduleCard,
                cableCard
        );

        ScrollPane scrollPane = new ScrollPane(body);
        scrollPane.setFitToWidth(true);
        scrollPane.getStyleClass().add("details-scroll");

        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        getChildren().addAll(header, scrollPane);
    }

    private VBox createCard(String title, Label... fields) {
        Label cardTitle = new Label(title);
        cardTitle.getStyleClass().add("detail-card-title");

        VBox card = new VBox(8);
        card.getChildren().add(cardTitle);
        card.getChildren().addAll(fields);
        card.getStyleClass().add("detail-card");

        return card;
    }

    public void showProject(Project project) {
        projectNameLabel.setText(text(project.getShipName()));

        lengthLabel.setText(
                "Länge: " + number(project.getLengthM(), "m")
        );
        widthLabel.setText(
                "Breite: " + number(project.getWidthM(), "m")
        );
        volumeLabel.setText(
                "GT/BRZ: " + number(project.getVolumeGt(), "")
        );
        areaLabel.setText(
                "Fläche: " + number(project.getAreaSqm(), "m²")
        );

        offerDateLabel.setText(
                "Angebotsdatum: " + text(project.getOfferDate())
        );
        orderDateLabel.setText(
                "Auftragsdatum: " + text(project.getOrderDate())
        );
        constructionStartLabel.setText(
                "Baubeginn: " + text(project.getConstructionStart())
        );
        constructionEndPlannedLabel.setText(
                "Bauende Soll: "
                        + text(project.getConstructionEndPlanned())
        );
        constructionEndActualLabel.setText(
                "Bauende Ist: "
                        + text(project.getConstructionEndActual())
        );

        cableCalculatedLabel.setText(
                "Kabel berechnet: "
                        + number(project.getCableCalculatedKm(), "km")
        );
        cableOfferLabel.setText(
                "Kabel Angebot: "
                        + number(project.getCableOfferKm(), "km")
        );
        cableActualLabel.setText(
                "Kabel Ist: "
                        + number(project.getCableActualKm(), "km")
        );
    }

    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    private String text(String value) {
        return value == null || value.isBlank() ? "n. b." : value;
    }

    private String number(Double value, String unit) {
        if (value == null) {
            return "n. b.";
        }

        return unit.isBlank() ? value.toString() : value + " " + unit;
    }
}