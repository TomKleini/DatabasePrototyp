package org.example.model;

import java.util.List;

/** Vollständiger Bearbeitungsstand eines vorhandenen Projekts.
 * Datumswerte werden wie bisher als ISO-Text gespeichert.
 * created_at und updated_at sind keine editierbaren Benutzereingaben.
 */
public record ProjectEditData(
        int id,
        Integer shipyardId,
        int categoryId,
        int shipTypeId,
        String shipName,
        String status,
        boolean relevant,
        Double lengthM,
        Double widthM,
        Double volumeGt,
        Double areaSqm,
        Double cableCalculatedKm,
        Double cableOfferKm,
        Double cableActualKm,
        boolean ringCableIncluded,
        String offerDate,
        String orderDate,
        String constructionStart,
        String constructionEndPlanned,
        String constructionEndActual,
        String editor,
        String evaluation,
        String link,
        String comments,
        List<Metric> metrics,
        List<Department> departments
) {
    public ProjectEditData {
        metrics = List.copyOf(metrics);
        departments = List.copyOf(departments);
    }

    public record Metric(int id, String code, String name, String unit,
                         Double target, Double actual) {
    }

    public record Department(int id, String code, String name, String value) {
    }
}
