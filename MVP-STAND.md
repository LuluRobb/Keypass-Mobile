# MVP - Zwischenstand vom 01.11.2026

Gesichert, weil das Nutzungslimit erreicht war. Grundlage sind `ANFORDERUNGEN.md` und der UI-Entwurf (Link in Abschnitt 13).

## Aufbau

Gradle-Root ist das Repo, Build mit `JAVA_HOME=C:\Program Files\Java\jdk-25.0.2`, Paket `de.lulurobb.keypassmobile`. Der Prototyp in `prototype/` ist ein eigener Build und bleibt unverändert.

| Modul | Inhalt | Stand |
|-------|--------|-------|
| `:model` | Gemeinsame Typen: `Vault`, `VaultEntry`, `EntrySection`, `FillTarget` | fertig |
| `:core` | `EntryIndex` (Liste, Suche, Matching), `Domains` (Public-Suffix-Liste), `EntryMatcher` (Phishing-Prüfung), Einstellungswerte, `PasswordExpiry`, `SyncEngine` (Offline-Kopie, Kandidat) | fertig, 69 Tests grün |
| `:kdbx` | `KdbxReader`, `PasswordKey.hash`, `Argon2Engine`, Gruppen, Icons, Papierkorb | fertig, 60 Tests grün |
| `:smb` | `SmbConnector`/`SmbConnection`, GUID-Prüfung vor der Anmeldung, Freigaben auflisten (eigenes SRVSVC, ohne smbj-rpc) | fertig, 32 Tests grün (Docker-Samba) |
| `:app` (nur `ui/`) | Alle Bildschirme zustandslos in Compose, Debug-Galerie "KPM Galerie", Screenshots im Emulator geprüft | fertig |
| `:autofill` | Autofill-Dienst, Felderkennung nur sichtbarer Felder (AF-11), `AutofillRequest`-API für das Auswahlfenster | fertig, 53 Tests grün |
| `:platform` | Keystore (AES für SMB-Passwort, RSA für Hash), Fingerabdruck, Sitzung, Vordergrunddienst, Zwischenablage, Einstellungen, SMB/KDBX-Adapter, Sync-Worker, Einrichtung | **abgebrochen, unvollständig**: Code liegt vor, Build und Tests nicht abschließend geprüft |

## Nächste Schritte

1. `:platform` fertigstellen: Stand prüfen (`:platform:testDebugUnitTest :platform:assembleDebug`, Gerätetests mit `:platform:connectedDebugAndroidTest`), Lücken gegen den Auftrag schließen (Keystore, Sitzung, Dienst, Sync, Einrichtung, Systemstatus für UI-06).
2. `:app` verdrahten: `MainActivity` und Navigation, ViewModels von `:platform` auf die UI-Zustände, `AutofillPickerActivity` (Klassenname aus `KpmAutofill.PICKER_ACTIVITY_CLASS`, Vertrag in `autofill/.../KpmAutofill.kt`), FLAG_SECURE, Edge-to-Edge, `adjustResize`, Datenextraktionsregeln, App-Icon, `implementation(project(":autofill"))` und `:platform`.
3. Ende-zu-Ende-Test im Emulator (AVD `kpm_api36`, Brave, Samba in Docker, Testseite `autofill/testpages/login-steps.html`).
4. Durchsicht, dann signiertes APK für das A55.

## Offene Punkte aus den Berichten

- kotpass: geschützte Werte und Argon2-Hook nur per Reflection erreichbar (Tests schlagen bei einem Update fehl). R8 ist aus; falls aktiviert, Keep-Regeln aus `kdbx` prüfen.
- Freigaben auflisten nur gegen Samba getestet, noch nicht gegen das echte NAS.
- Autofill: Felder mit `opacity:0` gelten als sichtbar.
- Compose-Textfelder (Host, Benutzer, Suche) werden fremden Autofill-Diensten angeboten; unser Dienst ignoriert die eigene App.
- Emulator: `adb reverse tcp:8080` ist noch aktiv, einige Brave-Tabs offen, als Autofill-Dienst ist der Prototyp eingestellt.
