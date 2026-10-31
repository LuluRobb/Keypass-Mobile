<#
Creates the KDBX test databases in ..\src\test\resources with KeePassXC 2.7.

keepassxc-cli 2.7 only creates AES-KDF/AES-256 databases (KDBX 3.1, or KDBX
4.x when the content needs it) and cannot choose KDF or cipher. For the Argon2/ChaCha20/Twofish variants the Gradle task
:kdbx:createTestDbTargets (kotpass) writes an empty KDBX 4.0 file with the
wanted settings, then "keepassxc-cli merge" copies the content of a KeePassXC
import into it. KeePassXC rewrites the whole file when saving (new master
seed, new KDF salt, key derived by its own Argon2), so all test files are
written by KeePassXC itself.

Usage:  powershell -ExecutionPolicy Bypass -File create-testdbs.ps1
The passwords are test values only. Keep them in sync with TestDbs.kt.
#>
param(
    [string]$KeePassXcCli = 'C:\Program Files\KeePassXC\keepassxc-cli.exe',
    [string]$JavaHome = 'C:\Program Files\Java\jdk-25.0.2'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

$kdbxDir = Split-Path -Parent $PSScriptRoot
$protoDir = Split-Path -Parent $kdbxDir
$resources = Join-Path $kdbxDir 'src\test\resources'
# Relative to the module directory and without spaces, for Gradle's --args.
$workRel = 'build/testdb-work'
$work = Join-Path $kdbxDir 'build\testdb-work'

$password = 'kpm-Test-2026!'
# Same as TestDbs.PASSWORD_UMLAUT, built from code points so that this file
# stays ASCII (Windows PowerShell 5.1 reads scripts without BOM as ANSI).
$passwordUmlaut = 'Pr' + [char]0x00FC + 'fung-' + [char]0x00C4 + [char]0x00DF + '-2026'

# keepassxc-cli reads passwords from stdin as UTF-8.
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)

function Invoke-KeePassXc([string[]]$StdinLines, [string[]]$Arguments) {
    $stdin = ($StdinLines -join "`n") + "`n"
    $output = $stdin | & $KeePassXcCli @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "keepassxc-cli $($Arguments[0]) failed with exit code $LASTEXITCODE"
    }
    return $output
}

function Import-KeePassXml([string]$Xml, [string]$Target, [string]$Pw, [int]$DecryptionTimeMs = 0) {
    $cliArgs = @('import', '-q', '-p')
    if ($DecryptionTimeMs -gt 0) { $cliArgs += @('-t', "$DecryptionTimeMs") }
    $cliArgs += @($Xml, $Target)
    Invoke-KeePassXc -StdinLines @($Pw, $Pw) -Arguments $cliArgs | Out-Null
    Write-Host "Imported $(Split-Path -Leaf $Xml) -> $(Split-Path -Leaf $Target)"
}

function Get-Base64Uuid([string]$Seed) {
    $md5 = [System.Security.Cryptography.MD5]::Create()
    return [Convert]::ToBase64String($md5.ComputeHash([Text.Encoding]::UTF8.GetBytes($Seed)))
}

# 3000 entries in 10 groups. Patterns are relied upon by KdbxProbeTest:
# title "Konto NNNN <word>", notes for every 3rd entry, tag "Wichtig" for
# every 5th, "Privat" for every 7th, an additional URL for every 4th.
function New-LargeXml([string]$Path, [int]$Count) {
    $words = 'Bank', 'Mail', 'Shop', 'Forum', 'Cloud', 'Spiel', 'Reise', 'Arbeit', 'Strom', 'Versicherung'
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.AppendLine('<?xml version="1.0" encoding="utf-8" standalone="yes"?>')
    [void]$sb.AppendLine('<KeePassFile><Meta><Generator>kpm-testdata</Generator><DatabaseName>KPM Test 3000</DatabaseName></Meta><Root>')
    [void]$sb.AppendLine("<Group><UUID>$(Get-Base64Uuid 'large-root')</UUID><Name>Root</Name>")
    $perGroup = [int]($Count / 10)
    for ($g = 0; $g -lt 10; $g++) {
        [void]$sb.AppendLine("<Group><UUID>$(Get-Base64Uuid "large-group-$g")</UUID><Name>Gruppe $g</Name>")
        for ($i = $g * $perGroup; $i -lt ($g + 1) * $perGroup; $i++) {
            $word = $words[$i % 10]
            $tags = @()
            if ($i % 5 -eq 0) { $tags += 'Wichtig' }
            if ($i % 7 -eq 0) { $tags += 'Privat' }
            $notes = if ($i % 3 -eq 0) { 'Notiz zu Konto {0:D4}' -f $i } else { '' }
            [void]$sb.Append("<Entry><UUID>$(Get-Base64Uuid "large-entry-$i")</UUID>")
            if ($tags.Count -gt 0) { [void]$sb.Append("<Tags>$($tags -join ';')</Tags>") }
            [void]$sb.Append(('<String><Key>Title</Key><Value>Konto {0:D4} {1}</Value></String>' -f $i, $word))
            [void]$sb.Append("<String><Key>UserName</Key><Value>nutzer$i@example.com</Value></String>")
            [void]$sb.Append("<String><Key>Password</Key><Value ProtectInMemory=`"True`">pw-$i-Test</Value></String>")
            [void]$sb.Append("<String><Key>URL</Key><Value>https://$($word.ToLowerInvariant())$($i % 100).example.com/login</Value></String>")
            [void]$sb.Append("<String><Key>Notes</Key><Value>$notes</Value></String>")
            if ($i % 4 -eq 0) {
                [void]$sb.Append("<String><Key>KP2A_URL</Key><Value>https://alt$i.example.org</Value></String>")
            }
            [void]$sb.AppendLine('</Entry>')
        }
        [void]$sb.AppendLine('</Group>')
    }
    [void]$sb.AppendLine('</Group></Root></KeePassFile>')
    [IO.File]::WriteAllText($Path, $sb.ToString(), (New-Object System.Text.UTF8Encoding($false)))
    Write-Host "Generated $(Split-Path -Leaf $Path) with $Count entries"
}

function Get-KdbxVersion([string]$Path) {
    $b = [IO.File]::ReadAllBytes($Path)
    return '{0}.{1}' -f [BitConverter]::ToUInt16($b, 10), [BitConverter]::ToUInt16($b, 8)
}

if (-not (Test-Path $KeePassXcCli)) { throw "keepassxc-cli not found: $KeePassXcCli" }
Write-Host "Using $(& $KeePassXcCli --version)"

if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null
New-Item -ItemType Directory -Force $resources | Out-Null

$basicXml = Join-Path $PSScriptRoot 'basic.xml'
$featuresXml = Join-Path $PSScriptRoot 'kdbx41-features.xml'
$largeXml = Join-Path $work 'large-3000.xml'
New-LargeXml -Path $largeXml -Count 3000

# 1. Content created by KeePassXC. -t 100 is the lowest decryption time the CLI accepts.
Import-KeePassXml $basicXml (Join-Path $work 'cli-default.kdbx') $password
Import-KeePassXml $basicXml (Join-Path $work 'src-basic.kdbx') $password 100
Import-KeePassXml $basicXml (Join-Path $work 'src-basic-umlaut.kdbx') $passwordUmlaut 100
Import-KeePassXml $featuresXml (Join-Path $work 'kdbx41-aeskdf-keepassxc.kdbx') $password 100
Import-KeePassXml $largeXml (Join-Path $work 'src-large.kdbx') $password 100

# 2. Empty KDBX 4 targets with the wanted KDF and cipher (kotpass).
# Fast settings keep the unit tests quick. The GUI default file uses what the
# KeePassXC 2.7 GUI sets for a new database: Argon2d, AES-256, 64 MiB,
# parallelism min(CPU threads, 4) and iterations from a benchmark for 1 s
# (rounds = 4 * 1000 ms / time for 4 rounds). On the dev PC (8 threads)
# 4 rounds took about 39 ms, so the GUI would choose about 100 iterations;
# KeePassXC then needs about 0.85 s to open the file. Fixed for reproducibility.
$guiDefaultIterations = 100
$targets = @(
    # name, kdf, cipher, iterations, memoryKiB, parallelism, password id, source
    @('argon2d-aes.kdbx', 'argon2d', 'aes', 2, 16384, 2, 'default', 'src-basic.kdbx'),
    @('argon2id-aes.kdbx', 'argon2id', 'aes', 2, 16384, 2, 'default', 'src-basic.kdbx'),
    @('argon2d-chacha20.kdbx', 'argon2d', 'chacha20', 2, 16384, 2, 'default', 'src-basic.kdbx'),
    @('argon2d-twofish.kdbx', 'argon2d', 'twofish', 2, 16384, 2, 'default', 'src-basic.kdbx'),
    @('argon2id-umlaut-password.kdbx', 'argon2id', 'aes', 2, 16384, 2, 'umlaut', 'src-basic-umlaut.kdbx'),
    @('kdbx41-features.kdbx', 'argon2d', 'aes', 2, 16384, 2, 'default', 'kdbx41-aeskdf-keepassxc.kdbx'),
    @('large-3000.kdbx', 'argon2d', 'aes', 2, 16384, 2, 'default', 'src-large.kdbx'),
    @('gui-default-argon2d.kdbx', 'argon2d', 'aes', $guiDefaultIterations, 65536, 4, 'default', 'src-basic.kdbx')
)
$specs = $targets | ForEach-Object { '{0}/{1},{2},{3},{4},{5},{6},{7}' -f $workRel, $_[0], $_[1], $_[2], $_[3], $_[4], $_[5], $_[6] }
$env:JAVA_HOME = $JavaHome
& (Join-Path $protoDir 'gradlew.bat') -p $protoDir -q ':kdbx:createTestDbTargets' "--args=$($specs -join ' ')"
if ($LASTEXITCODE -ne 0) { throw "Gradle task :kdbx:createTestDbTargets failed" }

# 3. KeePassXC merges the content into each target and saves it.
foreach ($t in $targets) {
    $pw = if ($t[6] -eq 'umlaut') { $passwordUmlaut } else { $password }
    Invoke-KeePassXc -StdinLines @($pw) -Arguments @('merge', '-q', '-s', (Join-Path $work $t[0]), (Join-Path $work $t[7])) | Out-Null
    Write-Host "Merged $($t[7]) -> $($t[0])"
}

# 4. Copy the results and show what KeePassXC reports for each file.
$final = @('cli-default.kdbx', 'kdbx41-aeskdf-keepassxc.kdbx') + ($targets | ForEach-Object { $_[0] })
foreach ($name in $final) {
    $src = Join-Path $work $name
    Copy-Item -Force $src (Join-Path $resources $name)
    $pw = if ($name -like '*umlaut*') { $passwordUmlaut } else { $password }
    $info = Invoke-KeePassXc -StdinLines @($pw) -Arguments @('db-info', '-q', $src)
    $details = $info | Where-Object { $_ -match '^(KDF|Verschl|Encryption|Cipher|Anzahl der (Gruppen|Eintr)|Number of (groups|entries))' }
    Write-Host ("{0}: KDBX {1}, {2} bytes" -f $name, (Get-KdbxVersion $src), (Get-Item $src).Length)
    $details | ForEach-Object { Write-Host "    $_" }
}
Write-Host "Done. Files in $resources"
