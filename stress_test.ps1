# Stress test: launch N clients that each fire commands in a loop
# Usage: .\stress_test.ps1 [-Clients 10] [-Iterations 5] [-Mode dynamic]
param(
    [int]    $Clients = 8,
    [int]    $Iterations = 10,
    [string] $Mode = "dynamic"
)

$ProjectDir = $PSScriptRoot

# Build a temp script file that each client will run
$cmdFile = Join-Path $env:TEMP "stress_cmds.txt"
@"
ping
time
ls
compute 1
ping
quit
"@ | Set-Content $cmdFile

Write-Host "Stress test: $Clients clients x $Iterations iterations (mode=$Mode)" -ForegroundColor Cyan

for ($i = 1; $i -le $Clients; $i++) {
    $name = "Stress-$i"
    Start-Process powershell -ArgumentList "-NoProfile -Command `"cd '$ProjectDir'; for (`$r=1; `$r -le $Iterations; `$r++) { java Client --name $name-`$r --mode $Mode --script '$cmdFile'; Start-Sleep 1 }`"" -WindowStyle Minimized
    Start-Sleep -Milliseconds 200
}

Write-Host "Launched $Clients stress clients." -ForegroundColor Green
Write-Host "Watch VisualOversee or the LB console for real-time routing updates."
