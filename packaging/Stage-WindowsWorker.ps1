[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$Java11Home,
    [string]$WorkerHome,
    [string]$MavenRepository,
    [string]$LicenseDirectory,
    [switch]$Offline,
    [switch]$SkipMaven
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$project = Split-Path -Parent $PSScriptRoot
if (!$WorkerHome) { $WorkerHome = Join-Path $project '.local/worker' }
if (!$MavenRepository) { $MavenRepository = Join-Path $project '.local/maven-repository' }

function Invoke-BuildStep {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "$File exited with code $LASTEXITCODE" }
}

$worker = (New-Item -ItemType Directory -Path $WorkerHome -Force).FullName
$lib = (New-Item -ItemType Directory -Path (Join-Path $worker 'lib') -Force).FullName
$stage = (New-Item -ItemType Directory -Path (Join-Path $project '.local/vendor-build') -Force).FullName
$javaBin = Join-Path $Java11Home 'bin'
$archive = Join-Path $project 'Baidu_Face_Offline_SDK_Windows_Java_8.4.zip'

# Never use the unrelated license bundled in the vendor ZIP. A deployment
# license is supplied explicitly, or taken from this machine's project folder.
if (!$LicenseDirectory -and (Test-Path -LiteralPath (Join-Path $project 'License'))) {
    $LicenseDirectory = Join-Path $project 'License'
}
if ($LicenseDirectory) {
    foreach ($file in @('license.ini', 'license.key')) {
        $licenseFile = Join-Path $LicenseDirectory $file
        if (!(Test-Path -LiteralPath $licenseFile -PathType Leaf) -or
            (Get-Item -LiteralPath $licenseFile).Length -eq 0) {
            throw "Deployment license requires nonempty $file"
        }
    }
}

if (!$SkipMaven) {
    $options = @('-B', '-ntp', "-Dmaven.repo.local=$MavenRepository",
        '-pl', 'detector-baidu-windows', '-am', '-DskipTests', 'package')
    if ($Offline) { $options = @('-o') + $options }
    Push-Location -LiteralPath $project
    try { Invoke-BuildStep 'mvn.cmd' $options }
    finally { Pop-Location }
}

# The ZIP also carries a precompiled bin directory made for a later JDK.
# Build the vendor wrapper sources with Java 11 for the Worker.
Invoke-BuildStep 'tar.exe' @('-xf', $archive, '-C', $stage,
    'FaceOfflineSdk/src', 'FaceOfflineSdk/models', 'FaceOfflineSdk/conf',
    'FaceOfflineSdk/opencv-jar/opencv-320.jar', 'FaceOfflineSdk/face.ini', 'FaceOfflineSdk/*.dll')
$vendor = Join-Path $stage 'FaceOfflineSdk'
$sdk = (New-Item -ItemType Directory -Path (Join-Path $worker 'sdk') -Force).FullName
if ($LicenseDirectory) {
    $licenseTarget = (New-Item -ItemType Directory -Path (Join-Path $sdk 'license') -Force).FullName
    foreach ($file in @('license.ini', 'license.key')) {
        $sourceFile = (Resolve-Path -LiteralPath (Join-Path $LicenseDirectory $file)).Path
        $targetFile = Join-Path $licenseTarget $file
        if ($sourceFile -ne $targetFile) {
            Copy-Item -LiteralPath $sourceFile -Destination $targetFile -Force
        }
    }
}
$classes = (New-Item -ItemType Directory -Path (Join-Path $stage 'classes') -Force).FullName
$opencv = Join-Path $vendor 'opencv-jar/opencv-320.jar'
$source = @(Get-ChildItem -LiteralPath (Join-Path $vendor 'src') -Recurse -File -Filter '*.java' |
    ForEach-Object FullName)
Invoke-BuildStep (Join-Path $javaBin 'javac.exe') (@('-encoding', 'UTF-8', '-cp', $opencv,
    '-d', $classes) + $source)
Invoke-BuildStep (Join-Path $javaBin 'jar.exe') @('--create', '--file',
    (Join-Path $lib 'baidu-wrapper-8.4.jar'), '-C', $classes, 'com')

Copy-Item -LiteralPath $opencv -Destination $lib -Force
Get-ChildItem -LiteralPath $vendor -File -Filter '*.dll' |
    Copy-Item -Destination $sdk -Force
Copy-Item -LiteralPath (Join-Path $vendor 'face.ini') -Destination $sdk -Force
Copy-Item -LiteralPath (Join-Path $vendor 'models') -Destination $sdk -Recurse -Force
$config = (New-Item -ItemType Directory -Path (Join-Path $sdk 'config') -Force).FullName
Get-ChildItem -LiteralPath (Join-Path $vendor 'conf') -File |
    Copy-Item -Destination $config -Force
$probes = (New-Item -ItemType Directory -Path (Join-Path $sdk 'probes') -Force).FullName
foreach ($file in @('01-no-face.jpg', '02-single-face.jpg')) {
    Copy-Item -LiteralPath (Join-Path $project "fixtures/jpeg/$file") -Destination $probes -Force
}

[xml]$pom = Get-Content -Raw -LiteralPath (Join-Path $project 'pom.xml')
$version = $pom.project.version
$jackson = $pom.project.properties.'jackson.version'
foreach ($artifact in @('detector-protocol', 'detector-baidu-windows')) {
    $built = Join-Path $project "$artifact/target/$artifact-$version.jar"
    Copy-Item -LiteralPath $built -Destination (Join-Path $lib "$artifact.jar") -Force
}
foreach ($artifact in @('jackson-annotations', 'jackson-core', 'jackson-databind')) {
    $dependency = Join-Path $MavenRepository "com/fasterxml/jackson/core/$artifact"
    $dependencyVersion = if ($artifact -eq 'jackson-annotations') {
        ($jackson -split '\.')[0..1] -join '.'
    } else { $jackson }
    $built = Join-Path $dependency "$dependencyVersion/$artifact-$dependencyVersion.jar"
    Copy-Item -LiteralPath $built -Destination (Join-Path $lib "$artifact.jar") -Force
}

$runtime = Join-Path $worker 'runtime'
if (!(Test-Path -LiteralPath (Join-Path $runtime 'bin/java.exe') -PathType Leaf)) {
    Invoke-BuildStep (Join-Path $javaBin 'jlink.exe') @('--add-modules',
        'java.base,java.desktop,java.logging,jdk.unsupported', '--strip-debug',
        '--no-header-files', '--no-man-pages', '--output', $runtime)
}
Write-Output $worker
