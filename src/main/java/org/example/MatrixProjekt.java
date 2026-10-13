package org.example;

import java.util.Map;

/**
 * Ein Projekt mit allen Zahlenwerten für die Korrelationsmatrix.
 *
 * Schlüssel in werte:
 * - Spalten aus projects, z. B. "length_m", "area_sqm"
 * - Kennzahlen als "CODE:soll" und "CODE:ist", z. B. "LIGHTING_POINTS:ist"
 */
public record MatrixProjekt(
        int id,
        String schiff,
        String werft,
        Map<String, Double> werte
) {
    public Double wert(String schluessel) {
        return werte.get(schluessel);
    }
}