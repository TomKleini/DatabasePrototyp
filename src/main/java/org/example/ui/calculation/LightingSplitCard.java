package org.example.ui.calculation;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.example.calculation.LightingSplitCalculator;
import org.example.calculation.LightingSplitCalculator.Result;
import org.example.repository.LightingSplitRepository;
import org.example.repository.LightingSplitRepository.Reference;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;

/**
 * Zeigt die Aufteilung der geschätzten Brennstellen in
 * Innenbeleuchtung, Außenbeleuchtung und Schalter/Steckdosen.
 * Die Rechnung selbst steckt in LightingSplitCalculator.
 *
 * Layout passt sich immer der verfügbaren Breite an
 * (drei gleich breite Spalten, Balken ohne Mindestbreite).
 */
public class LightingSplitCard extends VBox {

    private static final int MIN_RELIABLE_PROJECTS = 6;

    private final LightingSplitRepository repository;

    private final Label indoorValue = new Label("-");
    private final Label indoorShare = new Label();
    private final Label outdoorValue = new Label("-");
    private final Label outdoorShare = new Label();
    private final Label switchValue = new Label("-");
    private final Label switchShare = new Label();
    private final Label basis = new Label();

    private final DoubleProperty indoorRatio = new SimpleDoubleProperty(0);
    private final DoubleProperty outdoorRatio = new SimpleDoubleProperty(0);
    private final DoubleProperty switchRatio = new SimpleDoubleProperty(0);
    private Timeline running;

    private final NumberFormat integerFormat =
            NumberFormat.getIntegerInstance(Locale.GERMANY);

    public LightingSplitCard(LightingSplitRepository repository) {
        this.repository = repository;

        getStyleClass().add("split-card");
        setSpacing(10);
        setMinWidth(0);

        Label title = new Label("Aufteilung der Brennstellen");
        title.getStyleClass().add("detail-card-title");
        title.setMinWidth(0);

        Label preliminary = new Label("vorläufig");
        preliminary.getStyleClass().add("split-badge");
        preliminary.setMinWidth(Region.USE_PREF_SIZE);

        HBox titleRow = new HBox(8, title, preliminary);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        titleRow.setMinWidth(0);

        // Drei gleich breite Kacheln, die nie breiter als die Karte werden
        GridPane tiles = new GridPane();
        tiles.setHgap(10);
        tiles.setMinWidth(0);
        for (int i = 0; i < 3; i++) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(100.0 / 3);
            col.setMinWidth(0);
            col.setHgrow(Priority.ALWAYS);
            tiles.getColumnConstraints().add(col);
        }
        tiles.add(tile("Innen", indoorValue, indoorShare, "indoor"), 0, 0);
        tiles.add(tile("Außen", outdoorValue, outdoorShare, "outdoor"), 1, 0);
        tiles.add(tile("Schalter/Steckdosen", switchValue, switchShare, "switch"), 2, 0);

        SplitBar bar = new SplitBar();

        basis.getStyleClass().add("hint");
        basis.setWrapText(true);
        basis.setMinWidth(0);

        getChildren().addAll(titleRow, tiles, bar, basis);

        showEmpty("Noch keine Schätzung berechnet.");
    }

    /**
     * Nach jeder Neuberechnung der Brennstellen-Schätzung aufrufen.
     */
    public void update(
            Double estimatedTotal,
            Object category,
            Object shipType,
            boolean onlyRelevant
    ) {
        if (estimatedTotal == null || estimatedTotal.isNaN() || estimatedTotal <= 0) {
            showEmpty("Noch keine Schätzung berechnet.");
            return;
        }

        List<Reference> refs = repository.findReferences(
                filterValue(category),
                filterValue(shipType),
                onlyRelevant
        );

        Result result = LightingSplitCalculator.calculate(estimatedTotal, refs);
        if (result == null) {
            showEmpty("Keine Referenzprojekte mit vollständiger Aufteilung "
                    + "(Innen, Außen, Schalter/Steckdosen) für diese Filter.");
            return;
        }

        indoorValue.setText(integerFormat.format(result.indoor()));
        outdoorValue.setText(integerFormat.format(result.outdoor()));
        switchValue.setText(integerFormat.format(result.switchSocket()));
        indoorShare.setText(percent(result.indoorShare()));
        outdoorShare.setText(percent(result.outdoorShare()));
        switchShare.setText(percent(result.switchSocketShare()));

        String text = "Methode: " + LightingSplitCalculator.METHOD_NAME
                + ". Basis: " + result.projectCount()
                + (result.projectCount() == 1 ? " Projekt" : " Projekte")
                + " mit vollständiger Aufteilung (Soll-Werte).";
        if (result.projectCount() < MIN_RELIABLE_PROJECTS) {
            text += " Achtung: weniger als " + MIN_RELIABLE_PROJECTS
                    + " Projekte, nur grobe Richtung.";
        }
        basis.setText(text);

        animate(result.indoorShare(), result.outdoorShare(), result.switchSocketShare());
    }

    // ------------------------------------------------------------

    private VBox tile(String caption, Label value, Label shareLabel, String variant) {
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("split-tile-caption");
        captionLabel.setMinWidth(0);

        value.getStyleClass().add("split-tile-value");
        value.setMinWidth(0);

        shareLabel.getStyleClass().add("split-tile-share");
        shareLabel.setMinWidth(0);

        VBox tile = new VBox(2, captionLabel, value, shareLabel);
        tile.getStyleClass().addAll("split-tile", variant);
        tile.setMinWidth(0);
        tile.setMaxWidth(Double.MAX_VALUE);
        return tile;
    }

    private void showEmpty(String message) {
        indoorValue.setText("-");
        outdoorValue.setText("-");
        switchValue.setText("-");
        indoorShare.setText(" ");
        outdoorShare.setText(" ");
        switchShare.setText(" ");
        basis.setText(message);
        animate(0, 0, 0);
    }

    private void animate(double indoor, double outdoor, double switchSocket) {
        if (running != null) {
            running.stop();
        }
        running = new Timeline(new KeyFrame(
                Duration.millis(500),
                new KeyValue(indoorRatio, indoor, Interpolator.EASE_OUT),
                new KeyValue(outdoorRatio, outdoor, Interpolator.EASE_OUT),
                new KeyValue(switchRatio, switchSocket, Interpolator.EASE_OUT)
        ));
        running.play();
    }

    private static String filterValue(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        if (text.isEmpty() || text.toLowerCase(Locale.GERMAN).startsWith("alle")) {
            return null;
        }
        return text;
    }

    private static String percent(double ratio) {
        return String.format(Locale.GERMANY, "%.1f %%", ratio * 100);
    }

    /**
     * Gestapelter Balken. Positioniert die Segmente selbst und meldet
     * keine Mindestbreite, damit er das Layout nie aufdrückt.
     */
    private final class SplitBar extends Region {

        private final Region indoor = segment("indoor");
        private final Region outdoor = segment("outdoor");
        private final Region switchSocket = segment("switch");

        SplitBar() {
            getStyleClass().add("split-bar");
            getChildren().addAll(indoor, outdoor, switchSocket);

            Rectangle clip = new Rectangle();
            clip.setArcWidth(10);
            clip.setArcHeight(10);
            clip.widthProperty().bind(widthProperty());
            clip.heightProperty().bind(heightProperty());
            setClip(clip);

            indoorRatio.addListener((o, a, n) -> requestLayout());
            outdoorRatio.addListener((o, a, n) -> requestLayout());
            switchRatio.addListener((o, a, n) -> requestLayout());
        }

        private Region segment(String variant) {
            Region r = new Region();
            r.getStyleClass().addAll("split-bar-segment", variant);
            r.setManaged(false);
            return r;
        }

        @Override
        protected double computeMinWidth(double height) {
            return 0;
        }

        @Override
        protected double computePrefWidth(double height) {
            return 0;
        }

        @Override
        protected double computeMinHeight(double width) {
            return 10;
        }

        @Override
        protected double computePrefHeight(double width) {
            return 10;
        }

        @Override
        protected double computeMaxHeight(double width) {
            return 10;
        }

        @Override
        protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            double x = 0;

            double wi = w * indoorRatio.get();
            double wo = w * outdoorRatio.get();
            double ws = w * switchRatio.get();

            indoor.resizeRelocate(x, 0, wi, h);
            x += wi;
            outdoor.resizeRelocate(x, 0, wo, h);
            x += wo;
            switchSocket.resizeRelocate(x, 0, ws, h);
        }
    }
}
