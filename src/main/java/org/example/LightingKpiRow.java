package org.example;

/**
 * Ein Projekt mit seinen Beleuchtungskennzahlen (Soll/Ist)
 * aus der Tabelle project_metrics.
 */
public record LightingKpiRow(
        int projectId,
        String shipName,
        String shipyard,
        String category,
        String shipType,
        String status,

        Double lengthM,
        Double volumeGt,
        Double areaSqm,

        Double lightingPointsTarget,
        Double lightingPointsActual,
        Double ioTarget,
        Double ioActual,

        Double minPerLightingPointTarget,
        Double minPerLightingPointActual,
        Double minPerIoEngineeringTarget,
        Double minPerIoEngineeringActual,
        Double minPerIoDeliveryTarget,
        Double minPerIoDeliveryActual
) {

    /** Ist-Anzahl Brennstellen, falls vorhanden, sonst Soll. */
    public Double lightingPoints() {
        return actualOrTarget(lightingPointsActual, lightingPointsTarget);
    }

    /** Ist-Anzahl IOs, falls vorhanden, sonst Soll. */
    public Double ioCount() {
        return actualOrTarget(ioActual, ioTarget);
    }

    private static Double actualOrTarget(Double actual, Double target) {
        return actual != null ? actual : target;
    }
}