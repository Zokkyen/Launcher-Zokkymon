param(
    [string]$MavenCommand = "mvn",
    [string]$JarPath = "target/ZokkymonLauncher.jar",
    [string]$ExePath = "ZokkymonLauncher.exe",
    [string]$IconPath = "zokkymon.ico",
    [switch]$SkipClean,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8NoBom
[Console]::OutputEncoding = $utf8NoBom
$OutputEncoding = $utf8NoBom

$logsDir = Join-Path (Split-Path -Parent $MyInvocation.MyCommand.Path) "logs"
if (!(Test-Path $logsDir)) { New-Item -Path $logsDir -ItemType Directory | Out-Null }
$logFile = Join-Path $logsDir "build-local-exe-last.log"
if (Test-Path $logFile) { Remove-Item $logFile -Force }
Start-Transcript -Path $logFile -Force | Out-Null

function Invoke-Step {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [scriptblock]$Script
    )

    Write-Host "`n=== $Label ===" -ForegroundColor Cyan
    & $Script
}

function Resolve-Launch4j {
    $cmd = Get-Command launch4jc -ErrorAction SilentlyContinue
    if (-not $cmd) { $cmd = Get-Command launch4jc.exe -ErrorAction SilentlyContinue }
    if ($cmd) {
        return @{ Mode = "exe"; Path = $cmd.Source }
    }

    $exeCandidates = @(
        "C:\ProgramData\chocolatey\lib\launch4j\tools\launch4j\launch4jc.exe",
        "C:\Program Files\Launch4j\launch4jc.exe",
        "C:\Program Files (x86)\Launch4j\launch4jc.exe"
    )
    foreach ($candidate in $exeCandidates) {
        if (Test-Path $candidate) {
            return @{ Mode = "exe"; Path = $candidate }
        }
    }

    $jarCandidates = @(
        "C:\ProgramData\chocolatey\lib\launch4j\tools\launch4j\launch4j.jar",
        "C:\Program Files\Launch4j\launch4j.jar",
        "C:\Program Files (x86)\Launch4j\launch4j.jar"
    )

    foreach ($candidate in $jarCandidates) {
        if (Test-Path $candidate) {
            $launch4jHome = Split-Path -Parent $candidate
            return @{ Mode = "java"; Jar = $candidate; Classpath = "$candidate;$launch4jHome\\lib\\*" }
        }
    }

    throw "Launch4j introuvable. Installe Launch4j (ou choco install launch4j) puis relance."
}

try {
    $repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
    Set-Location $repoRoot

    Invoke-Step -Label "Build Maven" -Script {
    $args = @()
    if (-not $SkipClean) { $args += "clean" }
    $args += "package"
    if ($SkipTests) { $args = @("-DskipTests") + $args }

    Write-Host "> $MavenCommand $($args -join ' ')"
    & $MavenCommand @args
    if ($LASTEXITCODE -ne 0) {
        throw "Echec Maven (code=$LASTEXITCODE)."
    }
    }

    $jarAbs = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $JarPath))
    $exeAbs = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $ExePath))
    $iconAbs = [System.IO.Path]::GetFullPath((Join-Path $repoRoot $IconPath))

    if (!(Test-Path $jarAbs)) {
        throw "JAR introuvable apres build: $jarAbs"
    }
    if (!(Test-Path $iconAbs)) {
        throw "Icone introuvable: $iconAbs"
    }

    if (Test-Path $exeAbs) {
        try {
            Remove-Item -Path $exeAbs -Force
            Write-Host "Ancien EXE supprime: $exeAbs"
        }
        catch {
            $procName = [System.IO.Path]::GetFileNameWithoutExtension($exeAbs)
            $running = Get-Process -Name $procName -ErrorAction SilentlyContinue
            if ($running) {
                throw "Le fichier EXE est verrouille (processus '$procName' en cours). Ferme le launcher puis relance le script."
            }
            throw "Impossible d'ecrire l'EXE cible ($exeAbs). Ferme les applis qui peuvent l'utiliser (launcher, antivirus, explorateur) puis relance."
        }
    }

    $cfgPath = Join-Path $repoRoot "launch4j-local.xml"
    $jarXml = [System.Security.SecurityElement]::Escape($jarAbs)
    $exeXml = [System.Security.SecurityElement]::Escape($exeAbs)
    $iconXml = [System.Security.SecurityElement]::Escape($iconAbs)

    $xml = @"
<?xml version="1.0" encoding="UTF-8"?>
<launch4jConfig>
  <dontWrapJar>false</dontWrapJar>
  <headerType>gui</headerType>
  <jar>$jarXml</jar>
  <outfile>$exeXml</outfile>
  <errTitle>Java requis - ZokkymonLauncher</errTitle>
  <cmdLine></cmdLine>
  <chdir>.</chdir>
  <priority>normal</priority>
  <downloadUrl>https://adoptium.net/temurin/releases/?version=21</downloadUrl>
  <supportUrl>https://adoptium.net/temurin/releases/?version=21</supportUrl>
  <icon>$iconXml</icon>
  <jre>
    <path>%USERPROFILE%\.zokkymon\Jre_21</path>
    <requiresJdk>false</requiresJdk>
    <requires64Bit>true</requires64Bit>
    <minVersion>21.0.0</minVersion>
    <maxVersion></maxVersion>
  </jre>
</launch4jConfig>
"@

    [System.IO.File]::WriteAllText($cfgPath, $xml, $utf8NoBom)

    $launch4j = Resolve-Launch4j
    Invoke-Step -Label "Build EXE Launch4j" -Script {
    $l4jOut = Join-Path $logsDir "launch4j.stdout.log"
    $l4jErr = Join-Path $logsDir "launch4j.stderr.log"
    if (Test-Path $l4jOut) { Remove-Item $l4jOut -Force }
    if (Test-Path $l4jErr) { Remove-Item $l4jErr -Force }

    $filePath = "java"
    $argList = @("-cp", $launch4j.Classpath, "net.sf.launch4j.Main", $cfgPath)
    $argString = $null
    if ($launch4j.Mode -eq "exe") {
        $filePath = $launch4j.Path
        $argList = @($cfgPath)
    } else {
        $argString = "-cp `"$($launch4j.Classpath)`" net.sf.launch4j.Main `"$cfgPath`""
    }

    if ($argString -ne $null) {
        Write-Host "> $filePath $argString"
        $proc = Start-Process -FilePath $filePath -ArgumentList $argString -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $l4jOut -RedirectStandardError $l4jErr
    } else {
        Write-Host "> $filePath $($argList -join ' ')"
        $proc = Start-Process -FilePath $filePath -ArgumentList $argList -NoNewWindow -Wait -PassThru `
            -RedirectStandardOutput $l4jOut -RedirectStandardError $l4jErr
    }

    if (Test-Path $l4jOut) {
        Get-Content $l4jOut | ForEach-Object { Write-Host $_ }
    }
    if (Test-Path $l4jErr) {
        Get-Content $l4jErr | ForEach-Object { Write-Host $_ }
    }

    if ($proc.ExitCode -ne 0) {
        $tail = if (Test-Path $l4jErr) { (Get-Content $l4jErr | Select-Object -Last 10) -join [Environment]::NewLine } else { "(stderr vide)" }
        throw "Echec Launch4j (code=$($proc.ExitCode)). Voir $l4jOut et $l4jErr. Detail: $tail"
    }
    }

    if (!(Test-Path $exeAbs)) {
        throw "EXE non genere: $exeAbs"
    }

    Write-Host "`nOK: EXE genere -> $exeAbs" -ForegroundColor Green
    Write-Host "Log: $logFile"
    exit 0
}
catch {
    Write-Host "`n[ERREUR] $($_.Exception.Message)" -ForegroundColor Red
    Write-Host "Consulte le log: $logFile" -ForegroundColor Yellow
    exit 1
}
finally {
    Stop-Transcript | Out-Null
}
