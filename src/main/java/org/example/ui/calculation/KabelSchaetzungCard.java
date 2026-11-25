package org.example.ui.calculation;

import org.example.calculation.RegressionRechenweg;
import org.example.repository.KabelRepository;
import org.example.repository.KabelRepository.KabelProjekt;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Kabellängen-Schätzung für ein neues Schiff.
 *
 * Gleiche Logik wie die Brennstellen-Schätzung:
 * pro Bezugsgröße die beste Regression (RegressionRechenweg),
 * Gewicht = (1 / Fehler²) / Summe aller (1 / Fehler²).
 *
 * Es wird ZWEIMAL gerechnet:
 *   Angebot (Soll) aus cable_offer_km
 *   Real    (Ist)  aus cable_actual_km
 */
public class KabelSchaetzungCard extends VBox {

    private static final DecimalFormat ZAHL = format("#,##0.#");
    private static final DecimalFormat PROZENT = format("0.0");
    private static final int TOP_AEHNLICH = 5;
    private static final int WENIGE_SCHIFFE = 6;

    private record Bezug(String name, String einheit, String schluessel,
                         boolean standard, String warnung) {
    }

    private static final List<Bezug> BEZUEGE = List.of(
            new Bezug("Volumen", "GT", "volume_gt", true, null),
            new Bezug("Fläche", "m²", "area_sqm", true, null),
            new Bezug("Länge", "m", "length_m", true, null),
            new Bezug("Breite", "m", "width_m", false, null),
            new Bezug("Kabel berechnet", "km", "cable_calculated_km", false,
                    "Ist selbst schon eine Kabelschätzung. Nur verwenden, "
                            + "wenn dieser Wert für das neue Schiff vorliegt.")
    );

    private enum Ziel {
        ANGEBOT("Kabellänge Angebot", "Soll", "cable_offer_km"),
        REAL("Kabellänge Real", "Ist", "cable_actual_km");

        final String titel;
        final String kurz;
        final String schluessel;

        Ziel(String titel, String kurz, String schluessel) {
            this.titel = titel;
            this.kurz = kurz;
            this.schluessel = schluessel;
        }
    }

    private record Gewicht(String bezug, int n, double anteil, double einzel) {
    }

    private record Ergebnis(Double wert, double min, double max,
                            List<Gewicht> gewichte, int minN) {
    }

    private record Aehnlich(int rang, String schiff, double aehnlichkeit,
                            String verglichen, Double angebot, Double real,
                            Double hochAngebot, Double hochReal) {
    }

    private final KabelRepository repository = new KabelRepository();
    private List<KabelProjekt> projekte = List.of();

    private final Map<Bezug, CheckBox> auswahl = new LinkedHashMap<>();
    private final Map<Bezug, TextField> eingaben = new LinkedHashMap<>();

    private final Map<Ziel, ErgebnisKachel> kacheln = new LinkedHashMap<>();
    private final Map<Ziel, VBox> gewichtBoxen = new LinkedHashMap<>();
    private final Map<String, Double> letzteGewichte = new HashMap<>();

    private final Label faktorWert = new Label("-");
    private final Label faktorText = new Label();
    private final Label datenHinweis = new Label();

    private final TableView<Aehnlich> aehnlichTabelle = new TableView<>();
    private final Label aehnlichHinweis = new Label();

    /** Rechnet erst 250 ms nach der letzten Eingabe (flüssiges Tippen). */
    private final PauseTransition verzoegerung = new PauseTransition(Duration.millis(250));

    public KabelSchaetzungCard() {
        setSpacing(16);
        setMinWidth(0);
        getStyleClass().add("estimate-main");

        verzoegerung.setOnFinished(e -> neuRechnen());

        // ---------- Eingabe ----------
        GridPane eingabeRaster = new GridPane();
        eingabeRaster.setHgap(12);
        eingabeRaster.setVgap(12);

        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(58);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(42);
        c2.setHgrow(Priority.ALWAYS);
        eingabeRaster.getColumnConstraints().setAll(c1, c2);
        eingabeRaster.addRow(0, feldLabel("Verwenden"), feldLabel("Neues Schiff"));

        int zeile = 1;
        for (Bezug b : BEZUEGE) {
            CheckBox box = new CheckBox(b.name() + " (" + b.einheit() + ")");
            box.setSelected(b.standard());
            box.setWrapText(true);
            box.setOnAction(e -> neuRechnen());
            if (b.warnung() != null) {
                Tooltip.install(box, new Tooltip(b.warnung()));
                box.setText(box.getText() + "  ⚠");
            }

            TextField feld = new TextField();
            feld.setPromptText(b.name() + " in " + b.einheit());
            feld.setMinWidth(75);
            feld.setMaxWidth(Double.MAX_VALUE);
            feld.textProperty().addListener((o, alt, neu) -> verzoegerung.playFromStart());

            auswahl.put(b, box);
            eingaben.put(b, feld);
            eingabeRaster.addRow(zeile++, box, feld);
        }

        VBox eingabeKarte = karte("Projektdaten",
                "Bezugsgrößen für das neue Schiff festlegen.",
                eingabeRaster,
                beschreibung("Die Schätzung aktualisiert sich bei Eingabe automatisch."));

        // ---------- Ergebnis ----------
        GridPane ergebnisRaster = zweiSpalten();
        int spalte = 0;
        for (Ziel z : Ziel.values()) {
            ErgebnisKachel k = new ErgebnisKachel(z);
            kacheln.put(z, k);
            ergebnisRaster.add(k, spalte++, 0);
        }

        faktorWert.getStyleClass().add("split-tile-value");
        faktorText.getStyleClass().add("hint");
        faktorText.setWrapText(true);
        Label faktorTitel = new Label("Real gegenüber Angebot (historisch)");
        faktorTitel.getStyleClass().add("split-tile-caption");
        VBox faktorKachel = new VBox(2, faktorTitel, faktorWert, faktorText);
        faktorKachel.getStyleClass().addAll("split-tile", "switch");
        faktorKachel.setMinWidth(0);

        datenHinweis.getStyleClass().add("hint");
        datenHinweis.setWrapText(true);

        GridPane gewichtRaster = zweiSpalten();
        spalte = 0;
        for (Ziel z : Ziel.values()) {
            Label t = feldLabel("Gewichtung " + z.kurz);
            VBox box = new VBox(10);
            box.setMinWidth(0);
            gewichtBoxen.put(z, box);
            gewichtRaster.add(new VBox(8, t, box), spalte++, 0);
        }

        VBox ergebnisKarte = karte("Schätzung Kabellänge",
                "Gewichtetes Ergebnis der verwendbaren Bezugsgrößen, getrennt für Angebot und Real.",
                ergebnisRaster, faktorKachel, abschnitt("Gewichtung der Bezugsgrößen"),
                gewichtRaster, datenHinweis);

        GridPane kopf = new GridPane();
        kopf.setHgap(16);
        ColumnConstraints k1 = new ColumnConstraints();
        k1.setPercentWidth(40);
        ColumnConstraints k2 = new ColumnConstraints();
        k2.setPercentWidth(60);
        kopf.getColumnConstraints().setAll(k1, k2);
        kopf.add(eingabeKarte, 0, 0);
        kopf.add(ergebnisKarte, 1, 0);
        GridPane.setVgrow(eingabeKarte, Priority.ALWAYS);

        // ---------- Ähnliche Schiffe ----------
        baueAehnlichTabelle();
        aehnlichHinweis.setWrapText(true);
        aehnlichHinweis.getStyleClass().add("estimate-body-text");
        VBox aehnlichKarte = karte("Ähnlichste Schiffe",
                "Vergleich mit den bisherigen Projekten auf Basis der eingegebenen Werte.",
                aehnlichTabelle, aehnlichHinweis);

        getChildren().addAll(kopf, aehnlichKarte);

        var design = KabelSchaetzungCard.class.getResource("/org/example/schaetzung.css");
        if (design != null) {
            getStylesheets().add(design.toExternalForm());
        }

        neuRechnen();
    }

    /** null = alle. Gleiche Signatur wie KombiSchaetzungCard.setFilter. */
    public void setFilter(String kategorie, String schiffstyp, boolean nurRelevant) {
        try {
            projekte = repository.lade(kategorie, schiffstyp, nurRelevant);
        } catch (RuntimeException e) {
            projekte = List.of();
            datenHinweis.setText("Fehler beim Laden: " + e.getMessage());
        }
        neuRechnen();
    }

    // ============================================================
    // Rechnen
    // ============================================================

    private void neuRechnen() {
        for (Ziel z : Ziel.values()) {
            Ergebnis e = rechne(z);
            kacheln.get(z).zeige(e);
            zeigeGewichte(z, e);
        }
        zeigeFaktor();
        fuelleAehnlich();

        long angebot = projekte.stream().filter(p -> positiv(p.kabelAngebot())).count();
        long real = projekte.stream().filter(p -> positiv(p.kabelReal())).count();
        datenHinweis.setText("Datengrundlage: " + angebot + " Projekte mit Kabellänge Angebot, "
                + real + " mit Kabellänge Real (aktuelle Filter).");
    }

    private Ergebnis rechne(Ziel ziel) {
        Map<Bezug, RegressionRechenweg.Methode> methoden = new LinkedHashMap<>();
        Map<Bezug, Integer> anzahl = new HashMap<>();

        for (Bezug b : BEZUEGE) {
            if (!auswahl.get(b).isSelected() || eingabe(b) == null) {
                continue;
            }
            List<RegressionRechenweg.Punkt> punkte = new ArrayList<>();
            for (KabelProjekt p : projekte) {
                Double x = p.wert(b.schluessel());
                Double y = p.wert(ziel.schluessel);
                if (positiv(x) && positiv(y)) {
                    punkte.add(new RegressionRechenweg.Punkt(p.schiff(), "", x, y));
                }
            }
            RegressionRechenweg.Ergebnis r = RegressionRechenweg.berechne(punkte);
            RegressionRechenweg.Methode m = r == null ? null : r.beste();
            if (m != null && m.mape() != null) {
                methoden.put(b, m);
                anzahl.put(b, punkte.size());
            }
        }

        double summeRoh = 0;
        Map<Bezug, Double> roh = new HashMap<>();
        Map<Bezug, Double> einzel = new HashMap<>();
        for (var eintrag : methoden.entrySet()) {
            Double s = eintrag.getValue().prognose(eingabe(eintrag.getKey()));
            if (s == null || !Double.isFinite(s)) {
                continue;
            }
            double w = 1 / Math.pow(Math.max(eintrag.getValue().mape(), 0.5), 2);
            roh.put(eintrag.getKey(), w);
            einzel.put(eintrag.getKey(), s);
            summeRoh += w;
        }

        if (summeRoh <= 0) {
            return new Ergebnis(null, 0, 0, List.of(), 0);
        }

        double wert = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int minN = Integer.MAX_VALUE;
        List<Gewicht> gewichte = new ArrayList<>();

        for (Bezug b : roh.keySet()) {
            double anteil = roh.get(b) / summeRoh;
            double s = einzel.get(b);
            wert += anteil * s;
            min = Math.min(min, s);
            max = Math.max(max, s);
            minN = Math.min(minN, anzahl.get(b));
            gewichte.add(new Gewicht(b.name() + " (" + b.einheit() + ")",
                    anzahl.get(b), anteil, s));
        }

        gewichte.sort((a, b) -> Double.compare(b.anteil(), a.anteil()));
        return new Ergebnis(wert, min, max, gewichte, minN);
    }

    /** Median von Real / Angebot über alle Projekte mit beiden Werten. */
    private void zeigeFaktor() {
        List<Double> faktoren = new ArrayList<>();
        for (KabelProjekt p : projekte) {
            if (positiv(p.kabelAngebot()) && positiv(p.kabelReal())) {
                faktoren.add(p.kabelReal() / p.kabelAngebot());
            }
        }

        if (faktoren.isEmpty()) {
            faktorWert.setText("-");
            faktorText.setText("Keine Projekte mit Angebot und Real.");
            return;
        }

        faktoren.sort(Double::compare);
        int n = faktoren.size();
        double median = n % 2 == 1
                ? faktoren.get(n / 2)
                : (faktoren.get(n / 2 - 1) + faktoren.get(n / 2)) / 2;

        double prozent = (median - 1) * 100;
        faktorWert.setText((prozent >= 0 ? "+" : "") + PROZENT.format(prozent) + " %");
        faktorText.setText("Median aus " + n + (n == 1 ? " Projekt" : " Projekten")
                + ": So viel mehr (+) oder weniger (−) Kabel wurde real verlegt als angeboten."
                + (n < WENIGE_SCHIFFE ? " Wenige Projekte, nur grobe Richtung." : ""));
    }

    // ============================================================
    // Anzeige
    // ============================================================

    private void zeigeGewichte(Ziel ziel, Ergebnis e) {
        VBox box = gewichtBoxen.get(ziel);
        box.getChildren().clear();

        if (e.gewichte().isEmpty()) {
            box.getChildren().add(beschreibung("Erscheint, sobald eine Schätzung möglich ist."));
            return;
        }

        for (Gewicht g : e.gewichte()) {
            Label name = feldLabel(g.bezug());
            name.setMinWidth(0);
            Label wert = feldLabel(PROZENT.format(g.anteil() * 100) + " %");
            wert.setMinWidth(Region.USE_PREF_SIZE);
            Region abstand = new Region();
            HBox.setHgrow(abstand, Priority.ALWAYS);
            HBox kopf = new HBox(8, name, abstand, wert);
            kopf.setAlignment(Pos.CENTER_LEFT);

            String schluessel = ziel.name() + "|" + g.bezug();
            double vorher = letzteGewichte.getOrDefault(schluessel, 0.0);
            letzteGewichte.put(schluessel, g.anteil());

            ProgressBar balken = new ProgressBar(vorher);
            balken.setMaxWidth(Double.MAX_VALUE);
            balken.setMinWidth(0);
            balken.getStyleClass().add("estimate-weight-bar");
            new Timeline(new KeyFrame(Duration.millis(350),
                    new KeyValue(balken.progressProperty(), g.anteil(), Interpolator.EASE_OUT)))
                    .play();

            Label info = beschreibung("Einzelschätzung " + ZAHL.format(g.einzel())
                    + " km · " + g.n() + " Schiffe");

            box.getChildren().add(new VBox(4, kopf, balken, info));
        }
    }

    private void fuelleAehnlich() {
        List<Bezug> genutzt = BEZUEGE.stream()
                .filter(b -> auswahl.get(b).isSelected() && eingabe(b) != null)
                .toList();

        if (genutzt.isEmpty()) {
            aehnlichTabelle.getItems().clear();
            aehnlichHinweis.setText("");
            passeHoeheAn(aehnlichTabelle);
            return;
        }

        record Kandidat(KabelProjekt p, int anzahl, double abstand, double faktor) {
        }

        List<Kandidat> kandidaten = new ArrayList<>();
        for (KabelProjekt p : projekte) {
            int anzahl = 0;
            double quadrate = 0;
            double faktor = 0;
            for (Bezug b : genutzt) {
                Double alt = p.wert(b.schluessel());
                if (!positiv(alt)) {
                    continue;
                }
                double neu = eingabe(b);
                double rel = (alt - neu) / neu;
                quadrate += rel * rel;
                faktor += neu / alt;
                anzahl++;
            }
            if (anzahl > 0) {
                kandidaten.add(new Kandidat(p, anzahl,
                        Math.sqrt(quadrate / anzahl), faktor / anzahl));
            }
        }

        kandidaten.sort((a, b) -> a.anzahl() != b.anzahl()
                ? Integer.compare(b.anzahl(), a.anzahl())
                : Double.compare(a.abstand(), b.abstand()));

        List<Aehnlich> zeilen = new ArrayList<>();
        int rang = 1;
        for (Kandidat k : kandidaten.subList(0, Math.min(TOP_AEHNLICH, kandidaten.size()))) {
            KabelProjekt p = k.p();
            zeilen.add(new Aehnlich(rang++, p.schiff(),
                    Math.max(0, 100 * (1 - k.abstand())),
                    k.anzahl() + " von " + genutzt.size(),
                    p.kabelAngebot(), p.kabelReal(),
                    positiv(p.kabelAngebot()) ? p.kabelAngebot() * k.faktor() : null,
                    positiv(p.kabelReal()) ? p.kabelReal() * k.faktor() : null));
        }

        aehnlichTabelle.getItems().setAll(zeilen);
        passeHoeheAn(aehnlichTabelle);
        aehnlichHinweis.setText("„Hochgerechnet“ = Kabellänge des alten Schiffs proportional "
                + "auf die Größe des neuen umgerechnet (grobe Gegenprobe). Gezeigt werden die "
                + TOP_AEHNLICH + " ähnlichsten von " + kandidaten.size() + " Schiffen.");
    }

    private void baueAehnlichTabelle() {
        aehnlichTabelle.getColumns().setAll(List.of(
                zahlSpalte("Rang", a -> (double) a.rang(), ZAHL),
                textSpalte("Schiff", Aehnlich::schiff),
                zahlSpalte("Ähnlichkeit %", Aehnlich::aehnlichkeit, PROZENT),
                textSpalte("Verglichen", Aehnlich::verglichen),
                zahlSpalte("Angebot (km)", Aehnlich::angebot, ZAHL),
                zahlSpalte("Real (km)", Aehnlich::real, ZAHL),
                zahlSpalte("Hochger. Angebot", Aehnlich::hochAngebot, ZAHL),
                zahlSpalte("Hochger. Real", Aehnlich::hochReal, ZAHL)
        ));
        // Spalten teilen sich die Breite: nichts läuft rechts raus
        aehnlichTabelle.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        aehnlichTabelle.setPlaceholder(new Label("Werte des neuen Schiffs eingeben."));
        aehnlichTabelle.setMinWidth(0);
        aehnlichTabelle.getStyleClass().add("estimate-table");
        passeHoeheAn(aehnlichTabelle);
    }

    // ============================================================
    // Ergebnis-Kachel mit hochzählender Zahl
    // ============================================================

    private final class ErgebnisKachel extends VBox {

        private final Label zahl = new Label("-");
        private final Label spanne = new Label();
        private final DoubleProperty angezeigt = new SimpleDoubleProperty(0);
        private Timeline lauf;

        ErgebnisKachel(Ziel ziel) {
            super(6);
            getStyleClass().add("estimate-result-panel");
            setMinWidth(0);

            Label titel = feldLabel(ziel.titel + " (" + ziel.kurz + ")");
            titel.getStyleClass().add("estimate-result-title");

            zahl.getStyleClass().add("estimate-result-value");
            zahl.setMinWidth(0);

            spanne.setWrapText(true);
            spanne.getStyleClass().add("estimate-result-note");

            angezeigt.addListener((o, alt, neu) ->
                    zahl.setText(ZAHL.format(neu.doubleValue()) + " km"));

            getChildren().addAll(titel, zahl, spanne);
        }

        void zeige(Ergebnis e) {
            if (lauf != null) {
                lauf.stop();
            }
            if (e.wert() == null) {
                zahl.setText("-");
                spanne.setText("Werte eingeben. Es braucht mindestens 3 Schiffe "
                        + "mit dieser Kabellänge und der Bezugsgröße.");
                return;
            }

            lauf = new Timeline(new KeyFrame(Duration.millis(450),
                    new KeyValue(angezeigt, e.wert(), Interpolator.EASE_OUT)));
            lauf.play();

            String text = e.gewichte().size() > 1
                    ? "Einzelschätzungen " + ZAHL.format(e.min()) + " bis "
                    + ZAHL.format(e.max()) + " km."
                    : "Aus einer Bezugsgröße.";
            if (e.minN() < WENIGE_SCHIFFE) {
                text += " Weniger als " + WENIGE_SCHIFFE + " Schiffe, nur grobe Richtung.";
            }
            spanne.setText(text);
        }
    }

    // ============================================================
    // Hilfsmethoden
    // ============================================================

    private Double eingabe(Bezug b) {
        String text = eingaben.get(b).getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        String s = text.trim().replace(" ", "");
        if (s.contains(",")) {
            s = s.replace(".", "").replace(',', '.');
        } else if (s.matches("[1-9]\\d{0,2}(\\.\\d{3})+")) {
            s = s.replace(".", "");
        }
        try {
            double w = Double.parseDouble(s);
            return Double.isFinite(w) && w > 0 ? w : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean positiv(Double w) {
        return w != null && Double.isFinite(w) && w > 0;
    }

    private static GridPane zweiSpalten() {
        GridPane g = new GridPane();
        g.setHgap(12);
        g.setMinWidth(0);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints c = new ColumnConstraints();
            c.setPercentWidth(50);
            c.setMinWidth(0);
            g.getColumnConstraints().add(c);
        }
        return g;
    }

    private static VBox karte(String titel, String untertitel, Node... inhalt) {
        VBox box = new VBox(12, abschnitt(titel), beschreibung(untertitel));
        box.setMinWidth(0);
        box.getStyleClass().add("estimate-card");
        box.getChildren().addAll(inhalt);
        return box;
    }

    private static Label abschnitt(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("estimate-section-title");
        return l;
    }

    private static Label beschreibung(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.setMinWidth(0);
        l.getStyleClass().add("estimate-description");
        return l;
    }

    private static Label feldLabel(String text) {
        Label l = new Label(text);
        l.setWrapText(true);
        l.getStyleClass().add("estimate-field-label");
        return l;
    }

    private static <T> TableColumn<T, String> textSpalte(String titel, Function<T, String> wert) {
        TableColumn<T, String> s = new TableColumn<>(titel);
        s.setCellValueFactory(c -> new SimpleStringProperty(wert.apply(c.getValue())));
        s.setSortable(false);
        return s;
    }

    private static <T> TableColumn<T, Double> zahlSpalte(
            String titel, Function<T, Double> wert, DecimalFormat format) {
        TableColumn<T, Double> s = new TableColumn<>(titel);
        s.setCellValueFactory(c -> new SimpleObjectProperty<>(wert.apply(c.getValue())));
        s.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : format.format(item));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        s.setSortable(false);
        return s;
    }

    private static void passeHoeheAn(TableView<?> t) {
        double zeile = 30;
        t.setFixedCellSize(zeile);
        int anzahl = Math.max(1, t.getItems().size());
        double hoehe = zeile * anzahl + 44;
        t.setPrefHeight(hoehe);
        t.setMinHeight(hoehe);
        t.setMaxHeight(hoehe);
    }

    private static DecimalFormat format(String muster) {
        return new DecimalFormat(muster, DecimalFormatSymbols.getInstance(Locale.GERMANY));
    }
}
