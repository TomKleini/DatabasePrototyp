package org.example.calculation;

import org.example.model.MatrixProjekt;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Zusatzvergleich ohne Änderung der bisherigen Schätzung.
 * Die äußere Prüfung lässt ein Projekt vollständig weg. Auch Regressionstyp,
 * Modellgewichte und automatische Variantenwahl sehen dessen Zielwert nicht.
 */
public final class SchaetzungsVergleich {
    private SchaetzungsVergleich() {}

    public enum Basis { IST, SOLL, IST_SONST_SOLL }
    public enum Vergleich { KEINER, EIN_SCHIFF, DREI_GLEICH, DREI_ABSTAND, SCHIFFSTYP }
    public record Variante(String id, String name, Vergleich vergleich, double modellGewicht) {}
    public record TypOption(boolean aktiv, String kategorie, String schiffstyp, double maxRelativeAbweichung) {
        public TypOption {
            if (!Double.isFinite(maxRelativeAbweichung) || maxRelativeAbweichung <= 0 || maxRelativeAbweichung > 1)
                throw new IllegalArgumentException("Die Größenabweichung muss größer als 0 und höchstens 100 % sein.");
            if (aktiv && !bekannt(kategorie)) throw new IllegalArgumentException("Bitte die Kategorie des neuen Schiffs auswählen.");
        }
        public static TypOption aus() { return new TypOption(false, null, null, .25); }
    }
    public record Anfrage(List<MatrixProjekt> projekte, Map<String, Double> eingaben, Basis basis,
                          TypOption typOption) {
        public Anfrage(List<MatrixProjekt> projekte, Map<String, Double> eingaben, Basis basis) {
            this(projekte, eingaben, basis, TypOption.aus());
        }
        public Anfrage {
            Objects.requireNonNull(basis);
            Objects.requireNonNull(typOption);
            List<MatrixProjekt> copy = new ArrayList<>();
            Set<Integer> ids = new HashSet<>();
            for (MatrixProjekt p : projekte) {
                if (!ids.add(p.id())) throw new IllegalArgumentException("Projekt-ID doppelt: " + p.id());
                copy.add(new MatrixProjekt(p.id(), p.schiff(), p.werft(),
                        Collections.unmodifiableMap(new LinkedHashMap<>(p.werte())), p.kategorie(), p.schiffstyp()));
            }
            projekte = List.copyOf(copy);
            eingaben = Collections.unmodifiableMap(new LinkedHashMap<>(eingaben));
            if (eingaben.isEmpty()) throw new IllegalArgumentException("Bitte mindestens eine Bezugsgröße eingeben.");
            for (Double value : eingaben.values()) {
                if (!positiv(value)) throw new IllegalArgumentException("Bezugsgrößen müssen endlich und größer als 0 sein.");
            }
        }
    }
    public record Zeile(Variante variante, Double schaetzung,
                        RegressionRechenweg.Fehlerkennzahlen fehler, String hinweis) {}
    public record Referenz(int id, String schiff, double abstand, double brennstellen,
                           double faktor, double hochgerechnet, String quelle,
                           String kategorie, String schiffstyp) {}
    public record TypReferenzen(List<Referenz> referenzen, String regel, String hinweis) {}
    public record Testfall(int id, String schiff, double referenz,
                           Map<String, Double> prognosen, String automatischGewaehlteId,
                           boolean gemeinsamGeprueft, String kategorie, String schiffstyp,
                           String typRegel, String typHinweis) {}
    public record Fortschritt(int fertig, int gesamt) {}
    public record Ergebnis(List<Zeile> zeilen, List<Referenz> referenzen,
                           List<Testfall> testfaelle, int geladen, int mitZielwert,
                           int mitAllenBezuegen, int gemeinsamGeprueft,
                           String automatischGewaehlteId, boolean automatischePruefung,
                           List<String> bezuege, TypReferenzen typReferenzen) {}

    public static final String AUTO_ID = "auto";
    private static final int MIN_PRUEFFALLE = 3;
    private static final List<Variante> VARIANTEN = variantenErstellen();
    public static List<Variante> varianten() { return VARIANTEN; }
    public static List<Variante> varianten(boolean mitTyp) {
        if (!mitTyp) return VARIANTEN;
        List<Variante> result = new ArrayList<>(VARIANTEN);
        for (double weight : new double[]{0, .25, .5, .75})
            result.add(new Variante("SCHIFFSTYP_" + (int)(weight*100),
                    weight == 0 ? "Typregel: Referenzen" : "Mischung: Typregel",
                    Vergleich.SCHIFFSTYP, weight));
        return List.copyOf(result);
    }

    private static List<Variante> variantenErstellen() {
        List<Variante> result = new ArrayList<>();
        result.add(new Variante("modell", "Bisheriges Modell", Vergleich.KEINER, 1));
        for (Vergleich v : List.of(Vergleich.EIN_SCHIFF, Vergleich.DREI_GLEICH, Vergleich.DREI_ABSTAND)) {
            String label = switch (v) {
                case EIN_SCHIFF -> "1 ähnliches Schiff";
                case DREI_GLEICH -> "3 Schiffe, gleich gewichtet";
                case DREI_ABSTAND -> "3 Schiffe, nach Abstand gewichtet";
                default -> throw new IllegalStateException();
            };
            result.add(new Variante(v.name() + "_0", label, v, 0));
            for (double weight : new double[]{0.25, 0.5, 0.75}) {
                result.add(new Variante(v.name() + "_" + (int)(weight * 100),
                        "Mischung: " + label, v, weight));
            }
        }
        return List.copyOf(result);
    }

    public static Ergebnis vergleichen(Anfrage anfrage) {
        return vergleichen(anfrage, () -> false, progress -> {});
    }

    public static Ergebnis vergleichen(Anfrage anfrage, BooleanSupplier abbruch,
                                       Consumer<Fortschritt> fortschritt) {
        return new Lauf(anfrage, abbruch, fortschritt).berechnen();
    }

    private record Daten(MatrixProjekt projekt, double ziel, String quelle) {}
    private record Koeffizient(String bezug, double a, double b, double gewicht) {}
    private record Modell(List<Koeffizient> koeffizienten) {
        Double prognose(Map<String, Double> input) {
            double sum = 0, weights = 0;
            for (Koeffizient k : koeffizienten) {
                Double x = input.get(k.bezug());
                if (!positiv(x)) continue;
                double y = k.a() + k.b() * x;
                if (!Double.isFinite(y)) return null;
                sum += k.gewicht() * y;
                weights += k.gewicht();
            }
            return weights == 0 ? null : gueltigePrognose(sum / weights);
        }
    }
    /** Sortierte, ausgeschlossene IDs als Cache-Schlüssel; Namen sind keine Identität. */
    private record Ausschluss(List<Integer> ids) {
        static Ausschluss von(int... ids) { return new Ausschluss(Arrays.stream(ids).sorted().boxed().toList()); }
        boolean enthaelt(int id) { return ids.contains(id); }
    }
    private record Vorhersagen(Map<String, Double> werte, List<Referenz> referenzen, TypReferenzen typ) {}
    private record Fall(Daten daten, Map<String, Double> werte, String automatisch, TypReferenzen typ) {}

    private static final class Lauf {
        private final Anfrage anfrage;
        private final BooleanSupplier abbruch;
        private final Consumer<Fortschritt> fortschritt;
        private final List<Daten> daten = new ArrayList<>();
        private final List<Daten> pruefbar = new ArrayList<>();
        private final Map<Integer, MatrixProjekt> projektNachId = new HashMap<>();
        private final Map<Ausschluss, Modell> modelle = new HashMap<>();
        private final List<String> bezuege;
        private final List<Variante> varianten;

        Lauf(Anfrage anfrage, BooleanSupplier abbruch, Consumer<Fortschritt> fortschritt) {
            this.anfrage = anfrage; this.abbruch = abbruch; this.fortschritt = fortschritt;
            varianten = varianten(anfrage.typOption().aktiv());
            bezuege = List.copyOf(anfrage.eingaben().keySet());
            for (MatrixProjekt p : anfrage.projekte()) {
                Double ist = p.wert("LIGHTING_POINTS:ist"), soll = p.wert("LIGHTING_POINTS:soll");
                Double y = switch (anfrage.basis()) {
                    case IST -> ist;
                    case SOLL -> soll;
                    case IST_SONST_SOLL -> ist != null ? ist : soll;
                };
                if (y == null || !Double.isFinite(y) || y < 0) continue;
                String quelle = anfrage.basis() == Basis.SOLL ? "Soll"
                        : ist != null ? "Ist" : "Soll";
                Daten d = new Daten(p, y, quelle); daten.add(d);
                if (vollstaendig(p.werte())) { pruefbar.add(d); projektNachId.put(p.id(), p); }
            }
        }

        Ergebnis berechnen() {
            pruefeAbbruch();
            Vorhersagen aktuell = vorhersagen(Ausschluss.von(), anfrage.eingaben(),
                    anfrage.typOption().kategorie(), anfrage.typOption().schiffstyp());
            List<Fall> faelle = new ArrayList<>();
            int fertig = 0;
            for (Daten test : pruefbar) {
                pruefeAbbruch();
                Ausschluss aussen = Ausschluss.von(test.projekt().id());
                Vorhersagen prediction = vorhersagen(aussen, test.projekt().werte(),
                        test.projekt().kategorie(), test.projekt().schiffstyp());
                // Innere Prüfung wählt die Variante ausschließlich unter den übrigen Projekten.
                List<Fall> innen = new ArrayList<>();
                for (Daten innerTest : pruefbar) {
                    if (innerTest.projekt().id() == test.projekt().id()) continue;
                    pruefeAbbruch();
                    Vorhersagen inner = vorhersagen(Ausschluss.von(test.projekt().id(),
                                    innerTest.projekt().id()), innerTest.projekt().werte(),
                            innerTest.projekt().kategorie(), innerTest.projekt().schiffstyp());
                    innen.add(new Fall(innerTest, inner.werte(), null, inner.typ()));
                }
                Variante gewaehlt = waehlen(innen, prediction.werte());
                Map<String, Double> values = new LinkedHashMap<>(prediction.werte());
                values.put(AUTO_ID, gewaehlt == null ? null : values.get(gewaehlt.id()));
                faelle.add(new Fall(test, Collections.unmodifiableMap(values),
                        gewaehlt == null ? null : gewaehlt.id(), prediction.typ()));
                fortschritt.accept(new Fortschritt(++fertig, pruefbar.size()));
            }
            List<Fall> gemeinsamFix = faelle.stream().filter(this::alleFix).toList();
            List<Fall> gemeinsamAuto = gemeinsamFix.stream().filter(f -> f.werte().get(AUTO_ID) != null).toList();
            boolean autoPruefbar = gemeinsamAuto.size() >= MIN_PRUEFFALLE;
            List<Fall> gemeinsam = autoPruefbar ? gemeinsamAuto : gemeinsamFix;
            Variante auswahl = waehlen(faelle, aktuell.werte());
            List<Zeile> rows = new ArrayList<>();
            for (Variante v : varianten) {
                Double prediction = aktuell.werte().get(v.id());
                rows.add(new Zeile(v, prediction, kennzahlen(gemeinsam, v.id()),
                        v.vergleich() == Vergleich.SCHIFFSTYP ? aktuell.typ().hinweis()
                                : prediction == null ? "Nicht berechenbar: Daten oder gültige Prognose fehlen."
                                : v.vergleich() == Vergleich.KEINER ? "Wie bisher: Methodenwahl und Bezugsgewichte aus Trainingsdaten."
                                : v.vergleich() == Vergleich.EIN_SCHIFF ? "Ein vollständiges Referenzprojekt mit Zielwert."
                                : "Genau drei vollständige Referenzprojekte mit Zielwert erforderlich."));
            }
            Variante autoVariante = new Variante(AUTO_ID, "Automatische Auswahl (innere Prüfung)",
                    auswahl == null ? Vergleich.KEINER : auswahl.vergleich(),
                    auswahl == null ? Double.NaN : auswahl.modellGewicht());
            rows.add(new Zeile(autoVariante, auswahl == null ? null : aktuell.werte().get(auswahl.id()),
                    autoPruefbar ? kennzahlen(gemeinsam, AUTO_ID) : kennzahlen(List.of(), AUTO_ID),
                    auswahl == null ? "Für eine automatische Auswahl fehlen mindestens drei gemeinsame Prüffälle."
                            : "Aktuell gewählt: " + auswahl.name() + " / Modell "
                            + (int)(auswahl.modellGewicht() * 100) + " %."
                            + (auswahl.vergleich() == Vergleich.SCHIFFSTYP ? " " + aktuell.typ().hinweis() : "")));
            List<Testfall> details = new ArrayList<>();
            Set<Integer> gemeinsameIds = new HashSet<>();
            for (Fall f : gemeinsam) gemeinsameIds.add(f.daten().projekt().id());
            for (Fall f : faelle) details.add(new Testfall(f.daten().projekt().id(),
                    f.daten().projekt().schiff(), f.daten().ziel(), f.werte(), f.automatisch(),
                    gemeinsameIds.contains(f.daten().projekt().id()), f.daten().projekt().kategorie(),
                    f.daten().projekt().schiffstyp(), f.typ().regel(), f.typ().hinweis()));
            return new Ergebnis(List.copyOf(rows), aktuell.referenzen(), List.copyOf(details),
                    anfrage.projekte().size(), daten.size(), pruefbar.size(), gemeinsam.size(),
                    auswahl == null ? null : auswahl.id(), autoPruefbar, bezuege, aktuell.typ());
        }

        private Modell modell(Ausschluss ausgeschlossen) {
            Modell cached = modelle.get(ausgeschlossen);
            if (cached != null) return cached;
            List<Koeffizient> coefficients = new ArrayList<>();
            for (String bezug : bezuege) {
                pruefeAbbruch();
                List<RegressionRechenweg.Punkt> punkte = new ArrayList<>();
                for (Daten d : daten) {
                    if (ausgeschlossen.enthaelt(d.projekt().id())) continue;
                    Double x = d.projekt().wert(bezug);
                    if (positiv(x)) punkte.add(new RegressionRechenweg.Punkt(
                            Integer.toString(d.projekt().id()), d.quelle(), x, d.ziel()));
                }
                if (punkte.size() < 3) continue;
                RegressionRechenweg.Ergebnis result = RegressionRechenweg.berechne(punkte);
                RegressionRechenweg.Methode best = result == null ? null : result.beste();
                if (best == null || best.mape() == null || !Double.isFinite(best.mape())
                        || !Double.isFinite(best.a()) || !Double.isFinite(best.b())) continue;
                // Identische Regel wie in der bisherigen kombinierten Schätzung.
                double weight = 1 / Math.pow(Math.max(best.mape(), 0.5), 2);
                coefficients.add(new Koeffizient(bezug, best.a(), best.b(), weight));
            }
            Modell fit = new Modell(List.copyOf(coefficients)); modelle.put(ausgeschlossen, fit);
            return fit;
        }

        private Vorhersagen vorhersagen(Ausschluss ausgeschlossen, Map<String, Double> input,
                                        String kategorie, String typ) {
            pruefeAbbruch();
            Double model = modell(ausgeschlossen).prognose(input);
            List<Referenz> alleNachbarn = nachbarn(ausgeschlossen, input);
            List<Referenz> neighbors = List.copyOf(alleNachbarn.subList(0, Math.min(3, alleNachbarn.size())));
            TypReferenzen typRefs = typReferenzen(alleNachbarn, input, kategorie, typ);
            Double typWert = typRefs.referenzen().isEmpty() ? null
                    : typRefs.referenzen().size() == 1 ? typRefs.referenzen().get(0).hochgerechnet()
                    : abstandsMittel(typRefs.referenzen());
            Double one = neighbors.isEmpty() ? null : neighbors.get(0).hochgerechnet();
            Double three = neighbors.size() < 3 ? null : gueltigePrognose(neighbors.stream()
                    .mapToDouble(Referenz::hochgerechnet).average().orElse(Double.NaN));
            Double distance = neighbors.size() < 3 ? null : abstandsMittel(neighbors);
            Map<String, Double> result = new LinkedHashMap<>();
            for (Variante v : varianten) {
                Double ref = switch (v.vergleich()) {
                    case KEINER -> null;
                    case EIN_SCHIFF -> one;
                    case DREI_GLEICH -> three;
                    case DREI_ABSTAND -> distance;
                    case SCHIFFSTYP -> typWert;
                };
                Double prediction = v.modellGewicht() == 1 ? model : v.modellGewicht() == 0 ? ref
                        : model == null || ref == null ? null
                        : gueltigePrognose(v.modellGewicht() * model + (1-v.modellGewicht()) * ref);
                result.put(v.id(), prediction);
            }
            return new Vorhersagen(Collections.unmodifiableMap(result), neighbors, typRefs);
        }

        private List<Referenz> nachbarn(Ausschluss ausgeschlossen, Map<String, Double> input) {
            List<Referenz> result = new ArrayList<>();
            for (Daten d : pruefbar) {
                if (ausgeschlossen.enthaelt(d.projekt().id())) continue;
                double norm = 0, factor = 0;
                for (String key : bezuege) {
                    double neu = input.get(key), alt = d.projekt().wert(key);
                    norm = Math.hypot(norm, (alt-neu)/neu);
                    factor += neu / alt / bezuege.size();
                }
                double distance = norm / Math.sqrt(bezuege.size());
                Double scaled = gueltigePrognose(d.ziel() * factor);
                if (!Double.isFinite(distance) || !Double.isFinite(factor) || scaled == null) continue;
                result.add(new Referenz(d.projekt().id(), d.projekt().schiff(), distance,
                        d.ziel(), factor, scaled, d.quelle(), d.projekt().kategorie(), d.projekt().schiffstyp()));
            }
            result.sort(Comparator.comparingDouble(Referenz::abstand).thenComparingInt(Referenz::id));
            return List.copyOf(result);
        }

        private TypReferenzen typReferenzen(List<Referenz> alle, Map<String, Double> input,
                                            String kategorie, String typ) {
            if (!anfrage.typOption().aktiv()) return new TypReferenzen(List.of(), "AUS", "Typvergleich ausgeschaltet.");
            if (!bekannt(kategorie)) return new TypReferenzen(List.of(), "KEINE_KATEGORIE",
                    "Keine bekannte Kategorie; Typregel nicht berechenbar.");
            double grenze = anfrage.typOption().maxRelativeAbweichung();
            List<Referenz> kategorieRefs = alle.stream().filter(r -> gleichBekannt(kategorie, r.kategorie()))
                    .filter(r -> innerhalbGrenze(r.id(), input, grenze)).toList();
            List<Referenz> typRefs = kategorieRefs.stream().filter(r -> gleichBekannt(typ, r.schiffstyp())).toList();
            if (typRefs.size() >= 3) return new TypReferenzen(List.copyOf(typRefs.subList(0,3)), "DREI_GLEICHER_TYP",
                    "Drei passende Schiffe derselben Kategorie und desselben Typs, nach Abstand gewichtet.");
            if (!typRefs.isEmpty()) return new TypReferenzen(List.of(typRefs.get(0)), "EIN_GLEICHER_TYP",
                    "Ein passendes Schiff desselben Typs; nur " + typRefs.size() + " passende Typreferenz(en) vorhanden.");
            if (kategorieRefs.size() >= 3) return new TypReferenzen(List.copyOf(kategorieRefs.subList(0,3)), "DREI_GLEICHE_KATEGORIE",
                    "Kein passender bekannter Typ: drei Schiffe derselben Kategorie, nach Abstand gewichtet.");
            return new TypReferenzen(List.of(), "ZU_WENIGE_REFERENZEN",
                    "Keine passende Typreferenz und nur " + kategorieRefs.size()
                            + " passende Kategoriereferenz(en); drei erforderlich. Keine Referenzschätzung.");
        }

        private boolean innerhalbGrenze(int id, Map<String, Double> input, double grenze) {
            MatrixProjekt projekt = projektNachId.get(id);
            return bezuege.stream().allMatch(key -> Math.abs(projekt.wert(key)-input.get(key))/input.get(key) <= grenze + 1e-12);
        }

        private Variante waehlen(List<Fall> faelle, Map<String, Double> verfuegbar) {
            List<Fall> gemeinsam = faelle.stream().filter(this::alleFix).toList();
            if (gemeinsam.size() < MIN_PRUEFFALLE) return null;
            Variante best = null; double smallest = Double.POSITIVE_INFINITY;
            for (Variante v : varianten) {
                if (verfuegbar.get(v.id()) == null) continue;
                double mae = kennzahlen(gemeinsam, v.id()).mae();
                if (mae < smallest - 1e-9) { smallest = mae; best = v; }
            }
            return best;
        }
        private boolean alleFix(Fall fall) {
            return varianten.stream().allMatch(v -> fall.werte().get(v.id()) != null);
        }
        private RegressionRechenweg.Fehlerkennzahlen kennzahlen(List<Fall> faelle, String variante) {
            List<RegressionRechenweg.LooZeile> rows = new ArrayList<>();
            for (Fall f : faelle) rows.add(new RegressionRechenweg.LooZeile(
                    new RegressionRechenweg.Punkt(Integer.toString(f.daten().projekt().id()),
                            f.daten().quelle(), 0, f.daten().ziel()), f.werte().get(variante), null, null));
            return RegressionRechenweg.fehlerkennzahlen(rows);
        }
        private boolean vollstaendig(Map<String, Double> werte) {
            return bezuege.stream().allMatch(key -> positiv(werte.get(key)));
        }
        private void pruefeAbbruch() {
            if (abbruch.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
    }

    private static Double abstandsMittel(List<Referenz> neighbors) {
        List<Referenz> exact = neighbors.stream().filter(r -> r.abstand() <= 1e-12).toList();
        if (!exact.isEmpty()) return gueltigePrognose(exact.stream().mapToDouble(Referenz::hochgerechnet).average().orElse(Double.NaN));
        double min = neighbors.get(0).abstand(), sum = 0, weights = 0;
        for (Referenz n : neighbors) { double w = min/n.abstand(); sum += w*n.hochgerechnet(); weights += w; }
        return gueltigePrognose(sum/weights);
    }
    private static boolean positiv(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    private static Double gueltigePrognose(double value) { return Double.isFinite(value) && value >= 0 ? value : null; }

    /** Keine Vermutung bei Platzhaltern; Kategorie wird stets gemeinsam mit dem Typ geprüft. */
    public static boolean bekannt(String value) { return !normalisiert(value).isEmpty(); }
    private static boolean gleichBekannt(String a, String b) {
        String norm = normalisiert(a); return !norm.isEmpty() && norm.equals(normalisiert(b));
    }
    private static String normalisiert(String value) {
        if (value == null) return "";
        String norm = value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        String kompakt = norm.replaceAll("[\\s.\\-_/]", "");
        return Set.of("", "nb", "na", "ka", "unbekannt", "nichtbekannt", "nichtangegeben", "unknown", "null", "?")
                .contains(kompakt) ? "" : norm;
    }
}
