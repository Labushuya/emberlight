<#
.SYNOPSIS
    Erzeugt den Release-Signierschlüssel für emberlight und hinterlegt ihn als GitHub-Secrets.

.DESCRIPTION
    Erzeugt eine PKCS12-Keystore-Datei (RSA 4096, 100 Jahre gültig) mit zufälligem Passwort.
    Danach werden in den Zielordner eine LIESMICH.txt mit Passwort, Alias und Fingerprint geschrieben
    und die Rechte auf den aktuellen Benutzer eingeschränkt. Ohne -SkipGitHub setzt das Skript die
    Repository-Secrets KEYSTORE_BASE64, KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD und die Variable
    RELEASE_CERT_SHA256.

    Das Skript ist für die einmalige Erstellung gedacht. Existiert im Repository bereits das Secret
    KEYSTORE_BASE64, bricht es ab. Ein neuer Schlüssel würde alle Updates installierter Versionen
    brechen. Es gibt bewusst KEINEN Überschreib-Schalter.

    Technik:
    - Passwörter gehen über -storepass:env und -keypass:env an keytool, nie als Kommandozeilenargument.
      Die Umgebungsvariable existiert nur im Prozess dieses Skripts und wird im finally-Block gelöscht.
    - Werte für gh gehen über stdin, und zwar aus einer Datei ohne Zeilenumbruch (Start-Process
      -RedirectStandardInput). Die PowerShell-Pipe würde einen CRLF anhängen.
      Ergebnis der Prüfung: gh 2.101.0 entfernt abschließende Zeilenumbrüche selbst.
      pkg/cmd/secret/set/set.go und pkg/cmd/variable/set/set.go, Funktion getBody:
      bytes.TrimRight(body, "\r\n"). Die Dateiübergabe ist trotzdem sauber, weil sie keinen Zeilenumbruch anhängt.
    - Die Datei mit dem Wert liegt im geschützten Zielordner und wird sofort nach dem Aufruf gelöscht.

.PARAMETER OutputDir
    Zielordner für Keystore und LIESMICH.txt. Darf nicht in einem Git-Arbeitsverzeichnis liegen.
    Existiert er noch nicht, wird der nächste vorhandene Elternordner geprüft.

.PARAMETER Alias
    Schlüssel-Alias im Keystore. Standard: emberlight-signing. Bewusst nicht "emberlight", weil GitHub
    Secret-Werte in Logs schwärzt, die dann mit dem Alias übereinstimmen könnten.

.PARAMETER Repo
    GitHub-Repository im Format Besitzer/Name. Standard: Labushuya/emberlight.

.PARAMETER SkipGitHub
    Erzeugt nur Keystore und LIESMICH.txt. Es werden keine Secrets und keine Variablen gesetzt.

.EXAMPLE
    .\scripts\new-release-keystore.ps1 -OutputDir <keys-verzeichnis>

.EXAMPLE
    .\scripts\new-release-keystore.ps1 -OutputDir $env:TEMP\emberlight-test -SkipGitHub
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$OutputDir,

    [string]$Alias = 'emberlight-signing',

    [string]$Repo = 'Labushuya/emberlight',

    [switch]$SkipGitHub
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version 2.0

$KeystoreName = 'emberlight-release.p12'
$ReadmeName = 'LIESMICH.txt'
$PasswordEnvVar = 'EMBERLIGHT_KEYSTORE_PASS_TMP'
$DistinguishedName = 'CN=emberlight, O=Labushuya, C=DE'
$ForeignKeyFileExtensions = @('.p12', '.pfx', '.jks', '.keystore')

# Führt ein natives Programm aus und gibt Exit-Code und Ausgabe zurück. Fehlerausgabe von stderr
# wird nicht als Ausnahme behandelt, die Aufrufer prüfen den Exit-Code selbst.
function Invoke-Native {
    param(
        [Parameter(Mandatory = $true)][string]$FilePath,
        [string[]]$Arguments = @()
    )
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $lines = @(& $FilePath @Arguments 2>&1 | ForEach-Object { $_.ToString() })
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Output = $lines }
}

# Liefert den nächsten existierenden Ordner (Pfad selbst oder ein Elternordner).
function Get-ExistingAncestor {
    param([Parameter(Mandatory = $true)][string]$Path)
    $current = $Path
    while (-not (Test-Path -LiteralPath $current -PathType Container)) {
        if (Test-Path -LiteralPath $current -PathType Leaf) {
            throw "Der Pfad '$current' ist eine Datei, kein Ordner."
        }
        $parent = Split-Path -Parent $current
        if ([string]::IsNullOrEmpty($parent) -or $parent -eq $current) {
            throw "Es existiert kein Elternordner für '$Path'."
        }
        $current = $parent
    }
    return $current
}

# Schränkt Zugriff auf den aktuellen Benutzer ein. Vererbung wird entfernt.
# Die Konten werden über SIDs angegeben, damit es in jeder Windows-Sprache funktioniert. SYSTEM (S-1-5-18)
# und Administratoren (S-1-5-32-544) werden explizit entfernt: icacls /inheritance:r macht geerbte Einträge
# nur zu expliziten Kopien (getestet unter Windows 10, Get-Acl zeigt danach IsInherited=False).
function Limit-AclToCurrentUser {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [switch]$Directory
    )
    $sid = [System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if ($Directory) {
        $grant = "*${sid}:(OI)(CI)F"
    } else {
        $grant = "*${sid}:F"
    }
    $result = Invoke-Native -FilePath 'icacls.exe' -Arguments @(
        $Path, '/inheritance:r', '/remove', '*S-1-5-18', '*S-1-5-32-544', '/grant:r', $grant
    )
    if ($result.ExitCode -ne 0) {
        throw "icacls konnte die Rechte für '$Path' nicht einschränken (Exit-Code $($result.ExitCode))."
    }
}

function New-HexPassword {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($bytes)
    } finally {
        $rng.Dispose()
    }
    return ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

function Find-Keytool {
    if (-not [string]::IsNullOrEmpty($env:JAVA_HOME)) {
        $candidate = [IO.Path]::Combine($env:JAVA_HOME, 'bin', 'keytool.exe')
        if (Test-Path -LiteralPath $candidate -PathType Leaf) {
            return $candidate
        }
    }
    $command = Get-Command -Name 'keytool.exe' -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        return $command.Source
    }
    return $null
}

# Setzt ein Repository-Secret oder eine Repository-Variable über stdin. Der Wert liegt dafür kurz in einer
# Datei ohne Zeilenumbruch im geschützten Zielordner. Siehe Kommentar im Skriptkopf zu gh und Zeilenumbrüchen.
function Set-GitHubValue {
    param(
        [Parameter(Mandatory = $true)][ValidateSet('secret', 'variable')][string]$Kind,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][string]$Value,
        [Parameter(Mandatory = $true)][string]$GhPath,
        [Parameter(Mandatory = $true)][string]$Repo,
        [Parameter(Mandatory = $true)][string]$Dir
    )
    $encoding = New-Object System.Text.UTF8Encoding $false
    $stdinFile = [IO.Path]::Combine($Dir, ('stdin-' + [guid]::NewGuid().ToString('N') + '.tmp'))
    try {
        [IO.File]::WriteAllText($stdinFile, $Value, $encoding)
        $ghArgs = @($Kind, 'set', $Name, '--repo', $Repo)
        $process = Start-Process -FilePath $GhPath -ArgumentList $ghArgs -RedirectStandardInput $stdinFile -NoNewWindow -Wait -PassThru
        if ($process.ExitCode -ne 0) {
            throw "gh $Kind set $Name ist fehlgeschlagen (Exit-Code $($process.ExitCode))."
        }
    } finally {
        if (Test-Path -LiteralPath $stdinFile) {
            try {
                Remove-Item -LiteralPath $stdinFile -Force -ErrorAction Stop
            } catch {
                Write-Warning "Die temporäre Datei '$stdinFile' enthält einen Schlüsselwert und konnte nicht gelöscht werden. Bitte von Hand entfernen."
            }
        }
    }
}

# ---- Eingaben prüfen -------------------------------------------------------

if ($Alias -notmatch '^[A-Za-z][A-Za-z0-9_-]{0,63}$') {
    throw "Ungültiger Alias '$Alias'. Erlaubt sind Buchstaben, Ziffern, '_' und '-', maximal 64 Zeichen."
}
if ($Repo -notmatch '^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$') {
    throw "Ungültiges Repository '$Repo'. Erwartet wird das Format Besitzer/Name."
}

$outDir = [IO.Path]::GetFullPath([IO.Path]::Combine((Get-Location).ProviderPath, $OutputDir))
$keystorePath = [IO.Path]::Combine($outDir, $KeystoreName)
$readmePath = [IO.Path]::Combine($outDir, $ReadmeName)

# ---- Abbruchbedingungen, bevor etwas angelegt wird --------------------------

# 1. OutputDir darf nicht in einem Git-Arbeitsverzeichnis liegen. Geprüft wird der nächste existierende Ordner.
$git = Get-Command -Name 'git.exe' -ErrorAction SilentlyContinue
if ($null -eq $git) {
    Write-Warning 'git wurde nicht gefunden. Die Prüfung auf ein Git-Arbeitsverzeichnis entfällt. Bitte den Zielordner selbst prüfen.'
} else {
    $probe = Get-ExistingAncestor -Path $outDir
    $gitCheck = Invoke-Native -FilePath $git.Source -Arguments @('-C', $probe, 'rev-parse', '--is-inside-work-tree')
    $gitOutput = ($gitCheck.Output -join ' ').Trim()
    if ($gitCheck.ExitCode -eq 0 -and $gitOutput -eq 'true') {
        throw "Der Zielordner liegt in einem Git-Arbeitsverzeichnis ($probe). Schlüssel dürfen nie in einem Repository liegen. Bitte einen Ordner außerhalb wählen, z. B. <keys-verzeichnis>."
    }
    if ($gitCheck.ExitCode -ne 0 -and $gitOutput -notmatch 'not a git repository') {
        throw "Die Git-Prüfung für '$probe' ist fehlgeschlagen: $gitOutput"
    }
}

# 2. Es darf noch kein Keystore und keine LIESMICH im Zielordner liegen.
if (Test-Path -LiteralPath $outDir -PathType Leaf) {
    throw "Der Zielpfad '$outDir' ist eine Datei, kein Ordner."
}
if (Test-Path -LiteralPath $outDir -PathType Container) {
    $foreign = @(Get-ChildItem -LiteralPath $outDir -File | Where-Object { $ForeignKeyFileExtensions -contains $_.Extension.ToLowerInvariant() })
    if ($foreign.Count -gt 0 -or (Test-Path -LiteralPath $keystorePath) -or (Test-Path -LiteralPath $readmePath)) {
        throw "Im Zielordner '$outDir' liegt bereits ein Schlüssel oder eine $ReadmeName. Es wird nichts überschrieben. Bitte einen anderen Ordner wählen."
    }
}

# 3. keytool muss vorhanden sein.
$keytool = Find-Keytool
if ($null -eq $keytool) {
    throw 'keytool.exe wurde nicht gefunden. Bitte JAVA_HOME auf ein JDK setzen oder keytool in den PATH aufnehmen.'
}

# 4. Ohne -SkipGitHub: gh muss angemeldet sein, und das Repository darf noch kein KEYSTORE_BASE64 haben.
$gh = $null
if (-not $SkipGitHub) {
    $gh = Get-Command -Name 'gh.exe' -ErrorAction SilentlyContinue
    if ($null -eq $gh) {
        throw 'GitHub CLI (gh) wurde nicht gefunden. Ohne gh bitte -SkipGitHub verwenden.'
    }
    $auth = Invoke-Native -FilePath $gh.Source -Arguments @('auth', 'status', '--hostname', 'github.com')
    if ($auth.ExitCode -ne 0) {
        throw "gh ist nicht angemeldet. Bitte zuerst 'gh auth login' ausführen."
    }
    $secretList = Invoke-Native -FilePath $gh.Source -Arguments @('secret', 'list', '--repo', $Repo)
    if ($secretList.ExitCode -ne 0) {
        throw "Die Secrets von $Repo konnten nicht gelesen werden. Zugriff und Repository-Name prüfen."
    }
    if (@($secretList.Output | Where-Object { $_ -match '^KEYSTORE_BASE64\s' }).Count -gt 0) {
        throw @"
In $Repo existiert bereits das Secret KEYSTORE_BASE64. Ein neuer Schlüssel würde alle Updates
installierter emberlight-Versionen brechen. Das Skript überschreibt deshalb nichts.
Bestehenden Schlüssel verwenden, siehe docs/RELEASE.md, Abschnitt Signierschlüssel.
"@
    }
}

# ---- Erzeugen ------------------------------------------------------------------

$password = $null
try {
    New-Item -ItemType Directory -Path $outDir -Force | Out-Null
    # Rechte zuerst setzen, damit auch die Dateien, die keytool anlegt, nur den aktuellen Benutzer erben.
    Limit-AclToCurrentUser -Path $outDir -Directory

    $password = New-HexPassword
    [Environment]::SetEnvironmentVariable($PasswordEnvVar, $password, 'Process')

    # PKCS12: Store- und Key-Passwort sind identisch. Beide Optionen zeigen auf dieselbe Variable.
    $generate = Invoke-Native -FilePath $keytool -Arguments @(
        '-genkeypair',
        '-storetype', 'PKCS12',
        '-keyalg', 'RSA',
        '-keysize', '4096',
        '-validity', '36500',
        '-alias', $Alias,
        '-dname', $DistinguishedName,
        '-keystore', $keystorePath,
        '-storepass:env', $PasswordEnvVar,
        '-keypass:env', $PasswordEnvVar
    )
    if ($generate.ExitCode -ne 0 -or -not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
        if (Test-Path -LiteralPath $keystorePath -PathType Leaf) {
            Remove-Item -LiteralPath $keystorePath -Force
        }
        throw "keytool konnte den Keystore nicht erzeugen (Exit-Code $($generate.ExitCode)): $($generate.Output -join ' ')"
    }

    # Fingerprint aus dem frisch erzeugten Keystore lesen, nicht aus dem Rechenweg oben.
    $listing = Invoke-Native -FilePath $keytool -Arguments @(
        '-list', '-v',
        '-storetype', 'PKCS12',
        '-keystore', $keystorePath,
        '-alias', $Alias,
        '-storepass:env', $PasswordEnvVar
    )
    if ($listing.ExitCode -ne 0) {
        throw "Der erzeugte Keystore ist nicht lesbar (Exit-Code $($listing.ExitCode))."
    }
    # Die Passwort-Variable wird sofort gelöscht, bevor gh aufgerufen wird. Kindprozesse erben sie sonst.
    # Das finally oben bleibt als Absicherung.
    [Environment]::SetEnvironmentVariable($PasswordEnvVar, $null, 'Process')
    $fingerprintMatches = [regex]::Matches(($listing.Output -join "`n"), '(?m)^\s*SHA256:\s*((?:[0-9A-F]{2}:){31}[0-9A-F]{2})\s*$')
    if ($fingerprintMatches.Count -ne 1) {
        throw 'Der SHA-256-Fingerprint konnte nicht aus der keytool-Ausgabe gelesen werden.'
    }
    $fingerprintColon = $fingerprintMatches[0].Groups[1].Value
    $fingerprintPlain = $fingerprintColon.Replace(':', '')

    $created = Get-Date -Format 'yyyy-MM-dd HH:mm'
    $base64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystorePath))

    $readmeLines = @(
        'emberlight: Release-Signierschlüssel',
        '=====================================',
        '',
        "Keystore-Datei:       $KeystoreName",
        "Alias:                $Alias",
        "Passwort:             $password",
        "SHA-256-Fingerprint:  $fingerprintColon",
        "Erstellt am:          $created",
        '',
        "Zuordnung zu den GitHub-Secrets (Repository $Repo):",
        "  KEYSTORE_PASSWORD   = das Passwort oben",
        "  KEY_PASSWORD        = das Passwort oben (bei PKCS12 identisch)",
        "  KEY_ALIAS           = $Alias",
        '  KEYSTORE_BASE64     = Base64-Inhalt der Keystore-Datei. Erzeugen mit:',
        "                        [Convert]::ToBase64String([IO.File]::ReadAllBytes('$keystorePath'))",
        "  Variable RELEASE_CERT_SHA256 = $fingerprintPlain",
        '',
        'WARNUNG: Ohne diesen Schlüssel lässt sich keine installierte emberlight-App mehr aktualisieren.',
        'Ein neuer Schlüssel zwingt Nutzer, die App zu deinstallieren. Dabei gehen die lokalen Daten verloren.',
        '',
        'Bitte Ordner und Datei zusätzlich außerhalb dieses Rechners sichern: im Passwort-Manager und als',
        'Offline-Kopie (z. B. verschlüsselter USB-Stick). Der Ordner ist nur für den aktuellen Benutzer lesbar;',
        'Backup-Programme, die als System-Dienst laufen, können ihn deshalb nicht sichern. Also manuell sichern.',
        'Niemals committen, niemals teilen, niemals in Chats oder Tickets einfügen.',
        '',
        'Bei einem späteren Umzug in den Play Store kann derselbe Schlüssel über Play App Signing',
        'weiterverwendet werden. Es ist kein neuer Schlüssel nötig.',
        '',
        'Details zu Secrets, Probelauf und Release: docs/RELEASE.md im Repository.'
    )
    $readmeText = ($readmeLines -join "`r`n") + "`r`n"
    $readmeEncoding = New-Object System.Text.UTF8Encoding $true
    [IO.File]::WriteAllText($readmePath, $readmeText, $readmeEncoding)

    Limit-AclToCurrentUser -Path $keystorePath
    Limit-AclToCurrentUser -Path $readmePath

    # ---- GitHub ---------------------------------------------------------------

    $setNames = @()
    if (-not $SkipGitHub) {
        $values = @(
            @{ Kind = 'secret'; Name = 'KEYSTORE_BASE64'; Value = $base64 },
            @{ Kind = 'secret'; Name = 'KEYSTORE_PASSWORD'; Value = $password },
            @{ Kind = 'secret'; Name = 'KEY_ALIAS'; Value = $Alias },
            @{ Kind = 'secret'; Name = 'KEY_PASSWORD'; Value = $password },
            @{ Kind = 'variable'; Name = 'RELEASE_CERT_SHA256'; Value = $fingerprintPlain }
        )
        foreach ($item in $values) {
            Set-GitHubValue -Kind $item.Kind -Name $item.Name -Value $item.Value -GhPath $gh.Source -Repo $Repo -Dir $outDir
            $setNames += $item.Name
        }
        $base64 = $null
    }

    # ---- Ausgabe (ohne Passwort) -----------------------------------------------

    Write-Host ''
    Write-Host 'Keystore erstellt.'
    Write-Host "  Keystore:     $keystorePath"
    Write-Host "  LIESMICH:     $readmePath"
    Write-Host "  Alias:        $Alias"
    Write-Host "  Fingerprint:  $fingerprintColon"
    if ($SkipGitHub) {
        Write-Host '  GitHub:       übersprungen (-SkipGitHub). Keine Secrets gesetzt.'
    } else {
        Write-Host "  Gesetzt in ${Repo}:"
        foreach ($name in $setNames) {
            Write-Host "    - $name"
        }
        Write-Host '  Das Passwort steht ausschließlich in LIESMICH.txt.'
    }
} finally {
    [Environment]::SetEnvironmentVariable($PasswordEnvVar, $null, 'Process')
    $password = $null
    $base64 = $null
}
