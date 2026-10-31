# KeyPass Mobile - Anforderungen (Entwurf)

Schlanke Flutter-App für **Android**, um KeePassXC-Datenbanken (KDBX) auf dem Handy zu nutzen.
Schwerpunkte:

1. Datenbanken direkt von einer **SMB-Freigabe** öffnen, mit Offline-Kopie für unterwegs (ohne VPN).
2. Zugangsdaten **ohne Zwischenablage** in Apps und Browser eintragen.

Prioritäten: **MUSS** = MVP, **SOLL** = zweite Ausbaustufe, **KANN** = später / optional.

Festgelegte Rahmenbedingungen:

- Nur Android (vorerst kein iOS).
- Als Datenquelle reicht vorerst SMB.
- Der MVP öffnet Datenbanken nur lesend. Bearbeitet wird weiterhin am PC in KeePassXC.
- Die Offline-Kopie ist **immer nur die vollständige, verschlüsselte Datei**. Einzelne Passwörter oder entschlüsselte Inhalte werden **niemals** zwischengespeichert.
- Automatisch sperren beim vollständigen Schließen der App oder nach 2 Minuten.
- Das Master-Passwort wird **nie gespeichert** und bei jedem Entsperren eingetippt. Daher gibt es kein Entsperren per Biometrie.
- Verteilung vorerst privat als APK, nicht über den Play Store.

---

## 1. Datenquellen

| ID | Anforderung | Prio |
|----|-------------|------|
| DS-01 | Mehrere Datenbanken verwalten (Liste mit Name, Quelle, letzter Sync) | MUSS |
| DS-02 | **SMB-Freigabe** (Windows-Freigabe, NAS): Host, Freigabe, Pfad, Benutzer, Passwort, Domäne. Nur SMB 2/3, kein SMB 1. | MUSS |
| DS-03 | Verbindungsdaten zur Freigabe (Passwort) im Android Keystore ablegen, nie im Klartext | MUSS |
| DS-04 | "Verbindung testen"-Button beim Einrichten einer Quelle | SOLL |
| DS-05 | SMB-Verschlüsselung/Signierung nutzen, wenn der Server sie anbietet | SOLL |
| DS-06 | Schlüsseldatei (Key File) ebenfalls von der SMB-Freigabe laden (nur falls KX-06 umgesetzt wird) | KANN |
| DS-07 | Lokale Datei über den System-Dateiauswahldialog öffnen (Android Storage Access Framework) | SOLL |

## 2. Offline-Kopie und Synchronisation

Der Share ist unterwegs ohne VPN nicht erreichbar. Dafür gibt es die Offline-Kopie.

| ID | Anforderung | Prio |
|----|-------------|------|
| SY-01 | Die Offline-Kopie ist **ausschließlich die vollständige KDBX-Datei**, verschlüsselt und byte-identisch mit der Datei auf dem Share. Es werden **niemals** einzelne Passwörter, Einträge oder andere entschlüsselte Inhalte gespeichert: kein Klartext-Cache, kein Suchindex, kein Autofill-Cache auf dem Gerät. | MUSS |
| SY-02 | Bei jedem Zugriff wird die Datei (vom Share oder die Offline-Kopie) neu entsperrt. Entschlüsselte Inhalte existieren nur im Arbeitsspeicher, solange die Datenbank entsperrt ist. | MUSS |
| SY-03 | Ist der Share erreichbar, wird geprüft, ob die Datei dort neuer ist (Änderungsdatum, Größe). Wenn ja, wird die neue Version erst vollständig heruntergeladen und dann die Offline-Kopie ersetzt. So entsteht bei einem Verbindungsabbruch keine kaputte Kopie. | MUSS |
| SY-04 | Ist der Share nicht erreichbar (z. B. kein VPN), wird automatisch die Offline-Kopie geöffnet, mit sichtbarem Hinweis | MUSS |
| SY-05 | Anzeige, ob man mit der aktuellen Datei oder der Offline-Kopie arbeitet, inkl. Zeitpunkt des letzten Syncs | MUSS |
| SY-06 | Manueller "Jetzt synchronisieren"-Button | MUSS |
| SY-07 | *(ab Stufe 2, Bearbeiten)* Vor dem Zurückschreiben prüfen, ob die Datei auf dem Share inzwischen geändert wurde. Falls ja: wie in KeePassXC **zusammenführen** (Abgleich über Eintrags-UUID und Änderungszeitpunkt, Historie erhalten), statt die Datei blind zu überschreiben. | SOLL |
| SY-08 | *(ab Stufe 2)* Sicheres Schreiben: erst temporäre Datei, dann umbenennen. Vorher Backup der alten Version. | SOLL |
| SY-09 | *(ab Stufe 2)* Offline gemachte Änderungen landen nur in der verschlüsselten Offline-Kopie (wieder als vollständige Datei) und werden beim nächsten Kontakt mit dem Share zusammengeführt | SOLL |

## 3. KDBX-Kompatibilität (KeePassXC)

| ID | Anforderung | Prio |
|----|-------------|------|
| KX-01 | KDBX 4.x lesen (Standardformat von KeePassXC) | MUSS |
| KX-02 | KDBX 3.1 lesen | SOLL |
| KX-03 | Schlüsselableitung: Argon2d, Argon2id, AES-KDF | MUSS |
| KX-04 | Verschlüsselung: AES-256, ChaCha20 (Twofish als KANN) | MUSS |
| KX-05 | Entsperren mit Master-Passwort | MUSS |
| KX-06 | Zusätzliche Schlüsselfaktoren, wie KeePassXC sie erlaubt: Schlüsseldatei (XML v1/v2, binär, Hex) und YubiKey Challenge-Response (HMAC-SHA1) per NFC oder USB. Wird aktuell nicht genutzt. | KANN |
| KX-07 | *(ab Stufe 2)* KDBX 4.x schreiben, ohne Daten zu verlieren: Historie, Anhänge, Papierkorb, benutzerdefinierte Felder, Tags, Icons, CustomData (z. B. Einstellungen von KeePassXC-Browser) und unbekannte XML-Elemente bleiben erhalten | SOLL |
| KX-08 | TOTP aus KeePassXC-Einträgen anzeigen (Attribut `otp` mit `otpauth://`-URI sowie die älteren `TOTP Seed`/`TOTP Settings`-Felder) | SOLL |
| KX-09 | In KeePassXC gespeicherte Passkeys nutzen (Android Credential Manager) | KANN |

## 4. Eintragen ohne Zwischenablage (Kernfunktion)

| ID | Anforderung | Prio |
|----|-------------|------|
| AF-01 | **Android Autofill-Dienst** (AutofillService): App ist als Autofill-Anbieter im System auswählbar und füllt Benutzername/Passwort direkt in Apps und unterstützte Browser ein | MUSS |
| AF-02 | Zuordnung Eintrag zu App/Website: über die URL des Eintrags (Domain-Abgleich), zusätzliche URLs sowie App-Paketnamen. Die Konvention `androidapp://<paket>` von Keepass2Android prüfen, damit es zu bestehenden Datenbanken passt. | MUSS |
| AF-03 | Ist die Datenbank gesperrt, erscheint beim Autofill zuerst die Entsperr-Maske. Danach werden die Treffer angezeigt. | MUSS |
| AF-04 | Werte erst nach Auswahl eines Eintrags an das System übergeben, nicht vorab für alle Treffer | SOLL |
| AF-05 | Wenn kein Treffer: Suche direkt im Autofill-Dialog. *(Ab Stufe 2: gewählten Eintrag dauerhaft der App zuordnen, also den Paketnamen in den Eintrag schreiben.)* | SOLL |
| AF-06 | Inline-Vorschläge in der Tastaturleiste (Android 11+, z. B. Gboard) | SOLL |
| AF-07 | **Eigene Tastatur** (Input Method) als Fallback für Apps/Browser ohne Autofill-Unterstützung: Eintrag wählen, dann Benutzername, Passwort, TOTP per Tastendruck eintippen | SOLL |
| AF-08 | Zwischenablage nur als letzter Ausweg und nur auf ausdrücklichen Klick: Inhalt als sensibel markieren (Android 13+ blendet ihn dann aus) und nach einstellbarer Zeit automatisch leeren | MUSS |
| AF-09 | Phishing-Schutz: Warnung, wenn Paketname/Domain nicht zum Eintrag passt | SOLL |

## 5. Bedienung

| ID | Anforderung | Prio |
|----|-------------|------|
| UI-01 | Entsperren durch Eintippen des Master-Passworts. Im Eingabefeld sind Vorschläge, Autokorrektur und das Lernen der Tastatur abgeschaltet (Flutter: `enableIMEPersonalizedLearning: false`), damit die Tastatur sich das Passwort nicht merkt. | MUSS |
| UI-02 | Das Master-Passwort wird **nie** gespeichert, auch nicht verschlüsselt im Keystore. Daher gibt es kein Entsperren per Biometrie und kein "Passwort merken". | MUSS |
| UI-03 | Gruppenbaum durchblättern | MUSS |
| UI-04 | Volltextsuche über Titel, Benutzername, URL, Notizen, Tags (nur im Arbeitsspeicher, kein gespeicherter Index) | MUSS |
| UI-05 | Eintragsdetails: Felder anzeigen, Passwort nur auf Tipp einblenden, URL öffnen | MUSS |
| UI-06 | TOTP mit Countdown anzeigen | SOLL |
| UI-07 | *(ab Stufe 2)* Einträge und Gruppen anlegen, bearbeiten, löschen (Papierkorb wie KeePassXC) | SOLL |
| UI-08 | *(ab Stufe 2)* Passwortgenerator (Länge, Zeichenklassen, Passphrasen) | SOLL |
| UI-09 | Anhänge anzeigen | KANN |
| UI-10 | Eintragshistorie ansehen | KANN |
| UI-11 | Hell/Dunkel-Modus, Sprache Deutsch und Englisch | SOLL |

## 6. Sicherheit

| ID | Anforderung | Prio |
|----|-------------|------|
| SE-01 | **Sperren beim vollständigen Schließen der App** (aus der App-Übersicht gewischt oder Prozess beendet): Die entschlüsselte Datenbank wird aus dem Speicher verworfen. Beim nächsten Start muss neu entsperrt werden. | MUSS |
| SE-02 | **Automatisch sperren nach 2 Minuten** ohne Nutzung. Als Nutzung zählen Aktionen in der App sowie Autofill- und Tastatur-Vorgänge. Das gilt auch, wenn die App im Hintergrund liegt. | MUSS |
| SE-03 | Zusätzlich optional beim Ausschalten des Displays sperren | KANN |
| SE-04 | Siehe SY-01/SY-02: Entschlüsselte Inhalte nie auf Disk, nur im Arbeitsspeicher. Nach dem Sperren alle Referenzen verwerfen. (Hinweis: Dart-Strings lassen sich nicht zuverlässig überschreiben, daher wo möglich Byte-Arrays nutzen.) | MUSS |
| SE-05 | Screenshots und die Vorschau in der App-Übersicht unterbinden (FLAG_SECURE) | MUSS |
| SE-06 | Keine Telemetrie, kein Tracking, keine Netzwerkzugriffe außer zur konfigurierten SMB-Freigabe | MUSS |
| SE-07 | Keine Geheimnisse in Logs, Crash-Reports oder Fehlermeldungen | MUSS |
| SE-08 | App-Daten vom Android-Backup ausschließen (Verbindungsdaten, Offline-Kopie, Keystore-Schlüssel) | MUSS |
| SE-09 | Abhängigkeiten minimal halten und regelmäßig auf Sicherheitslücken prüfen | SOLL |

## 7. Nicht-funktionale Anforderungen

- Plattform: **nur Android**. minSdk Vorschlag Android 8 / API 26, weil Autofill erst ab dort verfügbar ist.
- Verteilung: privat als signierte Release-APK. Den Signaturschlüssel sicher und außerhalb des Repos aufbewahren. Updates lassen sich nur mit demselben Schlüssel über eine installierte Version installieren.
- Eine typische Datenbank (Argon2, KeePassXC-Standardparameter) soll auch auf Mittelklasse-Geräten in wenigen Sekunden entsperrt sein. Dafür Argon2 nativ (FFI) statt in reinem Dart. Wichtig, weil wegen SY-02 und SE-02 häufig neu entsperrt wird.
- Datenbanken mit mehreren tausend Einträgen sind flüssig durchsuchbar.
- Die App bleibt "simpel": kein eigener Cloud-Dienst, kein Konto, kein Abo.
- Quellcode offen (Lizenzen der verwendeten Bibliotheken prüfen; KeePassXC selbst steht unter GPL, das KDBX-Format ist offen dokumentiert).

## 8. Technische Hinweise und Risiken

- **KDBX in Dart:** Vorhandene Bibliotheken (z. B. das `kdbx`-Paket von AuthPass) auf Aktualität und KDBX-4.1-Unterstützung prüfen. Für den MVP reicht Lesen. Verlustfreies Schreiben ist erst in Stufe 2 relevant.
- **Argon2:** In reinem Dart zu langsam, daher eine native Implementierung per FFI einbinden.
- **SMB:** Es gibt kaum ausgereifte SMB-Bibliotheken für Dart. Realistisch ist ein nativer Kotlin-Teil über Platform Channels, z. B. mit `smbj` (SMB 2/3) oder `jcifs-ng`. Neben Autofill das größte Implementierungsrisiko.
- **Autofill und Tastatur** sind Android-Systemdienste und müssen nativ (Kotlin) umgesetzt werden. Der Dienst braucht Zugriff auf die entsperrte Datenbank, ohne dass die Flutter-UI läuft. Die Architektur dafür früh klären, z. B. ein gemeinsamer entsperrter Zustand im selben Prozess oder eine eigene Flutter-Engine im Dienst. Wegen SY-01 darf dieser Zustand nur im Arbeitsspeicher liegen.
- **"Vollständig schließen" erkennen:** Wird die App aus der Übersicht gewischt, endet der Prozess meist ohnehin, und damit ist der Arbeitsspeicher weg. Läuft der Autofill-Dienst im selben Prozess weiter, muss das Wegwischen explizit erkannt werden (z. B. `onTaskRemoved`) und die Datenbank aktiv gesperrt werden.
- **Browser-Autofill:** Die Unterstützung für Autofill-Dienste von Drittanbietern unterscheidet sich je nach Browser und Version (Chrome braucht eine Einstellung, Firefox unterstützt es). Früh mit den genutzten Browsern testen.

## 9. Nicht im Umfang (vorerst)

- iOS
- Veröffentlichung im Play Store
- Speichern des Master-Passworts, Entsperren per Biometrie
- WebDAV, SFTP, Cloud-APIs (Nextcloud, OneDrive, Google Drive ...)
- KeePassXC-Browser-Protokoll, SSH-Agent, Secret Service
- Eigener Sync-Server oder Cloud-Dienst
- Import/Export anderer Formate (CSV, 1Password, Bitwarden ...)
- Desktop-Versionen der Flutter-App

## 10. Ausbaustufen

1. **MVP (Android, nur lesen, nur SMB):**
   - SMB-Quelle und Offline-Kopie als vollständige verschlüsselte Datei
   - Entsperren durch Eintippen des Master-Passworts
   - Gruppen, Suche, Details
   - Android-Autofill, Zwischenablage-Fallback mit Auto-Löschen
   - Auto-Sperre (App geschlossen / 2 Minuten)
2. **Stufe 2:**
   - Bearbeiten und Zurückschreiben mit Merge
   - TOTP, eigene Tastatur, Passwortgenerator
   - Lokale Datei als Quelle
3. **Später:** WebDAV, SFTP, Schlüsseldatei, YubiKey, Anhänge, Passkeys, ggf. iOS.

## 11. Offene Fragen

Aktuell keine.
