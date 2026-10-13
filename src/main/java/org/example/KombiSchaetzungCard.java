package org.example;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Kombinierte Schätzung der Brennstellen aus mehreren Bezugsgrößen.
 *
 * Für jede Bezugsgröße wird eine eigene Formel gerechnet (beste Methode
 * laut Prognosefehler). Die Einzelschätzungen werden gewichtet gemittelt:
 *
 *   Brennstellen = Σ w_i · ŷ_i
 *   w_i = (1 / F_i²) / Σ (1 / F_j²)      F = Prognosefehler in %
 *
 * Genauere Bezugsgrößen bekommen so mehr Gewicht. Fehlt beim neuen Schiff
 * ein Wert, wird nur mit den vorhandenen gerechnet.
 *
 * Eigenständige Karte, lädt ihre Daten über MatrixRepository.
 */
public class KombiSchaetzungCard extends VBox {

    private static final DecimalFormat ZAHL = format("#,##0.##");
    private static final DecimalFormat GENAU = format("#,##0.####");
    private static final DecimalFormat PROZENT = format("0.0");

    private enum Basis {
        IST("Ist"),
        SOLL("Soll"),
        IST_SONST_SOLL("Ist, sonst Soll");

        private final String text;

        Basis(String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private record Bezug(
            String name,
            String einheit,
            String schluessel,
            boolean standard,
            String warnung
    ) {
    }

    private static final List<Bezug> BEZUEGE = List.of(
            new Bezug("Volumen", "GT", "volume_gt", true, null),
            new Bezug("Fläche", "m²", "area_sqm", true, null),
            new Bezug("Länge", "m", "length_m", false, null),
            new Bezug("Kabel berechnet", "km", "cable_calculated_km", false, null),
            new Bezug("Kabel Ist", "km", "cable_actual_km", false,
                    "Kabel Ist ist erst nach dem Bau bekannt und eignet sich "
                            + "nicht für ein Angebot, nur zur Analyse.")
    );

    /** Formel einer Bezugsgröße. */
    private record Formel(
            Bezug bezug,
            int n,
            RegressionRechenweg.Methode methode,
            Map<String, Double> looPrognosen
    ) {
        Double fehler() {
            return methode == null ? null : methode.mape();
        }
    }

    private record Zeile(
            String bezug,
            Integer n,
            String methode,
            String formel,
            Double fehler,
            Double eingabe,
            Double schaetzung,
            Double gewicht,
            Double beitrag,
            boolean summe
    ) {
    }

    private final MatrixRepository repository = new MatrixRepository();
    private List<MatrixProjekt> projekte = List.of();

    private final ComboBox<Basis> basisAuswahl = new ComboBox<>();
    private final Map<Bezug, CheckBox> auswahl = new LinkedHashMap<>();
    private final Map<Bezug, TextField> eingaben = new LinkedHashMap<>();
    private final TableView<Zeile> tabelle = new TableView<>();
    private final Label ergebnis = new Label();
    private final Label pruefung = new Label();

    /** Ein altes Schiff im Ähnlichkeits-Ranking. */
    private record Aehnlich(
            int rang,
            String schiff,
            Map<Bezug, Double> werte,
            Map<Bezug, Double> abweichung,
            int verglichen,
            double aehnlichkeit,
            Double brennstellen,
            String quelle,
            Double hochgerechnet
    ) {
    }

    private static final int TOP_AEHNLICH = 5;

    private final TableView<Aehnlich> aehnlichTabelle = new TableView<>();
    private final Label aehnlichHinweis = new Label();

    public KombiSchaetzungCard() {
        setSpacing(12);

        basisAuswahl.getItems().setAll(Basis.values());
        basisAuswahl.setValue(Basis.IST_SONST_SOLL);
        basisAuswahl.setOnAction(event -> neuRechnen());

        HBox kopf = new HBox(10, new Label("Brennstellen-Wert der alten Schiffe:"),
                basisAuswahl);
        kopf.setAlignment(Pos.CENTER_LEFT);

        GridPane eingabeRaster = new GridPane();
        eingabeRaster.setHgap(10);
        eingabeRaster.setVgap(6);
        eingabeRaster.addRow(0, fett("Verwenden"), fett("Neues Schiff"));

        int zeile = 1;
        for (Bezug b : BEZUEGE) {
            CheckBox box = new CheckBox(b.name() + " (" + b.einheit() + ")");
            box.setSelected(b.standard());
            box.setOnAction(event -> neuRechnen());
            if (b.warnung() != null) {
                Tooltip.install(box, new Tooltip(b.warnung()));
                box.setText(box.getText() + "  ⚠");
            }

            TextField feld = new TextField();
            feld.setPromptText(b.name() + " in " + b.einheit());
            feld.setPrefWidth(160);
            feld.textProperty().addListener((obs, alt, neu) -> neuRechnen());

            auswahl.put(b, box);
            eingaben.put(b, feld);
            eingabeRaster.addRow(zeile++, box, feld);
        }

        baueTabelle();

        ergebnis.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        ergebnis.setWrapText(true);
        pruefung.setWrapText(true);
        pruefung.getStyleClass().add("hint");

        Label erklaerung = new Label(
                "Formel: Brennstellen = Σ Gewicht × Einzelschätzung.  "
                        + "Gewicht = (1 / Fehler²) / Summe aller (1 / Fehler²). "
                        + "Eine Bezugsgröße mit halb so großem Prognosefehler "
                        + "bekommt viermal so viel Gewicht.");
        erklaerung.setWrapText(true);
        erklaerung.getStyleClass().add("hint");

        Label aehnlichTitel = new Label("Ähnlichste Schiffe zu den eingegebenen Werten");
        aehnlichTitel.setStyle("-fx-font-weight: bold; -fx-padding: 8 0 0 0;");
        aehnlichHinweis.setWrapText(true);
        aehnlichHinweis.getStyleClass().add("hint");
        aehnlichTabelle.setPlaceholder(new Label("Werte des neuen Schiffs eingeben."));

        getChildren().addAll(kopf, eingabeRaster, tabelle, ergebnis,
                erklaerung, pruefung, aehnlichTitel, aehnlichTabelle, aehnlichHinweis);
    }

    /** Wie KorrelationsMatrixCard.setFilter: null = alle. */
    public void setFilter(String kategorie, String schiffstyp, boolean nurRelevant) {
        try {
            projekte = repository.lade(kategorie, schiffstyp, nurRelevant);
        } catch (RuntimeException e) {
            projekte = List.of();
            pruefung.setText("Fehler beim Laden: " + e.getMessage());
        }
        neuRechnen();
    }

    /** Daten direkt setzen (z. B. für Tests). */
    void setProjekte(List<MatrixProjekt> projekte) {
        this.projekte = projekte == null ? List.of() : projekte;
        neuRechnen();
    }

    // ------------------------------------------------------------------
    // Rechnen
    // ------------------------------------------------------------------

    private void neuRechnen() {
        List<Formel> formeln = new ArrayList<>();
        for (Bezug b : BEZUEGE) {
            if (auswahl.get(b).isSelected()) {
                formeln.add(rechneFormel(b));
            }
        }

        // Gewichte für alle Bezugsgrößen mit Prognosefehler (für die Prüfung)
        Map<Bezug, Double> roh = new HashMap<>();
        for (Formel f : formeln) {
            if (f.fehler() != null) {
                roh.put(f.bezug(), 1 / Math.pow(Math.max(f.fehler(), 0.5), 2));
            }
        }

        // Für das neue Schiff nur Bezugsgrößen mit Eingabe
        double summeRoh = 0;
        for (Formel f : formeln) {
            if (roh.containsKey(f.bezug()) && eingabe(f.bezug()) != null) {
                summeRoh += roh.get(f.bezug());
            }
        }

        List<Zeile> zeilen = new ArrayList<>();
        double kombiniert = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int verwendet = 0;

        for (Formel f : formeln) {
            Double x = eingabe(f.bezug());
            RegressionRechenweg.Methode m = f.methode();

            Double schaetzung = (m != null && x != null) ? m.prognose(x) : null;
            Double gewicht = (schaetzung != null && roh.containsKey(f.bezug()) && summeRoh > 0)
                    ? roh.get(f.bezug()) / summeRoh
                    : null;
            Double beitrag = gewicht == null ? null : gewicht * schaetzung;

            if (beitrag != null) {
                kombiniert += beitrag;
                min = Math.min(min, schaetzung);
                max = Math.max(max, schaetzung);
                verwendet++;
            }

            zeilen.add(new Zeile(
                    f.bezug().name() + " (" + f.bezug().einheit() + ")",
                    f.n(),
                    m == null ? "zu wenige Schiffe" : m.name(),
                    m == null ? "–" : formelText(m, f.bezug()),
                    f.fehler(),
                    x, schaetzung, gewicht, beitrag, false));
        }

        if (verwendet > 0) {
            zeilen.add(new Zeile("Kombiniert", null, "", "Σ Beitrag",
                    null, null, null, 1.0, kombiniert, true));
        }

        tabelle.getItems().setAll(zeilen);
        passeHoeheAn(tabelle);

        if (verwendet == 0) {
            ergebnis.setText("Werte des neuen Schiffs eingeben, um die Brennstellen zu schätzen.");
        } else {
            String text = "Geschätzte Brennstellen: " + ZAHL.format(Math.round(kombiniert));
            if (verwendet > 1) {
                text += "   (Einzelschätzungen von " + ZAHL.format(Math.round(min))
                        + " bis " + ZAHL.format(Math.round(max)) + ")";
            }
            ergebnis.setText(text);
        }

        pruefung.setText(pruefText(formeln, roh));
        fuelleAehnlich();
    }

    private Formel rechneFormel(Bezug b) {
        List<RegressionRechenweg.Punkt> punkte = new ArrayList<>();

        for (MatrixProjekt p : projekte) {
            Double x = p.wert(b.schluessel());
            Double y = brennstellen(p);
            if (x != null && x > 0 && y != null) {
                punkte.add(new RegressionRechenweg.Punkt(p.schiff(), "", x, y));
            }
        }

        RegressionRechenweg.Ergebnis e = RegressionRechenweg.berechne(punkte);
        RegressionRechenweg.Methode beste = e == null ? null : e.beste();

        Map<String, Double> loo = new HashMap<>();
        if (beste != null) {
            for (RegressionRechenweg.LooZeile z : beste.loo()) {
                if (z.prognose() != null) {
                    loo.put(z.punkt().name(), z.prognose());
                }
            }
        }

        return new Formel(b, punkte.size(), beste, loo);
    }

    private Double brennstellen(MatrixProjekt p) {
        Double ist = p.wert("LIGHTING_POINTS:ist");
        Double soll = p.wert("LIGHTING_POINTS:soll");
        return switch (basisAuswahl.getValue()) {
            case IST -> ist;
            case SOLL -> soll;
            case IST_SONST_SOLL -> ist != null ? ist : soll;
        };
    }

    /**
     * Prüft die Kombination fair: Nur alte Schiffe, für die jede verwendete
     * Formel eine Leave-one-out-Prognose hat. Auf genau diesen Schiffen wird
     * der Fehler der Kombination mit dem Fehler jeder einzelnen Formel
     * verglichen.
     */
    private String pruefText(List<Formel> formeln, Map<Bezug, Double> roh) {
        List<Formel> nutzbar = formeln.stream()
                .filter(f -> roh.containsKey(f.bezug()))
                .toList();

        if (nutzbar.isEmpty()) {
            return "Für keine Bezugsgröße gibt es genug Schiffe (mindestens 3), "
                    + "um einen Prognosefehler und damit ein Gewicht zu berechnen.";
        }
        if (nutzbar.size() == 1) {
            return "Nur eine Bezugsgröße mit Prognosefehler, daher keine Kombination. "
                    + "Gewicht 100 %.";
        }

        double kombiSumme = 0;
        double[] einzelSumme = new double[nutzbar.size()];
        int anzahl = 0;

        for (MatrixProjekt p : projekte) {
            Double y = brennstellen(p);
            if (y == null || y == 0) {
                continue;
            }

            double[] prognosen = new double[nutzbar.size()];
            boolean alle = true;
            for (int i = 0; i < nutzbar.size(); i++) {
                Double prognose = nutzbar.get(i).looPrognosen().get(p.schiff());
                if (prognose == null) {
                    alle = false;
                    break;
                }
                prognosen[i] = prognose;
            }
            if (!alle) {
                continue;
            }

            double wert = 0;
            double gewichtSumme = 0;
            for (int i = 0; i < nutzbar.size(); i++) {
                double w = roh.get(nutzbar.get(i).bezug());
                wert += w * prognosen[i];
                gewichtSumme += w;
                einzelSumme[i] += Math.abs(prognosen[i] - y) / y * 100;
            }
            kombiSumme += Math.abs(wert / gewichtSumme - y) / y * 100;
            anzahl++;
        }

        StringBuilder sb = new StringBuilder();

        if (anzahl < 3) {
            sb.append("Nur ").append(anzahl).append(" alte Schiffe haben Werte für alle "
                    + "gewählten Bezugsgrößen. Ob die Kombination genauer ist als eine "
                    + "einzelne Bezugsgröße, lässt sich damit noch nicht fair prüfen.");
        } else {
            double kombiFehler = kombiSumme / anzahl;
            int besteIndex = 0;
            for (int i = 1; i < nutzbar.size(); i++) {
                if (einzelSumme[i] < einzelSumme[besteIndex]) {
                    besteIndex = i;
                }
            }
            double besterEinzel = einzelSumme[besteIndex] / anzahl;
            String besteName = nutzbar.get(besteIndex).bezug().name();

            sb.append("Prüfung auf denselben ").append(anzahl)
                    .append(" alten Schiffen (alle mit jeder gewählten Bezugsgröße): ");
            for (int i = 0; i < nutzbar.size(); i++) {
                sb.append(nutzbar.get(i).bezug().name()).append(" allein ")
                        .append(PROZENT.format(einzelSumme[i] / anzahl)).append(" %, ");
            }
            sb.append("Kombination ").append(PROZENT.format(kombiFehler)).append(" % daneben. ");
            sb.append(kombiFehler < besterEinzel
                    ? "Die Kombination ist genauer."
                    : "Die Kombination ist nicht genauer, nehmt besser nur " + besteName + ".");
            sb.append(" (Näherung: Die Gewichte stammen aus allen Schiffen.)");
        }

        long wenige = nutzbar.stream().filter(f -> f.n() < 6).count();
        if (wenige > 0) {
            sb.append("\nAchtung: Mindestens eine Formel beruht auf weniger als 6 Schiffen. "
                    + "Gewichte und Ergebnis sind dann nur eine grobe Richtung.");
        }

        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Ähnlichste Schiffe
    // ------------------------------------------------------------------

    /**
     * Rangliste der alten Schiffe, die dem neuen Schiff am ähnlichsten sind.
     *
     * Je Bezugsgröße: relative Abweichung = |x_alt − x_neu| / x_neu
     * Abstand    = √(Mittelwert der quadrierten Abweichungen)
     * Ähnlichkeit = 100 % × (1 − Abstand), mindestens 0 %
     *
     * Schiffe, die mehr der eingegebenen Werte haben, stehen vor Schiffen
     * mit weniger Werten; innerhalb davon entscheidet der Abstand.
     */
    private void fuelleAehnlich() {
        List<Bezug> genutzt = BEZUEGE.stream()
                .filter(b -> auswahl.get(b).isSelected() && eingabe(b) != null)
                .toList();

        baueAehnlichSpalten(genutzt);

        if (genutzt.isEmpty()) {
            aehnlichTabelle.getItems().clear();
            aehnlichHinweis.setText("");
            passeHoeheAn(aehnlichTabelle);
            return;
        }

        record Kandidat(MatrixProjekt p, Map<Bezug, Double> werte,
                        Map<Bezug, Double> abw, double abstand, double faktor) {
        }

        List<Kandidat> kandidaten = new ArrayList<>();

        for (MatrixProjekt p : projekte) {
            Map<Bezug, Double> werte = new LinkedHashMap<>();
            Map<Bezug, Double> abw = new LinkedHashMap<>();
            double summeQuadrate = 0;
            double summeFaktor = 0;

            for (Bezug b : genutzt) {
                Double alt = p.wert(b.schluessel());
                if (alt == null || alt <= 0) {
                    continue;
                }
                double neu = eingabe(b);
                double rel = (alt - neu) / neu;
                werte.put(b, alt);
                abw.put(b, rel * 100);
                summeQuadrate += rel * rel;
                summeFaktor += neu / alt;
            }

            if (!werte.isEmpty()) {
                double abstand = Math.sqrt(summeQuadrate / werte.size());
                kandidaten.add(new Kandidat(p, werte, abw, abstand,
                        summeFaktor / werte.size()));
            }
        }

        kandidaten.sort((a, b) -> a.werte().size() != b.werte().size()
                ? Integer.compare(b.werte().size(), a.werte().size())
                : Double.compare(a.abstand(), b.abstand()));

        List<Aehnlich> zeilen = new ArrayList<>();
        int rang = 1;
        for (Kandidat k : kandidaten.subList(0, Math.min(TOP_AEHNLICH, kandidaten.size()))) {
            Double ist = k.p().wert("LIGHTING_POINTS:ist");
            Double soll = k.p().wert("LIGHTING_POINTS:soll");
            Double bs = ist != null ? ist : soll;
            String quelle = ist != null ? "Ist" : soll != null ? "Soll" : "";

            zeilen.add(new Aehnlich(
                    rang++,
                    k.p().schiff(),
                    k.werte(),
                    k.abw(),
                    k.werte().size(),
                    Math.max(0, 100 * (1 - k.abstand())),
                    bs,
                    quelle,
                    bs == null ? null : bs * k.faktor()));
        }

        aehnlichTabelle.getItems().setAll(zeilen);
        passeHoeheAn(aehnlichTabelle);

        aehnlichHinweis.setText("Ähnlichkeit = 100 % minus die mittlere Abweichung "
                + "der eingegebenen Werte. Abw. % = wie viel größer (+) oder kleiner (−) "
                + "das alte Schiff ist. „Hochgerechnet“ = Brennstellen des alten Schiffs "
                + "proportional auf die Größe des neuen umgerechnet; das ist eine grobe "
                + "Gegenprobe zur Schätzung oben. Gezeigt werden die "
                + TOP_AEHNLICH + " ähnlichsten von " + kandidaten.size() + " Schiffen.");
    }

    private void baueAehnlichSpalten(List<Bezug> genutzt) {
        List<TableColumn<Aehnlich, ?>> spalten = new ArrayList<>();
        spalten.add(zahlSpalte("Rang", a -> (double) a.rang(), ZAHL, 60));
        spalten.add(textSpalte("Schiff", Aehnlich::schiff, 170));

        for (Bezug b : genutzt) {
            spalten.add(zahlSpalte(b.name() + " (" + b.einheit() + ")",
                    a -> a.werte().get(b), ZAHL, 125));
            spalten.add(zahlSpalte("Abw. %", a -> a.abweichung().get(b), PROZENT, 75));
        }

        spalten.add(zahlSpalte("Ähnlichkeit %", Aehnlich::aehnlichkeit, PROZENT, 110));
        spalten.add(textSpalte("Werte verglichen",
                a -> a.verglichen() + " von " + genutzt.size(), 140));
        spalten.add(zahlSpalte("Brennstellen", Aehnlich::brennstellen, ZAHL, 105));
        spalten.add(textSpalte("Quelle", Aehnlich::quelle, 60));
        spalten.add(zahlSpalte("Hochgerechnet", Aehnlich::hochgerechnet, ZAHL, 130));

        aehnlichTabelle.getColumns().setAll(spalten);
    }

    // ------------------------------------------------------------------
    // Tabelle
    // ------------------------------------------------------------------

    private void baueTabelle() {
        tabelle.getColumns().setAll(List.of(
                textSpalte("Bezugsgröße", Zeile::bezug, 150),
                zahlSpalte("n", z -> z.n() == null ? null : (double) z.n(), ZAHL, 60),
                textSpalte("Beste Methode", Zeile::methode, 170),
                textSpalte("Formel", Zeile::formel, 250),
                zahlSpalte("Prognosefehler %", Zeile::fehler, PROZENT, 145),
                zahlSpalte("Eingabe", Zeile::eingabe, ZAHL, 100),
                zahlSpalte("Einzelschätzung", Zeile::schaetzung, ZAHL, 125),
                zahlSpalte("Gewicht", z -> z.gewicht() == null ? null : z.gewicht() * 100,
                        PROZENT, 90),
                zahlSpalte("Beitrag", Zeile::beitrag, ZAHL, 100)
        ));
        tabelle.getColumns().get(7).setText("Gewicht %");
        tabelle.setRowFactory(t -> new TableRow<>() {
            @Override
            protected void updateItem(Zeile item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && item.summe()
                        ? "-fx-font-weight: bold;" : "");
            }
        });
        tabelle.setPlaceholder(new Label("Mindestens eine Bezugsgröße auswählen."));
    }

    private static String formelText(RegressionRechenweg.Methode m, Bezug b) {
        String x = b.name();
        if (m.name().startsWith("Median")) {
            return GENAU.format(m.b()) + " × " + x;
        }
        return ZAHL.format(m.a()) + (m.b() < 0 ? " − " : " + ")
                + GENAU.format(Math.abs(m.b())) + " × " + x;
    }

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
            double wert = Double.parseDouble(s);
            return wert > 0 ? wert : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

    private static Label fett(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold;");
        return label;
    }

    private static <T> TableColumn<T, String> textSpalte(
            String titel, Function<T, String> wert, double breite) {
        TableColumn<T, String> spalte = new TableColumn<>(titel);
        spalte.setCellValueFactory(c -> new SimpleStringProperty(wert.apply(c.getValue())));
        spalte.setPrefWidth(breite);
        spalte.setSortable(false);
        return spalte;
    }

    private static <T> TableColumn<T, Double> zahlSpalte(
            String titel, Function<T, Double> wert, DecimalFormat format, double breite) {
        TableColumn<T, Double> spalte = new TableColumn<>(titel);
        spalte.setCellValueFactory(c -> new SimpleObjectProperty<>(wert.apply(c.getValue())));
        spalte.setCellFactory(c -> new TableCell<>() {
            @Override
            protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : format.format(item));
                setAlignment(Pos.CENTER_RIGHT);
            }
        });
        spalte.setPrefWidth(breite);
        spalte.setSortable(false);
        return spalte;
    }

    private static void passeHoeheAn(TableView<?> t) {
        double zeile = 28;
        t.setFixedCellSize(zeile);
        int anzahl = Math.max(1, t.getItems().size());
        double hoehe = zeile * anzahl + 32 + 30;
        t.setPrefHeight(hoehe);
        t.setMinHeight(hoehe);
        t.setMaxHeight(hoehe);
    }

    private static DecimalFormat format(String muster) {
        return new DecimalFormat(muster, DecimalFormatSymbols.getInstance(Locale.GERMANY));
    }
}