[CmdletBinding()]
param([string]$WorkerHome)

$ErrorActionPreference = 'Stop'
if (!$WorkerHome) {
    $WorkerHome = Join-Path (Split-Path -Parent $PSScriptRoot) 'release/BaiduFacePhotoQuality/app/worker'
}
$root = (Resolve-Path -LiteralPath $WorkerHome).Path
$start = [Diagnostics.ProcessStartInfo]::new()
$start.FileName = Join-Path $root 'runtime/bin/java.exe'
$start.WorkingDirectory = Join-Path $root 'sdk'
$start.UseShellExecute = $false
$start.CreateNoWindow = $true
$start.RedirectStandardInput = $true
$start.RedirectStandardOutput = $true
$start.RedirectStandardError = $true
foreach ($argument in @('-XX:-CreateCoredumpOnCrash', "-Djava.library.path=$($start.WorkingDirectory)",
    '-cp', (Join-Path $root 'lib/*'), 'cn.zhixing.facephoto.worker.windows.WindowsWorker')) {
    $start.ArgumentList.Add($argument)
}
$start.Environment['PATH'] = "$($start.WorkingDirectory);$env:PATH"
$worker = [Diagnostics.Process]::new()
$worker.StartInfo = $start
try {
    if (!$worker.Start()) { throw 'Worker did not start' }
    # Native diagnostics may contain private license/device data. Drain, never print.
    $diagnostics = $worker.StandardError.BaseStream.CopyToAsync([IO.Stream]::Null)
    $output = $worker.StandardOutput.ReadToEndAsync()
    $worker.StandardInput.WriteLine('{"v":1,"channel":"face.worker","id":"probe-init","op":"initialize","args":{"configurationId":"baidu-windows-x64"}}')
    $worker.StandardInput.WriteLine('{"v":1,"channel":"face.worker","id":"probe-stop","op":"shutdown","args":{}}')
    $worker.StandardInput.Close()
    if (!$worker.WaitForExit(65000)) { throw 'Worker initialization timed out' }
    $frames = @($output.GetAwaiter().GetResult() -split '\r?\n' |
        Where-Object { $_.StartsWith('{') } | ForEach-Object { $_ | ConvertFrom-Json })
    $initial = $frames | Where-Object id -eq 'probe-init' | Select-Object -First 1
    $shutdown = $frames | Where-Object id -eq 'probe-stop' | Select-Object -First 1
    if (!$initial -or !$shutdown -or !$shutdown.ok -or $worker.ExitCode -ne 0) {
        throw 'Worker protocol/shutdown verification failed'
    }
    if ($initial.ok) {
        [pscustomobject]@{ initialized = $true; authorized = $initial.data.authorized;
            probes = $initial.data.probes; shutdown = $true }
    } else {
        [pscustomobject]@{ initialized = $false; code = $initial.error.code;
            nativeCode = $initial.error.nativeCode; authorized = $initial.error.authorized; shutdown = $true }
        throw 'Real SDK initialization failed; see sanitized result above'
    }
} finally {
    if ($worker.Id -and !$worker.HasExited) { $worker.Kill($true); $worker.WaitForExit() }
    $worker.Dispose()
}
