# CN3334 — Distributed System with Load Balancer

A Java-based distributed system featuring a **TCP/UDP Load Balancer**, multiple servers, clients, and a real-time Swing dashboard (`VisualOversee`).

---

## Architecture

```
┌──────────────────────────────────────────────────────────┐
│                     VisualOversee                         │
│  (polls port 11116, sends admin to 11117)                │
└─────────────────────────┬────────────────────────────────┘
                          │
       ┌──────────────────▼───────────────────┐
       │           LoadBalancer               │
       │  port 11114 – client queries         │
       │  port 11115 – server registration   │
       │  port 11116 – JSON status endpoint  │
       │  port 11117 – TCP admin/command port │
       └──────┬───────────────────┬───────────┘
              │ assigns           │ !join / !report
     ┌────────▼──┐        ┌──────▼──────┐
     │  Client A │        │  Server 1   │
     │  Client B │        │  Server 2   │
     └────────▲──┘        └──────┬──────┘
              └──── TCP session ──┘
```

---

## Ports

| Port  | Purpose                                 |
|-------|-----------------------------------------|
| 11114 | Client → LB: request a server           |
| 11115 | Server → LB: `!join`, `!leave`, `!report` |
| 11116 | HTTP/TCP: JSON status (VisualOversee polls here) |
| 11117 | TCP: admin command port (remote control)|

---

## Compile

```powershell
javac LoadBalancer.java Server.java Client.java VisualOversee.java
```

---

## Run (manual)

```powershell
# 1. Load Balancer
java LoadBalancer

# 2. Servers (in separate windows; port 0 = ephemeral)
java Server -p 0 -u 0 --lb-host localhost

# 3. Dashboard (auto-detects LB on localhost:11116)
java VisualOversee

# 4. Clients
java Client --name Alice --mode dynamic
java Client --name Bob   --mode static
java Client --name Carol --mode sticky
```

---

## Run (parallel script)

```powershell
# Start LB + 3 servers + 2 dynamic clients + 1 static client + dashboard
.\run_parallel.ps1 -Servers 3 -DynamicClients 2 -StaticClients 1 -Visualise

# Stress test (while cluster is running)
.\stress_test.ps1 -Clients 8 -Mode dynamic

# Stop everything
.\stop_all.ps1
```

---

## Components

### LoadBalancer

| Feature | Detail |
|---|---|
| **Static routing** | Weighted Round-Robin (`--mode static`) |
| **Dynamic routing** | Lowest RTT, live-client tiebreaker (`--mode dynamic`) |
| **Sticky sessions** | Re-routes client to its last server (`--mode sticky`) |
| **Server eviction** | Auto-removes servers that stop sending `!report` after `evictionTimeoutMs` (default 15 s) |
| **Health scores** | Rolling ping success rate (last 10 pings) per server |
| **Request counters** | Per-server total request count |
| **Drain/Undrain** | Gracefully shifts traffic away from a server |
| **IP/name bans** | Block clients by IP or name |
| **Bounded thread pool** | Max 200 concurrent client handler threads |
| **Admin console** | Interactive stdin command line |
| **Command port 11117** | Remote admin — see commands below |
| **JSON status 11116** | Full cluster state polled by VisualOversee |

### Server

| Flag | Default | Description |
|---|---|---|
| `--port / -p` | 0 | TCP port (0 = ephemeral) |
| `--udp-port / -u` | 0 | UDP port (0 = ephemeral) |
| `--lb-host` | localhost | Load balancer hostname |
| `--lb-port` | 11115 | LB registration port |
| `--max-clients` | 50 | Thread pool size |
| `--stream-limit` | 0 | Cap UDP ticks (0 = unlimited) |

**Commands** (received from clients):
`hello <name>`, `udp <port>`, `ping`, `time`, `ls`, `pwd`, `stream`, `cancel`, `sendfile <name>`, `compute <seconds>`, `quit`

**Persistence:** A background thread re-sends `!join` to the LB every 5–10 s, so the server automatically re-registers if the LB restarts.

### Client

| Flag | Default | Description |
|---|---|---|
| `--name / -n` | auto-generated | Client identifier |
| `--mode / -m` | static | `static`, `dynamic`, or `sticky` |
| `--lb-host` | localhost | LB hostname |
| `--lb-port` | 11114 | LB client port |
| `--script` | — | Path to a text file of commands to send |

**Auto-reconnect:** if the assigned server drops, the client automatically re-queries the LB and reconnects.

**Script mode:**
```text
# comment lines are ignored
ping
compute 2
sendfile data.txt
quit
```

---

## VisualOversee Dashboard

Launch: `java VisualOversee [lb-host] [status-port] [command-port] [project-dir]`

| Panel | Description |
|---|---|
| **Status bar** | Uptime, mode, ping interval, max-conn, ban count, eviction timeout; refresh-rate slider |
| **⚙ Servers table** | RTT bar, health score %, total request count, live count, drain status — red alert when RTT ≥ 200 ms or health < 50% |
| ** RTT History** | Rolling 60-sample sparkline per server |
| ** Server Timeline** | Gantt chart of the last 5 minutes — green = active, orange bar + red end = evicted |
| ** Live Clients** | Real-time clients per server (reported via `!report`) |
| ** Assignments** | Last 20 LB assignments, colour-coded DYN/STA/STK |
| ** Admin** | Type and send admin commands to port 11117; view response |
| ** Process Control** | Start/stop Server & Client processes directly from the dashboard; live output log |

### Starting processes from the dashboard

In the **Process Control** tab:
- **＋ Server** — spawns `java Server -p 0 -u 0 --lb-host <lb>` in the project directory
- **＋ Dynamic / Static / Sticky** — spawns a `java Client` with the chosen mode
- **✕ Stop** — terminates the selected process
- **✕ Stop All** — terminates all spawned processes

The live output of every spawned process is streamed into the log area.

---

## Admin Commands

Send via the Admin panel in VisualOversee, or via `nc localhost 11117`:

```
servers                   – list registered servers (RTT, weight, live count)
live                      – show live clients per server
status                    – servers + live
recent                    – last 50 assignments
weights                   – show server weights
drained                   – list drained servers
drain <host:port|all>     – mark unschedulable
undrain <host:port|all>   – restore scheduling
setweight <host:port> <N> – update weight
mode default <static|dynamic>
set ping <ms>             – ping interval
set maxconn <N>           – max live clients per server
set evict <ms>            – server eviction timeout
ban ip <addr>             – ban client IP
ban name <name>           – ban client by name
unban ip/name <x>
bans                      – list all bans
remove <host:port>        – force-remove a server
clear                     – clear assignment history
help
```

---

## File Transfer (sendfile)

Place files in the `served_files/` directory on the server machine, then:

```
sendfile myfile.txt
```

The server streams the file as Base64 lines; the client writes it as `received_myfile.txt` in the working directory.

---

## Tooling Scripts

| Script | Usage |
|---|---|
| `run_parallel.ps1` | `-Servers N -DynamicClients N -StaticClients N [-Visualise]` |
| `stress_test.ps1` | `-Clients N -Iterations N -Mode dynamic` |
| `stop_all.ps1` | `[-Force]` — kills all cluster Java processes |

