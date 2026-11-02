# MVP - Zwischenstand vom 02.11.2026

Grundlage sind `ANFORDERUNGEN.md` und der UI-Entwurf (Link in Abschnitt 13). Alle Module sind fertig, `:app` ist vollständig verdrahtet. Der Ende-zu-Ende-Test im Emulator gegen Samba in Docker ist durchgeführt, die dabei gefundenen Fehler sind behoben. Geprüft wurden Einrichtung, Liste, Detail und Kopieren, Sperren und Entsperren (Fingerabdruck und Passwort), Einstellungen, Synchronisation (neue und abgelehnte Version), Quellenwechsel, Offline-Modus, Autofill-Auswahl (auch bei gesperrtem Start und mit Phishing-Warnung, getestet in einer WebView mit echter Web-Domain), Benachrichtigung, Drehen und Prozess-Tod. Nicht getestet: die Autofill-Testseite im Browser.

## Aufbau

Gradle-Root ist das Repo, Build mit `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` (JDK 25.0.3), Paket `de.lulurobb.keypassmobile`. Emulator: AVD `kpm_api36` (API 36, google_apis x86_64, pixel_8). Für die Samba-Tests wird Docker Desktop benötigt. Der Prototyp in `prototype/` ist ein eigener Build und bleibt unverändert.

| Modul | Inhalt | Stand |
|-------|--------|-------|
| `:model` | Gemeinsame Typen: `Vault`, `VaultEntry`, `EntrySection`, `FillTarget` | fertig |
| `:core` | `EntryIndex` (Liste, Suche, Matching), `Domains` (Public-Suffix-Liste), `EntryMatcher` (Phishing-Prüfung), Einstellungswerte, `PasswordExpiry`, `SyncEngine` (Offline-Kopie, Kandidat, Quellenwechsel) | fertig, 78 Tests grün |
| `:kdbx` | `KdbxReader`, `PasswordKey.hash`, `Argon2Engine`, Gruppen, Icons, Papierkorb | fertig, 60 Tests grün |
| `:smb` | `SmbConnector`/`SmbConnection`, GUID-Prüfung vor der Anmeldung, Freigaben auflisten (eigenes SRVSVC, ohne smbj-rpc), zählt ausgeblendete Nicht-.kdbx-Dateien | fertig, 24 Tests grün ohne Docker (die Samba-Tests brauchen Docker und wurden auf diesem Rechner nicht ausgeführt) |
| `:autofill` | Autofill-Dienst, Felderkennung nur sichtbarer Felder (AF-11), `AutofillRequest`-API für das Auswahlfenster | fertig, 53 Tests grün |
| `:platform` | Keystore (AES für SMB-Passwort, RSA für Hash), Fingerabdruck mit Ablauf, Sitzung, Vordergrunddienst, Zwischenablage-Schutz, Einstellungen, SMB/KDBX-Adapter, Sync-Worker mit Berichten, Einrichtung, Quellenwechsel als Transaktion mit Journal und Absturz-Wiederherstellung | fertig, nach drei Review-Runden gehärtet, 100 Tests grün (+30 Gerätetests im Emulator) |
| `:app` | Alle Bildschirme in Compose, Debug-Galerie "KPM Galerie"; verdrahtet: `Application`, `MainActivity` mit zustandsabgeleiteter Navigation, `AppController`/`AppViewModel`, Modelle und Routen für Entsperren, Liste, Detail, Einstellungen, Einrichtung und Systemeinrichtung, `AutofillPickerActivity` mit Phishing-Prüfung, Launcher-Icon, Manifeste | fertig, 254 Tests grün |

## Nächste Schritte

1. Signiertes APK für das A55 (Signierschlüssel einmal anlegen und auf beiden PCs gleich verwenden).
2. Test auf dem echten Handy gegen das echte NAS, inklusive Autofill im Browser (Brave) mit `autofill/testpages/login-steps.html`.

## Offene Punkte

- kotpass: geschützte Werte und Argon2-Hook nur per Reflection erreichbar (Tests schlagen bei einem Update fehl). R8 ist aus; falls aktiviert, Keep-Regeln aus `kdbx` prüfen.
- Freigaben auflisten nur gegen Samba getestet, noch nicht gegen das echte NAS.
- Autofill: Felder mit `opacity:0` gelten als sichtbar.
- Compose-Textfelder (Host, Benutzer, Suche) werden fremden Autofill-Diensten angeboten; unser Dienst ignoriert die eigene App.
- SE-09 (Sperren bei Bildschirm aus) bewusst zurückgestellt.
- Gerätetests offen für: Biometrie-Dialog im Autofill-Fenster darf die Anfrage nicht abbrechen; Löschen des Passwortpuffers (SE-04) nach dem Verlassen des Eingabefelds.
- Autofill-Testseite im Browser nur auf dem echten Handy testbar (Chrome im Emulator hat keine Option für fremde Autofill-Dienste, Brave ist dort nicht installiert).
- Testumgebung Emulator: Samba-Container `kpm-e2e-samba` (Image dockurr/samba, Host-Port 14460, im Emulator 10.0.2.2:14460, Benutzer wie in `SambaContainer.kt`).
