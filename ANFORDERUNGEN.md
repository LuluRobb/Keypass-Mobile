# KeyPass Mobile - Anforderungen (Entwurf 2)

Schlanke Android-App (Kotlin), um eine KeePassXC-Datenbank (KDBX) auf dem Handy zum **Ausfüllen** zu nutzen.
Schwerpunkte:

1. Die Datenbank direkt von einer **SMB-Freigabe** oder aus einer **Datei auf dem Handy** lesen, mit Offline-Kopie für unterwegs (ohne VPN).
2. Zugangsdaten **ohne Zwischenablage** in Browser und Apps eintragen.

Prioritäten: **MUSS** = MVP, **SOLL** = zweite Ausbaustufe, **KANN** = später / optional.

Festgelegte Rahmenbedingungen:

- Nur Android, ein Nutzer, ein Gerät (aktuell Samsung Galaxy A55, One UI 8.5).
- Umsetzung nativ in **Kotlin mit Jetpack Compose**, kein Flutter.
- **Genau eine Datenbank**, Quelle ist eine SMB-Freigabe oder eine Datei auf dem Handy (immer nur eine von beiden, Abschnitt 1).
- Die App ist **nur zum Ausfüllen**: Sie liest die Datenbank, bearbeitet und speichert aber nie etwas. Bearbeitet wird ausschließlich am PC in KeePassXC.
- Die Offline-Kopie ist **immer nur die vollständige, verschlüsselte Datei**. Einzelne Passwörter oder entschlüsselte Inhalte werden **niemals** zwischengespeichert.
- Entsperren per **Fingerabdruck** oder Master-Passwort. Das Master-Passwort muss in einstellbaren Abständen erneut eingegeben werden (Standard **alle 7 Tage**).
- Automatisch sperren beim Wegwischen der App oder nach einstellbarer Zeit ohne Nutzung (Standard 2 Minuten).
- Verteilung privat als APK, nicht über den Play Store.

---

## 1. Datenquelle

Die Quelle ist entweder eine SMB-Freigabe (DS-02 bis DS-07) oder eine Datei auf dem Handy (DS-08 bis DS-15), immer genau eine. Weitere Anforderungen zur Datei als Quelle stehen in EN-07, SY-09 und UI-07 bis UI-09.

| ID | Anforderung | Prio |
|----|-------------|------|
| DS-01 | Genau eine Datenbank. In den Einstellungen: Quelle und Zeitpunkt des letzten Syncs | MUSS |
| DS-02 | **SMB-Freigabe** (Windows-Freigabe, NAS): Eingegeben werden nur Host, Benutzer, Passwort und optional Port und Domäne (unter "Erweitert"). Nach dem Verbinden wählt man Freigabe und Datei interaktiv aus: Die App listet die Freigaben des Servers, man öffnet Ordner und tippt die KDBX-Datei an (angezeigt werden nur Ordner und `.kdbx`-Dateien). Den Namen der Freigabe von Hand eingeben nur als Ausweg, wenn der Server sie nicht auflistet. Beim Ändern einer SMB-Quelle startet die Auswahl im Ordner der aktuellen Datei. Nur SMB 2/3, kein SMB 1. Die App braucht nur Leserecht. | MUSS |
| DS-03 | Das SMB-Passwort liegt verschlüsselt im Android Keystore, nie im Klartext. Der Schlüssel ist ohne Fingerabdruck nutzbar, damit der Hintergrund-Sync (SY-06) laufen kann. Das gespeicherte Passwort dient nur diesem automatischen Sync: Beim Ändern der Quelle (auch bei entsperrter App) muss das SMB-Passwort immer neu eingegeben werden. | MUSS |
| DS-04 | **Server-Identität prüfen:** Beim ersten erfolgreichen Sync merkt sich die App die Server-GUID, die der Server beim Verbindungsaufbau vor der Anmeldung schickt. Weicht sie später ab, bricht die App vor der Anmeldung ab und nutzt die Offline-Kopie. Bei einer legitimen Änderung (z. B. NAS neu aufgesetzt) fragt die App einmal nach. Das verhindert, dass sich die App in einem fremden Netz mit gleichem Adressbereich bei einem fremden Gerät anmeldet. Ohne Einstellungen, ohne SSID, ohne Standortberechtigung. Achtung: Samba bildet die GUID aus dem NetBIOS-Namen des Servers (nicht zufällig), Windows nutzt eine zufällige GUID. Bei Samba schützt die Prüfung daher nur, wenn der Servername unverwechselbar ist. | MUSS |
| DS-05 | Kurzes Verbindungs-Timeout (wenige Sekunden), damit ein nicht erreichbarer Share nichts blockiert | MUSS |
| DS-06 | Verbindung beim Einrichten prüfen. Das übernimmt das Verbinden in DS-02 (Fehler werden dort angezeigt), ein eigener Button "Verbindung testen" entfällt. | MUSS |
| DS-07 | SMB-Signierung verlangen und Verschlüsselung nutzen, wenn der Server sie anbietet (Standard von `smbj`, im Prototyp geprüft) | MUSS |

**Hinweise zur Einrichtung einer SMB-Quelle** (entscheidet der Nutzer auf dem Server, die App kann es nicht erzwingen):

- Für die App einen eigenen SMB-Benutzer anlegen, der nur den Ordner mit der KDBX lesen darf.
- Dem NAS einen unverwechselbaren Namen geben, keinen Standardnamen wie "DiskStation" oder "NAS" (wegen DS-04).
- Als Host die IP-Adresse eintragen. Die Namensauflösung fällt nicht unter das Verbindungs-Timeout (DS-05).

**Datei auf dem Handy als Quelle** (klickbarer UI-Entwurf mit allen Bildschirmen und Texten: https://claude.ai/artifact/KfMCnh64CRBB49s1u6ts3P, privat). Die genauen Texte stehen dort, die wichtigsten sind unten wörtlich übernommen.

| ID | Anforderung | Prio |
|----|-------------|------|
| DS-08 | **Zwei Quellenarten, genau eine aktiv:** die SMB-Freigabe (DS-02) oder eine **Datei auf dem Handy**. Die Datei wählt man über das Android Storage Access Framework (`ACTION_OPEN_DOCUMENT`) mit dauerhafter Leseberechtigung auf die gewählte Datei (persistable read URI permission). Die App braucht keine Speicher-Berechtigung und liest die Datei nur. | SOLL |
| DS-09 | **Dateiauswahl:** Mit `EXTRA_LOCAL_ONLY`, damit keine Cloud-Anbieter erscheinen. MIME-Filter `*/*`, weil KDBX keinen eigenen MIME-Typ hat. Ob die gewählte Datei eine KeePass-Datenbank ist, prüft die App danach selbst an der KDBX-Signatur. | SOLL |
| DS-10 | **Startort der Auswahl** (`EXTRA_INITIAL_URI`), die erste zutreffende Regel gilt: (1) nach einem Fehler (DS-12) der Ordner der abgelehnten Datei, (2) beim Ändern einer Dateiquelle der Ordner der aktuellen Datei, (3) der zuletzt gewählte Ordner (`lastPickedFolder`), falls bekannt, (4) sonst die Wurzel des internen Speichers. Daraus folgt: Die erste Einrichtung beginnt an der Wurzel des internen Speichers. Hat der Nutzer in derselben Einrichtung schon eine Datei gewählt (zurück von Schritt 3 und neu wählen), beginnt die Auswahl im Ordner dieser Datei. Beim Wechsel von SMB zu Datei beginnt sie im zuletzt gewählten Ordner, falls bekannt, sonst an der Wurzel. `lastPickedFolder` wird bei jeder Auswahl gespeichert, auch bei einer abgelehnten (DS-12), und bleibt über Neustarts und Quellenwechsel erhalten. Die App hat keine Funktion, die die Quelle entfernt oder alle Einstellungen zurücksetzt; der Wert verschwindet nur mit den App-Daten (Deinstallation oder „Daten löschen“ in Android). Ob die Auswahl auf dem A55 wirklich dort startet, muss noch geprüft werden (Abschnitt 13). | SOLL |
| DS-11 | **Keine Bestätigungsseite nach der Auswahl.** Die App prüft die Datei still. Dauert die Prüfung länger als 300 ms, zeigt sie die gemeinsame Ladeansicht mit „Lese Datei …“. Ist die Datei in Ordnung, geht es bei der Ersteinrichtung direkt zum Master-Passwort, beim Ändern der Quelle wird der Wechsel übernommen (DS-13). | SOLL |
| DS-12 | **Fehler nach der Auswahl:** Zurück auf dem Bildschirm, von dem die Auswahl geöffnet wurde, zeigt die App eine lange Snackbar (im UI-Entwurf 10 Sekunden) mit der Aktion „Neu wählen“. Die Aktion öffnet die Auswahl wieder im Ordner der abgelehnten Datei (DS-10). Drei Texte, `%1$s` ist der Dateiname: `„%1$s“ ist keine KeePass-Datenbank.` / `„%1$s“ ließ sich nicht lesen.` / `Android gibt keinen dauerhaften Zugriff auf „%1$s“.` Der letzte Text ist nur ein Notnagel für den Fall, dass Android die dauerhafte Berechtigung verweigert. | SOLL |
| DS-13 | **Quelle ändern, Meldung und Sperre:** Nach einem erfolgreichen Wechsel erscheint als einzige Meldung die Snackbar „Quelle gespeichert.“, ohne zusätzliche Hinweissätze davor oder danach. Die Sperrregel bleibt wie in der bestehenden Sitzungs- und Quellenwechsel-Logik, gilt jetzt aber für jeden Wechselweg (Datei zu Datei, Datei zu SMB, SMB zu Datei, SMB zu SMB): Öffnet sich die Datenbank der neuen Quelle mit dem Schlüssel im Arbeitsspeicher, wird nicht gesperrt. Sonst sperrt die App mit der bestehenden Meldung `Gesperrt: Neue Datenbank gewählt. Bitte mit ihrem Master-Passwort entsperren.` (`msg_locked_source_changed`). | SOLL |
| DS-14 | **Dialog „Ältere Version“:** Nur beim Ändern der Quelle (bei der Ersteinrichtung nie, weil es noch keine geöffnete Version gibt) und bei beiden Quellenarten. Er erscheint, wenn die gewählte Datenbank ein älteres Änderungsdatum hat als die geöffnete Version. Titel „Ältere Version“, Text `„%1$s“ ist vom %2$s. Geöffnet ist der Stand vom %3$s.` (Dateiname, Änderungsdatum der gewählten Datei, Stand der geöffneten Version), Buttons „Abbrechen“ (verwirft die Auswahl, die Quelle bleibt unverändert) und „Trotzdem verwenden“ (führt den Wechsel weiter). Grund: Eine alte Kopie hat oft dasselbe Master-Passwort. Ohne den Dialog würde sie sich mit dem Schlüssel im Arbeitsspeicher öffnen und die Offline-Kopie stillschweigend auf den alten Stand zurücksetzen (SY-05 hilft nicht, die Datei lässt sich ja erfolgreich entsperren). Verglichen wird nur dieselbe Datenbank. **Dieselbe Datenbank** heißt: dieselbe UUID der Wurzelgruppe in der KDBX-Datei (sie entsteht mit der Datenbank und bleibt in allen Kopien gleich). Die Prüfung läuft nur, wenn sich die neue Datei mit dem Schlüssel im Arbeitsspeicher öffnen lässt. Dann erscheint der Dialog, wenn die Wurzelgruppe dieselbe UUID hat und die Datei älter ist als die geöffnete Version. Lässt sich die Datei nicht mit dem Schlüssel im Arbeitsspeicher öffnen, gibt es keinen Dialog und es gilt die Sperrregel aus DS-13. **Für die Umsetzung zu prüfen:** ob kotpass die UUID der Wurzelgruppe bereitstellt. | SOLL |
| DS-15 | **Aufräumen beim Wechsel der Quellenart** (im Hintergrund nach dem Speichern, ohne Meldung auf dem Handy): Beim Wechsel von SMB zu Datei löscht die App das gespeicherte SMB-Passwort und die Server-Kennung (GUID). Beim Wechsel von Datei zu SMB gibt sie die dauerhafte Zugriffsberechtigung auf die Datei wieder ab. Beim Wechsel von einer Datei zu einer anderen Datei gibt sie die dauerhafte Zugriffsberechtigung der alten Datei ab, sodass die App immer nur eine hält. Die Berechtigung wird erst abgegeben, nachdem der Wechsel erfolgreich übernommen wurde, nie wenn der Nutzer abbricht (z. B. mit „Abbrechen“ im Dialog „Ältere Version“, DS-14). DS-03 und DS-04 gelten für SMB unverändert. | SOLL |

## 2. Offline-Kopie und Synchronisation

Der Share ist unterwegs ohne VPN nicht erreichbar. Dafür gibt es die Offline-Kopie. Das Entsperren wartet nie auf den Share.

| ID | Anforderung | Prio |
|----|-------------|------|
| SY-01 | Die Offline-Kopie ist **ausschließlich die vollständige KDBX-Datei**, verschlüsselt und byte-identisch mit der Datei auf dem Share. Es werden **niemals** einzelne Passwörter, Einträge oder andere entschlüsselte Inhalte gespeichert: kein Klartext-Cache, kein Suchindex, kein Autofill-Cache auf dem Gerät. | MUSS |
| SY-02 | Jedes Entsperren entschlüsselt die Datei neu. Entschlüsselte Inhalte existieren nur im Arbeitsspeicher, solange die Datenbank entsperrt ist. | MUSS |
| SY-03 | Beim Entsperren öffnet die App sofort die lokale Datei (Kandidat aus SY-06, sonst Offline-Kopie), ohne vorher den Share zu prüfen. | MUSS |
| SY-04 | Nach dem Entsperren prüft die App im Hintergrund, ob die Datei auf dem Share neuer ist (Änderungsdatum, Größe). Wenn ja, lädt sie die Datei vollständig in eine temporäre Datei, entsperrt sie mit dem Schlüssel aus dem Arbeitsspeicher und zeigt dann die neue Version an. | MUSS |
| SY-05 | Eine neue Datei ersetzt die Offline-Kopie erst, wenn sie sich erfolgreich entsperren ließ. So entsteht weder durch einen Verbindungsabbruch noch durch eine untergeschobene Datei eine unbrauchbare Offline-Kopie. | MUSS |
| SY-06 | **Hintergrund-Sync:** Ein periodischer Job lädt alle 4 Stunden eine neuere Datei, wenn der Share erreichbar ist. Er kann sie nicht entsperren und legt sie deshalb als Kandidat neben die Offline-Kopie. Beim nächsten Entsperren wird zuerst der Kandidat versucht. Gelingt das, ersetzt er die Offline-Kopie. | MUSS |
| SY-07 | Manueller "Jetzt synchronisieren"-Button. Ist die Datenbank entsperrt, läuft er wie SY-04, sonst wie SY-06. | MUSS |
| SY-08 | Anzeige, ob die aktuelle Version vom Share oder eine ältere Offline-Kopie geöffnet ist, inkl. Zeitpunkt des letzten erfolgreichen Syncs. "Aktuell" steht nur, wenn die geöffnete Version vom Share kam und seit dem letzten erfolgreichen Sync kein Problem aufgetreten ist (Share nicht erreichbar, Anmeldung fehlgeschlagen, Datei nicht gefunden, kein Zugriff, anderer Server); sonst steht "Offline-Kopie" mit dem passenden Grund und dem Stand des letzten erfolgreichen Syncs. | MUSS |
| SY-09 | **Datei als Quelle:** Auch eine Datei auf dem Handy (DS-08) behält ihre eigene, verschlüsselte Offline-Kopie und gleicht sie alle 4 Stunden ab. Dieser Abschnitt gilt unverändert, einschließlich SY-05. Die Datei übernimmt dabei die Rolle des Shares. Die Gründe für "Offline-Kopie" sind bei einer Datei "Datei nicht gefunden" und "Kein Zugriff mehr" (UI-09). | SOLL |

## 3. KDBX-Kompatibilität (KeePassXC)

Die App liest nur. Schreiben ist nicht vorgesehen.

| ID | Anforderung | Prio |
|----|-------------|------|
| KX-01 | KDBX 4.x lesen (Standardformat von KeePassXC) | MUSS |
| KX-02 | Schlüsselableitung Argon2d und Argon2id, Verschlüsselung AES-256 und ChaCha20 | MUSS |
| KX-03 | KDBX 3.1, AES-KDF, Twofish (mitnehmen, wenn die gewählte Bibliothek sie ohnehin unterstützt) | KANN |
| KX-04 | Zusätzliche Schlüsselfaktoren: Schlüsseldatei (XML v1/v2, binär, Hex) und YubiKey Challenge-Response (HMAC-SHA1) per NFC oder USB. Wird aktuell nicht genutzt. | KANN |
| KX-05 | In KeePassXC gespeicherte Passkeys nutzen (Android Credential Manager) | KANN |

## 4. Entsperren

| ID | Anforderung | Prio |
|----|-------------|------|
| EN-01 | Entsperren durch Eintippen des Master-Passworts. Im Eingabefeld sind Vorschläge, Autokorrektur und das Lernen der Tastatur abgeschaltet (Android: `IME_FLAG_NO_PERSONALIZED_LEARNING`), damit die Tastatur sich das Passwort nicht merkt. | MUSS |
| EN-02 | Entsperren per **Fingerabdruck**. Nur starke Biometrie (Klasse 3), die Gesichtserkennung des A55 zählt nicht dazu. Display-PIN oder Muster sind kein Ausweichweg. Der Ausweichweg ist immer das Master-Passwort. Der Fingerabdruck-Dialog öffnet sich beim Entsperren sofort, Abbrechen führt zur Passworteingabe. | MUSS |
| EN-03 | Gespeichert wird nicht das Master-Passwort, sondern sein SHA-256-Hash (die Passwort-Komponente des KDBX-Schlüssels). Er wird mit einem Schlüssel im Android Keystore verschlüsselt, den das System nur nach erfolgreichem Fingerabdruck freigibt (BiometricPrompt mit CryptoObject, keine reine Ja/Nein-Abfrage). | MUSS |
| EN-04 | Wird ein neuer Fingerabdruck registriert, wird der Keystore-Schlüssel ungültig. Danach muss einmal das Master-Passwort eingegeben werden. | MUSS |
| EN-05 | Das Master-Passwort muss nach einer einstellbaren Frist erneut eingegeben werden: 1, 3, 7 oder 14 Tage, Standard **7 Tage**. Die App prüft das selbst, zusätzlich bekommt der Keystore-Schlüssel bei jeder Passworteingabe ein Ablaufdatum nach dieser Frist. Eine kürzere Frist greift sofort, eine längere erst ab der nächsten Passworteingabe, weil sich das Ablaufdatum des Schlüssels nicht verlängern lässt. Nach Ablauf wird der gespeicherte Wert gelöscht. | MUSS |
| EN-06 | Lässt sich die Datei mit dem gespeicherten Wert nicht öffnen (z. B. weil das Master-Passwort am PC geändert wurde), fragt die App nach dem Master-Passwort und speichert den neuen Wert. Lässt sich ein Kandidat (SY-06) mit dem eingegebenen Passwort nicht öffnen, öffnet die App die Offline-Kopie und weist darauf hin. | MUSS |
| EN-07 | **Zeile unter dem Datenbanknamen:** Der Entsperrbildschirm zeigt bei beiden Quellenarten unter dem Namen „<Ort> · geändert <Datum>“, also wo die Datenbank liegt und wann die Datei zuletzt geändert wurde. So sieht man, welcher Stand geöffnet wird. Bei einer SMB-Quelle ist der Ort der Pfad, wie ihn der Nutzer eingegeben hat. Die Infokarte beim ersten Entsperren entfällt ganz, mit beiden Varianten („Die App speichert nicht dein Passwort ...“, `unlock_first_run_info`, und die Variante ohne Fingerabdruck, `unlock_first_run_info_no_fingerprint`). | SOLL |

## 5. Ausfüllen ohne Zwischenablage (Kernfunktion)

| ID | Anforderung | Prio |
|----|-------------|------|
| AF-01 | **Android Autofill-Dienst** (AutofillService): Die App ist als Autofill-Anbieter im System auswählbar und füllt Benutzername und Passwort in Browser und Apps ein. | MUSS |
| AF-02 | Bei Anmeldefeldern zeigt der Dienst immer einen Vorschlag "KeyPass Mobile" ohne weiteren Zusatz, unabhängig davon, ob es passende Einträge gibt. Er erscheint oben in der Tastatur (Inline-Vorschläge, Android 11+), sonst als Liste unter dem Feld. | MUSS |
| AF-03 | Ein Tipp auf den Vorschlag öffnet die Eintragsliste (UI-01) im Vollbild. Oben steht, wohin ausgefüllt wird (AF-06, AF-07). Ist die Datenbank gesperrt, kommt zuerst das Entsperren. | MUSS |
| AF-04 | Ein Tipp auf einen Eintrag trägt sofort Benutzername und Passwort ein und schließt das Fenster. Werte gehen erst nach der Auswahl an das System, nie vorab für mehrere Einträge. Auch bei genau einem passenden Eintrag erscheint immer die Liste, es wird nie automatisch ausgefüllt. | MUSS |
| AF-05 | Passende Einträge stehen ganz oben. Im Browser nach der Domain der Seite: gleiche registrierbare Domain inkl. Subdomains, ermittelt über eine mitgelieferte Public-Suffix-Liste, ohne Netzwerkzugriff. In Apps nach dem App-Namen, abgeglichen mit Titel und URL der Einträge. In KeePassXC müssen keine Zuordnungen gepflegt werden. | MUSS |
| AF-06 | **Phishing-Schutz im Browser:** Wählt man einen Eintrag, dessen URL nicht zur Domain der Seite passt, erscheint eine deutliche Warnung, die bestätigt werden muss. Passende Einträge werden ohne Rückfrage ausgefüllt. | MUSS |
| AF-07 | In Apps steht oben im Fenster der App-Name, wie ihn das System anzeigt (z. B. "PayPal"), darunter der Paketname (z. B. `com.paypal.android.p2pmobile`), damit sichtbar ist, wohin ausgefüllt wird. Lässt sich der Name nicht lesen, steht der Paketname allein. Weil jede App ihren Namen frei wählen kann, bleibt der Paketname als Hinweis sichtbar. Für Apps gibt es keine Warnung. | MUSS |
| AF-08 | Kein Angebot, neue Zugangsdaten zu speichern, da die App nichts speichert | MUSS |
| AF-09 | **Zwischenablage als letzter Ausweg:** In der direkt geöffneten App (UI-02) lassen sich Benutzername und Passwort auf ausdrücklichen Klick kopieren. Der Inhalt wird als sensibel markiert. Die App löscht die Zwischenablage nicht selbst, weder nach einer Zeit noch beim Sperren (Entscheidung vom 06.11.2026, Abschnitt 13). Das reicht auch für Anmeldeseiten über reines HTTP (z. B. Router), auf denen Brave keinen Autofill-Dienst anfragt. | MUSS |
| AF-10 | **Eigene Tastatur** (Input Method) als Plan B: Eintrag wählen, dann Benutzername und Passwort per Tastendruck eintippen. Wird MUSS, falls Autofill in Brave im Prototyp nicht zuverlässig funktioniert. | SOLL |
| AF-11 | Der Dienst füllt nur sichtbare Felder. Versteckte Felder (z. B. ein noch verborgener zweiter Anmeldeschritt) bleiben leer. | MUSS |

## 6. Bedienung

| ID | Anforderung | Prio |
|----|-------------|------|
| UI-01 | **Eine Ansicht für App und Autofill:** Liste aller Einträge mit Volltextsuche über Titel, Benutzername, URL, Notizen, Tags und Gruppe (nur im Arbeitsspeicher, kein gespeicherter Index). Die Liste ist nach Gruppen gegliedert: ein Abschnitt pro Gruppe mit dem Gruppenpfad als Überschrift (inkl. Untergruppen), darin A–Z, darüber Filter nach Hauptgruppe. Suchergebnisse erscheinen als eine Liste mit der Gruppe in jeder Zeile. Kein aufklappbarer Gruppenbaum, keine Sortierung nach zuletzt benutzt (die App speichert nichts über die Nutzung). Im Autofill stehen darüber die passenden Einträge (AF-05). | MUSS |
| UI-02 | Direkt geöffnet: Ein Tipp auf einen Eintrag öffnet eine knappe Ansicht mit Icon, Titel, Adresse (URL), Gruppe, Benutzername, Notizen und den Buttons "Benutzername kopieren" und "Passwort kopieren" (AF-09). Das Passwort ist zunächst verdeckt und lässt sich mit einem Augen-Symbol ein- und ausblenden. Es wird wieder verdeckt und aus dem Speicher entfernt beim Verlassen der Ansicht, beim Sperren und wenn die App in den Hintergrund geht. | MUSS |
| UI-03 | Einstellungen: Quelle (UI-08), Sync-Status mit "Jetzt synchronisieren" (SY-07, SY-08), Fingerabdruck an/aus, Frist für das Master-Passwort (EN-05), Zeit bis zur automatischen Sperre (SE-02) | MUSS |
| UI-04 | Hell/Dunkel nach Systemeinstellung. Sprache nur Deutsch. | MUSS |
| UI-05 | Eintrags-Icons aus der Datenbank anzeigen: eigene Icons (z. B. in KeePassXC heruntergeladene Favicons) direkt aus der KDBX, Standard-Icons (KeePass-Icon-Nummern) über eine eigene Zuordnung zu Material-Symbolen. Nichts wird aus dem Netz nachgeladen (SE-06). | MUSS |
| UI-06 | Am Ende der Einrichtung zwei Hinweise mit je einem Button, der die passende Systemeinstellung öffnet: KeyPass Mobile als Autofill-Dienst wählen (AF-01) und Benachrichtigungen erlauben (ab Android 13 nötig für die Benachrichtigung aus SE-03). Beides ist auch später aus den Einstellungen erreichbar. | MUSS |
| UI-07 | **Einrichtung mit zwei Quellenarten:** Der erste Bildschirm heißt „Wo liegt deine Datenbank?“ und bietet die beiden Quellenarten an (SMB-Freigabe, Datei auf dem Handy), danach folgt der Server bzw. die Datei. Beide Wege haben vier Schritte: Quelle, Datei, Master-Passwort, System. Bei einer Datei ist die Dateiauswahl von Android der Schritt 2 (DS-09 bis DS-12). Im Serverformular entfällt der Hilfetext „Am besten ein eigener Benutzer ...“ (`setup_field_user_helper`). | SOLL |
| UI-08 | **Quelle in den Einstellungen:** Der Abschnitt heißt „Quelle“ (bisher „Datenbank“, `settings_section_database`). Oben steht ein einzelner Eintrag, der die aktuelle Quelle nur anzeigt, nichts lässt sich dort ändern. Bei einer Datei: Art, Dateiname, Ort. Bei SMB: Art, Pfad, Benutzer, Servername mit „gemerkt am“ und Datum, Server-GUID. Darunter steht die Zeile „Quelle verwalten“. Sie öffnet eine einfache Liste ohne Abschnittsüberschriften und ohne Unterzeilen. Bei einer Dateiquelle: „Andere Datei wählen“ und „Zu SMB-Freigabe wechseln“. Bei einer SMB-Quelle: „SMB-Freigabe ändern“ und „Zu Datei wechseln“. Die Zeile „Quelle verwalten“ hat keine Unterzeile, und der Eintrag einer Dateiquelle hat keine Zeile „Nur Lesezugriff auf diese Datei“. Der Titel dieses Bildschirms (`setup_edit_source_title`) ist „Quelle verwalten“ (bisher „Quelle ändern“). Die Wege sind DS-02, DS-03 (SMB-Passwort immer neu) und DS-09 bis DS-15. | SOLL |
| UI-09 | **Hinweis in der Liste, wenn die Datenbank fehlt oder der Zugriff weg ist, für beide Quellenarten.** Bei einer Datei: „Datei nicht gefunden“ oder „Kein Zugriff mehr“, jeweils mit der Aktion „Datei neu wählen“ (öffnet die Dateiauswahl, DS-10). Bei SMB drei Ursachen mit je Titel, Text und Aktion: Datei nicht gefunden, Kein Zugriff, Anmeldung fehlgeschlagen. Die Aktionen führen in das SMB-Serverformular, das Passwort ist wegen DS-03 leer. Der bestehende Hinweis und Dialog „Anderer Server“ (DS-04) bleibt unverändert. Ein nur nicht erreichbarer Share ist normale Offline-Nutzung und bekommt keinen Hinweis. Der Sync-Status in den Einstellungen nennt dazu den Grund und zeigt dieselbe Aktion als Button (beim anderen Server weiter nur der Dialog). Es steht höchstens ein Hinweis gleichzeitig über der Liste. Ein Hinweis zu fehlender Quelle (Datei nicht gefunden, Kein Zugriff mehr oder eine der drei SMB-Ursachen) hat Vorrang vor dem Hinweis „Neue Version abgelehnt“ (`NewVersionRejected`), weil es ohne erreichbare Quelle keine neue Version gibt. Funktioniert die Quelle wieder, gelten die normalen Regeln. Die Aktionen laufen wie ein Quellenwechsel (DS-13, DS-14). Hinweis zum Stand der App: Die drei SMB-Ursachen (`NOT_FOUND`, `ACCESS_DENIED`, `AUTH_FAILED`) erscheinen bisher nur in den Einstellungen (`SyncMapping.offlineReason`), der Hinweis in der Liste dafür ist neu. | SOLL |

**Texte zu UI-07 und UI-09** (aus dem UI-Entwurf, `<Datum>` ist der Stand der Offline-Kopie, `<Benutzer>` der SMB-Benutzer):

- Einrichtung, Überschrift „Wo liegt deine Datenbank?“, darunter: „KeyPass Mobile liest die KDBX-Datei nur und behält eine verschlüsselte Kopie für unterwegs.“
- Einrichtung, Auswahl „SMB-Freigabe“: „NAS oder Windows-PC im Heimnetz. Unterwegs ohne VPN gilt die Kopie auf dem Handy.“
- Einrichtung, Auswahl „Datei auf dem Handy“: „Zum Beispiel in einem Ordner, den Syncthing aktuell hält. Du wählst sie in der Dateiauswahl von Android.“
- Datei, nicht gefunden: Titel „Datei nicht gefunden“, Text „Geöffnet ist die Offline-Kopie vom <Datum>. Wähl die Datei neu, damit die App wieder abgleichen kann.“, Aktion „Datei neu wählen“. Status in den Einstellungen „Datei nicht gefunden“, Grund „Die Datei wurde verschoben, umbenannt oder gelöscht.“
- Datei, kein Zugriff: Titel „Kein Zugriff mehr auf die Datei“, Text und Aktion wie oben. Status „Kein Zugriff mehr“, Grund „Android lässt die App die Datei nicht mehr lesen. Wählst du sie neu, gibt Android den Zugriff wieder frei.“
- SMB, nicht gefunden: Titel „Datei nicht gefunden“, Text „Auf dem Share liegt sie nicht mehr. Geöffnet ist die Offline-Kopie vom <Datum>. Wähl die Datei neu, damit die App wieder abgleichen kann.“, Aktion „Datei neu wählen“. Status „Datei nicht gefunden“, Grund „Die Datei liegt nicht mehr auf dem Share.“
- SMB, kein Zugriff: Titel „Kein Zugriff auf die Datei“, Text „Der Server lässt <Benutzer> die Datei nicht mehr lesen. Geöffnet ist die Offline-Kopie vom <Datum>. Prüf die Rechte auf dem Share oder wähl eine andere Datei.“, Aktion „SMB-Freigabe ändern“. Status „Kein Zugriff“, Grund „Der Server lässt <Benutzer> die Datei nicht mehr lesen.“
- SMB, Anmeldung fehlgeschlagen: Titel „Anmeldung fehlgeschlagen“, Text „Der Server hat die Anmeldung von <Benutzer> abgelehnt. Geöffnet ist die Offline-Kopie vom <Datum>. Gib die Zugangsdaten neu ein, damit die App wieder abgleichen kann.“, Aktion „SMB-Freigabe ändern“. Status „Anmeldung fehlgeschlagen“, Grund „Der Server hat die Anmeldung von <Benutzer> abgelehnt.“
- `<Datum>` in diesen Texten (und in „Geöffnet ist die Offline-Kopie …“ im Sync-Status der Einstellungen): Ist der Stand von heute, steht „von heute, 00:33“, sonst „vom 28.10., 08:12“ (nicht „vom 00:33“).

## 7. Sicherheit

| ID | Anforderung | Prio |
|----|-------------|------|
| SE-01 | **Sperren beim Wegwischen** aus der App-Übersicht oder bei Prozessende: Die entschlüsselte Datenbank wird verworfen, danach muss neu entsperrt werden. | MUSS |
| SE-02 | **Automatisch sperren** nach einstellbarer Zeit ohne Nutzung: 30 Sekunden, 1, 2 oder 5 Minuten, Standard **2 Minuten**. Als Nutzung zählen Aktionen in der App sowie Autofill- und Tastatur-Vorgänge. Das gilt auch, wenn die App im Hintergrund liegt. | MUSS |
| SE-03 | **Vordergrunddienst, solange entsperrt:** Benachrichtigung "KeyPass Mobile entsperrt" mit Button "Sperren". Der Dienst sorgt dafür, dass SE-01 und SE-02 auch dann greifen, wenn Android die App im Hintergrund einfriert. Nach dem Sperren verschwindet die Benachrichtigung. | MUSS |
| SE-04 | Entschlüsselte Inhalte nie auf Disk, nur im Arbeitsspeicher (SY-01, SY-02). Solange entsperrt, liegt auch der Passwort-Hash im Arbeitsspeicher (für SY-04). Beim Sperren alle Referenzen verwerfen. Kotlin-Strings lassen sich nicht zuverlässig überschreiben, daher wo möglich `ByteArray`/`CharArray` nutzen und nach Gebrauch überschreiben. | MUSS |
| SE-05 | Screenshots und die Vorschau in der App-Übersicht unterbinden (FLAG_SECURE), auch im Autofill-Fenster | MUSS |
| SE-06 | Keine Telemetrie, kein Tracking, keine Netzwerkzugriffe außer zur konfigurierten SMB-Freigabe (auch keine Favicons und keine Online-Abfragen für den Domain-Abgleich) | MUSS |
| SE-07 | Keine Geheimnisse in Logs, Crash-Reports oder Fehlermeldungen | MUSS |
| SE-08 | App-Daten vom Android-Backup ausschließen (Verbindungsdaten, Offline-Kopie, Kandidat, verschlüsselter Passwort-Hash) | MUSS |
| SE-09 | Zusätzlich optional beim Ausschalten des Displays sperren | KANN |
| SE-10 | Abhängigkeiten minimal halten und regelmäßig auf Sicherheitslücken prüfen | SOLL |

**Wovor die App schützt:**

- Verlorenes oder gestohlenes, gesperrtes Handy
- Jemand, der kurz das entsperrte Handy in der Hand hat (automatische Sperre, Fingerabdruck)
- Phishing-Seiten im Browser (AF-06)
- Anmeldung an fremden SMB-Geräten in fremden Netzen (DS-04)

**Wovor die App nicht schützt:**

- Gerootete Geräte und Schadsoftware mit Root-Rechten
- Forensische Auswertung eines entsperrten Handys
- Gezielte Nachahmung des SMB-Servers (die Server-GUID ist nicht geheim). Ein Angreifer könnte dabei das SMB-Passwort abgreifen. Mit einem Benutzer, der nur lesen darf, erhält er damit aber nur die verschlüsselte KDBX-Datei.
- Apps mit gefälschtem Namen (nur der Paketname dient als Hinweis, AF-07)

## 8. Nicht-funktionale Anforderungen

- Plattform: **nur Android**, minSdk 33 (Android 13). Zielgerät: Samsung Galaxy A55 mit One UI 8.5.
- Technik: Kotlin mit Jetpack Compose. UI, Autofill-Dienst, Sync und der entsperrte Zustand laufen in einem Prozess.
- Entsperren (vom Fingerabdruck bzw. Bestätigen des Passworts bis zur Eintragsliste) dauert auf dem Zielgerät **höchstens 2 Sekunden**. Wird das verfehlt, die Entschlüsselungszeit in den Sicherheitseinstellungen der Datenbank in KeePassXC senken.
- Datenbanken mit mehreren tausend Einträgen sind flüssig durchsuchbar.
- Verteilung: privat als signierte Release-APK. Den Signaturschlüssel sicher und außerhalb des Repos aufbewahren. Updates lassen sich nur mit demselben Schlüssel über eine installierte Version installieren. Geht der Schlüssel verloren, muss die App neu installiert und eingerichtet werden. Daten gehen dabei nicht verloren, weil die Datenbank auf dem Share bzw. als Datei auf dem Handy liegt (bei einer Dateiquelle ist die Datei danach neu zu wählen, DS-08).
- Die App bleibt "simpel": kein eigener Cloud-Dienst, kein Konto, kein Abo.
- Quellcode offen (Lizenzen der verwendeten Bibliotheken prüfen; KeePassXC selbst steht unter GPL, das KDBX-Format ist offen dokumentiert).

## 9. Technische Hinweise und Risiken

- **Autofill in Brave:** Brave reicht Autofill ab Version 1.79.119 an fremde Dienste weiter, wenn man in Brave "Autofill using another service" wählt und Brave neu startet. Im Prototyp auf dem A55 bestätigt (Abschnitt 13). Plan B (eigene Tastatur, AF-10) bleibt SOLL.
- **Inline-Vorschläge:** Die Samsung-Tastatur auf dem A55 fordert Inline-Vorschläge an (Abschnitt 13). Ohne Unterstützung zeigt Android den Vorschlag als Liste unter dem Feld.
- **KDBX in Kotlin:** kotpass 0.13.0 (MIT) liest KDBX 3.1, 4.0 und 4.1 mit Argon2d, Argon2id, AES-KDF sowie AES-256, ChaCha20 und Twofish, inkl. zusätzlicher URLs (`KP2A_URL*`), Tags und eigener Felder (im Prototyp mit von KeePassXC 2.7.11 geschriebenen Dateien getestet). Einschränkungen: Feldwerte kommen als String, Schutz im Speicher nur per XOR. Wie aktiv kotpass gepflegt wird, ist noch nicht geprüft.
- **Gruppen und Icons (UI-01, UI-05):** kotpass bildet laut API den Gruppenbaum, die Standard-Icon-Nummer und eigene Icons ab. Im Prototyp noch nicht geprüft, beim MVP mit einer echten KeePassXC-Datei testen.
- **Argon2:** kotpass rechnet Argon2 in reinem Kotlin und single-threaded, auf dem PC gut 4x langsamer als KeePassXC (KeePassXC-Standard: Argon2d, 64 MiB, Parallelität 4, Iterationen auf 1 s am PC kalibriert). Daher natives Argon2 (argon2kt 1.6.0, MIT, JNI) über die austauschbare KDF-Schnittstelle von kotpass einhängen. Damit öffnet die echte Datei auf dem A55 in 1,5 s (Abschnitt 13).
- **Öffnen per Hash (EN-03):** kotpass hat keine eigene API dafür, akzeptiert den 32-Byte-SHA-256-Hash aber als Schlüsseldatei-Komponente. Der zusammengesetzte Schlüssel ist dann identisch (getestet mit 3.1, 4.0, 4.1). Gilt nur für Datenbanken mit reinem Passwort. Die App sollte immer selbst hashen und über den Hash öffnen, weil kotpass beim Öffnen per Passwort eine unbereinigte Kopie hinterlässt.
- **SMB:** `smbj` 0.15.0 (SMB 2/3). Die Server-GUID steht nach dem Verbindungsaufbau und vor der Anmeldung bereit, ein Abbruch an dieser Stelle sendet nachweislich keine Anmeldedaten (Samba-Log geprüft). Signierung verlangen und Verschlüsselung nutzen, wenn angeboten, ist der Standard (nur AES-128). Unter Android über BouncyCastle (MD4 für NTLM). `smbj` wandelt das Passwort intern in einen String um (SE-04 nur eingeschränkt erfüllbar). Ordner einer Freigabe auflisten kann `smbj` selbst. Die Liste der Freigaben braucht dagegen einen RPC-Aufruf (SRVSVC NetShareEnum), den `smbj` nicht mitbringt. Kandidat dafür ist `smbj-rpc` von Rapid7 (Lizenz und Pflegezustand prüfen). Die Server-GUID merkt sich die App schon bei dieser ersten Verbindung (DS-04).
- **Biometrie:** BiometricPrompt mit CryptoObject und `BIOMETRIC_STRONG`. Keystore-Schlüssel mit `setUserAuthenticationRequired(true)`, `setInvalidatedByBiometricEnrollment(true)` und Ablaufdatum (`setKeyValidityForConsumptionEnd`). StrongBox nutzen, wenn verfügbar. Für den Passwort-Hash ein RSA-Schlüsselpaar statt eines AES-Schlüssels: Verschlüsseln mit dem öffentlichen Schlüssel geht ohne Fingerabdruck, Entschlüsseln nur nach Fingerabdruck. So reicht bei fälligem Master-Passwort (EN-05) die Passworteingabe, ohne zusätzlichen Fingerabdruck. Achtung bei RSA-OAEP: Der Keystore unterstützt für MGF1 nur SHA-1, das muss beim Verschlüsseln ausdrücklich angegeben werden.
- **Hintergrund:** Vordergrunddienst für Sperre und `onTaskRemoved` (Wegwischen erkennen). Ab Android 14 braucht er einen Diensttyp; ohne Play Store ist `specialUse` unproblematisch. Hintergrund-Sync über WorkManager.
- **App-Namen:** Für den Abgleich nach App-Namen (AF-05) liest die App den Namen der anfragenden App. Laut Prototyp geht das ohne `<queries>` und ohne `QUERY_ALL_PACKAGES`, weil die Autofill-Anfrage die anfragende App sichtbar macht (Abschnitt 13).
- **Hintergrund-Sync auf Samsung:** Die Akku-Optimierung von One UI kann den periodischen Job (SY-06) verzögern. Falls das im Alltag stört, die App in den Akku-Einstellungen auf "Nicht eingeschränkt" stellen.

## 10. Ausbaustufen

0. **Prototypen (vor allem anderen):**
   - Minimaler Autofill-Dienst: Vorschlag "KeyPass Mobile" in Brave mit Samsung Keyboard auf dem A55, Testdaten eintragen.
   - Die echte Datenbank mit der gewählten KDBX-Bibliothek öffnen und die Entsperrzeit messen.
   - SMB-Download mit `smbj` inkl. Auslesen der Server-GUID vor der Anmeldung.
   - Alles in einer Test-App mit rein zweckmäßiger Oberfläche (Eingabefelder, Buttons, Textausgabe). Die UI wird erst nach den Tests gemeinsam geplant.
1. **MVP (alle MUSS-Anforderungen):**
   - SMB-Quelle mit Server-Prüfung, Offline-Kopie, Kandidat, Hintergrund-Sync und "Jetzt synchronisieren"
   - Entsperren per Fingerabdruck oder Master-Passwort, Master-Passwort nach einstellbarer Frist
   - Autofill-Vorschlag "KeyPass Mobile" mit Eintragsliste, passenden Einträgen oben und Phishing-Warnung
   - Zwischenablage-Fallback (Kopieren auf Klick, als sensibel markiert)
   - Auto-Sperre (Wegwischen / einstellbare Zeit ohne Nutzung) über Vordergrunddienst
   - Hell/Dunkel, SMB-Signierung und -Verschlüsselung (fallen mit Compose bzw. `smbj` praktisch ohne Aufwand ab)
2. **Stufe 2:**
   - Eigene Tastatur (AF-10)
   - Datei auf dem Handy als Quelle über das Android Storage Access Framework (DS-08 bis DS-15, EN-07, SY-09, UI-07 bis UI-09), inklusive Hinweis in der Liste auch bei SMB
3. **Später:** Schlüsseldatei, YubiKey, Passkeys, KDBX 3.1 / AES-KDF / Twofish, Sperren beim Ausschalten des Displays.

## 11. Nicht im Umfang

- iOS, Veröffentlichung im Play Store, Desktop-Versionen
- Bearbeiten, Zurückschreiben, Zusammenführen, Speichern neuer Zugangsdaten, Passwortgenerator (die App ist nur zum Ausfüllen)
- Mehrere Datenbanken, mehrere Nutzer
- Anzeigen von Passwörtern, aufklappbarer Gruppenbaum (Gruppen nur zur Gliederung, UI-01), Anhänge, Eintragshistorie
- TOTP (wird nicht genutzt)
- Entsperren per Display-PIN, Muster oder Gesichtserkennung
- Zuordnung von Apps über `androidapp://`-URLs in der Datenbank
- WebDAV, SFTP, Cloud-APIs (Nextcloud, OneDrive, Google Drive ...). Auch in der Dateiauswahl für die Datei auf dem Handy erscheinen keine Cloud-Anbieter (DS-09).
- KeePassXC-Browser-Protokoll, SSH-Agent, Secret Service
- Eigener Sync-Server oder Cloud-Dienst
- Import/Export anderer Formate (CSV, 1Password, Bitwarden ...)

## 12. Änderungen gegenüber Entwurf 1

Ergebnis der Prüfung der Anforderungen. Entwurf 1 liegt in `ANFORDERUNGEN.entwurf1.md`.

- **Selbst bauen:** Bestätigt. Keepass2Android deckt vieles ab, aber nicht direktes SMB, und Apps, die SMB als Ordner einbinden, haben nicht funktioniert.
- **Kotlin statt Flutter:** SMB, Autofill, Tastatur, Keystore, Biometrie, Argon2 und die Sperre sind ohnehin nativ. Der Autofill-Dienst braucht die entsperrte Datenbank ohne laufende UI. Mit Kotlin gibt es dafür nur einen Ort im Arbeitsspeicher statt zwei Laufzeiten.
- **Fingerabdruck statt Passwort bei jedem Entsperren:** Mit 2-Minuten-Sperre wäre sonst bei fast jedem Login das Master-Passwort fällig. Gespeichert wird nur der Hash, gebunden an den Fingerabdruck, alle 7 Tage Master-Passwort.
- **Nur Ausfüllen, dauerhaft:** Bearbeiten, Zusammenführen und Zurückschreiben (Stufe 2 in Entwurf 1) entfallen ganz, ebenso Passwortgenerator, Gruppenbaum, Anhänge, Historie und TOTP.
- **Eine Datenbank** statt mehrerer.
- **Autofill über festen Vorschlag und Auswahl** statt automatischer Zuordnung: Funktioniert in Browser und Apps ohne Pflege in KeePassXC. Phishing-Schutz über den Domain-Abgleich im Browser, in Apps wird der Paketname angezeigt.
- **Sync:** Das Entsperren wartet nicht mehr auf den Share. Neue Versionen werden im Hintergrund geladen und ersetzen die Offline-Kopie erst nach erfolgreichem Entsperren. Neu sind der Hintergrund-Sync mit Kandidat und die Prüfung der Server-GUID statt einer WLAN-Erkennung.
- **Vordergrunddienst mit Benachrichtigung**, damit die Sperre auch im Hintergrund zuverlässig greift.
- **minSdk 33** statt 26, weil nur ein aktuelles Gerät unterstützt wird.
- **Prototypen zuerst**, weil Autofill in Brave das größte Risiko ist.

## 13. Offene Fragen

Werden durch die Prototypen (Abschnitt 10, Schritt 0) beantwortet. Stand nach dem Emulatortest vom 31.10.2026 (Android 16, x86_64, Gboard). Die Zeiten aus dem Emulator sind nicht auf das A55 übertragbar.

- Funktioniert Autofill in Brave mit Samsung Keyboard zuverlässig? Falls nein, wird die eigene Tastatur (AF-10) MUSS.
  - Emulator: Ja, mit Gboard (Vorschlag in der Tastatur und als Liste unter dem Feld), nachdem in Brave "Autofill using another service" gewählt und Brave neu gestartet wurde. Domain-Abgleich und Phishing-Warnung funktionieren.
  - Einschränkung: Brave fragt den Dienst nur auf HTTPS-Seiten und localhost an. Auf reinen HTTP-Seiten (z. B. Router unter `http://192.168.178.1`) kommt keine Anfrage (Verhalten von Chromium).
  - A55 (One UI 8.5, Samsung Keyboard): Ja. Die Samsung-Tastatur fordert Inline-Vorschläge an und zeigt "KeyPass Mobile" oben in der Tastatur, in Brave (mit und ohne `autocomplete`-Attribute) ebenso wie in Apps (PayPal, Sparkasse). Die eigene Tastatur (AF-10) bleibt SOLL.
- Wird die Entsperrzeit von höchstens 2 Sekunden mit der echten Datenbank und nativem Argon2 auf dem A55 erreicht?
  - Emulator: Testdatei mit Argon2d, 64 MiB, Parallelität 4, 100 Iterationen: 2,6 s mit nativem Argon2, 25,6 s mit kotpass in reinem Kotlin (etwa 10x langsamer). Natives Argon2 ist damit Pflicht. 3000 Einträge entschlüsseln und einlesen: etwa 0,9 s.
  - A55: Ja. Echte Datenbank mit nativem Argon2: 1,5 s gesamt (davon Liste aufbauen 9 ms). Mit kotpass in reinem Kotlin: 13,2 s, also etwa 9x langsamer.
- Laufen `smbj` und BouncyCastle unter Android, und wie schnell ist der Download auf dem Handy?
  - Emulator: Ja. NTLM-Anmeldung (MD4 über BouncyCastle), Signierung und Verschlüsselung (SMB 3.1.1, AES-128-GCM) funktionieren. Bei falscher GUID bricht die App vor der Anmeldung ab, laut Samba-Log kommt keine Anmeldung an. Download etwa 3,5 MiB/s, begrenzt durch das Netz des Emulators. Der Verbindungsaufbau dauert etwa 0,5 s (am PC 9 ms), Ursache noch unklar.
  - A55: Ja. Die echte Datenbank (265.530 Bytes) lädt per WLAN in 276 ms, inklusive Verbindungsaufbau und Anmeldung.
- Welche GUID liefert das echte NAS, und entspricht sie seinem Namen?
  - A55: `74696572-6e61-2d6f-6e61-730000000000`. Das ist der Name "reitano-nas" als ASCII, aufgefüllt mit Nullen. Die GUID ist also nicht zufällig, sondern aus dem Namen abgeleitet. Das Risiko ist in Abschnitt 7 bereits berücksichtigt (Nachahmung des Servers, Benutzer nur mit Leserechten).
- Kann die App den Namen fremder Apps ohne zusätzliche Berechtigung lesen (AF-05)?
  - Emulator: Ja. Ohne `<queries>` und ohne `QUERY_ALL_PACKAGES` zeigt die App "Brave (com.brave.browser)" an, weil die Autofill-Anfrage die anfragende App sichtbar macht.
  - A55: Ja, auch in Apps, die kein Browser sind (PayPal-App `com.paypal.android.p2pmobile`).

Bereits beantwortet: `smbj` stellt die Server-GUID vor der Anmeldung bereit (siehe Abschnitt 9).

Nach dem Emulatortest entschieden:

- Anmeldeseiten über reines HTTP (z. B. Router-Oberfläche): Die Zwischenablage reicht (AF-09), keine eigene Tastatur nur dafür.
- Versteckte Passwortfelder werden nicht gefüllt (AF-11). Der Prototyp füllt sie noch mit, das wird erst im MVP umgesetzt.

Nach den Prototypen:

- Gestaltung der Oberfläche: erledigt am 31.10.2026. Klickbarer UI-Entwurf mit allen Bildschirmen und Entscheidungen: https://claude.ai/artifact/7efcpsCWoK55cPQBWFNmq7 (privat). Die Entscheidungen stehen in den Anforderungen oben (DS-02, EN-02, EN-05, AF-02 bis AF-04, AF-09, UI-01 bis UI-05, SE-02, SY-06).

Vor dem MVP entschieden (01.11.2026):

- DS-06, DS-07 und UI-04 gehören zum MVP, weil sie praktisch ohne Aufwand abfallen.
- Neu UI-06: Hinweise für Autofill-Dienst und Benachrichtigungen am Ende der Einrichtung (fehlten im UI-Entwurf).
- Passwort-Hash mit RSA-Schlüsselpaar im Keystore, damit bei fälligem Master-Passwort kein zusätzlicher Fingerabdruck nötig ist (Abschnitt 9, Biometrie).
- Paketname der App: `de.lulurobb.keypassmobile`.

Nach dem Test auf dem A55 entschieden (06.11.2026, Entscheidung des Projektinhabers):

- **Kein automatisches Löschen der Zwischenablage** (AF-09, UI-03, SE-03). Die App löscht kopierte Benutzernamen und Passwörter weder nach einer Zeit noch beim Sperren. Die Einstellung "Kopiertes löschen nach" (15, 30 oder 60 Sekunden) ist entfallen, ebenso der Hinweis zur Samsung-Tastatur. Grund: Das Löschen ist unzuverlässig. Tastaturen mit eigenem Zwischenablage-Verlauf (z. B. die Samsung-Tastatur auf dem A55) behalten den kopierten Wert trotzdem, und diesen Verlauf kann die App nicht löschen. Der Aufwand dafür (Löschmarke mit Keystore-HMAC, Alarm als Rückfallebene, Prüfung beim Start und beim Fortsetzen) steht in keinem Verhältnis zum Nutzen. Kopiertes bleibt in der Zwischenablage, bis etwas anderes kopiert wird. Es wird weiterhin als sensibel markiert, damit Android die Vorschau ausblendet. Der bevorzugte Weg bleibt Autofill (AF-01).

Datei auf dem Handy als Quelle (06.11.2026, Entscheidung des Projektinhabers im klickbaren UI-Entwurf https://claude.ai/artifact/KfMCnh64CRBB49s1u6ts3P, privat):

- Die Datei ist neben der SMB-Freigabe eine zweite Quellenart und steht nicht mehr unter "Später". Die Entscheidungen stehen in DS-08 bis DS-15, EN-07, SY-09 und UI-07 bis UI-09, Cloud-APIs bleiben außerhalb des Umfangs (Abschnitt 11).
- Der Hinweis in der Liste bei fehlender Datenbank oder verlorenem Zugriff gilt für beide Quellenarten, auch für SMB (UI-09).
- Am 07.11.2026 auf dem A55 bestanden (Rückmeldung des Projektinhabers): Startet die Dateiauswahl auf dem Samsung A55 am gewünschten Ort (`EXTRA_INITIAL_URI`, DS-10)?
- Entschieden: Für den Dialog "Ältere Version" gilt dieselbe UUID der Wurzelgruppe als dieselbe Datenbank, geprüft nur bei einer Datei, die sich mit dem Schlüssel im Arbeitsspeicher öffnen lässt (DS-14).
- Geprüft: kotpass stellt die UUID der Wurzelgruppe bereit (DS-14), als `DatabaseContent.group.uuid` (kotpass 0.13.0). Fehlt sie in der Datei, bricht kotpass schon beim Lesen ab, der Wert stammt also immer aus der Datei.
- Umgesetzt. Der Test auf dem A55 mit dem Dev-Build 0.1.0-dev.1 ist am 07.11.2026 bestanden (Rückmeldung des Projektinhabers), die Entscheidungen D1 bis D12 (Details in `MVP-STAND.md`) sind am 07.11.2026 vom Projektinhaber bestätigt. Offen bleiben nur die drei Langzeitpunkte (Syncthing-Ersetzen, 4-Stunden-Job ohne Netz, Berechtigung nach Neustart). Die ursprüngliche Prüfliste für das A55:
  - Startet die Dateiauswahl in allen vier Fällen von DS-10 wirklich am gewünschten Ort?
  - Blendet `EXTRA_LOCAL_ONLY` Google Drive, OneDrive und Samsung Cloud aus?
  - Ortstexte und Titel der Wurzeln bei SD-Karte und anderen Anbietern. Eine normale App kann Wurzeltitel und `findDocumentPath` vermutlich nicht abfragen, die Rückfallebene ist dann kein Ortstext.
  - `raw:`-Ordner in Downloads als Startort.
  - Syncthing: Behält es die Änderungszeit, bleibt die URI stabil, und überlebt die Berechtigung das Ersetzen der Datei und einen Neustart?
  - Der 4-Stunden-Job ohne Netz unter der Akku-Optimierung von One UI.
  - Wird die gespeicherte Berechtigung zuverlässig erkannt? Der URI-Text der gewählten Datei muss dem Eintrag in `persistedUriPermissions` entsprechen, auch nach einem Neustart des Handys und nachdem Syncthing die Datei ersetzt hat.
  - Anbieter ohne Änderungsdatum: Eine Änderung ohne Größenänderung würde nicht erkannt. Der interne Speicher und Downloads liefern beides; prüfen, welche Anbieter auf dem A55 tatsächlich vorkommen.
