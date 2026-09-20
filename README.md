# Foto Finals

Android-App zum mehrstufigen Aussortieren größerer Fotosammlungen.

## Aktuelle Version

**0.3.0** · Android `versionCode 3`

## Neuer Ablauf in 0.3

1. Album/Fotos im Android Photo Picker auswählen.
2. Foto Finals analysiert die Auswahl lokal auf kurze, ähnlich aussehende Aufnahmeserien.
3. Erkannte Serien werden vorab als Vergleichsraster gezeigt.
4. Pro Serie ein oder mehrere Fotos behalten – oder die Serie auflösen und alle behalten.
5. Anschließend beginnt die normale Rundenauswahl.
6. Ein Finger wischt links/rechts; zwei Finger zoomen und verschieben.
7. Finalisten können geteilt oder an Google Fotos übergeben werden.

## Serienerkennung

Die App kombiniert zwei Signale:

- zeitliche Nähe der Aufnahmen (typisch höchstens 10 Sekunden)
- visuelle Ähnlichkeit über einen kleinen lokalen Bild-Fingerprint

Wenn Aufnahmedaten fehlen, wird nur bei direkt benachbarten und besonders ähnlichen Bildern gruppiert. Die Analyse verändert keine Originale und legt keine Personenprofile an.

## Versionierung

- neue Funktionen: Minor-Version, z. B. `0.3.0 → 0.4.0`
- reine Fehlerkorrekturen: Patch-Version, z. B. `0.3.0 → 0.3.1`
- erste stabile Fassung: `1.0.0`
- Android-`versionCode` steigt bei jedem installierbaren Release.

## Google Fotos

**An Google Fotos übergeben** verwendet den Android-Share-Mechanismus und adressiert die installierte Google-Fotos-App direkt. Das Zielalbum wird dort manuell gewählt. Foto Finals löscht keine Originale.

## Build

Ein Push auf `main` startet **Build Android APK**. Das Artefakt enthält die App-Version, z. B. `FotoFinals-v0.3.0-debug.apk`.

## Signierung

Die CI erzeugt derzeit eine Debug-APK. Vor einer stabilen Version soll eine feste Release-Signierung über GitHub Secrets eingerichtet werden.
