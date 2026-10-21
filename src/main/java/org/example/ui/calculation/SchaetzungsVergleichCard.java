package org.example.ui.calculation;

import org.example.calculation.SchaetzungsVergleich;
import org.example.model.MatrixProjekt;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.concurrent.Task;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.function.Function;

/** Zusatzvergleich: Rechenlauf wird ausdrücklich gestartet und läuft im Hintergrund. */
public class SchaetzungsVergleichCard extends VBox {
    private final Button berechnen = new Button("Alle Varianten vergleichen");
    private final Button abbrechen = new Button("Abbrechen");
    private final ProgressBar fortschritt = new ProgressBar();
    private final Label status = new Label();
    private final Label referenzen = new Label();
    private final Label typReferenzen = new Label();
    private final boolean mitSchiffstyp;
    private final ComboBox<String> zielKategorie = new ComboBox<>();
    private final ComboBox<String> zielTyp = new ComboBox<>();
    private final ComboBox<Integer> groessenGrenze = new ComboBox<>();
    private List<MatrixProjekt> projekte = List.of();
    private Map<String, Double> eingaben = Map.of();
    private SchaetzungsVergleich.Basis basis = SchaetzungsVergleich.Basis.IST_SONST_SOLL;
    private boolean setztOptionen;
    private final Label detailTitel = new Label("Prüffälle: Eine Variante in der Tabelle auswählen.");
    private final TableView<SchaetzungsVergleich.Zeile> tabelle = new TableView<>();
    private final TableView<SchaetzungsVergleich.Testfall> details = new TableView<>();
    private final DecimalFormat zahl = new DecimalFormat("#,##0.##", DecimalFormatSymbols.getInstance(Locale.GERMANY));
    private SchaetzungsVergleich.Anfrage anfrage;
    private SchaetzungsVergleich.Ergebnis ergebnis;
    private Task<SchaetzungsVergleich.Ergebnis> aktiv;

    public SchaetzungsVergleichCard() {
        this(false);
    }

    /** Zwei unabhängige Ansichten verwenden dieselben geprüften Rechenregeln. */
    public SchaetzungsVergleichCard(boolean mitSchiffstyp) {
        this.mitSchiffstyp = mitSchiffstyp;
        setSpacing(10);
        setMinWidth(0);
        Label intro = hint("Zusätzlicher Vergleich: Modell, 1 oder genau 3 ähnliche Schiffe und Mischungen mit "
                + "25 %, 50 % oder 75 % Modellanteil. Die bisherige Schätzung bleibt oben erhalten.");
        if (mitSchiffstyp) intro.setText("Vergleich mit Schiffstyp: Zielkategorie, Typ und Größenabweichung festlegen. "
                + "Die 18 Varianten werden auf denselben hier prüfbaren Schiffen verglichen. "
                + "Der separate Methodenvergleich darüber bleibt erhalten.");
        zielKategorie.setId("methodenvergleich-kategorie");
        zielTyp.setId("methodenvergleich-typ");
        groessenGrenze.setId("methodenvergleich-groessengrenze");
        zielKategorie.setPromptText("Kategorie des neuen Schiffs");
        zielTyp.setPromptText("Schiffstyp (optional)");
        zielKategorie.setPrefWidth(230); zielTyp.setPrefWidth(230);
        zielTyp.setPlaceholder(new Label("Keine bekannten Typen in dieser Kategorie."));
        groessenGrenze.getItems().setAll(10, 25, 50); groessenGrenze.setValue(25);
        groessenGrenze.setPrefWidth(90);
        Label kategorieLabel = new Label("Kategorie des neuen Schiffs"); kategorieLabel.setLabelFor(zielKategorie);
        Label typLabel = new Label("Schiffstyp des neuen Schiffs"); typLabel.setLabelFor(zielTyp);
        HBox ziel = new HBox(10, new VBox(4, kategorieLabel, zielKategorie), new VBox(4, typLabel, zielTyp));
        HBox grenze = new HBox(10, new Label("Max. Größenabweichung je Eingabe [%]:"), groessenGrenze);
        grenze.setAlignment(Pos.CENTER_LEFT);
        Label typErklaerung = hint("Ab drei passenden Schiffen desselben Typs: drei verwenden. Bei einem oder zwei: "
                + "das nächste verwenden. Sonst genau drei passende Schiffe derselben Kategorie. "
                + "Fehlen sie, bleibt die Typregel leer. Unbekannte Typen zählen nicht als Übereinstimmung. "
                + "25 % ist eine Testannahme, keine bestätigte Grenze. Die Typregel begrenzt nur die Referenzen; "
                + "das Modell verwendet weiterhin den oben gefilterten Datenbestand. Bei aktivierter Typregel "
                + "werden alle Fehler auf den dann gemeinsam prüfbaren Fällen berechnet; die Fallzahl kann sinken. "
                + "Vergleiche deshalb die Zeilen innerhalb desselben Laufs.");
        VBox typOptionen = new VBox(8, ziel, grenze, typErklaerung);
        zielKategorie.valueProperty().addListener((o,a,b) -> {
            if (!setztOptionen) { typenAktualisieren(); anfrageAktualisieren(); }
        });
        zielTyp.valueProperty().addListener((o,a,b) -> { if (!setztOptionen) anfrageAktualisieren(); });
        groessenGrenze.valueProperty().addListener((o,a,b) -> anfrageAktualisieren());
        berechnen.getStyleClass().add("primary-button");
        berechnen.setId("methodenvergleich-start");
        berechnen.setOnAction(e -> starten());
        abbrechen.setOnAction(e -> { abbrechen(); status.setText("Vergleich abgebrochen."); });
        HBox aktionen = new HBox(10, berechnen, abbrechen, fortschritt);
        aktionen.setAlignment(Pos.CENTER_LEFT);
        fortschritt.setPrefWidth(130);
        status.setWrapText(true); referenzen.setWrapText(true); detailTitel.setWrapText(true); typReferenzen.setWrapText(true);
        tabelle.setId("methodenvergleich-tabelle");
        tabelle.setMinWidth(0);
        tabelle.setPrefHeight(480); tabelle.setMinHeight(300);
        text("Variante", z -> z.variante().name(), 340);
        nummer("Modell % (aktuell)", z -> Double.isFinite(z.variante().modellGewicht()) ? z.variante().modellGewicht()*100 : null, 135);
        nummer("Vergleich % (aktuell)", z -> Double.isFinite(z.variante().modellGewicht()) ? (1-z.variante().modellGewicht())*100 : null, 145);
        nummer("Neues Schiff: Brennstellen", SchaetzungsVergleich.Zeile::schaetzung, 185);
        nummer("MAE [Brennstellen]", z -> z.fehler().mae(), 150);
        nummer("MAPE [%]", z -> z.fehler().mape(), 100);
        nummer("MdAPE [%]", z -> z.fehler().mdape(), 105);
        nummer("Max. Fehler [Brennstellen]", z -> z.fehler().maxAbsolut(), 185);
        nummer("Max. Fehler [%]", z -> z.fehler().maxProzent(), 130);
        text("Prüfumfang", z -> z.fehler().nAbsolut() + " absolut / " + z.fehler().nProzent() + " prozentual", 180);
        text("Hinweis", SchaetzungsVergleich.Zeile::hinweis, 410);
        tabelle.setPlaceholder(new Label("Werte oben eingeben und Vergleich starten."));
        tabelle.getSelectionModel().selectedItemProperty().addListener((o, old, row) -> zeigeDetails(row));
        details.setPrefHeight(250); details.setMinHeight(180);
        details.setMinWidth(0);
        details.setPlaceholder(new Label("Noch keine Prüffälle vorhanden."));
        Label erklaerung = hint("Fehlerwerte verwenden dieselben gemeinsamen äußeren Prüffälle. "
                + "Jedes geprüfte Projekt wird vorher vollständig entfernt. Methodenauswahl und Modellgewichte "
                + "werden ohne dieses Projekt neu berechnet. Die Auto-Zeile prüft zusätzlich die Auswahl der "
                + "Variante in einer inneren Prüfung; ihre Gewichtung kann deshalb je Prüfschiff wechseln. "
                + "Eine nachträglich von Hand gewählte Tabellenzeile hat noch keine unabhängige Bestätigung dieser Auswahl. "
                + "Ähnlichkeit ist keine Trefferwahrscheinlichkeit. Fehlende oder negative Prognosen werden nicht als 0 gewertet.");
        Label voraussetzungen = hint("Vergleichsschiffe müssen alle eingegebenen Bezugsgrößen und einen gültigen "
                + "Brennstellenwert gemäß der gewählten Soll-/Ist-Basis besitzen. Für die drei-Schiffe-Verfahren werden "
                + "genau drei Referenzen benötigt. „Hochgerechnet“ verwendet wie bisher den Mittelwert der Größenverhältnisse; "
                + "das ist eine proportionale Näherung. Kabel-Ist als Eingabe gehört zur nachträglichen Analyse.");
        getChildren().add(intro);
        if (mitSchiffstyp) getChildren().add(typOptionen);
        getChildren().addAll(aktionen, status, referenzen);
        if (mitSchiffstyp) getChildren().add(typReferenzen);
        getChildren().addAll(tabelle, detailTitel, details, erklaerung, voraussetzungen);
        beschaeftigt(false);
        berechnen.setDisable(true);
    }

    public void setKontext(List<MatrixProjekt> projekte, Map<String, Double> eingaben,
                           SchaetzungsVergleich.Basis basis) {
        this.projekte = List.copyOf(projekte);
        this.eingaben = Map.copyOf(eingaben);
        this.basis = basis;
        setztOptionen = true;
        try {
            String vorher = zielKategorie.getValue();
            zielKategorie.getItems().setAll(projekte.stream().map(MatrixProjekt::kategorie)
                    .filter(SchaetzungsVergleich::bekannt).distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList());
            zielKategorie.setValue(zielKategorie.getItems().contains(vorher) ? vorher
                    : zielKategorie.getItems().size() == 1 ? zielKategorie.getItems().get(0) : null);
        } finally { setztOptionen = false; }
        typenAktualisieren();
        anfrageAktualisieren();
    }

    private void typenAktualisieren() {
        setztOptionen = true;
        try {
            String vorher = zielTyp.getValue(), kategorie = zielKategorie.getValue();
            List<String> typen = new ArrayList<>();
            typen.add("Nicht bekannt");
            projekte.stream().filter(p -> Objects.equals(kategorie, p.kategorie()))
                    .map(MatrixProjekt::schiffstyp).filter(SchaetzungsVergleich::bekannt)
                    .distinct().sorted(String.CASE_INSENSITIVE_ORDER).forEach(typen::add);
            zielTyp.getItems().setAll(typen);
            zielTyp.setValue(typen.contains(vorher) ? vorher : "Nicht bekannt");
        } finally { setztOptionen = false; }
    }

    private void anfrageAktualisieren() {
        if (setztOptionen) return;
        abbrechen();
        anfrage = null; ergebnis = null;
        tabelle.getItems().clear(); details.getItems().clear();
        detailTitel.setText("Prüffälle: Eine Variante in der Tabelle auswählen.");
        referenzen.setText("");
        typReferenzen.setText("");
        if (eingaben.isEmpty()) {
            status.setText("Mindestens eine ausgewählte Bezugsgröße mit einem gültigen Wert oben eingeben.");
            berechnen.setDisable(true); return;
        }
        try {
            SchaetzungsVergleich.TypOption option = mitSchiffstyp
                    ? new SchaetzungsVergleich.TypOption(true, zielKategorie.getValue(), zielTyp.getValue(),
                    groessenGrenze.getValue() / 100.0) : SchaetzungsVergleich.TypOption.aus();
            anfrage = new SchaetzungsVergleich.Anfrage(projekte, eingaben, basis, option);
            status.setText("Werte oder Filter geändert. Vergleich mit den aktuellen Eingaben neu berechnen.");
            berechnen.setDisable(false);
        } catch (RuntimeException e) {
            status.setText(e.getMessage()); berechnen.setDisable(true);
        }
    }

    private void starten() {
        if (anfrage == null || aktiv != null) return;
        SchaetzungsVergleich.Anfrage snapshot = anfrage;
        Task<SchaetzungsVergleich.Ergebnis> task = new Task<>() {
            @Override protected SchaetzungsVergleich.Ergebnis call() {
                return SchaetzungsVergleich.vergleichen(snapshot, this::isCancelled,
                        p -> updateProgress(p.fertig(), Math.max(1, p.gesamt())));
            }
        };
        aktiv = task; beschaeftigt(true);
        fortschritt.progressProperty().bind(task.progressProperty());
        status.setText("Äußere und innere Prüfung laufen. Die Oberfläche bleibt bedienbar.");
        task.setOnSucceeded(e -> {
            if (aktiv != task) return;
            aktiv = null; beschaeftigt(false);
            ergebnis = task.getValue(); anzeigen();
        });
        task.setOnFailed(e -> {
            if (aktiv != task) return;
            aktiv = null; beschaeftigt(false);
            task.getException().printStackTrace();
            status.setText("Vergleich fehlgeschlagen: " + Objects.toString(task.getException().getMessage(), "Bitte Konsole prüfen."));
        });
        task.setOnCancelled(e -> { if (aktiv == task) { aktiv = null; beschaeftigt(false); status.setText("Vergleich abgebrochen."); } });
        Thread thread = new Thread(task, "brennstellen-methodenvergleich");
        thread.setDaemon(true); thread.start();
    }

    private void anzeigen() {
        tabelle.getItems().setAll(ergebnis.zeilen());
        status.setText("Gemeinsam geprüft: " + ergebnis.gemeinsamGeprueft() + " von "
                + ergebnis.mitAllenBezuegen() + " vollständigen Projekten mit Zielwert. "
                + "Geladen: " + ergebnis.geladen() + ", mit Zielwert: " + ergebnis.mitZielwert() + ". "
                + (ergebnis.automatischePruefung() ? "Auto-Zeile ist auf denselben Fällen verschachtelt geprüft. "
                : "Für die verschachtelte Auto-Prüfung fehlen ausreichend gemeinsame Fälle. ")
                + (ergebnis.gemeinsamGeprueft() == 0
                ? "Noch kein gemeinsamer Fehlervergleich möglich. Für die äußere Prüfung mit drei Referenzen "
                + "sind mindestens vier vollständige Projekte nötig, für die innere Prüfung mindestens fünf; "
                + "auch das Modell muss berechenbar sein. Weniger Bezugsgrößen auswählen oder fehlende Daten ergänzen. "
                + "Aktuelle Schätzungen können trotzdem verfügbar sein. "
                + (anfrage.typOption().aktiv() ? "Den bisherigen Vergleich separat unter Zusätzlicher Methodenvergleich öffnen." : "")
                : ergebnis.gemeinsamGeprueft() < 6 ? "Kleine Datenbasis: Ergebnisse nur als Orientierung verwenden." : ""));
        StringBuilder refs = new StringBuilder("Referenzen für das neue Schiff: ");
        for (SchaetzungsVergleich.Referenz r : ergebnis.referenzen()) {
            if (refs.charAt(refs.length()-1) != ' ') refs.append("; ");
            refs.append(r.schiff()).append(" [ID ").append(r.id()).append("]: ")
                    .append(zahl.format(r.brennstellen())).append(" ").append(r.quelle())
                    .append(" → hochgerechnet ").append(zahl.format(r.hochgerechnet()));
        }
        if (ergebnis.referenzen().isEmpty()) refs.append("keine vollständigen Referenzprojekte vorhanden.");
        referenzen.setText(refs.toString());
        if (anfrage.typOption().aktiv()) {
            StringBuilder typText = new StringBuilder("Typregel für das neue Schiff: ")
                    .append(ergebnis.typReferenzen().hinweis()).append(" ");
            for (SchaetzungsVergleich.Referenz r : ergebnis.typReferenzen().referenzen()) {
                typText.append(r.schiff()).append(" [ID ").append(r.id()).append(", ")
                        .append(Objects.toString(r.schiffstyp(), "Typ unbekannt")).append("]: ")
                        .append(zahl.format(r.brennstellen())).append(" ").append(r.quelle())
                        .append(" → ").append(zahl.format(r.hochgerechnet())).append("; ");
            }
            typReferenzen.setText(typText.toString());
        }
        tabelle.getSelectionModel().selectFirst();
    }

    private void zeigeDetails(SchaetzungsVergleich.Zeile row) {
        details.getColumns().clear();
        if (row == null || ergebnis == null) { details.getItems().clear(); return; }
        String id = row.variante().id();
        detailTitel.setText("Äußere Prüfung: " + row.variante().name() + " / Modell "
                + (Double.isFinite(row.variante().modellGewicht()) ? zahl.format(row.variante().modellGewicht()*100) + " %" : "nicht verfügbar")
                + (id.equals(SchaetzungsVergleich.AUTO_ID) ? " (oben aktuelles Gewicht; je Testfall neu gewählt)" : ""));
        detailText("ID", f -> Integer.toString(f.id()), 65);
        detailText("Schiff", SchaetzungsVergleich.Testfall::schiff, 190);
        detailText("Kategorie", f -> Objects.toString(f.kategorie(), "–"), 175);
        detailText("Schiffstyp", f -> Objects.toString(f.schiffstyp(), "–"), 175);
        detailText("Referenz [Brennstellen]", f -> zahl.format(f.referenz()), 175);
        detailText("Prognose", f -> format(f.prognosen().get(id)), 120);
        detailText("Absoluter Fehler", f -> f.prognosen().get(id) == null ? "–"
                : zahl.format(Math.abs(f.prognosen().get(id)-f.referenz())), 130);
        detailText("Im gemeinsamen Vergleich", f -> f.gemeinsamGeprueft() ? "Ja" : "Nein", 190);
        detailText("Auto: gewählte Variante", f -> Objects.toString(f.automatischGewaehlteId(), "–"), 230);
        if (anfrage.typOption().aktiv()) detailText("Typregel im Prüffall", SchaetzungsVergleich.Testfall::typHinweis, 480);
        details.getItems().setAll(ergebnis.testfaelle());
    }

    private void abbrechen() {
        Task<?> old = aktiv; aktiv = null;
        if (old != null) old.cancel(true);
        beschaeftigt(false);
    }
    private void beschaeftigt(boolean running) {
        fortschritt.progressProperty().unbind();
        if (!running) fortschritt.setProgress(0);
        fortschritt.setVisible(running); fortschritt.setManaged(running);
        abbrechen.setVisible(running); abbrechen.setManaged(running);
        berechnen.setDisable(running || anfrage == null);
    }
    private Label hint(String text) { Label l = new Label(text); l.setWrapText(true); l.getStyleClass().add("hint"); return l; }
    private String format(Double value) { return value == null ? "–" : zahl.format(value); }
    private void text(String title, Function<SchaetzungsVergleich.Zeile,String> value, double width) {
        TableColumn<SchaetzungsVergleich.Zeile,String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty); setText(empty ? null : item);
                setTooltip(empty || item == null || item.isBlank() ? null : new Tooltip(item));
            }
        });
        col.setPrefWidth(width); tabelle.getColumns().add(col);
    }
    private void nummer(String title, Function<SchaetzungsVergleich.Zeile,Double> value, double width) {
        TableColumn<SchaetzungsVergleich.Zeile,Double> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleObjectProperty<>(value.apply(c.getValue())));
        col.setCellFactory(c -> new TableCell<>() {
            @Override protected void updateItem(Double item, boolean empty) {
                super.updateItem(item, empty); setText(empty ? null : format(item)); setAlignment(Pos.CENTER_RIGHT);
            }
        });
        col.setPrefWidth(width); tabelle.getColumns().add(col);
    }
    private void detailText(String title, Function<SchaetzungsVergleich.Testfall,String> value, double width) {
        TableColumn<SchaetzungsVergleich.Testfall,String> col = new TableColumn<>(title);
        col.setCellValueFactory(c -> new SimpleStringProperty(value.apply(c.getValue())));
        col.setPrefWidth(width); details.getColumns().add(col);
    }
}
