# ============================================================
# run_parallel.ps1  –  Launch the full cluster in new windows
# ============================================================
# Usage:
#   .\run_parallel.ps1                          # 1 LB + 3 servers + 2 dynamic + 2 static clients
#   .\run_parallel.ps1 -Servers 5 -DynamicClients 3 -StaticClients 2
#   .\run_parallel.ps1 -Visualise               # also open the VisualOversee dashboard
#   .\run_parallel.ps1 -SkipBuild               # skip javac step
#
# NOTE: Clients connect to the LB address compiled into Client.java (localhost:11114).
#       Edit the LOAD_BALANCER_ADDRESS / LOAD_BALANCER_PORT constants to change it.
# ============================================================

param(
    [int]    $Servers = 3,   # Number of Server instances
    [int]    $DynamicClients = 2,   # Clients that use dynamic (RTT-based) routing
    [int]    $StaticClients = 2,   # Clients that use static (weighted round-robin) routing
    [string] $LbHost = "localhost",
    [int]    $LbRegPort = 11115,
    [switch] $SkipBuild
)

$ProjectDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $ProjectDir

# ---- 1. Compile ----
if (-not $SkipBuild) {
    Write-Host "Compiling..." -ForegroundColor Cyan
    & javac LoadBalancer.java Server.java Client.java VisualOversee.java
    if ($LASTEXITCODE -ne 0) {
        Write-Error "Compilation failed. Fix errors then retry."
        exit 1
    }
    Write-Host "Compilation OK." -ForegroundColor Green
}

# ---- Helper: open a new terminal window ----
function Start-Window {
    param([string]$Title, [string]$Command)
    Start-Process powershell -ArgumentList `
        "-NoExit", "-Command", `
        "`$Host.UI.RawUI.WindowTitle = '$Title'; Set-Location '$ProjectDir'; $Command"
}

# ---- 2. Load Balancer ----
Write-Host "`nStarting Load Balancer..." -ForegroundColor Cyan
Start-Window -Title "LB  |  admin console" -Command "java LoadBalancer"
Start-Sleep -Milliseconds 1500   # wait for LB to bind ports

# ---- 3. Servers (all use ephemeral ports — run as many as you like) ----
Write-Host "`nStarting $Servers server(s)..." -ForegroundColor Yellow
for ($i = 1; $i -le $Servers; $i++) {
    Start-Window -Title "Server-$i" -Command `
        "java Server --lb-host $LbHost --lb-port $LbRegPort -p 0 -u 0"
    Write-Host "  Server-$i started"
    Start-Sleep -Milliseconds 400   # stagger registrations
}

# Give servers time to register before clients connect
Start-Sleep -Milliseconds 1500

# ---- 4a. Dynamic clients (RTT-based selection) ----
$allNames = @("Alice", "Bob", "Carol", "Dave", "Eve", "Frank", "Grace", "Heidi",
    "Ivan", "Judy", "Karl", "Laura", "Mallory", "Niaj", "Olivia", "Peggy",
    "Quinn", "Ruth", "Steve", "Tina", "Uma", "Victor", "Wendy", "Xena")
$nameIdx = 0

if ($DynamicClients -gt 0) {
    Write-Host "`nStarting $DynamicClients dynamic client(s)..." -ForegroundColor Green
}
for ($i = 1; $i -le $DynamicClients; $i++) {
    $name = if ($nameIdx -lt $allNames.Count) { $allNames[$nameIdx++] } else { "DynClient$i" }
    Start-Window -Title "Client [dynamic] $name" -Command `
        "java Client --mode dynamic --name $name"
    Write-Host "  Client $name  [dynamic]"
    Start-Sleep -Milliseconds 200
}

# ---- 4b. Static clients (weighted round-robin selection) ----
if ($StaticClients -gt 0) {
    Write-Host "`nStarting $StaticClients static client(s)..." -ForegroundColor Magenta
}
for ($i = 1; $i -le $StaticClients; $i++) {
    $name = if ($nameIdx -lt $allNames.Count) { $allNames[$nameIdx++] } else { "StatClient$i" }
    Start-Window -Title "Client [static]  $name" -Command `
        "java Client --mode static --name $name"
    Write-Host "  Client $name  [static]"
    Start-Sleep -Milliseconds 200
}

# ---- Summary ----
Write-Host ""
Write-Host "======================================" -ForegroundColor White
Write-Host " Cluster is up" -ForegroundColor Green
Write-Host "======================================" -ForegroundColor White
Write-Host "  Load Balancer    : 1  (type 'help' in its window)"
Write-Host "  Servers          : $Servers  (ephemeral TCP+UDP ports each)"
Write-Host "  Dynamic clients  : $DynamicClients  (RTT-based routing)"
Write-Host "  Static  clients  : $StaticClients  (weighted round-robin routing)"
Write-Host ""
Write-Host "Useful LB console commands: servers  live  status  weights  bans"

# ---- Optional: VisualOversee dashboard ----
if ($Visualise) {
    Write-Host "`nOpening VisualOversee dashboard..." -ForegroundColor Cyan
    Start-Window -Title "VisualOversee" -Command "java VisualOversee $LbHost 11116"
}
