package org.example.importer;

import org.example.database.Database;

/**
 * Einmaliger Import der Excel-Tabelle in die Datenbank.
 * Vorher database.db löschen und die App nicht gleichzeitig laufen lassen.
 * Über IntelliJ starten, damit das Arbeitsverzeichnis dasselbe wie bei der App ist.
 */
public class ImportRunner {

    public static void main(String[] args) {
        Database.createTables();
        Database.insertMasterData();
        ExcelImporter.importSheet(
                "C:\\Users\\eda.guenes\\OneDrive - VINCI Energies\\Datenbank 2025-09-25 - Kopie.xlsx",
                "Forschung & Spezial"
        );
    }
}
