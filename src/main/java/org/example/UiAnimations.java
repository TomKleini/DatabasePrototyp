package org.example;

import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.ParallelTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.scene.Node;
import javafx.scene.chart.XYChart;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/**
 * Kleine, performante UI-Animationen.
 * Laufen nur beim Klick, danach keine Hintergrundlast.
 */
public final class UiAnimations {

    private static final Duration FAST = Duration.millis(200);
    private static final Duration CHART = Duration.millis(600);

    private UiAnimations() {
    }

    /**
     * Macht die Sidebar ein-/ausklappbar.
     * Gibt eine Aktion zurück, die beim Klick ausgeführt wird.
     */
    public static Runnable makeCollapsible(Region sidebar, double expandedWidth) {
        DoubleProperty width = new SimpleDoubleProperty(expandedWidth);

        sidebar.minWidthProperty().bind(width);
        sidebar.prefWidthProperty().bind(width);
        sidebar.maxWidthProperty().bind(width);

        // Inhalt beim Zuklappen sauber abschneiden
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(width);
        clip.heightProperty().bind(sidebar.heightProperty());
        sidebar.setClip(clip);

        boolean[] collapsed = {false};
        Timeline[] running = {null};

        return () -> {
            if (running[0] != null) {
                running[0].stop();
            }

            collapsed[0] = !collapsed[0];
            double target = collapsed[0] ? 0 : expandedWidth;

            if (!collapsed[0]) {
                sidebar.setVisible(true);
            }

            Timeline timeline = new Timeline(
                    new KeyFrame(
                            FAST,
                            new KeyValue(width, target, Interpolator.EASE_BOTH)
                    )
            );

            if (collapsed[0]) {
                timeline.setOnFinished(e -> sidebar.setVisible(false));
            }

            running[0] = timeline;
            timeline.play();
        };
    }

    /**
     * Wechselt die Seite in der Mitte mit kurzem Einblenden.
     */
    public static void showPage(BorderPane root, Node page) {
        if (root.getCenter() == page) {
            return;
        }

        page.setOpacity(0);
        page.setTranslateY(8);
        root.setCenter(page);

        FadeTransition fade = new FadeTransition(FAST, page);
        fade.setToValue(1);

        TranslateTransition slide = new TranslateTransition(FAST, page);
        slide.setToY(0);

        new ParallelTransition(fade, slide).play();
    }

    /**
     * Lässt Balken/Linien von 0 auf ihren Wert wachsen.
     * Kommt in Schritt 2 (Diagramme) zum Einsatz.
     */
    public static <X> void growChart(XYChart<X, Number> chart) {
        chart.setAnimated(false);
        Timeline timeline = new Timeline();

        for (XYChart.Series<X, Number> series : chart.getData()) {
            for (XYChart.Data<X, Number> data : series.getData()) {
                double target = data.getYValue() == null
                        ? 0
                        : data.getYValue().doubleValue();

                data.setYValue(0.0);

                timeline.getKeyFrames().add(
                        new KeyFrame(
                                CHART,
                                new KeyValue(
                                        data.YValueProperty(),
                                        target,
                                        Interpolator.EASE_OUT
                                )
                        )
                );
            }
        }

        timeline.play();
    }
}