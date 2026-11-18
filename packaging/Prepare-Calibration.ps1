[CmdletBinding()]
param([string]$PhotoRoot, [string]$OutputCsv)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
if (!$PhotoRoot) { $PhotoRoot = Join-Path $project 'FaceCapture' }
if (!$OutputCsv) { $OutputCsv = Join-Path $project 'design/calibration-seed.csv' }
if (Test-Path -LiteralPath $OutputCsv) { throw "Existing annotations must be preserved. Select a new -OutputCsv." }
$root = (Resolve-Path -LiteralPath $PhotoRoot).Path
$samples = foreach ($day in Get-ChildItem -LiteralPath $root -Directory | Sort-Object Name) {
    $photos = @(Get-ChildItem -LiteralPath $day.FullName -File |
        Where-Object Extension -Match '^\.jpe?g$' | Sort-Object Name)
    if (!$photos.Count) { continue }
    # Evenly spaced seeds across each capture day. Human reviewers later add
    # pose, lighting and confirmed-normal samples after actual SDK measurement.
    $indices = 0..([Math]::Min(25, $photos.Count) - 1) |
        ForEach-Object { [int][Math]::Floor($_ * $photos.Count / [Math]::Min(25, $photos.Count)) }
    foreach ($index in $indices) {
        $photo = $photos[$index]
        $minute = if ($photo.BaseName -match '^(\d{2})(\d{2})\d{2}_') {
            [int]$Matches[1] * 60 + [int]$Matches[2]
        } else { 0 }
        # Contiguous five-minute captures stay together in one partition.
        $group = $day.Name + '-' + [Math]::Floor($minute / 5)
        $hash = [Security.Cryptography.SHA256]::HashData([Text.Encoding]::UTF8.GetBytes($group))
        $partition = if ($hash[0] % 5 -eq 0) { 'validation' } else { 'calibration' }
        [pscustomobject]@{
            relative_path = [IO.Path]::GetRelativePath($root, $photo.FullName).Replace('\','/')
            sha256 = (Get-FileHash -LiteralPath $photo.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            capture_date = $day.Name
            group_id = $group
            partition = $partition
            human_status = ''
            human_reasons = ''
            lighting = ''
            pose = ''
            confirmed_normal = ''
            reviewer = ''
            note = ''
        }
    }
}
$samples | Export-Csv -LiteralPath $OutputCsv -NoTypeInformation -Encoding utf8BOM
"Created $(@($samples).Count) unlabelled calibration seeds."
