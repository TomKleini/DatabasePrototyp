package org.example;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Labeled;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.List;

/**
 * Passt die Spaltenbreiten einer TableView an Überschrift und Inhalt an,
 * sodass beides vollständig lesbar ist.
 *
 * Gemessen wird mit der Schrift, die per CSS (app.css) tatsächlich
 * verwendet wird. Deshalb wird erst gemessen, wenn die Tabelle
 * auf dem Bildschirm hängt.
 */
public final class TableAutoSize {

    /** Platz für Zellränder und Sortierpfeil. */
    private static final double PADDING = 32;

    /** Obergrenze, damit lange Kommentare die Tabelle nicht sprengen. */
    private static final double MAX_WIDTH = 450;

    private TableAutoSize() {
    }

    /**
     * Breiten berechnen und bei jeder Änderung der Zeilen
     * automatisch neu berechnen.
     */
    public static <T> void install(TableView<T> table) {
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        table.getItems().addListener(
                (ListChangeListener<T>) change -> autoSize(table)
        );
        autoSize(table);
    }

    /** Breiten anhand der aktuell angezeigten Zeilen berechnen. */
    public static <T> void autoSize(TableView<T> table) {
        autoSize(table, table.getItems());
    }

    /**
     * Breiten anhand einer bestimmten Liste berechnen, z. B. aller
     * Projekte statt nur der gefilterten. So springen die Spalten
     * beim Filtern nicht hin und her.
     */
    public static <T> void autoSize(TableView<T> table, List<T> items) {
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        // Noch nicht sichtbar: CSS-Schrift ist unbekannt.
        // Messung nachholen, sobald die Tabelle in einer Szene hängt.
        if (table.getScene() == null) {
            runWhenShown(table, () -> autoSize(table, items));
            return;
        }

        table.applyCss();

        Font headerFont = findHeaderFont(table);
        Font cellFont = findCellFont(table, headerFont);

        for (TableColumn<T, ?> column : table.getColumns()) {
            double headerWidth = measure(column.getText(), headerFont) + PADDING;
            double contentWidth = 0;

            for (T item : items) {
                Object value = column.getCellData(item);

                if (value != null) {
                    contentWidth = Math.max(
                            contentWidth,
                            measure(value.toString(), cellFont)
                    );
                }
            }

            double width = Math.min(
                    Math.max(headerWidth, contentWidth + PADDING),
                    Math.max(headerWidth, MAX_WIDTH)
            );

            // Überschrift wird nie abgeschnitten, auch nicht beim
            // manuellen Verkleinern der Spalte.
            column.setMinWidth(headerWidth);
            column.setPrefWidth(width);
        }
    }

    private static void runWhenShown(TableView<?> table, Runnable action) {
        table.sceneProperty().addListener(new ChangeListener<Scene>() {
            @Override
            public void changed(
                    ObservableValue<? extends Scene> observable,
                    Scene oldScene,
                    Scene newScene
            ) {
                if (newScene != null) {
                    table.sceneProperty().removeListener(this);
                    Platform.runLater(action);
                }
            }
        });
    }

    /** Schrift der Spaltenüberschriften, wie sie per CSS gesetzt ist. */
    private static Font findHeaderFont(TableView<?> table) {
        for (Node node : table.lookupAll(".column-header .label")) {
            if (node instanceof Labeled label) {
                return label.getFont();
            }
        }

        Font fallback = Font.getDefault();
        return Font.font(fallback.getFamily(), FontWeight.BOLD, fallback.getSize());
    }

    /** Schrift der Zellen; falls noch keine Zellen existieren, normale
     *  Variante der Überschriften-Schrift. */
    private static Font findCellFont(TableView<?> table, Font headerFont) {
        for (Node node : table.lookupAll(".table-cell")) {
            if (node instanceof Labeled cell) {
                return cell.getFont();
            }
        }

        return Font.font(headerFont.getFamily(), FontWeight.NORMAL, headerFont.getSize());
    }

    private static double measure(String text, Font font) {
        if (text == null || text.isEmpty()) {
            return 0;
        }

        Text node = new Text(text);
        node.setFont(font);
        return node.getLayoutBounds().getWidth();
    }
}