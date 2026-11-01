<#
Creates ..\src\test\resources\keepassxc-structure.kdbx with KeePassXC 2.7.

Verifies what the prototype did not: groups, standard icons, custom icons,
recycle bin, entry templates group and history in a file that KeePassXC
itself wrote. Everything is done by keepassxc-cli:

  1. import structure.xml (nested groups with umlauts, standard and custom
     icons, tags, additional URLs, multi-line notes, a history entry, an
     entry templates group, an unprotected password). -t 100 gives AES-KDF,
     KDBX 4.1 (needed for tags and custom icon names).
  2. rm "Sonstiges/Wegwerfen": KeePassXC creates the recycle bin itself and
     moves the entry there.
  3. rmdir "Alt": KeePassXC moves the group with its subgroup "Archiv" into
     the recycle bin.
  4. edit -u administrator "Sonstiges/Router": KeePassXC adds a history entry.

Each step opens and saves the file with KeePassXC's own code.

Usage:  powershell -ExecutionPolicy Bypass -File create-structure-testdb.ps1
The password is a test value only. Keep it in sync with TestDbs.kt.
#>
param(
    [string]$KeePassXcCli = 'C:\Program Files\KeePassXC\keepassxc-cli.exe',
    # Defaults to ..\src\test\resources; set it to write somewhere else.
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 3

$kdbxDir = Split-Path -Parent $PSScriptRoot
if ($OutDir -eq '') { $OutDir = Join-Path $kdbxDir 'src\test\resources' }
$work = Join-Path $kdbxDir 'build\testdb-work-structure'
$password = 'kpm-Test-2026!'
$name = 'keepassxc-structure.kdbx'

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

if (-not (Test-Path $KeePassXcCli)) { throw "keepassxc-cli not found: $KeePassXcCli" }
Write-Host "Using keepassxc-cli $(& $KeePassXcCli --version)"

if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force $work | Out-Null
New-Item -ItemType Directory -Force $OutDir | Out-Null

$xml = Join-Path $PSScriptRoot 'structure.xml'
$db = Join-Path $work $name

Invoke-KeePassXc -StdinLines @($password, $password) -Arguments @('import', '-q', '-p', '-t', '100', $xml, $db) | Out-Null
Write-Host "Imported structure.xml"

Invoke-KeePassXc -StdinLines @($password) -Arguments @('rm', '-q', $db, 'Sonstiges/Wegwerfen') | Out-Null
Write-Host "Recycled entry Sonstiges/Wegwerfen"

Invoke-KeePassXc -StdinLines @($password) -Arguments @('rmdir', '-q', $db, 'Alt') | Out-Null
Write-Host "Recycled group Alt"

Invoke-KeePassXc -StdinLines @($password) -Arguments @('edit', '-q', '-u', 'administrator', $db, 'Sonstiges/Router') | Out-Null
Write-Host "Edited Sonstiges/Router (history entry)"

$target = Join-Path $OutDir $name
Copy-Item -Force $db $target

# Show the result as KeePassXC sees it.
$b = [IO.File]::ReadAllBytes($target)
Write-Host ('{0}: KDBX {1}.{2}, {3} bytes' -f $name, [BitConverter]::ToUInt16($b, 10), [BitConverter]::ToUInt16($b, 8), $b.Length)
Invoke-KeePassXc -StdinLines @($password) -Arguments @('db-info', '-q', $target) | ForEach-Object { Write-Host "    $_" }
Write-Host 'Groups and entries:'
Invoke-KeePassXc -StdinLines @($password) -Arguments @('ls', '-q', '-R', $target) | ForEach-Object { Write-Host "    $_" }
Write-Host "Done: $target"
