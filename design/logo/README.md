# Logo und App-Icon

Ein Motiv für alles: App-Icon, Logo in der App, Benachrichtigung und Autofill.

## Format

Gearbeitet wird in **SVG**. Daraus entstehen die Android-Vector-Drawables (XML), die die App tatsächlich verwendet.
PNG wird nicht von Hand gepflegt, sondern bei Bedarf aus der SVG exportiert (z. B. für GitHub oder einen Store).
Wegen minSdk 33 braucht die App selbst keine PNG-Icons.

## Wo das Motiv verwendet wird

| Verwendung | Größe | Form | Datei im Projekt |
|---|---|---|---|
| App-Icon (Adaptive Icon) | Fläche 108 dp, sichtbar 72 dp, Safe Zone 66 dp | Hintergrund + Vordergrund | `app/src/main/res/drawable/ic_launcher_background.xml` und `ic_launcher_foreground.xml`, Verlaufsfarben in `app/src/main/res/values/colors.xml` |
| Themed Icon (Android 13+) | wie App-Icon | einfarbige Silhouette, das System färbt sie ein | `app/src/main/res/mipmap-anydpi/ic_launcher.xml` (`monochrome`) |
| Benachrichtigung | 24 dp | weiße Silhouette, nur die Deckkraft zählt | `platform/src/main/res/drawable/platform_ic_lock.xml` |
| Autofill-Ersatz | 24 dp | einfarbig | `autofill/src/main/res/drawable/kpm_autofill_ic_key.xml` |

Daraus folgt: Das Motiv muss **als einfarbige Silhouette funktionieren** und **bei 24 dp noch erkennbar** sein.

## Regeln für die SVG

Damit die Umwandlung in ein Vector-Drawable sauber klappt:

- nur Pfade und einfache Formen, Text in Pfade umwandeln
- flache Farben oder einfache lineare Verläufe, keine Filter, Schatten, Weichzeichner, Masken oder eingebetteten Bilder
- Farben direkt als Attribut (`fill="#..."`), keine CSS-Klassen oder `<style>`-Blöcke
- Motiv innerhalb der Safe Zone (Kreis mit 66 dp Durchmesser um die Mitte der 108er-Fläche)

## Das Logo

Weißer Schlüssel (Material Symbols „key“, Apache License 2.0) auf einem Petrol-Verlauf von oben links nach unten rechts:

| | Hex | RGB |
|---|---|---|
| Start (oben links) | `#12384F` | 18, 56, 79 |
| Ende (unten rechts) | `#26968A` | 38, 150, 138 |

Der Verlauf reicht über den sichtbaren Bereich (18 bis 90 auf der 108er-Fläche), damit beide Farben im maskierten Icon vollständig zu sehen sind.

## Dev-App

Die Dev-App (Build-Typ `dev`, Name "KeyPass Dev", läuft neben der normalen App) hat denselben weißen Schlüssel auf einem grünen Verlauf, damit man beide Icons auf dem Homescreen sofort unterscheidet. Die Farben stehen in `app/src/dev/res/values/colors.xml` und überschreiben nur die beiden Verlaufsfarben. Themed Icon, Benachrichtigung und Autofill-Symbol bleiben wie bei der normalen App.

| | Hex | RGB | Kontrast zu Weiß |
|---|---|---|---|
| Start (oben links) | `#1B4D2E` | 27, 77, 46 | 9,8 : 1 |
| Ende (unten rechts) | `#4A9A3F` | 74, 154, 63 | 3,5 : 1 |

Zum Vergleich Petrol: Ende `#26968A` mit 3,6 : 1. Der Farbton des Endes liegt bei etwa 113 Grad (Petrol: 174 Grad). Die Verlaufsfarben (`ic_launcher_gradient_start` und `_end`) werden nur vom Launcher-Icon verwendet, das Logo in der App selbst ist davon nicht betroffen.

## Dateien

- `logo.svg`: das Logo in Vollfarbe, 108 x 108, Ebenen `hintergrund` und `vordergrund`
- `logo-mono.svg`: einfarbige Silhouette für Themed Icon, Benachrichtigung und Autofill
