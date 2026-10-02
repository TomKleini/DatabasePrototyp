package org.example;

import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.IntConsumer;

public class ProjectFormView extends BorderPane {

    private final ProjectRepository repository;
    private final ScrollPane scrollPane = new ScrollPane();
    private final Label stepLabel = new Label();
    private final Label errorLabel = new Label();
    private final Button backButton = new Button("Zurück");
    private final Button nextButton = new Button("Weiter");

    private final TextField shipNameField = new TextField();
    private final ComboBox<LookupItem> shipyardBox = new ComboBox<>();
    private final ComboBox<LookupItem> categoryBox = new ComboBox<>();
    private final ComboBox<LookupItem> shipTypeBox = new ComboBox<>();
    private final TextField statusField = new TextField();

    private final TextField lengthField = new TextField();
    private final TextField widthField = new TextField();
    private final TextField volumeField = new TextField();
    private final TextField areaField = new TextField();

    private final TextField cableCalculatedField = new TextField();
    private final TextField cableOfferField = new TextField();
    private final TextField cableActualField = new TextField();
    private final CheckBox ringCableBox =
            new CheckBox("Ringkabel enthalten");

    private final DatePicker offerDatePicker = new DatePicker();
    private final DatePicker orderDatePicker = new DatePicker();
    private final DatePicker constructionStartPicker = new DatePicker();
    private final DatePicker constructionEndPlannedPicker =
            new DatePicker();
    private final DatePicker constructionEndActualPicker =
            new DatePicker();

    private final VBox[] pages = new VBox[4];

    private int currentStep = 0;
    private Runnable onCancel = () -> {};
    private IntConsumer onSaved = id -> {};

    public ProjectFormView(ProjectRepository repository) {
        this.repository = repository;
        getStyleClass().add("content");

        shipNameField.setPromptText("Schiffsname");
        statusField.setPromptText("z. B. Angebot oder Auftrag");

        shipyardBox.getItems().setAll(repository.findShipyards());
        categoryBox.getItems().setAll(repository.findCategories());

        shipyardBox.setPromptText("Werft auswählen (optional)");
        categoryBox.setPromptText("Kategorie auswählen");
        shipTypeBox.setPromptText("Schiffstyp auswählen");

        categoryBox.valueProperty().addListener(
                (observable, oldValue, category) -> {
                    shipTypeBox.getItems().clear();
                    shipTypeBox.setValue(null);

                    if (category != null) {
                        shipTypeBox.getItems().setAll(
                                repository.findShipTypes(category.id())
                        );
                    }
                }
        );

        pages[0] = page(
                "1. Zuordnung",
                field("Schiffsname *", shipNameField),
                field("Werft", shipyardBox),
                field("Kategorie *", categoryBox),
                field("Schiffstyp *", shipTypeBox),
                field("Projektstatus *", statusField)
        );

        pages[1] = page(
                "2. Technische Daten",
                field("Länge (m)", lengthField),
                field("Breite (m)", widthField),
                field("GT/BRZ", volumeField),
                field("Fläche (m²)", areaField)
        );

        pages[2] = page(
                "3. Kabeldaten",
                field("Kabel berechnet (km)", cableCalculatedField),
                field("Kabel Angebot (km)", cableOfferField),
                field("Kabel Ist (km)", cableActualField),
                ringCableBox
        );

        pages[3] = page(
                "4. Termine",
                field("Angebotsdatum", offerDatePicker),
                field("Auftragsdatum", orderDatePicker),
                field("Baubeginn", constructionStartPicker),
                field("Bauende Soll", constructionEndPlannedPicker),
                field("Bauende Ist", constructionEndActualPicker)
        );

        Label title = new Label("Neues Projekt");
        title.getStyleClass().add("page-title");

        VBox top = new VBox(8, title, stepLabel);
        setTop(top);

        scrollPane.setFitToWidth(true);
        setCenter(scrollPane);

        errorLabel.setStyle("-fx-text-fill: #b42318;");

        Button cancelButton = new Button("Abbrechen");
        cancelButton.setOnAction(event -> onCancel.run());

        backButton.setOnAction(event -> {
            currentStep--;
            showStep();
        });

        nextButton.getStyleClass().add("primary-button");
        nextButton.setOnAction(event -> nextOrSave());

        HBox actions = new HBox(
                10,
                cancelButton,
                backButton,
                nextButton
        );
        actions.setAlignment(Pos.CENTER_RIGHT);

        VBox footer = new VBox(8, errorLabel, actions);
        footer.setStyle("-fx-padding: 16 0 0 0;");
        setBottom(footer);

        showStep();
    }

    private VBox page(String heading, javafx.scene.Node... fields) {
        Label headingLabel = new Label(heading);
        headingLabel.getStyleClass().add("page-title");

        VBox content = new VBox(14);
        content.getChildren().add(headingLabel);
        content.getChildren().addAll(fields);
        content.getStyleClass().add("content-card");

        return content;
    }

    private VBox field(String label, javafx.scene.Node input) {
        Label caption = new Label(label);

        VBox wrapper = new VBox(5, caption, input);
        wrapper.setMaxWidth(420);

        if (input instanceof javafx.scene.layout.Region region) {
            region.setMaxWidth(Double.MAX_VALUE);
            VBox.setVgrow(region, Priority.NEVER);
        }

        return wrapper;
    }

    private void showStep() {
        errorLabel.setText("");
        stepLabel.setText("Schritt " + (currentStep + 1) + " von 4");

        scrollPane.setContent(pages[currentStep]);
        scrollPane.setVvalue(0);

        backButton.setDisable(currentStep == 0);
        nextButton.setText(
                currentStep == 3
                        ? "Projekt speichern"
                        : "Weiter"
        );
    }

    private void nextOrSave() {
        try {
            validateCurrentStep();
            errorLabel.setText("");

            if (currentStep < 3) {
                currentStep++;
                showStep();
                return;
            }

            NewProjectData project = new NewProjectData(
                    shipyardBox.getValue() == null
                            ? null
                            : shipyardBox.getValue().id(),
                    shipTypeBox.getValue().id(),
                    shipNameField.getText().trim(),
                    statusField.getText().trim(),

                    optionalNumber(lengthField, "Länge"),
                    optionalNumber(widthField, "Breite"),
                    optionalNumber(volumeField, "GT/BRZ"),
                    optionalNumber(areaField, "Fläche"),

                    optionalNumber(
                            cableCalculatedField,
                            "Kabel berechnet"
                    ),
                    optionalNumber(cableOfferField, "Kabel Angebot"),
                    optionalNumber(cableActualField, "Kabel Ist"),
                    ringCableBox.isSelected(),

                    offerDatePicker.getValue(),
                    orderDatePicker.getValue(),
                    constructionStartPicker.getValue(),
                    constructionEndPlannedPicker.getValue(),
                    constructionEndActualPicker.getValue()
            );

            int newId = repository.insert(project);
            onSaved.accept(newId);

        } catch (IllegalArgumentException exception) {
            errorLabel.setText(exception.getMessage());

        } catch (RuntimeException exception) {
            errorLabel.setText(
                    "Speichern fehlgeschlagen: "
                            + exception.getMessage()
            );
        }
    }

    private void validateCurrentStep() {
        if (currentStep == 0) {
            if (shipNameField.getText().isBlank()) {
                throw new IllegalArgumentException(
                        "Bitte einen Schiffsnamen eingeben."
                );
            }

            if (categoryBox.getValue() == null) {
                throw new IllegalArgumentException(
                        "Bitte eine Kategorie auswählen."
                );
            }

            if (shipTypeBox.getValue() == null) {
                throw new IllegalArgumentException(
                        "Bitte einen Schiffstyp auswählen."
                );
            }

            if (statusField.getText().isBlank()) {
                throw new IllegalArgumentException(
                        "Bitte einen Projektstatus eingeben."
                );
            }
        }

        if (currentStep == 1) {
            optionalNumber(lengthField, "Länge");
            optionalNumber(widthField, "Breite");
            optionalNumber(volumeField, "GT/BRZ");
            optionalNumber(areaField, "Fläche");
        }

        if (currentStep == 2) {
            optionalNumber(cableCalculatedField, "Kabel berechnet");
            optionalNumber(cableOfferField, "Kabel Angebot");
            optionalNumber(cableActualField, "Kabel Ist");
        }
    }

    private Double optionalNumber(TextField field, String label) {
        String input = field.getText().trim();

        if (input.isEmpty()) {
            return null;
        }

        try {
            double value = Double.parseDouble(
                    input.replace(",", ".")
            );

            if (!Double.isFinite(value) || value < 0) {
                throw new NumberFormatException();
            }

            return value;

        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    label + ": Bitte eine Zahl ab 0 eingeben."
            );
        }
    }

    public void setOnCancel(Runnable onCancel) {
        this.onCancel = onCancel;
    }

    public void setOnSaved(IntConsumer onSaved) {
        this.onSaved = onSaved;
    }
}