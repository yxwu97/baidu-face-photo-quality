[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Java11Home,
    [string]$Java21Home = $env:JAVA_HOME,
    [string]$MavenRepository,
    [string]$WixBin,
    [string]$LicenseDirectory,
    [switch]$Offline,
    [switch]$ImageOnly,
    [switch]$SkipMaven
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = Split-Path -Parent $PSScriptRoot
if (!$MavenRepository) { $MavenRepository = Join-Path $project '.local/maven-repository' }
if (!$Java21Home) { throw 'Specify -Java21Home or set JAVA_HOME to JDK 21 x64' }
if ($WixBin) { $env:PATH = "$WixBin;$env:PATH" }
$env:JAVA_HOME = $Java21Home
$env:JAVA11_HOME = $Java11Home

# Preserve the deployment machine's existing license across atomic upgrades.
# On the first package, use the locally supplied License folder.
if (!$LicenseDirectory) {
    $installedLicense = Join-Path $project 'release/BaiduFacePhotoQuality/app/worker/sdk/license'
    $LicenseDirectory = if (Test-Path -LiteralPath $installedLicense) {
        $installedLicense
    } else { Join-Path $project 'License' }
}
foreach ($file in @('license.ini', 'license.key')) {
    $licenseFile = Join-Path $LicenseDirectory $file
    if (!(Test-Path -LiteralPath $licenseFile -PathType Leaf) -or
        (Get-Item -LiteralPath $licenseFile).Length -eq 0) {
        throw "Deployment license requires nonempty $file; specify -LicenseDirectory"
    }
}

function Invoke-BuildStep {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$File exited with code $LASTEXITCODE" }
}

if (!$SkipMaven) {
    $arguments = @('-B', '-ntp', "-Dmaven.repo.local=$MavenRepository", 'clean', 'verify')
    if ($Offline) { $arguments = @('-o') + $arguments }
    Push-Location -LiteralPath $project
    try { Invoke-BuildStep 'mvn.cmd' $arguments }
    finally { Pop-Location }
}

# Create each development build under a fresh directory. It becomes eligible
# for formal release once the target SDK and full acceptance gates pass.
$stageName = 'build-' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')
$build = (New-Item -ItemType Directory -Path (Join-Path $project ".local/windows-package/$stageName") -Force).FullName
$inputDir = (New-Item -ItemType Directory -Path (Join-Path $build 'input')).FullName
$destination = (New-Item -ItemType Directory -Path (Join-Path $build 'output')).FullName
$name = 'BaiduFacePhotoQuality'
[xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $project 'pom.xml')
$version = $pom.project.version
$appVersion = $version -replace '-SNAPSHOT$',''
# MSI's third field is limited to 65535. Retain the full dated product version
# in the application and advance a separate Windows installer build ordinal.
if ($version -match '^(\d+)\.(\d+)\.(\d{8})(\d{5})$') {
    $prefix = "$($Matches[1]).$($Matches[2])"
    $ordinal = 1
    $previousInfo = Join-Path $project 'release/BaiduFacePhotoQuality/app/build-info.json'
    if (Test-Path -LiteralPath $previousInfo) {
        $previous = Get-Content -Raw -LiteralPath $previousInfo | ConvertFrom-Json
        $previousWindows = [version]$previous.windowsInstallerVersion
        if ("$($previousWindows.Major).$($previousWindows.Minor)" -eq $prefix) {
            $ordinal = $previousWindows.Build
            if ($previous.productVersion -ne $version) { $ordinal++ }
        }
    }
    if ($ordinal -gt 65535) { throw 'Windows installer build field exhausted for this major.minor.' }
    $appVersion = "$prefix.$ordinal"
}
$javafx = $pom.project.properties.'javafx.version'
$sqlite = $pom.project.properties.'sqlite.version'
$jackson = $pom.project.properties.'jackson.version'

function Copy-MavenArtifact {
    param([string]$Group, [string]$Artifact, [string]$Version, [string]$Classifier = '')
    $suffix = if ($Classifier) { "-$Classifier" } else { '' }
    $file = "$Artifact-$Version$suffix.jar"
    $relative = ($Group -replace '\.', '/') + "/$Artifact/$Version/$file"
    Copy-Item -LiteralPath (Join-Path $MavenRepository $relative) -Destination $inputDir
}

foreach ($artifact in @('desktop', 'core', 'detector-protocol')) {
    Copy-Item -LiteralPath (Join-Path $project "$artifact/target/$artifact-$version.jar") -Destination $inputDir
}
foreach ($fx in @('base', 'graphics', 'controls', 'fxml')) {
    Copy-MavenArtifact 'org.openjfx' "javafx-$fx" $javafx 'win'
}
Copy-MavenArtifact 'org.xerial' 'sqlite-jdbc' $sqlite
Copy-MavenArtifact 'com.fasterxml.jackson.core' 'jackson-core' $jackson
Copy-MavenArtifact 'com.fasterxml.jackson.core' 'jackson-databind' $jackson
Copy-MavenArtifact 'com.fasterxml.jackson.core' 'jackson-annotations' (($jackson -split '\.')[0..1] -join '.')

@{
    productVersion = $version
    windowsInstallerVersion = $appVersion
    acceptance = 'development-build-pending-target-sdk-calibration'
} | ConvertTo-Json | Set-Content -Encoding utf8NoBOM -LiteralPath (Join-Path $inputDir 'build-info.json')
$jpackage = Join-Path $Java21Home 'bin/jpackage.exe'
Invoke-BuildStep $jpackage @('--type', 'app-image', '--name', $name, '--app-version', $appVersion,
    '--dest', $destination, '--input', $inputDir,
    '--main-jar', "desktop-$version.jar", '--main-class', 'cn.zhixing.facephoto.desktop.DesktopLauncher',
    '--add-modules', 'java.desktop,java.sql,java.scripting,java.naming,java.xml,jdk.unsupported,jdk.zipfs')
$workerHome = Join-Path $destination "$name/app/worker"
& (Join-Path $PSScriptRoot 'Stage-WindowsWorker.ps1') -Java11Home $Java11Home -WorkerHome $workerHome -MavenRepository $MavenRepository -LicenseDirectory $LicenseDirectory -SkipMaven | Out-Null

if (!$ImageOnly) {
    if (!(Get-Command candle.exe -ErrorAction SilentlyContinue) -or
        !(Get-Command light.exe -ErrorAction SilentlyContinue)) {
        throw "WiX 3 candle.exe/light.exe unavailable. App image is available at $destination."
    }
    Invoke-BuildStep $jpackage @('--type', 'msi', '--name', $name, '--app-version', $appVersion,
        '--app-image', (Join-Path $destination $name), '--dest', $destination,
        '--win-per-user-install', '--win-dir-chooser', '--win-menu')
}
$release = Join-Path $project 'release'
$backup = Join-Path $build 'previous-release'
try {
    if (Test-Path -LiteralPath $release) {
        [IO.Directory]::Move($release, $backup)
    }
    [IO.Directory]::Move($destination, $release)
} catch {
    if ((Test-Path -LiteralPath $backup) -and !(Test-Path -LiteralPath $release)) {
        [IO.Directory]::Move($backup, $release)
    }
    throw
}
Write-Output $release
