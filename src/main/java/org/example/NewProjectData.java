package org.example;

import java.time.LocalDate;

public record NewProjectData(
        Integer shipyardId,
        int shipTypeId,
        String shipName,
        String status,

        Double lengthM,
        Double widthM,
        Double volumeGt,
        Double areaSqm,

        Double cableCalculatedKm,
        Double cableOfferKm,
        Double cableActualKm,
        boolean ringCableIncluded,

        LocalDate offerDate,
        LocalDate orderDate,
        LocalDate constructionStart,
        LocalDate constructionEndPlanned,
        LocalDate constructionEndActual
) {
}