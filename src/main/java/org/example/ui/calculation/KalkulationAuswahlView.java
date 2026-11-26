package org.example.ui.calculation;

import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;
import org.example.repository.KpiRepository;

import java.util.Objects;

/** Einstieg in die Kalkulation: Beleuchtung und Kabel. */
public class KalkulationAuswahlView extends BorderPane {

    private final KpiRepository repository;
    private final ScrollPane auswahl;
    private final HBox bereichNavigation;
    private final Label breadcrumb = new Label();
    private final Label fehler = new Label();
    private KalkulationView beleuchtungView;
    private KabelKalkulationView kabelView;

    public KalkulationAuswahlView(KpiRepository repository) {
        this.repository = Objects.requireNonNull(repository);
        getStyleClass().add("calculation-area");
        setMinWidth(0);
        setMinHeight(0);

        Label title = new Label("Kalkulation");
        title.getStyleClass().add("page-title");
        title.getStyleClass().add("calculation-choice-heading");

        Button lighting = choice("Beleuchtung", "lighting", true);
        lighting.setId("calculation-lighting-open");
        lighting.setOnAction(event -> showLighting());

        Button cable = choice("Kabel", "cable", true);
        cable.setId("calculation-cable-open");
        cable.setOnAction(event -> showCable());

        Button effort = choice("Aufwandsschätzung", "effort", false);
        effort.setId("calculation-effort-card");

        FlowPane cards = new FlowPane(24, 24, lighting, cable, effort);
        cards.setId("calculation-area-cards");
        cards.setAlignment(Pos.CENTER);
        cards.setMinHeight(Region.USE_PREF_SIZE);

        fehler.setWrapText(true);
        fehler.getStyleClass().add("calculation-menu-error");
        fehler.setVisible(false);
        fehler.setManaged(false);
        BorderPane content = new BorderPane(cards, title, null, fehler, null);
        content.getStyleClass().add("calculation-choice-content");
        BorderPane.setAlignment(title, Pos.CENTER);
        BorderPane.setMargin(title, new Insets(0, 0, 24, 0));
        BorderPane.setMargin(fehler, new Insets(16, 0, 0, 0));
        content.setMinWidth(0);
        content.setMinHeight(Region.USE_PREF_SIZE);

        auswahl = new ScrollPane(content);
        auswahl.setId("calculation-menu");
        auswahl.setFitToWidth(true);
        auswahl.setFitToHeight(true);
        auswahl.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        auswahl.getStyleClass().add("calculation-menu-scroll");

        Button back = new Button("‹ Zur Kalkulationsauswahl");
        back.setId("calculation-back");
        back.getStyleClass().add("calculation-back-button");
        back.setOnAction(event -> showStart());
        breadcrumb.getStyleClass().add("calculation-breadcrumb");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        bereichNavigation = new HBox(12, back, spacer, breadcrumb);
        bereichNavigation.setAlignment(Pos.CENTER_LEFT);
        bereichNavigation.setPadding(new Insets(14, 28, 0, 28));
        showStart();
    }

    /** Aufruf beim Klick auf „Kalkulation“ in der Hauptnavigation. */
    public void showStart() {
        versteckeFehler();
        setTop(null);
        zeige(auswahl);
    }

    private void showLighting() {
        try {
            if (beleuchtungView == null) {
                // Erst beim Öffnen erzeugen: Auswahlseite braucht keinen Datenbankzugriff.
                beleuchtungView = new KalkulationView(repository);
            } else {
                // Dasselbe Objekt behalten: Eingaben und Reiter bleiben erhalten.
                beleuchtungView.reload();
            }
            oeffneBereich("Kalkulation / Beleuchtung", beleuchtungView);
        } catch (RuntimeException exception) {
            zeigeFehler("Die Beleuchtungsdaten konnten nicht geladen werden. "
                    + "Bitte Datenbankverbindung prüfen und erneut öffnen.", exception);
        }
    }

    private void showCable() {
        try {
            if (kabelView == null) {
                kabelView = new KabelKalkulationView();
            } else {
                kabelView.reload();
            }
            oeffneBereich("Kalkulation / Kabel", kabelView);
        } catch (RuntimeException exception) {
            zeigeFehler("Die Kabeldaten konnten nicht geladen werden. "
                    + "Bitte Datenbankverbindung prüfen und erneut öffnen.", exception);
        }
    }

    private void oeffneBereich(String pfad, Node view) {
        versteckeFehler();
        breadcrumb.setText(pfad);
        setTop(bereichNavigation);
        zeige(view);
    }

    /** Kurzes Einblenden beim Wechsel (200 ms, keine Dauerlast). */
    private void zeige(Node view) {
        if (getCenter() == view) {
            return;
        }
        view.setOpacity(0);
        setCenter(view);
        FadeTransition fade = new FadeTransition(Duration.millis(200), view);
        fade.setToValue(1);
        fade.play();
    }

    private void zeigeFehler(String text, RuntimeException exception) {
        exception.printStackTrace();
        showStart();
        fehler.setText(text);
        fehler.setVisible(true);
        fehler.setManaged(true);
    }

    private void versteckeFehler() {
        fehler.setText("");
        fehler.setVisible(false);
        fehler.setManaged(false);
    }

    private Button choice(String title, String icon, boolean available) {
        Button button = new Button(title);
        button.setGraphic(symbol(icon));
        button.setContentDisplay(ContentDisplay.TOP);
        button.setGraphicTextGap(10);
        button.setAlignment(Pos.CENTER);
        button.getStyleClass().add("calculation-choice");
        button.setMinSize(210, 118);
        button.setPrefSize(210, 118);
        button.setMaxSize(210, 118);
        button.setDisable(!available);
        button.setAccessibleText(title);
        if (!available) button.setAccessibleHelp("Dieser Bereich ist in Vorbereitung.");
        return button;
    }

    /** Vektorsymbole ohne zusätzliche Dateien, Schriftarten oder Bibliotheken. */
    private StackPane symbol(String kind) {
        String path = switch (kind) {
            case "lighting" -> "M22 43 C22 36 12 34 12 23 C12 12 21 5 31 5 "
                    + "C41 5 50 12 50 23 C50 34 40 36 40 43 Z "
                    + "M22 49 H40 M26 55 H36 M25 43 V28 L31 33 L37 28 V43";
            case "cable" -> "M6 8 H18 V20 H6 Z M8 3 V8 M16 3 V8 "
                    + "M12 20 V27 C12 40 43 25 43 40 V45 "
                    + "M37 45 H49 V57 H37 Z M40 57 V62 M46 57 V62";
            case "effort" -> "M20 10 H12 V58 H52 V10 H44 M24 5 H40 V15 H24 Z "
                    + "M20 27 L23 30 L28 24 M34 27 H44 "
                    + "M20 39 L23 42 L28 36 M34 39 H44 "
                    + "M20 51 L23 54 L28 48 M34 51 H44";
            default -> throw new IllegalArgumentException("Unbekanntes Symbol: " + kind);
        };
        SVGPath shape = new SVGPath();
        shape.setContent(path);
        shape.setScaleX(0.72);
        shape.setScaleY(0.72);
        shape.getStyleClass().add("calculation-choice-icon");
        StackPane box = new StackPane(shape);
        box.setMinSize(44, 44);
        box.setPrefSize(44, 44);
        box.setMaxSize(44, 44);
        box.setMouseTransparent(true);
        return box;
    }
}
