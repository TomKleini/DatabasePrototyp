package org.example;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Rechenweg für "y aus x" mit allen Zwischenschritten.
 *
 * Drei Methoden zum Vergleich:
 * 1. Normale Regression (kleinste Quadrate)   y = a + b · x
 * 2. Theil-Sen (robust)                        b = Median der Paar-Steigungen
 * 3. Median-Quote (robust, ohne Fixanteil)     y = k · x, k = Median(y / x)
 *
 * Dazu: Pearson r, Spearman ρ, Signifikanz, Ausreißer-Hinweis und der
 * Leave-one-out-Prognosefehler (MAPE) je Methode.
 */
public final class RegressionRechenweg {

    private RegressionRechenweg() {
    }

    // ------------------------------------------------------------------
    // Datentypen
    // ------------------------------------------------------------------

    /** Ein Schiff mit Bezugsgröße x und Zielgröße y. */
    public record Punkt(String name, String quelle, double x, double y) {
    }

    /** Zwischenwerte der normalen Regression für ein Schiff. */
    public record Zeile(
            Punkt punkt,
            double dx,
            double dy,
            double dxdy,
            double dx2,
            double dy2,
            double yDach,
            double residuum,
            double residuum2,
            boolean auffaellig
    ) {
    }

    /** Prognose für ein Schiff, gerechnet ohne dieses Schiff. */
    public record LooZeile(
            Punkt punkt,
            Double prognose,
            Double abweichung,
            Double abweichungProzent
    ) {
    }

    /** Robuste Zwischenwerte für ein Schiff. */
    public record RobusteZeile(
            Punkt punkt,
            Double quote,
            double achsenwertTs,
            double rangX,
            double rangY,
            double d,
            double d2
    ) {
    }

    /** Steigung zwischen zwei Schiffen (für Theil-Sen). */
    public record Paarsteigung(
            String schiffA,
            String schiffB,
            double dx,
            double dy,
            double steigung
    ) {
    }

    public record Robust(
            List<RobusteZeile> zeilen,
            List<Paarsteigung> paare,
            Double k,
            double bTs,
            double aTs,
            double rho,
            boolean rangGleichstand,
            double summeD2
    ) {
    }

    /** Eine Schätzmethode y = a + b · x mit ihrem Prognosefehler. */
    public record Methode(
            String name,
            String formel,
            double a,
            double b,
            List<LooZeile> loo,
            Double mape
    ) {
        public double prognose(double x) {
            return a + b * x;
        }
    }

    public record Ergebnis(
            List<Zeile> zeilen,
            int n,
            double summeX,
            double summeY,
            double mittelX,
            double mittelY,
            double sxy,
            double sxx,
            double syy,
            double b,
            double a,
            double r,
            double r2,
            double sse,
            Double se,
            Double tKrit,
            Double rKrit,
            List<LooZeile> loo,
            Double mape,
            double minX,
            double maxX,
            Robust robust,
            List<Methode> methoden
    ) {
        public double prognose(double x) {
            return a + b * x;
        }

        public boolean signifikant() {
            return rKrit != null && Math.abs(r) > rKrit;
        }

        public boolean imDatenbereich(double x) {
            return x >= minX && x <= maxX;
        }

        /** Methode mit dem kleinsten Leave-one-out-Fehler, sonst null. */
        public Methode beste() {
            return methoden.stream()
                    .filter(m -> m.mape() != null)
                    .min(Comparator.comparingDouble(Methode::mape))
                    .orElse(null);
        }
    }

    // ------------------------------------------------------------------
    // Hauptrechnung
    // ------------------------------------------------------------------

    /**
     * Rechnet alles. Gibt null zurück, wenn weniger als 2 Schiffe
     * vorliegen oder alle x-Werte gleich sind.
     */
    public static Ergebnis berechne(List<Punkt> punkte) {
        int n = punkte.size();
        if (n < 2) {
            return null;
        }

        double summeX = 0;
        double summeY = 0;
        double minX = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;

        for (Punkt p : punkte) {
            summeX += p.x();
            summeY += p.y();
            minX = Math.min(minX, p.x());
            maxX = Math.max(maxX, p.x());
        }

        double mittelX = summeX / n;
        double mittelY = summeY / n;

        double sxy = 0;
        double sxx = 0;
        double syy = 0;

        for (Punkt p : punkte) {
            double dx = p.x() - mittelX;
            double dy = p.y() - mittelY;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }

        if (sxx == 0) {
            return null;
        }

        double b = sxy / sxx;
        double a = mittelY - b * mittelX;
        double r = syy == 0 ? 0 : sxy / Math.sqrt(sxx * syy);

        double sse = 0;
        for (Punkt p : punkte) {
            double res = p.y() - (a + b * p.x());
            sse += res * res;
        }

        Double se = n > 2 ? Math.sqrt(sse / (n - 2)) : null;

        List<Zeile> zeilen = new ArrayList<>();
        for (Punkt p : punkte) {
            double dx = p.x() - mittelX;
            double dy = p.y() - mittelY;
            double yDach = a + b * p.x();
            double res = p.y() - yDach;
            boolean auffaellig = se != null && se > 0
                    && Math.abs(res) > 2 * se;

            zeilen.add(new Zeile(p, dx, dy, dx * dy, dx * dx, dy * dy,
                    yDach, res, res * res, auffaellig));
        }

        Double tKrit = n > 2 ? tKritisch(n - 2) : null;
        Double rKrit = tKrit == null
                ? null
                : tKrit / Math.sqrt(tKrit * tKrit + (n - 2));

        Robust robust = robust(punkte);

        List<Methode> methoden = new ArrayList<>();
        methoden.add(methode("Normale Regression", "y = a + b · x",
                a, b, punkte, RegressionRechenweg::kleinsteQuadrate));
        methoden.add(methode("Theil-Sen (robust)", "y = a + b · x",
                robust.aTs(), robust.bTs(), punkte,
                RegressionRechenweg::theilSen));
        if (robust.k() != null) {
            methoden.add(methode("Median-Quote (robust)", "y = k · x",
                    0, robust.k(), punkte,
                    RegressionRechenweg::medianQuote));
        }

        Methode normal = methoden.get(0);

        return new Ergebnis(zeilen, n, summeX, summeY, mittelX, mittelY,
                sxy, sxx, syy, b, a, r, r * r, sse, se, tKrit, rKrit,
                normal.loo(), normal.mape(), minX, maxX, robust, methoden);
    }

    private static Methode methode(
            String name,
            String formel,
            double a,
            double b,
            List<Punkt> punkte,
            Function<List<Punkt>, double[]> verfahren
    ) {
        List<LooZeile> loo = leaveOneOut(punkte, verfahren);
        return new Methode(name, formel, a, b, loo, mape(loo));
    }

    // ------------------------------------------------------------------
    // Robuste Werte
    // ------------------------------------------------------------------

    private static Robust robust(List<Punkt> punkte) {
        int n = punkte.size();

        // Theil-Sen: Steigung für jedes Schiffspaar
        List<Paarsteigung> paare = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                Punkt p = punkte.get(i);
                Punkt q = punkte.get(j);
                double dx = q.x() - p.x();
                if (dx == 0) {
                    continue;
                }
                double dy = q.y() - p.y();
                paare.add(new Paarsteigung(p.name(), q.name(), dx, dy, dy / dx));
            }
        }
        paare.sort(Comparator.comparingDouble(Paarsteigung::steigung));

        double bTs = median(paare.stream().map(Paarsteigung::steigung).toList());

        List<Double> achsenwerte = punkte.stream()
                .map(p -> p.y() - bTs * p.x())
                .toList();
        double aTs = median(achsenwerte);

        // Median-Quote
        List<Double> quoten = punkte.stream()
                .map(p -> p.x() != 0 ? p.y() / p.x() : null)
                .toList();
        List<Double> gueltigeQuoten = quoten.stream()
                .filter(q -> q != null)
                .toList();
        Double k = gueltigeQuoten.isEmpty() ? null : median(gueltigeQuoten);

        // Spearman: Ränge (bei gleichen Werten mittlerer Rang)
        double[] rangX = raenge(punkte.stream().mapToDouble(Punkt::x).toArray());
        double[] rangY = raenge(punkte.stream().mapToDouble(Punkt::y).toArray());

        boolean gleichstand = hatGleichstand(rangX) || hatGleichstand(rangY);
        double rho = pearson(rangX, rangY);

        List<RobusteZeile> zeilen = new ArrayList<>();
        double summeD2 = 0;
        for (int i = 0; i < n; i++) {
            double d = rangX[i] - rangY[i];
            summeD2 += d * d;
            zeilen.add(new RobusteZeile(punkte.get(i), quoten.get(i),
                    achsenwerte.get(i), rangX[i], rangY[i], d, d * d));
        }

        return new Robust(zeilen, paare, k, bTs, aTs, rho, gleichstand, summeD2);
    }

    // ------------------------------------------------------------------
    // Die drei Verfahren (liefern {a, b} oder null)
    // ------------------------------------------------------------------

    private static double[] kleinsteQuadrate(List<Punkt> punkte) {
        if (punkte.size() < 2) {
            return null;
        }

        double mx = punkte.stream().mapToDouble(Punkt::x).average().orElse(0);
        double my = punkte.stream().mapToDouble(Punkt::y).average().orElse(0);
        double sxy = 0;
        double sxx = 0;

        for (Punkt p : punkte) {
            sxy += (p.x() - mx) * (p.y() - my);
            sxx += (p.x() - mx) * (p.x() - mx);
        }

        if (sxx == 0) {
            return null;
        }

        double b = sxy / sxx;
        return new double[]{my - b * mx, b};
    }

    private static double[] theilSen(List<Punkt> punkte) {
        List<Double> steigungen = new ArrayList<>();
        for (int i = 0; i < punkte.size(); i++) {
            for (int j = i + 1; j < punkte.size(); j++) {
                double dx = punkte.get(j).x() - punkte.get(i).x();
                if (dx != 0) {
                    steigungen.add((punkte.get(j).y() - punkte.get(i).y()) / dx);
                }
            }
        }

        if (steigungen.isEmpty()) {
            return null;
        }

        double b = median(steigungen);
        double a = median(punkte.stream().map(p -> p.y() - b * p.x()).toList());
        return new double[]{a, b};
    }

    private static double[] medianQuote(List<Punkt> punkte) {
        List<Double> quoten = punkte.stream()
                .filter(p -> p.x() != 0)
                .map(p -> p.y() / p.x())
                .toList();

        return quoten.isEmpty() ? null : new double[]{0, median(quoten)};
    }

    // ------------------------------------------------------------------
    // Prüfung
    // ------------------------------------------------------------------

    /**
     * Jedes Schiff einmal weglassen, mit den übrigen rechnen und das
     * weggelassene Schiff schätzen. Erst ab 3 Schiffen sinnvoll.
     */
    private static List<LooZeile> leaveOneOut(
            List<Punkt> punkte,
            Function<List<Punkt>, double[]> verfahren
    ) {
        List<LooZeile> ergebnis = new ArrayList<>();
        if (punkte.size() < 3) {
            return ergebnis;
        }

        for (int i = 0; i < punkte.size(); i++) {
            Punkt weggelassen = punkte.get(i);
            List<Punkt> rest = new ArrayList<>(punkte);
            rest.remove(i);

            double[] ab = verfahren.apply(rest);
            if (ab == null) {
                ergebnis.add(new LooZeile(weggelassen, null, null, null));
                continue;
            }

            double prognose = ab[0] + ab[1] * weggelassen.x();
            double abweichung = prognose - weggelassen.y();
            Double prozent = weggelassen.y() != 0
                    ? abweichung / weggelassen.y() * 100
                    : null;

            ergebnis.add(new LooZeile(weggelassen, prognose, abweichung, prozent));
        }

        return ergebnis;
    }

    /** Mittlerer absoluter Prozentfehler über alle berechenbaren Schiffe. */
    private static Double mape(List<LooZeile> loo) {
        double summe = 0;
        int anzahl = 0;

        for (LooZeile z : loo) {
            if (z.abweichungProzent() != null) {
                summe += Math.abs(z.abweichungProzent());
                anzahl++;
            }
        }

        return anzahl == 0 ? null : summe / anzahl;
    }

    // ------------------------------------------------------------------
    // Hilfsrechnungen
    // ------------------------------------------------------------------

    /** Median; bei gerader Anzahl Mittel der beiden mittleren Werte. */
    static double median(List<Double> werte) {
        List<Double> sortiert = werte.stream().sorted().toList();
        int n = sortiert.size();
        if (n == 0) {
            return Double.NaN;
        }
        return n % 2 == 1
                ? sortiert.get(n / 2)
                : (sortiert.get(n / 2 - 1) + sortiert.get(n / 2)) / 2;
    }

    /** Ränge 1..n, gleiche Werte bekommen den mittleren Rang. */
    static double[] raenge(double[] werte) {
        int n = werte.length;
        Integer[] index = new Integer[n];
        for (int i = 0; i < n; i++) {
            index[i] = i;
        }
        java.util.Arrays.sort(index, Comparator.comparingDouble(i -> werte[i]));

        double[] raenge = new double[n];
        int i = 0;
        while (i < n) {
            int j = i;
            while (j + 1 < n && werte[index[j + 1]] == werte[index[i]]) {
                j++;
            }
            double mittlererRang = (i + j) / 2.0 + 1;
            for (int t = i; t <= j; t++) {
                raenge[index[t]] = mittlererRang;
            }
            i = j + 1;
        }
        return raenge;
    }

    private static boolean hatGleichstand(double[] raenge) {
        for (double rang : raenge) {
            if (rang != Math.floor(rang)) {
                return true;
            }
        }
        // ganze Ränge können trotzdem doppelt sein (z. B. drei gleiche Werte)
        return java.util.Arrays.stream(raenge).distinct().count() < raenge.length;
    }

    private static double pearson(double[] x, double[] y) {
        int n = x.length;
        double mx = java.util.Arrays.stream(x).average().orElse(0);
        double my = java.util.Arrays.stream(y).average().orElse(0);
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            sxy += (x[i] - mx) * (y[i] - my);
            sxx += (x[i] - mx) * (x[i] - mx);
            syy += (y[i] - my) * (y[i] - my);
        }
        return sxx == 0 || syy == 0 ? 0 : sxy / Math.sqrt(sxx * syy);
    }

    /** t-Werte, zweiseitig, 5 %, für 1 bis 30 Freiheitsgrade. */
    private static final double[] T_TABELLE = {
            12.706, 4.303, 3.182, 2.776, 2.571, 2.447, 2.365, 2.306,
            2.262, 2.228, 2.201, 2.179, 2.160, 2.145, 2.131, 2.120,
            2.110, 2.101, 2.093, 2.086, 2.080, 2.074, 2.069, 2.064,
            2.060, 2.056, 2.052, 2.048, 2.045, 2.042
    };

    /** Kritischer t-Wert (entspricht Excel T.INV.2S(0,05; FG)). */
    static double tKritisch(int freiheitsgrade) {
        if (freiheitsgrade <= 30) {
            return T_TABELLE[freiheitsgrade - 1];
        }
        if (freiheitsgrade <= 40) {
            return 2.021;
        }
        if (freiheitsgrade <= 60) {
            return 2.000;
        }
        if (freiheitsgrade <= 120) {
            return 1.980;
        }
        return 1.960;
    }
}