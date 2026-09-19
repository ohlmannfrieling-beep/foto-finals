# Foto Finals – Prototype 0.1

Android-App zum mehrstufigen Aussortieren von Fotos.

## Bedienung
1. **Neue Auswahl** öffnen.
2. Fotos im Android Photo Picker auswählen.
3. **Links** = raus, **rechts** = behalten.
4. Nach jeder Runde frei entscheiden:
   - **Auswahl abschließen**
   - **Weitere Runde** nur mit den behaltenen Fotos
5. Finalisten erscheinen als Raster.

## Enthalten
- beliebig viele Auswahlrunden
- Abschluss bereits nach Runde 1 oder 2 möglich
- Undo
- Pinch-to-Zoom
- lokales Speichern/Fortsetzen
- keine Löschfunktion

## APK automatisch bauen
Nach dem Upload nach GitHub startet der Workflow **Build Android APK** automatisch.
Nach erfolgreichem Lauf:

**Actions → Build Android APK → letzter erfolgreicher Lauf → Artifacts → FotoFinals-debug-apk**

Das heruntergeladene ZIP enthält `app-debug.apk`.
