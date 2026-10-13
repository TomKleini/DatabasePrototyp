package org.example;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Pos;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Rechenweg "Brennstellen aus Fläche": zeigt jede Zwischengröße mit den
 * echten Projektdaten, vergleicht normale Regression, Theil-Sen und
 * Median-Quote und empfiehlt die Methode mit dem kleinsten Prognosefehler.
 */
public class RechenwegCard extends VBox {

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

    private record DatenZeile(
            String schiff,
            String quelle,
            Double x,
            Double y,
            Double dx,
            Double dy,
            Double dxdy,
            Double dx2,
            Double dy2,
            Double yDach,
            Double residuum,
            Double residuum2,
            String hinweis,
            boolean hervorheben
    ) {
    }

    private record Schritt(
            String groesse,
            String formel,
            String eingesetzt,
            String ergebnis,
            String excel
    ) {
    }

    private record MethodenZeile(
            String methode,
            String formel,
            String kalkulation,
            Double mape,
            String bewertung,
            boolean beste
    ) {
    }

    private record LooVergleich(
            String schiff,
            double x,
            double ist,
            Double prognoseNormal,
            Double fehlerNormal,
            Double prognoseTs,
            Double fehlerTs,
            Double prognoseQuote,
            Double fehlerQuote
    ) {
    }

    private static final int MIN_IST = 3;
    private static final int MAX_PAARE_SICHTBAR = 10;

    private static final DecimalFormat ZAHL = format("#,##0.##");
    private static final DecimalFormat GENAU = format("#,##0.####");
    private static final DecimalFormat EXCEL = format("0.####");

    private final ComboBox<Basis> basisAuswahl = new ComboBox<>();
    private final Label datenInfo = new Label();

    private final TableView<DatenZeile> datenTabelle = new TableView<>();
    private final TableView<Schritt> schrittTabelle = new TableView<>();
    private final TableView<RegressionRechenweg.RobusteZeile> robustTabelle =
            new TableView<>();
    private final TableView<RegressionRechenweg.Paarsteigung> paarTabelle =
            new TableView<>();
    private final TableView<Schritt> robustSchritte = new TableView<>();
    private final TableView<MethodenZeile> methodenTabelle = new TableView<>();
    private final Label methodenFazit = new Label();
    private final TableView<LooVergleich> looTabelle = new TableView<>();

    private final NumberAxis xAchse = new NumberAxis();
    private final NumberAxis yAchse = new NumberAxis();
    private final LineChart<Number, Number> diagramm =
            new LineChart<>(xAchse, yAchse);

    private final TextField probeFlaeche = new TextField();
    private final Label probeErgebnis = new Label();

    private List<LightingKpiRow> rows = List.of();
    private List<RegressionRechenweg.Punkt> punkte = List.of();
    private RegressionRechenweg.Ergebnis ergebnis;
    private boolean basisVomNutzerGewaehlt = false;

    public RechenwegCard() {
        setSpacing(14);

        basisAuswahl.getItems().setAll(Basis.values());
        basisAuswahl.setValue(Basis.IST);
        basisAuswahl.setOnAction(event -> {
            basisVomNutzerGewaehlt = true;
            neuRechnen();
        });

        datenInfo.getStyleClass().add("hint");
        datenInfo.setWrapText(true);

        Button kopieren = new Button("Daten für Excel kopieren");
        kopieren.setOnAction(event -> fuerExcelKopieren());

        HBox kopf = new HBox(10,
                new Label("Brennstellen-Wert:"), basisAuswahl, kopieren);
        kopf.setAlignment(Pos.CENTER_LEFT);

        baueDatenTabelle();
        baueSchrittTabelle(schrittTabelle);
        baueRobustTabelle();
        bauePaarTabelle();
        baueSchrittTabelle(robustSchritte);
        baueMethodenTabelle();
        baueLooTabelle();
        baueDiagramm();

        methodenFazit.setWrapText(true);
        methodenFazit.getStyleClass().add("hint");

        probeFlaeche.setPromptText("Fläche in m²");
        probeFlaeche.setPrefWidth(140);
        probeFlaeche.textProperty().addListener((obs, alt, neu) -> probeRechnen());
        probeErgebnis.setWrapText(true);

        HBox probe = new HBox(10, new Label("Fläche neues Schiff:"),
                probeFlaeche, probeErgebnis);
        probe.setAlignment(Pos.TOP_LEFT);

        getChildren().addAll(
                kopf,
                datenInfo,
                abschnitt("1. Methodenvergleich"),
                methodenTabelle,
                methodenFazit,
                abschnitt("2. Probe-Rechnung"),
                probe,
                abschnitt("3. Diagramm"),
                diagramm,
                abschnitt("4. Normale Regression: Zwischenwerte je Schiff"),
                datenTabelle,
                abschnitt("5. Normale Regression: Rechenschritte"),
                schrittTabelle,
                abschnitt("6. Robuste Methoden: Werte je Schiff"),
                robustTabelle,
                abschnitt("7. Theil-Sen: Steigungen aller Schiffspaare (sortiert)"),
                paarTabelle,
                abschnitt("8. Robuste Methoden: Rechenschritte"),
                robustSchritte,
                abschnitt("9. Prüfung: Jedes Schiff einmal weglassen und schätzen"),
                looTabelle
        );
    }

    /** Neue (gefilterte) Projektdaten übernehmen. */
    public void setDaten(List<LightingKpiRow> rows) {
        this.rows = rows == null ? List.of() : rows;

        if (!basisVomNutzerGewaehlt) {
            long mitIst = this.rows.stream()
                    .filter(r -> r.areaSqm() != null
                            && r.lightingPointsActual() != null)
                    .count();
            basisAuswahl.setValue(mitIst >= MIN_IST
                    ? Basis.IST
                    : Basis.IST_SONST_SOLL);
        }

        neuRechnen();
    }

    // ------------------------------------------------------------------
    // Rechnen
    // ------------------------------------------------------------------

    private void neuRechnen() {
        punkte = sammlePunkte(basisAuswahl.getValue());
        ergebnis = RegressionRechenweg.berechne(punkte);

        long ist = punkte.stream().filter(p -> "Ist".equals(p.quelle())).count();
        long soll = punkte.size() - ist;
        long ohneFlaeche = rows.stream().filter(r -> r.areaSqm() == null).count();

        StringBuilder info = new StringBuilder();
        info.append(punkte.size()).append(" Schiffe mit Fläche und Brennstellen (")
                .append(ist).append(" × Ist, ").append(soll).append(" × Soll).");
        if (ohneFlaeche > 0) {
            info.append(ohneFlaeche == 1
                    ? " 1 Projekt ohne Fläche ist nicht enthalten."
                    : " " + ohneFlaeche + " Projekte ohne Fläche sind nicht enthalten.");
        }
        if (ergebnis == null) {
            info.append(" Für die Rechnung werden mindestens 2 Schiffe "
                    + "mit unterschiedlicher Fläche gebraucht.");
        } else if (ergebnis.n() < 5) {
            info.append(" Bei so wenigen Schiffen sind alle Ergebnisse nur eine Tendenz.");
        }
        datenInfo.setText(info.toString());

        fuelleMethoden();
        fuelleDiagramm();
        fuelleDatenTabelle();
        fuelleSchrittTabelle();
        fuelleRobust();
        fuelleLoo();
        probeRechnen();
    }

    private List<RegressionRechenweg.Punkt> sammlePunkte(Basis basis) {
        List<RegressionRechenweg.Punkt> liste = new ArrayList<>();

        for (LightingKpiRow row : rows) {
            Double x = row.areaSqm();
            if (x == null || x <= 0) {
                continue;
            }

            Double y = null;
            String quelle = null;

            if (basis != Basis.SOLL && row.lightingPointsActual() != null) {
                y = row.lightingPointsActual();
                quelle = "Ist";
            } else if (basis != Basis.IST && row.lightingPointsTarget() != null) {
                y = row.lightingPointsTarget();
                quelle = "Soll";
            }

            if (y != null) {
                liste.add(new RegressionRechenweg.Punkt(
                        row.shipName(), quelle, x, y));
            }
        }

        return liste;
    }

    // ------------------------------------------------------------------
    // 1. Methodenvergleich
    // ------------------------------------------------------------------

    private void baueMethodenTabelle() {
        methodenTabelle.getColumns().setAll(List.of(
                textSpalte("Methode", MethodenZeile::methode, 200),
                textSpalte("Formel", MethodenZeile::formel, 110),
                textSpalte("Mit euren Werten", MethodenZeile::kalkulation, 330),
                zahlSpalte("Prognosefehler %", MethodenZeile::mape, ZAHL),
                textSpalte("Bewertung", MethodenZeile::bewertung, 220)
        ));
        methodenTabelle.setRowFactory(tabelle -> fettWenn(MethodenZeile::beste));
        methodenTabelle.setPlaceholder(new Label("Noch keine Rechnung möglich."));
    }

    private void fuelleMethoden() {
        List<MethodenZeile> zeilen = new ArrayList<>();
        RegressionRechenweg.Methode beste = ergebnis == null ? null : ergebnis.beste();

        if (ergebnis != null) {
            for (RegressionRechenweg.Methode m : ergebnis.methoden()) {
                String formel = m.a() == 0 && m.name().startsWith("Median")
                        ? "Brennstellen = " + g(m.b()) + " × Fläche"
                        : "Brennstellen = " + z(m.a()) + " + " + g(m.b()) + " × Fläche";
                boolean istBeste = m == beste;
                zeilen.add(new MethodenZeile(m.name(), m.formel(), formel,
                        m.mape(), istBeste ? "kleinster Fehler, empfohlen" : "",
                        istBeste));
            }
        }

        methodenTabelle.getItems().setAll(zeilen);
        passeHoeheAn(methodenTabelle, 0);

        if (ergebnis == null) {
            methodenFazit.setText("");
        } else if (beste == null) {
            methodenFazit.setText("Der Prognosefehler lässt sich erst ab 3 Schiffen "
                    + "berechnen. Bis dahin ist keine Empfehlung möglich.");
        } else {
            methodenFazit.setText("Prognosefehler = mittlere Abweichung, wenn jedes "
                    + "Schiff einmal weggelassen und mit den übrigen geschätzt wird "
                    + "(Details in Abschnitt 9). Die Methode mit dem kleinsten Fehler "
                    + "hätte die bisherigen Schiffe am besten vorhergesagt.");
        }
    }

    // ------------------------------------------------------------------
    // 2. Probe-Rechnung
    // ------------------------------------------------------------------

    private void probeRechnen() {
        String text = probeFlaeche.getText() == null ? "" : probeFlaeche.getText().trim();

        if (text.isEmpty() || ergebnis == null) {
            probeErgebnis.setText("");
            return;
        }

        Double flaeche = parseZahl(text);
        if (flaeche == null) {
            probeErgebnis.setText("Bitte eine Zahl eingeben, z. B. 3.600");
            return;
        }

        RegressionRechenweg.Methode beste = ergebnis.beste();
        StringBuilder sb = new StringBuilder();

        for (RegressionRechenweg.Methode m : ergebnis.methoden()) {
            sb.append(m.name()).append(": ");
            if (m.a() != 0 || !m.name().startsWith("Median")) {
                sb.append(z(m.a())).append(" + ");
            }
            sb.append(g(m.b())).append(" × ").append(z(flaeche))
                    .append(" = ").append(z(m.prognose(flaeche)))
                    .append(" Brennstellen");
            if (m == beste) {
                sb.append("   (empfohlen)");
            }
            sb.append('\n');
        }

        if (ergebnis.se() != null) {
            sb.append("Typische Abweichung der normalen Regression: ± ")
                    .append(z(ergebnis.se())).append(" Brennstellen\n");
        }
        if (!ergebnis.imDatenbereich(flaeche)) {
            sb.append("Achtung: Die Fläche liegt außerhalb der bekannten Schiffe (")
                    .append(z(ergebnis.minX())).append(" bis ")
                    .append(z(ergebnis.maxX())).append(" m²), die Schätzung ist unsicher.");
        }

        probeErgebnis.setText(sb.toString().trim());
    }

    // ------------------------------------------------------------------
    // 3. Diagramm
    // ------------------------------------------------------------------

    private void baueDiagramm() {
        xAchse.setLabel("Fläche (m²)");
        yAchse.setLabel("Brennstellen");
        xAchse.setForceZeroInRange(false);
        yAchse.setForceZeroInRange(false);

        StringConverter<Number> deutsch = new StringConverter<>() {
            @Override
            public String toString(Number wert) {
                return wert == null ? "" : ZAHL.format(wert.doubleValue());
            }

            @Override
            public Number fromString(String text) {
                return null;
            }
        };
        xAchse.setTickLabelFormatter(deutsch);
        yAchse.setTickLabelFormatter(deutsch);

        diagramm.setAnimated(false);
        diagramm.setLegendVisible(true);
        diagramm.setPrefHeight(380);
        diagramm.setMaxWidth(900);
    }

    private void fuelleDiagramm() {
        diagramm.getData().clear();
        if (ergebnis == null) {
            return;
        }

        XYChart.Series<Number, Number> schiffe = new XYChart.Series<>();
        schiffe.setName("Schiffe");
        XYChart.Series<Number, Number> auffaellig = new XYChart.Series<>();
        auffaellig.setName("Auffällige Schiffe");

        List<RegressionRechenweg.Punkt> normalePunkte = new ArrayList<>();
        List<RegressionRechenweg.Punkt> auffaelligePunkte = new ArrayList<>();

        for (RegressionRechenweg.Zeile z : ergebnis.zeilen()) {
            RegressionRechenweg.Punkt p = z.punkt();
            if (z.auffaellig()) {
                auffaellig.getData().add(new XYChart.Data<>(p.x(), p.y()));
                auffaelligePunkte.add(p);
            } else {
                schiffe.getData().add(new XYChart.Data<>(p.x(), p.y()));
                normalePunkte.add(p);
            }
        }

        diagramm.getData().add(schiffe);
        if (!auffaellig.getData().isEmpty()) {
            diagramm.getData().add(auffaellig);
        }

        List<XYChart.Series<Number, Number>> geraden = new ArrayList<>();
        for (RegressionRechenweg.Methode m : ergebnis.methoden()) {
            XYChart.Series<Number, Number> gerade = new XYChart.Series<>();
            gerade.setName(m.name());
            gerade.getData().add(new XYChart.Data<>(ergebnis.minX(), m.prognose(ergebnis.minX())));
            gerade.getData().add(new XYChart.Data<>(ergebnis.maxX(), m.prognose(ergebnis.maxX())));
            diagramm.getData().add(gerade);
            geraden.add(gerade);
        }

        // Punkte ohne Verbindungslinie, Geraden ohne Punkte
        nurPunkte(schiffe, normalePunkte);
        nurPunkte(auffaellig, auffaelligePunkte);
        for (XYChart.Series<Number, Number> gerade : geraden) {
            for (XYChart.Data<Number, Number> d : gerade.getData()) {
                if (d.getNode() != null) {
                    d.getNode().setVisible(false);
                }
            }
        }
    }

    private void nurPunkte(
            XYChart.Series<Number, Number> serie,
            List<RegressionRechenweg.Punkt> punkteDerSerie
    ) {
        if (serie.getNode() != null) {
            serie.getNode().setStyle("-fx-stroke: transparent;");
        }
        for (int i = 0; i < serie.getData().size(); i++) {
            XYChart.Data<Number, Number> d = serie.getData().get(i);
            RegressionRechenweg.Punkt p = punkteDerSerie.get(i);
            if (d.getNode() != null) {
                Tooltip.install(d.getNode(), new Tooltip(p.name() + " ("
                        + p.quelle() + ")\nFläche: " + z(p.x())
                        + " m²\nBrennstellen: " + z(p.y())));
            }
        }
    }

    // ------------------------------------------------------------------
    // 4. Normale Regression: Daten
    // ------------------------------------------------------------------

    private void baueDatenTabelle() {
        datenTabelle.getColumns().setAll(List.of(
                textSpalte("Schiff", DatenZeile::schiff, 170),
                textSpalte("Quelle", DatenZeile::quelle, 60),
                zahlSpalte("x Fläche (m²)", DatenZeile::x, ZAHL),
                zahlSpalte("y Brennstellen", DatenZeile::y, ZAHL),
                zahlSpalte("x − x̄", DatenZeile::dx, ZAHL),
                zahlSpalte("y − ȳ", DatenZeile::dy, ZAHL),
                zahlSpalte("(x − x̄)(y − ȳ)", DatenZeile::dxdy, ZAHL),
                zahlSpalte("(x − x̄)²", DatenZeile::dx2, ZAHL),
                zahlSpalte("(y − ȳ)²", DatenZeile::dy2, ZAHL),
                zahlSpalte("ŷ = a + b·x", DatenZeile::yDach, ZAHL),
                zahlSpalte("y − ŷ", DatenZeile::residuum, ZAHL),
                zahlSpalte("(y − ŷ)²", DatenZeile::residuum2, ZAHL),
                textSpalte("Hinweis", DatenZeile::hinweis, 230)
        ));
        datenTabelle.setRowFactory(tabelle -> fettWenn(DatenZeile::hervorheben));
        datenTabelle.setPlaceholder(new Label("Keine Schiffe mit Fläche und Brennstellen."));
    }

    private void fuelleDatenTabelle() {
        List<DatenZeile> zeilen = new ArrayList<>();

        if (ergebnis != null) {
            for (RegressionRechenweg.Zeile z : ergebnis.zeilen()) {
                zeilen.add(new DatenZeile(
                        z.punkt().name(), z.punkt().quelle(),
                        z.punkt().x(), z.punkt().y(),
                        z.dx(), z.dy(), z.dxdy(), z.dx2(), z.dy2(),
                        z.yDach(), z.residuum(), z.residuum2(),
                        z.auffaellig() ? "auffällig: |y − ŷ| > 2 · sₑ, prüfen" : "",
                        false));
            }

            zeilen.add(new DatenZeile("Σ Summe", "",
                    ergebnis.summeX(), ergebnis.summeY(),
                    null, null,
                    ergebnis.sxy(), ergebnis.sxx(), ergebnis.syy(),
                    null, null, ergebnis.sse(), "", true));

            zeilen.add(new DatenZeile("Ø Mittelwert (x̄, ȳ)", "",
                    ergebnis.mittelX(), ergebnis.mittelY(),
                    null, null, null, null, null, null, null, null, "", true));
        }

        datenTabelle.getItems().setAll(zeilen);
        passeHoeheAn(datenTabelle, 0);
    }

    // ------------------------------------------------------------------
    // 5. Normale Regression: Rechenschritte
    // ------------------------------------------------------------------

    private void baueSchrittTabelle(TableView<Schritt> tabelle) {
        tabelle.getColumns().setAll(List.of(
                textSpalte("Größe", Schritt::groesse, 200),
                textSpalte("Formel", Schritt::formel, 250),
                textSpalte("Mit euren Werten", Schritt::eingesetzt, 400),
                textSpalte("Ergebnis", Schritt::ergebnis, 340),
                textSpalte("Excel-Gegenprobe", Schritt::excel, 420)
        ));
        tabelle.setPlaceholder(new Label("Noch keine Rechnung möglich."));
    }

    private void fuelleSchrittTabelle() {
        List<Schritt> s = new ArrayList<>();
        RegressionRechenweg.Ergebnis e = ergebnis;

        if (e != null) {
            int n = e.n();

            s.add(new Schritt("n (Anzahl Schiffe)", "Schiffe mit x und y", "",
                    String.valueOf(n), "=ANZAHL(x)"));
            s.add(new Schritt("x̄ (mittlere Fläche)", "Σx / n",
                    z(e.summeX()) + " / " + n, z(e.mittelX()) + " m²",
                    "=MITTELWERT(x)"));
            s.add(new Schritt("ȳ (mittlere Brennstellen)", "Σy / n",
                    z(e.summeY()) + " / " + n, z(e.mittelY()),
                    "=MITTELWERT(y)"));
            s.add(new Schritt("Sxy", "Σ(x − x̄)(y − ȳ)",
                    "Summe der Spalte (x − x̄)(y − ȳ)", z(e.sxy()),
                    "=KOVARIANZ.P(x;y)*ANZAHL(x)"));
            s.add(new Schritt("Sxx", "Σ(x − x̄)²",
                    "Summe der Spalte (x − x̄)²", z(e.sxx()),
                    "=SUMQUADABW(x)"));
            s.add(new Schritt("Syy", "Σ(y − ȳ)²",
                    "Summe der Spalte (y − ȳ)²", z(e.syy()),
                    "=SUMQUADABW(y)"));
            s.add(new Schritt("b (Steigung)", "Sxy / Sxx",
                    z(e.sxy()) + " / " + z(e.sxx()),
                    g(e.b()) + " Brennstellen pro m²", "=STEIGUNG(y;x)"));
            s.add(new Schritt("a (Fixanteil)", "ȳ − b · x̄",
                    z(e.mittelY()) + " − " + g(e.b()) + " × " + z(e.mittelX()),
                    z(e.a()) + " Brennstellen", "=ACHSENABSCHNITT(y;x)"));
            s.add(new Schritt("r (Pearson)", "Sxy / √(Sxx · Syy)",
                    z(e.sxy()) + " / √(" + z(e.sxx()) + " × " + z(e.syy()) + ")",
                    g(e.r()), "=KORREL(x;y)"));
            s.add(new Schritt("R² (Bestimmtheit)", "r²", g(e.r()) + "²",
                    g(e.r2()) + " (" + z(e.r2() * 100) + " % erklärt)",
                    "=BESTIMMTHEITSMASS(y;x)"));

            if (e.se() != null) {
                s.add(new Schritt("sₑ (typische Abweichung)", "√(Σ(y − ŷ)² / (n − 2))",
                        "√(" + z(e.sse()) + " / " + (n - 2) + ")",
                        "± " + z(e.se()) + " Brennstellen", "=STFEHLERYX(y;x)"));
                s.add(new Schritt("Grenze für „auffällig“", "2 · sₑ",
                        "2 × " + z(e.se()),
                        z(2 * e.se()) + " Brennstellen", ""));
            }

            if (e.rKrit() != null) {
                s.add(new Schritt("t krit (5 %, zweiseitig)",
                        "t-Tabelle mit n − 2 Freiheitsgraden",
                        "FG = " + (n - 2), g(e.tKrit()), "=T.INV.2S(0,05;n-2)"));
                s.add(new Schritt("r krit", "t / √(t² + n − 2)",
                        g(e.tKrit()) + " / √(" + g(e.tKrit()) + "² + " + (n - 2) + ")",
                        g(e.rKrit()), ""));
                s.add(new Schritt("Signifikant?", "|r| > r krit",
                        g(Math.abs(e.r())) + (e.signifikant() ? " > " : " ≤ ") + g(e.rKrit()),
                        e.signifikant() ? "ja, Zusammenhang belastbar"
                                : "nein, kann Zufall sein", ""));
            }

            if (e.mape() != null) {
                s.add(new Schritt("Prognosefehler", "Ø |Prognose − Ist| / Ist",
                        "Mittel der Beträge in Abschnitt 9",
                        z(e.mape()) + " %", ""));
            }
        }

        schrittTabelle.getItems().setAll(s);
        passeHoeheAn(schrittTabelle, 0);
    }

    // ------------------------------------------------------------------
    // 6.–8. Robuste Methoden
    // ------------------------------------------------------------------

    private void baueRobustTabelle() {
        robustTabelle.getColumns().setAll(List.of(
                textSpalte("Schiff", z -> z.punkt().name(), 170),
                zahlSpalte("x Fläche (m²)", z -> z.punkt().x(), ZAHL),
                zahlSpalte("y Brennstellen", z -> z.punkt().y(), ZAHL),
                zahlSpalte("Quote y / x", RegressionRechenweg.RobusteZeile::quote, GENAU),
                zahlSpalte("y − b_TS · x", RegressionRechenweg.RobusteZeile::achsenwertTs, ZAHL),
                zahlSpalte("Rang x", RegressionRechenweg.RobusteZeile::rangX, ZAHL),
                zahlSpalte("Rang y", RegressionRechenweg.RobusteZeile::rangY, ZAHL),
                zahlSpalte("d = Rang x − Rang y", RegressionRechenweg.RobusteZeile::d, ZAHL),
                zahlSpalte("d²", RegressionRechenweg.RobusteZeile::d2, ZAHL)
        ));
        robustTabelle.setPlaceholder(new Label("Noch keine Rechnung möglich."));
    }

    private void bauePaarTabelle() {
        paarTabelle.getColumns().setAll(List.of(
                textSpalte("Schiff A", RegressionRechenweg.Paarsteigung::schiffA, 170),
                textSpalte("Schiff B", RegressionRechenweg.Paarsteigung::schiffB, 170),
                zahlSpalte("Δ Fläche", RegressionRechenweg.Paarsteigung::dx, ZAHL),
                zahlSpalte("Δ Brennstellen", RegressionRechenweg.Paarsteigung::dy, ZAHL),
                zahlSpalte("Steigung Δy / Δx", RegressionRechenweg.Paarsteigung::steigung, GENAU)
        ));
        paarTabelle.setPlaceholder(new Label("Ab 2 Schiffen mit unterschiedlicher Fläche."));
    }

    private void fuelleRobust() {
        List<Schritt> s = new ArrayList<>();

        if (ergebnis == null) {
            robustTabelle.getItems().clear();
            paarTabelle.getItems().clear();
        } else {
            RegressionRechenweg.Robust r = ergebnis.robust();
            int n = ergebnis.n();
            int paare = r.paare().size();

            robustTabelle.getItems().setAll(r.zeilen());
            paarTabelle.getItems().setAll(r.paare());

            // Median-Quote
            if (r.k() != null) {
                s.add(new Schritt("k (Median-Quote)", "Median der Spalte „Quote y / x“",
                        "Median der " + n + " Quoten",
                        g(r.k()) + " Brennstellen pro m²", "=MEDIAN(Quote)"));
            }

            // Theil-Sen
            s.add(new Schritt("b_TS (Theil-Sen-Steigung)",
                    "Median aller Paar-Steigungen",
                    "Median der " + paare + " Steigungen aus Abschnitt 7",
                    g(r.bTs()) + " Brennstellen pro m²", "=MEDIAN(Steigung)"));
            s.add(new Schritt("a_TS (Theil-Sen-Fixanteil)", "Median(y − b_TS · x)",
                    "Median der Spalte „y − b_TS · x“",
                    z(r.aTs()) + " Brennstellen", "=MEDIAN(y − b_TS·x)"));

            // Spearman
            String rhoWeg = r.rangGleichstand()
                    ? "Pearson-r der Spalten „Rang x“ und „Rang y“ (gleiche Werte → mittlerer Rang)"
                    : "1 − 6 × " + z(r.summeD2()) + " / (" + n + " × (" + n + "² − 1))";
            s.add(new Schritt("ρ (Spearman)",
                    r.rangGleichstand() ? "Pearson-r der Ränge" : "1 − 6 · Σd² / (n · (n² − 1))",
                    rhoWeg, g(r.rho()),
                    "=KORREL(RANG.MITTELW(x;x;1);RANG.MITTELW(y;y;1))"));
            s.add(new Schritt("Vergleich r und ρ", "r (Pearson) vs. ρ (Spearman)",
                    g(ergebnis.r()) + " vs. " + g(r.rho()),
                    deutungRhoR(ergebnis.r(), r.rho()), ""));
        }

        passeHoeheAn(robustTabelle, 0);
        passeHoeheAn(paarTabelle, MAX_PAARE_SICHTBAR);

        robustSchritte.getItems().setAll(s);
        passeHoeheAn(robustSchritte, 0);
    }

    private static String deutungRhoR(double r, double rho) {
        double diff = Math.abs(r - rho);
        if (diff < 0.1) {
            return "ähnlich (Unterschied unter 0,1)";
        }
        if (Math.abs(r) > Math.abs(rho)) {
            return "r deutlich größer: extreme Werte ziehen r nach oben";
        }
        return "ρ deutlich größer: Ausreißer drücken r nach unten";
    }

    // ------------------------------------------------------------------
    // 9. Leave-one-out für alle Methoden
    // ------------------------------------------------------------------

    private void baueLooTabelle() {
        looTabelle.getColumns().setAll(List.of(
                textSpalte("Schiff (weggelassen)", LooVergleich::schiff, 180),
                zahlSpalte("Fläche (m²)", LooVergleich::x, ZAHL),
                zahlSpalte("Ist-Brennstellen", LooVergleich::ist, ZAHL),
                zahlSpalte("Prognose normal", LooVergleich::prognoseNormal, ZAHL),
                zahlSpalte("Fehler % normal", LooVergleich::fehlerNormal, ZAHL),
                zahlSpalte("Prognose Theil-Sen", LooVergleich::prognoseTs, ZAHL),
                zahlSpalte("Fehler % Theil-Sen", LooVergleich::fehlerTs, ZAHL),
                zahlSpalte("Prognose Median-Quote", LooVergleich::prognoseQuote, ZAHL),
                zahlSpalte("Fehler % Median-Quote", LooVergleich::fehlerQuote, ZAHL)
        ));
        looTabelle.setPlaceholder(new Label("Ab 3 Schiffen verfügbar."));
    }

    private void fuelleLoo() {
        List<LooVergleich> zeilen = new ArrayList<>();

        if (ergebnis != null && ergebnis.n() >= 3) {
            List<RegressionRechenweg.Methode> m = ergebnis.methoden();
            for (int i = 0; i < ergebnis.n(); i++) {
                RegressionRechenweg.LooZeile normal = m.get(0).loo().get(i);
                RegressionRechenweg.LooZeile ts = m.get(1).loo().get(i);
                RegressionRechenweg.LooZeile quote = m.size() > 2 ? m.get(2).loo().get(i) : null;

                zeilen.add(new LooVergleich(
                        normal.punkt().name(), normal.punkt().x(), normal.punkt().y(),
                        normal.prognose(), normal.abweichungProzent(),
                        ts.prognose(), ts.abweichungProzent(),
                        quote == null ? null : quote.prognose(),
                        quote == null ? null : quote.abweichungProzent()));
            }
        }

        looTabelle.getItems().setAll(zeilen);
        passeHoeheAn(looTabelle, 0);
    }

    // ------------------------------------------------------------------
    // Export
    // ------------------------------------------------------------------

    /** Kopiert Schiff, Quelle, x und y als Tabulator-Text für Excel. */
    private void fuerExcelKopieren() {
        StringBuilder sb = new StringBuilder("Schiff\tQuelle\tFläche (m²)\tBrennstellen\n");
        for (RegressionRechenweg.Punkt p : punkte) {
            sb.append(p.name()).append('\t')
                    .append(p.quelle()).append('\t')
                    .append(EXCEL.format(p.x())).append('\t')
                    .append(EXCEL.format(p.y())).append('\n');
        }

        ClipboardContent inhalt = new ClipboardContent();
        inhalt.putString(sb.toString());
        Clipboard.getSystemClipboard().setContent(inhalt);
    }

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

    private static Label abschnitt(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-padding: 6 0 0 0;");
        return label;
    }

    private static <T> TableRow<T> fettWenn(Function<T, Boolean> bedingung) {
        return new TableRow<>() {
            @Override
            protected void updateItem(T item, boolean empty) {
                super.updateItem(item, empty);
                setStyle(!empty && item != null && bedingung.apply(item)
                        ? "-fx-font-weight: bold;"
                        : "");
            }
        };
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
            String titel, Function<T, Double> wert, DecimalFormat format) {
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
        spalte.setPrefWidth(Math.max(125, titel.length() * 9.5));
        spalte.setSortable(false);
        return spalte;
    }

    /**
     * Tabelle so hoch wie ihr Inhalt. Mit maxZeilen > 0 wird die Höhe
     * begrenzt, der Rest ist per Scrollen erreichbar.
     */
    private static void passeHoeheAn(TableView<?> tabelle, int maxZeilen) {
        double zeile = 28;
        tabelle.setFixedCellSize(zeile);
        int anzahl = Math.max(1, tabelle.getItems().size());
        if (maxZeilen > 0) {
            anzahl = Math.min(anzahl, maxZeilen);
        }
        // Kopfzeile + Zeilen + Platz für eine waagerechte Scrollleiste
        double hoehe = zeile * anzahl + 32 + 30;
        tabelle.setPrefHeight(hoehe);
        tabelle.setMinHeight(hoehe);
        tabelle.setMaxHeight(hoehe);
    }

    /** Deutsche Eingabe: "3.600" -> 3600, "3600,5" -> 3600.5 */
    private static Double parseZahl(String text) {
        String s = text.replace(" ", "");
        if (s.contains(",")) {
            s = s.replace(".", "").replace(',', '.');
        } else if (s.matches("[1-9]\\d{0,2}(\\.\\d{3})+")) {
            s = s.replace(".", "");
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static DecimalFormat format(String muster) {
        return new DecimalFormat(muster, DecimalFormatSymbols.getInstance(Locale.GERMANY));
    }

    private static String z(double wert) {
        return ZAHL.format(wert);
    }

    private static String g(double wert) {
        return GENAU.format(wert);
    }
}