package org.example;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.StringConverter;

/**
 * Korrelationsmatrix über alle Zahlenwerte der Projekte (Spearman ρ).
 *
 * - Zeile = y, Spalte = x
 * - In jeder Zelle ρ und n (Anzahl Schiffe mit beiden Werten)
 * - Grau = zu wenige Schiffe für eine Aussage
 * - Klick auf eine Zelle zeigt Diagramm, Kennzahlen und Daten des Paars
 *
 * Eigenständige Karte: lädt ihre Daten selbst über MatrixRepository
 * und verändert die übrige Kalkulation nicht.
 */
public class KorrelationsMatrixCard extends VBox {

    /** Ab so vielen Schiffen mit beiden Werten wird ρ farbig angezeigt. */
    private static final int MIN_N = 4;
    private static final int TOP_ANZAHL = 12;

    private static final DecimalFormat ZAHL = format("#,##0.##");
    private static final DecimalFormat RHO = format("0.00");
    private static final DecimalFormat GENAU = format("#,##0.####");

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

    /**
     * Ein Merkmal der Matrix. Für Stammdaten sind sollSchluessel und
     * istSchluessel gleich (kein Soll/Ist).
     */
    private record Merkmal(
            String name,
            String sollSchluessel,
            String istSchluessel,
            boolean standard
    ) {
        boolean hatSollIst() {
            return !sollSchluessel.equals(istSchluessel);
        }
    }

    private static final List<Merkmal> MERKMALE = List.of(
            stamm("Länge (m)", "length_m", true),
            stamm("Breite (m)", "width_m", true),
            stamm("Volumen (GT)", "volume_gt", true),
            stamm("Fläche (m²)", "area_sqm", true),
            stamm("Kabel berechnet (km)", "cable_calculated_km", true),
            stamm("Kabel Angebot (km)", "cable_offer_km", false),
            stamm("Kabel Ist (km)", "cable_actual_km", true),
            kennzahl("Brennstellen", "LIGHTING_POINTS", true),
            kennzahl("IOs", "IO_COUNT", true),
            kennzahl("Kabeldatensätze", "CABLE_RECORDS", true),
            kennzahl("Brandmelder", "FIRE_DETECTORS", false),
            kennzahl("Min/Brennstelle", "LIGHTING_POINT_TIME", true),
            kennzahl("Min/IO Engineering", "IO_ENGINEERING_TIME", true),
            kennzahl("Min/IO Abgabe", "IO_DELIVERY_TIME", false),
            kennzahl("Kabel Engineering-Zeit", "CABLE_ENGINEERING_TIME", false),
            kennzahl("Kabel Verlegezeit", "CABLE_ROUTING_TIME", false)
    );

    private static Merkmal stamm(String name, String spalte, boolean standard) {
        return new Merkmal(name, spalte, spalte, standard);
    }

    private static Merkmal kennzahl(String name, String code, boolean standard) {
        return new Merkmal(name, code + ":soll", code + ":ist", standard);
    }

    /** Ergebnis für ein Paar (y aus x). */
    private record Paar(
            Merkmal y,
            Merkmal x,
            List<RegressionRechenweg.Punkt> punkte,
            RegressionRechenweg.Ergebnis ergebnis
    ) {
        int n() {
            return punkte.size();
        }

        Double rho() {
            return ergebnis == null ? null : ergebnis.robust().rho();
        }

        Double r() {
            return ergebnis == null ? null : ergebnis.r();
        }

        boolean aussagekraeftig() {
            return ergebnis != null && n() >= MIN_N;
        }
    }

    private record TopZeile(
            String paar,
            Double rho,
            Double r,
            int n,
            String bewertung,
            Paar quelle
    ) {
    }

    private record DetailZeile(String schiff, String quelle, double x, double y) {
    }

    private final MatrixRepository repository = new MatrixRepository();
    private List<MatrixProjekt> projekte = List.of();

    private final ComboBox<Basis> basisAuswahl = new ComboBox<>();
    private final Map<Merkmal, CheckBox> auswahl = new LinkedHashMap<>();
    private final Label info = new Label();

    private final GridPane matrix = new GridPane();
    private final ScrollPane matrixScroll = new ScrollPane(matrix);
    private final TableView<TopZeile> topTabelle = new TableView<>();

    private final Label detailTitel = new Label();
    private final Label detailWerte = new Label();
    private final NumberAxis xAchse = new NumberAxis();
    private final NumberAxis yAchse = new NumberAxis();
    private final LineChart<Number, Number> diagramm = new LineChart<>(xAchse, yAchse);
    private final TableView<DetailZeile> detailTabelle = new TableView<>();
    private final VBox detailBereich = new VBox(10);

    private Merkmal gewaehltY;
    private Merkmal gewaehltX;

    public KorrelationsMatrixCard() {
        setSpacing(12);

        basisAuswahl.getItems().setAll(Basis.values());
        basisAuswahl.setValue(Basis.IST_SONST_SOLL);
        basisAuswahl.setOnAction(event -> neuRechnen());

        HBox kopf = new HBox(10, new Label("Kennzahlen-Wert:"), basisAuswahl);
        kopf.setAlignment(Pos.CENTER_LEFT);

        FlowPane merkmalAuswahl = new FlowPane(14, 6);
        for (Merkmal m : MERKMALE) {
            CheckBox box = new CheckBox(m.name());
            box.setSelected(m.standard());
            box.setOnAction(event -> neuRechnen());
            auswahl.put(m, box);
            merkmalAuswahl.getChildren().add(box);
        }

        info.setWrapText(true);
        info.getStyleClass().add("hint");

        matrix.setHgap(2);
        matrix.setVgap(2);
        // Nur waagerecht scrollen; die Höhe wird in zeichneMatrix() fest gesetzt.
        matrixScroll.setFitToHeight(false);
        matrixScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        matrixScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        matrixScroll.setStyle("-fx-background-color: transparent;");

        // Mausrad (senkrecht) an die Seite weitergeben, damit die Seite
        // auch scrollt, wenn der Mauszeiger über der Matrix steht.
        matrixScroll.addEventFilter(ScrollEvent.SCROLL, event -> {
            if (event.getDeltaX() == 0 && event.getDeltaY() != 0) {
                event.consume();
                fireEvent(event.copyFor(this, this));
            }
        });

        Label legende = new Label("Blau = gleichläufig (größer x → größer y), "
                + "Rot = gegenläufig, je kräftiger desto stärker. Grau = weniger als "
                + MIN_N + " Schiffe mit beiden Werten, keine Aussage. "
                + "Zeile = y, Spalte = x. Klick auf eine Zelle zeigt die Details.");
        legende.setWrapText(true);
        legende.getStyleClass().add("hint");

        baueTopTabelle();
        baueDetail();

        getChildren().addAll(
                kopf,
                abschnitt("Merkmale"),
                merkmalAuswahl,
                info,
                abschnitt("Matrix (Spearman ρ, darunter n)"),
                matrixScroll,
                legende,
                abschnitt("Stärkste Zusammenhänge"),
                topTabelle,
                detailBereich
        );
    }

    /**
     * Daten passend zu den Filtern der Kalkulation neu laden.
     *
     * @param kategorie   null = alle Kategorien
     * @param schiffstyp  null = alle Schiffstypen
     * @param nurRelevant nur auswertungsrelevante Projekte
     */
    public void setFilter(String kategorie, String schiffstyp, boolean nurRelevant) {
        try {
            projekte = repository.lade(kategorie, schiffstyp, nurRelevant);
        } catch (RuntimeException e) {
            projekte = List.of();
            info.setText("Fehler beim Laden: " + e.getMessage());
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
        List<Merkmal> aktiv = MERKMALE.stream()
                .filter(m -> auswahl.get(m).isSelected())
                .toList();

        info.setText(projekte.size() + " Projekte geladen, "
                + aktiv.size() + " Merkmale ausgewählt. "
                + "Werte: " + basisAuswahl.getValue() + " (gilt für Kennzahlen; "
                + "Stammdaten wie Länge oder Fläche haben kein Soll/Ist).");

        Map<String, Paar> paare = new LinkedHashMap<>();
        for (Merkmal y : aktiv) {
            for (Merkmal x : aktiv) {
                if (x != y) {
                    paare.put(schluessel(y, x), rechnePaar(y, x));
                }
            }
        }

        zeichneMatrix(aktiv, paare);
        fuelleTop(aktiv, paare);

        if (gewaehltY != null && aktiv.contains(gewaehltY) && aktiv.contains(gewaehltX)) {
            zeigeDetail(rechnePaar(gewaehltY, gewaehltX));
        } else {
            gewaehltY = null;
            gewaehltX = null;
            detailBereich.getChildren().clear();
        }
    }

    private Paar rechnePaar(Merkmal y, Merkmal x) {
        List<RegressionRechenweg.Punkt> punkte = new ArrayList<>();

        for (MatrixProjekt p : projekte) {
            Wert wx = wert(p, x);
            Wert wy = wert(p, y);
            if (wx != null && wy != null) {
                String quelle = wx.quelle().isEmpty() ? wy.quelle()
                        : wy.quelle().isEmpty() ? wx.quelle()
                        : wx.quelle() + "/" + wy.quelle();
                punkte.add(new RegressionRechenweg.Punkt(
                        p.schiff(), quelle, wx.zahl(), wy.zahl()));
            }
        }

        return new Paar(y, x, punkte, RegressionRechenweg.berechne(punkte));
    }

    private record Wert(double zahl, String quelle) {
    }

    private Wert wert(MatrixProjekt p, Merkmal m) {
        if (!m.hatSollIst()) {
            Double w = p.wert(m.istSchluessel());
            return w == null ? null : new Wert(w, "");
        }

        Double ist = p.wert(m.istSchluessel());
        Double soll = p.wert(m.sollSchluessel());

        return switch (basisAuswahl.getValue()) {
            case IST -> ist == null ? null : new Wert(ist, "Ist");
            case SOLL -> soll == null ? null : new Wert(soll, "Soll");
            case IST_SONST_SOLL -> ist != null ? new Wert(ist, "Ist")
                    : soll != null ? new Wert(soll, "Soll") : null;
        };
    }

    private static String schluessel(Merkmal y, Merkmal x) {
        return y.name() + "|" + x.name();
    }

    // ------------------------------------------------------------------
    // Matrix
    // ------------------------------------------------------------------

    private void zeichneMatrix(List<Merkmal> aktiv, Map<String, Paar> paare) {
        matrix.getChildren().clear();

        Label ecke = zelle("y ↓   x →", 150, 52);
        ecke.setStyle("-fx-font-size: 11px; -fx-text-fill: #6b7280;");
        matrix.add(ecke, 0, 0);

        for (int j = 0; j < aktiv.size(); j++) {
            Label kopf = zelle(aktiv.get(j).name(), 98, 52);
            kopf.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            matrix.add(kopf, j + 1, 0);
        }

        for (int i = 0; i < aktiv.size(); i++) {
            Merkmal y = aktiv.get(i);

            Label zeilenKopf = zelle(y.name(), 150, 44);
            zeilenKopf.setAlignment(Pos.CENTER_LEFT);
            zeilenKopf.setStyle("-fx-font-weight: bold; -fx-font-size: 11px;");
            matrix.add(zeilenKopf, 0, i + 1);

            for (int j = 0; j < aktiv.size(); j++) {
                Merkmal x = aktiv.get(j);
                Label feld;

                if (x == y) {
                    feld = zelle("—", 98, 44);
                    feld.setStyle("-fx-background-color: #f3f4f6; -fx-text-fill: #9ca3af;");
                } else {
                    Paar paar = paare.get(schluessel(y, x));
                    feld = matrixZelle(paar);
                }

                matrix.add(feld, j + 1, i + 1);
            }
        }

        // Feste Höhe aus Zeilenanzahl: Kopf 52 + je Zeile 44 + Abstände
        double hoehe = 52 + aktiv.size() * 44 + (aktiv.size() + 1) * matrix.getVgap();
        matrixScroll.setPrefViewportHeight(hoehe);
        matrixScroll.setMinViewportHeight(hoehe);
    }

    private Label matrixZelle(Paar paar) {
        String text;
        if (!paar.aussagekraeftig()) {
            text = "–\nn = " + paar.n();
        } else {
            text = RHO.format(paar.rho()) + "\nn = " + paar.n();
        }

        Label feld = zelle(text, 98, 44);
        feld.setStyle(farbe(paar));
        feld.setCursor(Cursor.HAND);
        feld.setOnMouseClicked(event -> {
            gewaehltY = paar.y();
            gewaehltX = paar.x();
            zeigeDetail(paar);
        });

        String tip = paar.y().name() + " aus " + paar.x().name()
                + "\nn = " + paar.n();
        if (paar.ergebnis() != null) {
            tip += "\nρ (Spearman) = " + RHO.format(paar.rho())
                    + "\nr (Pearson) = " + RHO.format(paar.r());
        }
        Tooltip.install(feld, new Tooltip(tip));

        return feld;
    }

    private static String farbe(Paar paar) {
        if (!paar.aussagekraeftig()) {
            return "-fx-background-color: #e5e7eb; -fx-text-fill: #6b7280;"
                    + " -fx-font-size: 11px;";
        }

        double rho = paar.rho();
        double staerke = Math.min(1, Math.abs(rho));
        String rgb = rho >= 0 ? "37, 99, 235" : "220, 38, 38";
        double alpha = 0.08 + 0.82 * staerke;
        String text = alpha > 0.55 ? "white" : "#111827";

        return String.format(Locale.US,
                "-fx-background-color: rgba(%s, %.2f); -fx-text-fill: %s;"
                        + " -fx-font-size: 11px;", rgb, alpha, text);
    }

    private static Label zelle(String text, double breite, double hoehe) {
        Label label = new Label(text);
        label.setWrapText(true);
        label.setAlignment(Pos.CENTER);
        label.setStyle("-fx-text-alignment: center;");
        label.setMinSize(breite, hoehe);
        label.setPrefSize(breite, hoehe);
        label.setMaxSize(breite, hoehe);
        label.setPadding(new Insets(2, 4, 2, 4));
        return label;
    }

    // ------------------------------------------------------------------
    // Stärkste Zusammenhänge
    // ------------------------------------------------------------------

    private void baueTopTabelle() {
        topTabelle.getColumns().setAll(List.of(
                textSpalte("Zusammenhang (y aus x)", TopZeile::paar, 330),
                zahlSpalte("ρ (Spearman)", TopZeile::rho, RHO),
                zahlSpalte("r (Pearson)", TopZeile::r, RHO),
                zahlSpalte("n", z -> (double) z.n(), ZAHL),
                textSpalte("Bewertung", TopZeile::bewertung, 300)
        ));
        topTabelle.setPlaceholder(new Label("Noch keine Paare mit mindestens "
                + MIN_N + " Schiffen."));
        topTabelle.getSelectionModel().selectedItemProperty().addListener((obs, alt, neu) -> {
            if (neu != null) {
                gewaehltY = neu.quelle().y();
                gewaehltX = neu.quelle().x();
                zeigeDetail(neu.quelle());
            }
        });
    }

    private void fuelleTop(List<Merkmal> aktiv, Map<String, Paar> paare) {
        List<Paar> kandidaten = new ArrayList<>();

        // Jedes Paar nur einmal (ρ ist symmetrisch)
        for (int i = 0; i < aktiv.size(); i++) {
            for (int j = i + 1; j < aktiv.size(); j++) {
                Paar paar = paare.get(schluessel(aktiv.get(i), aktiv.get(j)));
                if (paar != null && paar.aussagekraeftig()) {
                    kandidaten.add(paar);
                }
            }
        }

        kandidaten.sort(Comparator.comparingDouble((Paar p) -> Math.abs(p.rho())).reversed());

        List<TopZeile> zeilen = kandidaten.stream()
                .limit(TOP_ANZAHL)
                .map(p -> new TopZeile(
                        p.y().name() + " aus " + p.x().name(),
                        p.rho(), p.r(), p.n(), bewertung(p), p))
                .toList();

        topTabelle.getItems().setAll(zeilen);
        passeHoeheAn(topTabelle, 0);
    }

    private static String bewertung(Paar p) {
        double a = Math.abs(p.rho());
        String staerke = a >= 0.7 ? "stark" : a >= 0.4 ? "mittel" : "schwach";
        String richtung = p.rho() >= 0 ? "gleichläufig" : "gegenläufig";
        String zusatz = p.n() < 6 ? ", wenige Schiffe" : "";
        if (Math.abs(p.r() - p.rho()) >= 0.2) {
            zusatz += ", r und ρ weichen ab: Ausreißer prüfen";
        }
        return staerke + ", " + richtung + zusatz;
    }

    // ------------------------------------------------------------------
    // Detail zu einem Paar
    // ------------------------------------------------------------------

    private void baueDetail() {
        detailTitel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        detailWerte.setWrapText(true);

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
        xAchse.setForceZeroInRange(false);
        yAchse.setForceZeroInRange(false);
        diagramm.setAnimated(false);
        diagramm.setPrefHeight(340);
        diagramm.setMaxWidth(820);

        detailTabelle.getColumns().setAll(List.of(
                textSpalte("Schiff", DetailZeile::schiff, 200),
                textSpalte("Quelle", DetailZeile::quelle, 80),
                zahlSpalte("x", DetailZeile::x, ZAHL),
                zahlSpalte("y", DetailZeile::y, ZAHL)
        ));
    }

    private void zeigeDetail(Paar paar) {
        detailBereich.getChildren().clear();

        detailTitel.setText("Detail: " + paar.y().name() + " (y) aus "
                + paar.x().name() + " (x)");

        RegressionRechenweg.Ergebnis e = paar.ergebnis();
        StringBuilder sb = new StringBuilder();
        sb.append("n = ").append(paar.n()).append(" Schiffe mit beiden Werten");

        if (e == null) {
            sb.append(". Zu wenige Schiffe oder alle x-Werte gleich, keine Rechnung möglich.");
        } else {
            sb.append("   ·   ρ (Spearman) = ").append(RHO.format(e.robust().rho()))
                    .append("   ·   r (Pearson) = ").append(RHO.format(e.r()))
                    .append("   ·   R² = ").append(RHO.format(e.r2()));
            if (e.rKrit() != null) {
                sb.append("   ·   signifikant: ").append(e.signifikant() ? "ja" : "nein");
            }
            sb.append('\n');

            for (RegressionRechenweg.Methode m : e.methoden()) {
                sb.append(m.name()).append(": y = ");
                if (m.name().startsWith("Median")) {
                    sb.append(GENAU.format(m.b())).append(" · x");
                } else {
                    sb.append(ZAHL.format(m.a()))
                            .append(m.b() < 0 ? " − " : " + ")
                            .append(GENAU.format(Math.abs(m.b()))).append(" · x");
                }
                if (m.mape() != null) {
                    sb.append("   (Prognosefehler ").append(ZAHL.format(m.mape())).append(" %)");
                }
                sb.append('\n');
            }

            RegressionRechenweg.Methode beste = e.beste();
            if (beste != null) {
                sb.append("Kleinster Prognosefehler: ").append(beste.name()).append('.');
            }
            if (paar.n() < MIN_N) {
                sb.append("\nAchtung: weniger als ").append(MIN_N)
                        .append(" Schiffe, nur als Hinweis lesen.");
            }
        }
        detailWerte.setText(sb.toString().trim());

        fuelleDiagramm(paar);

        detailTabelle.getColumns().get(2).setText("x " + paar.x().name());
        detailTabelle.getColumns().get(3).setText("y " + paar.y().name());
        detailTabelle.getItems().setAll(paar.punkte().stream()
                .map(p -> new DetailZeile(p.name(), p.quelle(), p.x(), p.y()))
                .toList());
        passeHoeheAn(detailTabelle, 12);

        detailBereich.getChildren().addAll(detailTitel, detailWerte, diagramm, detailTabelle);
    }

    private void fuelleDiagramm(Paar paar) {
        diagramm.getData().clear();
        xAchse.setLabel(paar.x().name());
        yAchse.setLabel(paar.y().name());

        XYChart.Series<Number, Number> schiffe = new XYChart.Series<>();
        schiffe.setName("Schiffe");
        for (RegressionRechenweg.Punkt p : paar.punkte()) {
            schiffe.getData().add(new XYChart.Data<>(p.x(), p.y()));
        }
        diagramm.getData().add(schiffe);

        List<XYChart.Series<Number, Number>> geraden = new ArrayList<>();
        RegressionRechenweg.Ergebnis e = paar.ergebnis();
        if (e != null) {
            for (RegressionRechenweg.Methode m : e.methoden()) {
                if (m.name().startsWith("Median")) {
                    continue;   // Übersicht: normale Regression und Theil-Sen
                }
                XYChart.Series<Number, Number> gerade = new XYChart.Series<>();
                gerade.setName(m.name());
                gerade.getData().add(new XYChart.Data<>(e.minX(), m.prognose(e.minX())));
                gerade.getData().add(new XYChart.Data<>(e.maxX(), m.prognose(e.maxX())));
                diagramm.getData().add(gerade);
                geraden.add(gerade);
            }
        }

        if (schiffe.getNode() != null) {
            schiffe.getNode().setStyle("-fx-stroke: transparent;");
        }
        for (int i = 0; i < schiffe.getData().size(); i++) {
            XYChart.Data<Number, Number> d = schiffe.getData().get(i);
            RegressionRechenweg.Punkt p = paar.punkte().get(i);
            if (d.getNode() != null) {
                Tooltip.install(d.getNode(), new Tooltip(p.name()
                        + "\n" + paar.x().name() + ": " + ZAHL.format(p.x())
                        + "\n" + paar.y().name() + ": " + ZAHL.format(p.y())));
            }
        }
        for (XYChart.Series<Number, Number> gerade : geraden) {
            for (XYChart.Data<Number, Number> d : gerade.getData()) {
                if (d.getNode() != null) {
                    d.getNode().setVisible(false);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Hilfsmethoden
    // ------------------------------------------------------------------

    private static Label abschnitt(String text) {
        Label label = new Label(text);
        label.setStyle("-fx-font-weight: bold; -fx-padding: 6 0 0 0;");
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
        spalte.setPrefWidth(Math.max(110, titel.length() * 9.5));
        spalte.setSortable(false);
        return spalte;
    }

    private static void passeHoeheAn(TableView<?> tabelle, int maxZeilen) {
        double zeile = 28;
        tabelle.setFixedCellSize(zeile);
        int anzahl = Math.max(1, tabelle.getItems().size());
        if (maxZeilen > 0) {
            anzahl = Math.min(anzahl, maxZeilen);
        }
        double hoehe = zeile * anzahl + 32 + 30;
        tabelle.setPrefHeight(hoehe);
        tabelle.setMinHeight(hoehe);
        tabelle.setMaxHeight(hoehe);
    }

    private static DecimalFormat format(String muster) {
        return new DecimalFormat(muster, DecimalFormatSymbols.getInstance(Locale.GERMANY));
    }
}