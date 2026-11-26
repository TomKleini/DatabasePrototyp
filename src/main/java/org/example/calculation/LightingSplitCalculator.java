package org.example.calculation;

import org.example.repository.LightingSplitRepository.Reference;

import java.util.List;

/**
 * Rechenlogik für die Aufteilung der geschätzten Brennstellen.
 *
 * VORLÄUFIGE METHODE: feste historische Anteile.
 *   Anteil Innen    = Summe Innen    / Summe aller drei Teile
 *   Anteil Außen    = Summe Außen    / Summe aller drei Teile
 *   Anteil Schalter = Summe Schalter / Summe aller drei Teile
 * Große Schiffe zählen dadurch stärker.
 *
 * Eine bessere Methode ersetzt später nur calculate(...),
 */
public final class LightingSplitCalculator {

    public static final String METHOD_NAME =
            "Historische Anteile (nach Brennstellen gewichtet)";

    public record Result(
            long indoor,
            long outdoor,
            long switchSocket,
            double indoorShare,
            double outdoorShare,
            double switchSocketShare,
            int projectCount
    ) {
    }

    private LightingSplitCalculator() {
    }

    /** @return null, wenn keine verwertbaren Referenzen vorhanden sind */
    public static Result calculate(double estimatedTotal, List<Reference> refs) {
        double sumIndoor = 0;
        double sumOutdoor = 0;
        double sumSwitch = 0;

        for (Reference r : refs) {
            sumIndoor += r.indoor();
            sumOutdoor += r.outdoor();
            sumSwitch += r.switchSocket();
        }

        double sumAll = sumIndoor + sumOutdoor + sumSwitch;
        if (refs.isEmpty() || sumAll <= 0) {
            return null;
        }

        double indoorShare = sumIndoor / sumAll;
        double outdoorShare = sumOutdoor / sumAll;
        double switchShare = sumSwitch / sumAll;

        long total = Math.round(estimatedTotal);
        long indoor = Math.round(total * indoorShare);
        long outdoor = Math.round(total * outdoorShare);
        // Rundungsrest landet hier, damit die Summe exakt der Schätzung entspricht
        long switchSocket = total - indoor - outdoor;

        return new Result(
                indoor, outdoor, switchSocket,
                indoorShare, outdoorShare, switchShare,
                refs.size()
        );
    }
}
