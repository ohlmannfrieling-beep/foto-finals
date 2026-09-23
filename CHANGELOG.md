# Changelog

## 0.6.0

- robuster Import für große Bildmengen: Android Photo Picker als primärer Auswahlweg, direkter Google-Fotos-Import bleibt als Fallback erhalten
- neue Import-Zwischenstufe: Auswahl kann in mehreren Blöcken ergänzt werden, bevor die Analyse startet
- bestehende Auswahl wird bei zusätzlichen Blöcken dedupliziert
- gemeinsame Bildanalyse für Kopien- und Serienerkennung: jedes Foto wird im Normalfall nur noch einmal für Fingerprints/Grunddaten geladen
- Analyse von Cloud-Fotos mit begrenzter Parallelität statt rein sequenzieller Verarbeitung
- Serienerkennung verwendet die bereits berechneten Fingerprints und löst keine zweite vollständige Bilddekodierung mehr aus
- Fortschrittsanzeige während der ersten Vorabanalyse
- Analyse-Cache wird beim Start einer neuen Auswahl gezielt geleert
- Android versionCode auf 6 erhöht

## 0.5.0

- vorgeschaltete lokale Kopienprüfung vor der Serienerkennung
- nahezu identische Bilder werden über zwei visuelle Fingerprints erkannt; ähnliche Serienbilder werden bewusst strenger abgegrenzt
- technisch hochwertigste Version einer Kopiengruppe wird anhand Auflösung, Aufnahmedaten und Dateigröße zuerst vorgeschlagen
- Kopienvergleich mit Zoom sowie Möglichkeit, eine oder mehrere Versionen zu behalten oder die Erkennung zu verwerfen
- dezente Kennzeichnung beim Swipe: `WA` bei starken WhatsApp-Hinweisen, `WA?` bei unsicheren Hinweisen und `Kopie ↓` für erkannte schwächere Versionen
- Analyse läuft lokal und verändert keine Originalfotos
- Duplikat-/WhatsApp-Zustand wird zusammen mit dem Projekt gespeichert
- Unit-Tests für die konservative Fingerprint-Schwelle; CI testet vor dem APK-Build
- Android versionCode auf 5 erhöht

## 0.4.0

- Serienvergleich: Fotos lassen sich antippen und in einer Vollbildansicht per Zwei-Finger-Geste bis 6× zoomen und verschieben
- Swipe in der Rundenauswahl bewegt das Foto nur noch horizontal, ohne Dreh- oder Transparenzeffekt
- Google-Fotos-Auswahl wieder direkt über Google Fotos geöffnet, damit dortige Alben erreichbar sind; bei fehlender App Fallback auf den Android Photo Picker
- Android versionCode auf 4 erhöht

## 0.3.0

- Swipe-Erkennung neu aufgebaut: ein Finger entscheidet, zwei Finger zoomen/verschieben
- sichtbares Feedback während des Links-/Rechts-Wischens
- automatische Serienerkennung nach Aufnahmezeit und lokalem Bild-Fingerprint
- eigener Serienvergleich vor Runde 1
- pro Serie können ein oder mehrere Fotos behalten werden
- falsch erkannte Serien können aufgelöst werden, ohne Fotos zu verlieren
- Serienerkennung kann komplett übersprungen werden
- Serienauswahl und Fortschritt werden lokal gespeichert
- Android versionCode auf 3 erhöht

## 0.2.0

- Photo Picker startet standardmäßig im Albums-Tab
- alternative Fotoübersicht auf der Startseite
- Vorschau und Anzahl der ausgewählten Fotos vor Runde 1
- Finalisten können einzeln geöffnet werden
- Finalisten können über Android geteilt werden
- direkte Übergabe der Finalisten an Google Fotos
- Versionsanzeige in der App
- Android versionCode auf 2 erhöht
- Build-Artefakte enthalten die Versionsnummer
- robustere Zustände bei leerer Auswahl und unbekanntem gespeicherten Screen

## 0.1.0

- erster lauffähiger Prototyp
- mehrstufige Auswahl per Swipe
- Undo
- Pinch-to-Zoom
- lokales Fortsetzen
- Finalisten-Raster
