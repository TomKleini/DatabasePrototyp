package org.example;

public class Project {

    private final int id;
    private final String shipName;
    private final String shipyard;
    private final String category;
    private final String shipType;
    private final String status;
    private final boolean relevant;

    private final Double lengthM;
    private final Double widthM;
    private final Double volumeGt;
    private final Double areaSqm;

    private final Double cableCalculatedKm;
    private final Double cableOfferKm;
    private final Double cableActualKm;

    private final boolean ringCableIncluded;

    private final String offerDate;
    private final String orderDate;

    private final String constructionStart;
    private final String constructionEndPlanned;
    private final String constructionEndActual;

//jetzt um die Projektdetails auszufüllen auch weitere daten hinzugefügt

    public Project(
        int id,
        String shipName,
        String shipyard,
        String category,
        String shipType,
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
        String constructionEndActual
) {
    this.id = id;
    this.shipName = shipName;
    this.shipyard = shipyard;
    this.category = category;
    this.shipType = shipType;
    this.status = status;
    this.relevant = relevant;

    this.lengthM = lengthM;
    this.widthM = widthM;
    this.volumeGt = volumeGt;
    this.areaSqm = areaSqm;

    this.cableCalculatedKm = cableCalculatedKm;
    this.cableOfferKm = cableOfferKm;
    this.cableActualKm = cableActualKm;

    this.ringCableIncluded = ringCableIncluded;

    this.offerDate = offerDate;
    this.orderDate = orderDate;

    this.constructionStart = constructionStart;
    this.constructionEndPlanned = constructionEndPlanned;
    this.constructionEndActual = constructionEndActual;
}

    public int getId() {
        return id;
    }

    public String getShipName() {
        return shipName;
    }

    public String getShipyard() {
        return shipyard;
    }

    public String getCategory() {
        return category;
    }

    public String getShipType() {
        return shipType;
    }

    public String getStatus() {
        return status;
    }

    public boolean isRelevant() {
        return relevant;
    }
    public Double getLengthM() {
        return lengthM;
    }

    public Double getWidthM() {
        return widthM;
    }

    public Double getVolumeGt() {
        return volumeGt;
    }

    public Double getAreaSqm() {
        return areaSqm;
    }

    public Double getCableCalculatedKm() {
        return cableCalculatedKm;
    }

    public Double getCableOfferKm() {
        return cableOfferKm;
    }

    public Double getCableActualKm() {
        return cableActualKm;
    }

    public boolean isRingCableIncluded() {
        return ringCableIncluded;
    }

    public String getOfferDate() {
        return offerDate;
    }

    public String getOrderDate() {
        return orderDate;
    }

    public String getConstructionStart() {
        return constructionStart;
    }

    public String getConstructionEndPlanned() {
        return constructionEndPlanned;
    }

    public String getConstructionEndActual() {
        return constructionEndActual;
    }
}