# Foto Finals

Android-App zum mehrstufigen Aussortieren größerer Fotosammlungen.

## Aktuelle Version

**0.2.0** · Android `versionCode 2`

Versionierung:
- neue Funktionen: Minor-Version, z. B. `0.2.0 → 0.3.0`
- reine Fehlerkorrekturen: Patch-Version, z. B. `0.2.0 → 0.2.1`
- erste stabile Fassung: `1.0.0`
- der Android-`versionCode` wird bei jedem installierbaren Release erhöht.

## Bedienung

1. **Album / Fotos auswählen** öffnet den Android Photo Picker direkt in der Albumansicht.
2. Gewünschtes Album öffnen und Fotos auswählen.
3. Die App zeigt vor Runde 1 die Anzahl und eine Vorschau der gewählten Fotos.
4. In jeder Runde:
   - **links** = raus
   - **rechts** = behalten
   - **Undo** = letzte Entscheidung zurücknehmen
5. Nach jeder Runde:
   - **Auswahl abschließen**
   - oder **Weitere Runde** nur mit den behaltenen Fotos
6. Finalisten erscheinen als Raster und können geteilt oder an Google Fotos übergeben werden.

## Album-Auswahl

AndroidX unterstützt das gezielte Öffnen des Photo Pickers im **Albums-Tab**. Bei aktuellen Android-Versionen startet Foto Finals deshalb direkt dort.

Ein komplettes **Google-Fotos-Cloud-Album** kann Android/Google Fotos derzeit nicht als einzelnes auswählbares Objekt an Dritt-Apps übergeben. In einem Cloud-Album muss deshalb weiterhin die Mehrfachauswahl verwendet werden. Lokale Ordner/Alben können später optional über einen eigenen Medienzugriff ergänzt werden.

## Google Fotos

**An Google Fotos übergeben** verwendet den Android-Share-Mechanismus und adressiert die installierte Google-Fotos-App direkt. Google Fotos entscheidet anschließend, welche Import-/Album-Aktion angeboten wird. Foto Finals löscht keine Originale.

Ein vollautomatisches Erstellen eines Albums aus bereits vorhandenen Google-Fotos-Medien ist mit der aktuellen Google Photos Library API nicht zuverlässig möglich: Seit März 2025 sind Album- und Medienoperationen weitgehend auf von der eigenen App erzeugte Inhalte beschränkt.

## Enthalten

- beliebig viele Auswahlrunden
- Album-orientierter Photo-Picker-Einstieg
- Auswahl-Vorschau vor Runde 1
- Undo auch am Rundenende
- Pinch-to-Zoom
- lokales Speichern/Fortsetzen
- Finalisten-Raster
- einzelnes Foto antippbar
- Teilen der Finalisten
- direkte Übergabe an Google Fotos
- keine Löschfunktion

## APK automatisch bauen

Nach einem Push auf `main` startet **Build Android APK** automatisch.

Das Build-Artefakt trägt die App-Version im Namen, z. B.:

`FotoFinals-v0.2.0-debug-apk`

Das ZIP enthält:

`FotoFinals-v0.2.0-debug.apk`

## Signierung

Die CI erzeugt derzeit eine Debug-APK. Für dauerhaft installierbare Updates ohne Deinstallation soll vor einer stabilen Version eine feste Release-Signierung über GitHub Secrets eingerichtet werden.
