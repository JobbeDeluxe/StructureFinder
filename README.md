# StructureFinder 1.0.0 (Paper 26.3)

Paper-Plugin zur Struktursuche um einen *online befindlichen Spieler auf demselben Paper-Backend* mit Inventarmenü und über RCON ausführbaren Textbefehlen.

## Installation

1. Java 25 und Paper 26.3 verwenden.
2. `./gradlew build` (Linux/macOS) bzw. `.\gradlew.bat build` (Windows) ausführen. Der enthaltene Wrapper lädt Gradle 9.2.1 automatisch. Ergebnis: `build/libs/StructureFinder-1.0.0.jar`.
3. JAR in `plugins/` des betreffenden Paper-Backends kopieren, Server neu starten.
4. `plugins/StructureFinder/config.yml` nach Bedarf ändern.
5. OP bzw. Rechte `structurefinder.use`, `structurefinder.admin` vergeben.

## Ingame

- `/structures` -> Online-Spieler auswählen -> Strukturtyp -> startet Suche -> Ergebnismenü.
- Linksklick auf Ergebnis: sich selbst teleportieren; Rechtsklick: Zielspieler teleportieren.

## RCON / Konsole

Befehle ohne vorangestellten `/`:

```
structures players
structures types
structures find JobbeDeluxe mansion 10
structures results JobbeDeluxe mansion
structures tp JobbeDeluxe mansion 1
structures tp JobbeDeluxe mansion 1 target
structures cancel JobbeDeluxe mansion
```

Die Suche läuft über mehrere Ticks; RCON-Aufrufe geben **zunächst nur den Startstatus** aus. `results` erneut aufrufen, bis die Suche abgeschlossen ist. RCON kann nur einen **Zielspieler** teleportieren (kein `self`).

## Wichtige Einschränkungen

- **Annäherung, nicht garantiert mathematisch die nächsten zehn Strukturen.** Die Vanilla-/Paper-API liefert pro Abfrage nur den nächsten Fundort. Das Plugin durchsucht dafür mehrere Punkte spiralförmig und sortiert eindeutige Treffer nach Entfernung zur beim Start gespeicherten Position.
- Suche läuft auf dem Hauptthread, **ein Locate-Aufruf pro Intervall**. Auch ein einzelner Vanilla-Locate-Aufruf kann kurzfristig blockieren oder Chunks generieren. `max-probes`, `radius-chunks`, `ticks-between-probes` an die Serverleistung anpassen.
- Strukturen mit eigenem Dimensionstyp (z.B. Netherfestung) nur aus der richtigen Welt suchen; Ergebnisse sind auf die Welt des Zielspielers beschränkt.
- Eine Struktur im Untergrund wird nur von oben angeflogen (Oberflächen-Teleport), nicht in den Innenraum.
- Ergebnislisten sind flüchtig und werden beim Serverneustart gelöscht.
- In Velocity wird pro Paper-Backend ein eigenes Plugin benötigt. Spieler auf einem anderen Backend sind hier nicht sichtbar. RCON muss direkt mit dem zutreffenden Paper-Backend verbunden werden.
- Es gibt hier **keinen Hintergrund-Thread für Vanilla-Locate**, da diese API nicht asynchron sicher ist.
- Diese Quelle ist gegen die dokumentierte Paper-API geschrieben. Vor Produktiveinsatz Build und Laufzeit auf Paper 26.3 testen.

## Ohne lokale Java-/Gradle-Installation bauen

Dieses Projekt enthält `.github/workflows/build.yml`. Bei jedem Push und Pull Request wird das Plugin automatisch gebaut. Im Tab **Actions** kann der Workflow **Build Paper Plugin** auch manuell gestartet werden. Danach das Artefakt `StructureFinder-jar` herunterladen. Alternativ Java 25 installieren und mit dem enthaltenen Gradle-Wrapper bauen.
