# stop_all.ps1 — Kill all java processes belonging to this cluster
# Usage: .\stop_all.ps1
# Kills: LoadBalancer, Server, Client, VisualOversee java processes

param([switch] $Force)

$targets = @("LoadBalancer", "Server", "Client", "VisualOversee")

$procs = Get-WmiObject Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" |
Where-Object { $cmd = $_.CommandLine; ($targets | Where-Object { $cmd -like "*$_*" }) -ne $null }

if (-not $procs) {
    Write-Host "No matching cluster Java processes found." -ForegroundColor Yellow
    exit 0
}

Write-Host "Found $($procs.Count) process(es) to stop:" -ForegroundColor Cyan
$procs | ForEach-Object { Write-Host "  PID $($_.ProcessId): $($_.CommandLine.Substring(0, [Math]::Min(80, $_.CommandLine.Length)))..." }

if (-not $Force) {
    $confirm = Read-Host "Stop all of the above? [y/N]"
    if ($confirm -ne 'y' -and $confirm -ne 'Y') { Write-Host "Aborted."; exit 0 }
}

$procs | ForEach-Object {
    try {
        Stop-Process -Id $_.ProcessId -Force
        Write-Host "Stopped PID $($_.ProcessId)" -ForegroundColor Green
    }
    catch {
        Write-Host "Failed to stop PID $($_.ProcessId): $_" -ForegroundColor Red
    }
}
Write-Host "Done." -ForegroundColor Green
