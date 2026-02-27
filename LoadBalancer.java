import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class LoadBalancer {
    private static final int CLIENT_PORT = 11114; // client handshakes
    private static final int REGISTRATION_PORT = 11115; // server register + reports
    private static final int STATUS_PORT = 11116; // read-only JSON status (VisualOversee)
    private static final int COMMAND_PORT = 11117; // remote admin command channel

    private ServerSocket clientSocket;
    private ServerSocket registrationSocket;
    private ServerSocket statusSocket;
    private ServerSocket commandSocket;
    private final long startedAt = System.currentTimeMillis();

    // Registered servers (by value object)
    private final List<ServerInfo> servers = Collections.synchronizedList(new ArrayList<>());

    // Weighted RR support
    private final ConcurrentMap<ServerInfo, Integer> weights = new ConcurrentHashMap<>();
    private volatile List<ServerInfo> weightedList = new CopyOnWriteArrayList<>();
    private final AtomicInteger rrIndex = new AtomicInteger(0);

    // Dynamic mode RTT cache
    private final ConcurrentMap<ServerInfo, Long> rtts = new ConcurrentHashMap<>();
    private ScheduledExecutorService rttScheduler;
    private volatile int pingIntervalMs = 1000;
    private final Object rttLock = new Object(); // guards rttScheduler restart

    // Assignment bookkeeping (best-effort)
    private final ConcurrentMap<ServerInfo, CopyOnWriteArrayList<ClientRecord>> assignedByServer = new ConcurrentHashMap<>();
    private final Deque<ClientRecord> recentAssignments = new ConcurrentLinkedDeque<>();
    private static final int MAX_RECENT = 500;
    private static final int MAX_ASSIGNED_PER_SERVER = 200;

    // Live client reports from servers
    private final ConcurrentMap<ServerInfo, CopyOnWriteArrayList<LiveClient>> liveByServer = new ConcurrentHashMap<>();

    // Drain / limits / bans
    private final Set<ServerInfo> drained = ConcurrentHashMap.newKeySet();
    private volatile int maxPerServer = Integer.MAX_VALUE;
    private final Set<String> bannedIps = ConcurrentHashMap.newKeySet();
    private final Set<String> bannedNames = ConcurrentHashMap.newKeySet();

    // Sticky sessions: client name → last assigned server
    private final ConcurrentMap<String, ServerInfo> stickyMap = new ConcurrentHashMap<>();

    // Request counters per server
    private final ConcurrentMap<ServerInfo, AtomicLong> requestCounts = new ConcurrentHashMap<>();

    // Rolling ping health: last 10 results (true=success) per server
    private static final int HEALTH_WINDOW = 10;
    private final ConcurrentMap<ServerInfo, Deque<Boolean>> pingHistory = new ConcurrentHashMap<>();

    // Eviction: remove servers whose last report is older than evictionTimeoutMs
    private final ConcurrentMap<ServerInfo, Long> lastSeenAt = new ConcurrentHashMap<>();
    private volatile long evictionTimeoutMs = 15_000;

    // Default mode when client omits it
    private volatile String defaultMode = "static";

    private final AtomicInteger clientIdCounter = new AtomicInteger(1);

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    // Shutdown flag
    private volatile boolean isShutdown = false;
    private final ExecutorService clientHandlers = Executors.newCachedThreadPool();

    public LoadBalancer() throws IOException {
        clientSocket = new ServerSocket(CLIENT_PORT);
        clientSocket.setReuseAddress(true);
        clientSocket.setSoTimeout(1000); // allow periodic shutdown check
        registrationSocket = new ServerSocket(REGISTRATION_PORT);
        registrationSocket.setReuseAddress(true);
        registrationSocket.setSoTimeout(1000); // allow periodic shutdown check
        statusSocket = new ServerSocket(STATUS_PORT);
        statusSocket.setReuseAddress(true);
        statusSocket.setSoTimeout(1000);
        commandSocket = new ServerSocket(COMMAND_PORT);
        commandSocket.setReuseAddress(true);
        commandSocket.setSoTimeout(1000);
        System.out.println("Load Balancer on " + CLIENT_PORT + " (clients), "
                + REGISTRATION_PORT + " (servers), " + STATUS_PORT + " (status), "
                + COMMAND_PORT + " (remote-admin)");
        startRttRefresher();

        // Shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            isShutdown = true;
            synchronized (rttLock) {
                if (rttScheduler != null)
                    rttScheduler.shutdownNow();
            }
            if (clientHandlers != null)
                clientHandlers.shutdownNow();
            closeQuietly(clientSocket);
            closeQuietly(registrationSocket);
            closeQuietly(statusSocket);
            closeQuietly(commandSocket);
        }));
    }

    public void start() {
        new Thread(this::handleServerChannel, "LB-ServerChannel").start();
        new Thread(this::handleClientRequests, "LB-Clients").start();
        new Thread(this::adminConsole, "LB-Console").start();
        new Thread(this::startStatusServer, "LB-Status").start();
        new Thread(this::startCommandServer, "LB-Command").start();
        startEvictionScheduler();
    }

    // =================== Eviction Scheduler ===================
    private void startEvictionScheduler() {
        ScheduledExecutorService evSched = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LB-Eviction");
            t.setDaemon(true);
            return t;
        });
        evSched.scheduleAtFixedRate(() -> {
            long cutoff = System.currentTimeMillis() - evictionTimeoutMs;
            List<ServerInfo> snap;
            synchronized (servers) {
                snap = new ArrayList<>(servers);
            }
            for (ServerInfo s : snap) {
                Long seen = lastSeenAt.get(s);
                if (seen != null && seen < cutoff) {
                    System.out.println("[Eviction] Removing stale server: " + s
                            + " (last seen " + (System.currentTimeMillis() - seen) + "ms ago)");
                    removeServer(s);
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    // =================== Command Server (port 11117) ===================
    private void startCommandServer() {
        while (!isShutdown) {
            try {
                Socket conn;
                try {
                    conn = commandSocket.accept();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                clientHandlers.submit(() -> handleCommandConnection(conn));
            } catch (Exception e) {
                if (!isShutdown)
                    System.out.println("Command server error: " + e.getMessage());
            }
        }
    }

    private void handleCommandConnection(Socket sock) {
        try (Socket s = sock;
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            String line = in.readLine();
            if (line == null)
                return;
            // Capture output of the command and send it back
            List<String> response = executeAdminCommand(line.trim());
            for (String l : response)
                out.println(l);
            out.println("END");
        } catch (Exception ignored) {
        }
    }

    /** Execute a single admin command string, return output lines. */
    private List<String> executeAdminCommand(String line) {
        List<String> out = new ArrayList<>();
        String[] parts = line.trim().split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty())
            return out;
        String cmd = parts[0].toLowerCase(Locale.ROOT);
        try {
            switch (cmd) {
                case "servers":
                    out.addAll(serversLines());
                    break;
                case "live":
                    out.addAll(liveLines());
                    break;
                case "status":
                    out.addAll(serversLines());
                    out.addAll(liveLines());
                    break;
                case "weights":
                    weights.forEach((sv, w) -> out.add(sv + " -> " + w));
                    break;
                case "drained":
                    drained.forEach(sv -> out.add("- " + sv));
                    break;
                case "bans":
                    out.add("IPs: " + bannedIps);
                    out.add("Names: " + bannedNames);
                    break;
                case "drain":
                    if (parts.length == 2 && "all".equalsIgnoreCase(parts[1]))
                        drainAll();
                    else if (parts.length == 2)
                        drain(parseServer(parts[1]));
                    out.add("OK");
                    break;
                case "undrain":
                    if (parts.length == 2 && "all".equalsIgnoreCase(parts[1]))
                        undrainAll();
                    else if (parts.length == 2)
                        undrain(parseServer(parts[1]));
                    out.add("OK");
                    break;
                case "setweight":
                    if (parts.length == 3)
                        setWeight(parseServer(parts[1]), Integer.parseInt(parts[2]));
                    out.add("OK");
                    break;
                case "remove":
                    if (parts.length == 2)
                        removeServer(parseServer(parts[1]));
                    out.add("OK");
                    break;
                case "ban":
                    if (parts.length == 3 && "ip".equalsIgnoreCase(parts[1]))
                        bannedIps.add(parts[2]);
                    else if (parts.length == 3 && "name".equalsIgnoreCase(parts[1]))
                        bannedNames.add(parts[2]);
                    out.add("OK");
                    break;
                case "unban":
                    if (parts.length == 3 && "ip".equalsIgnoreCase(parts[1]))
                        bannedIps.remove(parts[2]);
                    else if (parts.length == 3 && "name".equalsIgnoreCase(parts[1]))
                        bannedNames.remove(parts[2]);
                    out.add("OK");
                    break;
                case "set":
                    if (parts.length == 3 && "ping".equalsIgnoreCase(parts[1]))
                        setPingInterval(Integer.parseInt(parts[2]));
                    else if (parts.length == 3 && "maxconn".equalsIgnoreCase(parts[1]))
                        maxPerServer = Integer.parseInt(parts[2]);
                    else if (parts.length == 3 && "evict".equalsIgnoreCase(parts[1]))
                        evictionTimeoutMs = Long.parseLong(parts[2]);
                    out.add("OK");
                    break;
                default:
                    out.add("Unknown: " + cmd);
            }
        } catch (Exception e) {
            out.add("ERROR: " + e.getMessage());
        }
        return out;
    }

    private List<String> serversLines() {
        List<String> out = new ArrayList<>();
        List<ServerInfo> snap;
        synchronized (servers) {
            snap = new ArrayList<>(servers);
        }
        snap.sort(Comparator.comparing((ServerInfo sv) -> sv.address).thenComparingInt(sv -> sv.port));
        for (ServerInfo sv : snap) {
            Long rtt = rtts.get(sv);
            int live = liveByServer.getOrDefault(sv, new CopyOnWriteArrayList<>()).size();
            int w = weights.getOrDefault(sv, 1);
            long req = requestCounts.getOrDefault(sv, new AtomicLong(0)).get();
            int health = healthScore(sv);
            out.add(String.format("%s:%d  rtt=%s  w=%d  live=%d  req=%d  health=%d%%%s",
                    sv.address, sv.port,
                    rtt == null ? "n/a" : rtt + "ms",
                    w, live, req, health,
                    drained.contains(sv) ? " [DRAINED]" : ""));
        }
        return out;
    }

    private List<String> liveLines() {
        List<String> out = new ArrayList<>();
        List<ServerInfo> snap;
        synchronized (servers) {
            snap = new ArrayList<>(servers);
        }
        for (ServerInfo sv : snap) {
            List<LiveClient> list = liveByServer.getOrDefault(sv, new CopyOnWriteArrayList<>());
            out.add(sv + "  (" + list.size() + " clients)");
            for (LiveClient lc : list)
                out.add("  • " + lc.name + "  ip=" + lc.ip);
        }
        return out;
    }

    // =================== Status Server (port 11116) ===================
    private void startStatusServer() {
        while (!isShutdown) {
            try {
                Socket conn;
                try {
                    conn = statusSocket.accept();
                } catch (java.net.SocketTimeoutException e) {
                    continue;
                }
                try (Socket s = conn;
                        PrintWriter pw = new PrintWriter(s.getOutputStream(), true)) {
                    pw.println(buildStatusJson());
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                if (!isShutdown)
                    System.out.println("Status server error: " + e.getMessage());
            }
        }
    }

    private String buildStatusJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        sb.append("\"upSince\":").append(startedAt).append(',');
        sb.append("\"defaultMode\":\"").append(defaultMode).append("\",");
        sb.append("\"maxPerServer\":").append(maxPerServer).append(',');
        sb.append("\"pingIntervalMs\":").append(pingIntervalMs).append(',');
        sb.append("\"evictionTimeoutMs\":").append(evictionTimeoutMs).append(',');
        // banned IPs
        sb.append("\"bannedIps\":[");
        boolean first = true;
        for (String ip : bannedIps) {
            if (!first)
                sb.append(',');
            sb.append('\"').append(ip).append('\"');
            first = false;
        }
        sb.append("],");
        // banned names
        sb.append("\"bannedNames\":[");
        first = true;
        for (String n : bannedNames) {
            if (!first)
                sb.append(',');
            sb.append('\"').append(n).append('\"');
            first = false;
        }
        sb.append("],");
        // servers
        sb.append("\"servers\":[");
        List<ServerInfo> snap;
        synchronized (servers) {
            snap = new ArrayList<>(servers);
        }
        for (int i = 0; i < snap.size(); i++) {
            ServerInfo sv = snap.get(i);
            if (i > 0)
                sb.append(',');
            Long rtt = rtts.get(sv);
            int live = liveByServer.getOrDefault(sv, new CopyOnWriteArrayList<>()).size();
            int weight = weights.getOrDefault(sv, 1);
            long req = requestCounts.getOrDefault(sv, new AtomicLong(0)).get();
            int health = healthScore(sv);
            long seen = lastSeenAt.getOrDefault(sv, startedAt);
            boolean drain = drained.contains(sv);
            sb.append('{');
            sb.append("\"addr\":\"").append(sv.address).append("\",");
            sb.append("\"port\":").append(sv.port).append(',');
            sb.append("\"rttMs\":").append(rtt == null ? -1 : rtt).append(',');
            sb.append("\"weight\":").append(weight).append(',');
            sb.append("\"drained\":").append(drain).append(',');
            sb.append("\"liveCount\":").append(live).append(',');
            sb.append("\"requestCount\":").append(req).append(',');
            sb.append("\"healthScore\":").append(health).append(',');
            sb.append("\"lastSeenMs\":").append(seen).append(',');
            sb.append("\"liveClients\":[");
            List<LiveClient> lc = liveByServer.getOrDefault(sv, new CopyOnWriteArrayList<>());
            for (int j = 0; j < lc.size(); j++) {
                if (j > 0)
                    sb.append(',');
                sb.append("{\"name\":\"").append(esc(lc.get(j).name))
                        .append("\",\"ip\":\"").append(lc.get(j).ip).append("\"}");
            }
            sb.append("]}");
        }
        sb.append("],");
        // recent assignments (last 20)
        sb.append("\"recentAssignments\":[");
        Object[] recent = recentAssignments.toArray();
        int start = Math.max(0, recent.length - 20);
        for (int i = start; i < recent.length; i++) {
            ClientRecord cr = (ClientRecord) recent[i];
            if (i > start)
                sb.append(',');
            sb.append('{');
            sb.append("\"clientName\":\"").append(esc(cr.clientName)).append("\",");
            sb.append("\"mode\":\"").append(cr.mode).append("\",");
            sb.append("\"server\":\"").append(cr.server.address).append(':').append(cr.server.port).append("\",");
            sb.append("\"assignedAt\":").append(cr.assignedAt);
            sb.append('}');
        }
        sb.append("]}");
        return sb.toString();
    }

    /** Minimal JSON string escaping. */
    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // =================== Admin Console ===================
    private void adminConsole() {
        System.out.println("Admin console ready. Type 'help'.");
        try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length == 0 || parts[0].isEmpty())
                    continue;
                String cmd = parts[0].toLowerCase(Locale.ROOT);

                try {
                    switch (cmd) {
                        case "servers":
                            printServers();
                            break;
                        case "clients":
                            printAssigned();
                            break;
                        case "live":
                            printLive();
                            break;
                        case "status":
                            printServers();
                            printLive();
                            break;
                        case "recent":
                            printRecent();
                            break;

                        case "drained":
                            printDrained();
                            break;
                        case "drain":
                            if (parts.length == 2 && "all".equalsIgnoreCase(parts[1]))
                                drainAll();
                            else if (parts.length == 2)
                                drain(parseServer(parts[1]));
                            else
                                System.out.println("Usage: drain <host:port|all>");
                            break;
                        case "undrain":
                            if (parts.length == 2 && "all".equalsIgnoreCase(parts[1]))
                                undrainAll();
                            else if (parts.length == 2)
                                undrain(parseServer(parts[1]));
                            else
                                System.out.println("Usage: undrain <host:port|all>");
                            break;

                        case "setweight":
                            if (parts.length != 3) {
                                System.out.println("Usage: setweight <host:port> <N>");
                                break;
                            }
                            setWeight(parseServer(parts[1]), Integer.parseInt(parts[2]));
                            break;
                        case "weights":
                            printWeights();
                            break;

                        case "mode":
                            if (parts.length == 3 && "default".equalsIgnoreCase(parts[1])) {
                                if (!"static".equalsIgnoreCase(parts[2]) && !"dynamic".equalsIgnoreCase(parts[2])) {
                                    System.out.println("Value must be static|dynamic");
                                } else {
                                    defaultMode = parts[2].toLowerCase(Locale.ROOT);
                                    System.out.println("Default mode set to " + defaultMode);
                                }
                            } else {
                                System.out.println("Usage: mode default <static|dynamic>");
                            }
                            break;

                        case "set":
                            if (parts.length == 3 && "ping".equalsIgnoreCase(parts[1])) {
                                int ms = Integer.parseInt(parts[2]);
                                setPingInterval(ms);
                                System.out.println("RTT ping interval set to " + ms + "ms");
                            } else if (parts.length == 3 && "maxconn".equalsIgnoreCase(parts[1])) {
                                maxPerServer = Integer.parseInt(parts[2]);
                                System.out.println("Max live clients per server set to " + maxPerServer);
                            } else if (parts.length == 3 && "evict".equalsIgnoreCase(parts[1])) {
                                evictionTimeoutMs = Long.parseLong(parts[2]);
                                System.out.println("Server eviction timeout set to " + evictionTimeoutMs + "ms");
                            } else {
                                System.out.println("Usage: set ping <ms> | set maxconn <N> | set evict <ms>");
                            }
                            break;

                        case "ban":
                            if (parts.length == 3 && "ip".equalsIgnoreCase(parts[1])) {
                                bannedIps.add(parts[2]);
                                System.out.println("Banned IP " + parts[2]);
                            } else if (parts.length == 3 && "name".equalsIgnoreCase(parts[1])) {
                                bannedNames.add(parts[2]);
                                System.out.println("Banned name " + parts[2]);
                            } else
                                System.out.println("Usage: ban ip <x> | ban name <x>");
                            break;
                        case "unban":
                            if (parts.length == 3 && "ip".equalsIgnoreCase(parts[1])) {
                                bannedIps.remove(parts[2]);
                                System.out.println("Unbanned IP " + parts[2]);
                            } else if (parts.length == 3 && "name".equalsIgnoreCase(parts[1])) {
                                bannedNames.remove(parts[2]);
                                System.out.println("Unbanned name " + parts[2]);
                            } else
                                System.out.println("Usage: unban ip <x> | unban name <x>");
                            break;
                        case "bans":
                            System.out.println("Banned IPs: " + bannedIps);
                            System.out.println("Banned names: " + bannedNames);
                            break;

                        case "remove":
                            if (parts.length != 2) {
                                System.out.println("Usage: remove <host:port>");
                                break;
                            }
                            removeServer(parseServer(parts[1]));
                            break;

                        case "clear":
                            assignedByServer.clear();
                            recentAssignments.clear();
                            System.out.println("Cleared assignment history.");
                            break;

                        case "help":
                            System.out.println("Commands:\n" +
                                    "  servers          - list servers (rtt, weight, drain, live)\n" +
                                    "  live             - show live clients per server (reported)\n" +
                                    "  clients          - show recent LB assignments\n" +
                                    "  status           - servers + live\n" +
                                    "  recent           - last " + MAX_RECENT + " assignments\n" +
                                    "  drain <hp|all>   - mark server(s) unschedulable\n" +
                                    "  undrain <hp|all> - make schedulable again\n" +
                                    "  drained          - list drained servers\n" +
                                    "  setweight <hp> <N> | weights\n" +
                                    "  mode default <static|dynamic>\n" +
                                    "  set ping <ms> | set maxconn <N>\n" +
                                    "  ban ip <x> | ban name <x> | unban ip/name <x> | bans\n" +
                                    "  remove <host:port>\n" +
                                    "  clear | help");
                            break;
                        default:
                            System.out.println("Unknown command. Type 'help'.");
                    }
                } catch (Exception ex) {
                    System.out.println("Error: " + ex.getMessage());
                }
            }
        } catch (IOException e) {
            System.out.println("Console error: " + e.getMessage());
        }
    }

    private void printServers() {
        System.out.println("\n== SERVERS ==");
        List<ServerInfo> snapshot;
        synchronized (servers) {
            snapshot = new ArrayList<>(servers);
        }
        if (snapshot.isEmpty()) {
            System.out.println("(none)");
            return;
        }
        snapshot.sort(Comparator.comparing((ServerInfo s) -> s.address).thenComparingInt(s -> s.port));
        for (ServerInfo s : snapshot) {
            Long rtt = rtts.get(s);
            int live = liveByServer.getOrDefault(s, new CopyOnWriteArrayList<>()).size();
            Integer w = weights.getOrDefault(s, 1);
            String flags = (drained.contains(s) ? " drained" : "");
            System.out.printf("- %s:%d  rtt=%s  weight=%d  live=%d%s%n",
                    s.address, s.port, (rtt == null ? "n/a" : (rtt + "ms")), w, live, flags);
        }
    }

    private void printAssigned() {
        System.out.println("\n== RECENT ASSIGNMENTS (by LB) ==");
        if (recentAssignments.isEmpty()) {
            System.out.println("(none)");
            return;
        }
        recentAssignments.stream().limit(100)
                .forEach(cr -> System.out.printf("%s  client=%s  mode=%s  -> %s:%d  from=%s%n",
                        TS_FMT.format(Instant.ofEpochMilli(cr.assignedAt)), cr.clientName, cr.mode, cr.server.address,
                        cr.server.port, cr.lbSeenRemote));
    }

    private void printRecent() {
        printAssigned();
    }

    private void printLive() {
        System.out.println("\n== LIVE CLIENTS (reported by servers) ==");
        List<ServerInfo> snapshot;
        synchronized (servers) {
            snapshot = new ArrayList<>(servers);
        }
        if (snapshot.isEmpty()) {
            System.out.println("(no servers)");
            return;
        }
        snapshot.sort(Comparator.comparing((ServerInfo s) -> s.address).thenComparingInt(s -> s.port));
        for (ServerInfo s : snapshot) {
            List<LiveClient> list = liveByServer.getOrDefault(s, new CopyOnWriteArrayList<>());
            System.out.printf("%s:%d  (%d clients)%n", s.address, s.port, list.size());
            for (LiveClient lc : list) {
                System.out.printf("  • %s  ip=%s  at=%s%n", lc.name, lc.ip,
                        TS_FMT.format(Instant.ofEpochMilli(lc.reportedAt)));
            }
        }
        System.out.println();
    }

    private void printWeights() {
        System.out.println("\n== WEIGHTS ==");
        if (weights.isEmpty()) {
            System.out.println("(all weight=1)");
            return;
        }
        weights.forEach((s, w) -> System.out.println(s + " -> " + w));
    }

    private void printDrained() {
        System.out.println("\n== DRAINED SERVERS ==");
        if (drained.isEmpty()) {
            System.out.println("(none)");
            return;
        }
        drained.forEach(s -> System.out.println("- " + s));
    }

    // =================== Server channel ===================
    private void handleServerChannel() {
        while (!isShutdown) {
            try {
                Socket s = null;
                try {
                    s = registrationSocket.accept();
                } catch (java.net.SocketTimeoutException ste) {
                    continue; // check shutdown flag periodically
                }
                try (Socket sock = s;
                        BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                        PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {

                    String msg = in.readLine();
                    if (msg == null)
                        continue;

                    if (msg.startsWith("!join")) {
                        // "!join ... <port>"
                        String[] parts = msg.trim().split("\\s+");
                        int port = Integer.parseInt(parts[parts.length - 1]);
                        ServerInfo info = new ServerInfo(sock.getInetAddress().getHostAddress(), port);
                        synchronized (servers) {
                            if (!servers.contains(info)) {
                                servers.add(info);
                                weights.putIfAbsent(info, 1);
                                requestCounts.putIfAbsent(info, new AtomicLong(0));
                                lastSeenAt.put(info, System.currentTimeMillis());
                                rebuildWeighted();
                                System.out.println("Registered server: " + info);
                            } else {
                                lastSeenAt.put(info, System.currentTimeMillis()); // re-join after LB restart
                                System.out.println("Server re-registered: " + info);
                            }
                        }
                        out.println("!ack");
                    } else if (msg.startsWith("!leave")) {
                        // "!leave <port>" — server shutting down gracefully
                        String[] parts = msg.trim().split("\\s+");
                        int port = Integer.parseInt(parts[parts.length - 1]);
                        ServerInfo info = new ServerInfo(sock.getInetAddress().getHostAddress(), port);
                        removeServer(info);
                        System.out.println("Server gracefully left: " + info);
                        out.println("!bye");
                    } else if (msg.startsWith("!report")) {
                        // "!report <port> clients <n> <name>@<ip> ..."
                        try {
                            handleReport(sock.getInetAddress().getHostAddress(), msg);
                        } catch (Exception ex) {
                            System.out.println("Bad report: " + ex.getMessage());
                        }
                        // no response needed
                    } else {
                        out.println("!err");
                    }
                }
            } catch (Exception e) {
                if (!isShutdown)
                    System.out.println("Registration channel error: " + e.getMessage());
            }
        }
    }

    private void handleReport(String srcIp, String line) {
        String[] p = line.trim().split("\\s+");
        if (p.length < 4 || !p[2].equalsIgnoreCase("clients"))
            return;
        int tcpPort = Integer.parseInt(p[1]);
        int n = Integer.parseInt(p[3]);

        ServerInfo key = new ServerInfo(srcIp, tcpPort);
        lastSeenAt.put(key, System.currentTimeMillis()); // refresh eviction timer
        List<LiveClient> list = new ArrayList<>();
        for (int i = 0; i < n && 4 + i < p.length; i++) {
            String token = p[4 + i]; // "<name>@<ip>"
            int at = token.lastIndexOf('@');
            String name = (at > 0) ? token.substring(0, at) : token;
            String ip = (at > 0) ? token.substring(at + 1) : "unknown";
            list.add(new LiveClient(name, ip, System.currentTimeMillis()));
        }
        liveByServer.put(key, new CopyOnWriteArrayList<>(list));
    }

    // =================== Client handling ===================
    private void handleClientRequests() {
        while (!isShutdown) {
            try {
                Socket client = null;
                try {
                    client = clientSocket.accept();
                } catch (java.net.SocketTimeoutException ste) {
                    continue; // allow shutdown check
                }
                try (Socket sock = client) {
                    String lbSeenRemote = String.valueOf(sock.getRemoteSocketAddress());
                    String remoteIp = extractIp(lbSeenRemote);

                    sock.setSoTimeout(1000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    PrintWriter out = new PrintWriter(sock.getOutputStream(), true);

                    String name = null;
                    String mode = defaultMode;
                    String line = null;
                    try {
                        line = in.readLine();
                    } catch (IOException ignore) {
                    }
                    if (line != null && line.toUpperCase().startsWith("HELLO")) {
                        String[] parts = line.trim().split("\\s+");
                        if (parts.length >= 2)
                            name = parts[1];
                        if (parts.length >= 3)
                            mode = parts[2].toLowerCase(Locale.ROOT);
                    }
                    if (name == null || name.isEmpty()) {
                        name = "Client-" + clientIdCounter.getAndIncrement();
                    }

                    // Bans
                    if (bannedIps.contains(remoteIp) || bannedNames.contains(name)) {
                        out.println("NO_SERVER_AVAILABLE");
                        System.out.println("Denied client '" + name + "' from " + remoteIp + " (banned).");
                        continue;
                    }

                    ServerInfo chosen;
                    if ("sticky".equalsIgnoreCase(mode)) {
                        // Sticky: reuse last assigned server if still alive, else fall back to dynamic
                        ServerInfo prev = stickyMap.get(name);
                        chosen = (prev != null && isSchedulableNow(prev)) ? prev : selectServerDynamic();
                    } else {
                        chosen = "dynamic".equalsIgnoreCase(mode) ? selectServerDynamic() : selectServerStatic();
                    }
                    if (chosen == null) {
                        out.println("NO_SERVER_AVAILABLE");
                        System.out.println("No server available for client '" + name + "'");
                        continue;
                    }
                    stickyMap.put(name, chosen); // record for sticky future requests
                    requestCounts.computeIfAbsent(chosen, k -> new AtomicLong(0)).incrementAndGet();
                    out.println(chosen.address + ":" + chosen.port);
                    System.out.println("Client '" + name + "' (" + mode + ") -> " + chosen);

                    ClientRecord rec = new ClientRecord(name, mode, System.currentTimeMillis(), chosen, lbSeenRemote);
                    CopyOnWriteArrayList<ClientRecord> aList = assignedByServer.computeIfAbsent(chosen,
                            k -> new CopyOnWriteArrayList<>());
                    aList.add(rec);
                    // Trim to cap in one shot — subList.clear() is O(n), not O(n²)
                    if (aList.size() > MAX_ASSIGNED_PER_SERVER)
                        aList.subList(0, aList.size() - MAX_ASSIGNED_PER_SERVER).clear();
                    recentAssignments.addLast(rec);
                    while (recentAssignments.size() > MAX_RECENT)
                        recentAssignments.pollFirst();
                }
            } catch (Exception ex) {
                if (!isShutdown)
                    System.out.println("Error processing client: " + ex.getMessage());
            }
        }
    }

    private String extractIp(String remote) {
        // formats like "/127.0.0.1:54321"
        int slash = remote.indexOf('/');
        int colon = remote.lastIndexOf(':');
        if (slash >= 0 && colon > slash)
            return remote.substring(slash + 1, colon);
        colon = remote.indexOf(':');
        if (colon > 0)
            return remote.substring(0, colon);
        return remote;
    }

    // =================== Selection ===================
    /**
     * Static selection: Weighted Round-Robin.
     * Uses the pre-built weightedList (reflects weights). Falls back to candidates
     * when weightedList is empty (no servers registered yet).
     * Math.floorMod handles AtomicInteger wraparound correctly.
     */
    private ServerInfo selectServerStatic() {
        List<ServerInfo> candidates = getSchedulableServers();
        if (candidates.isEmpty())
            return null;
        // Pick the active pool: weighted expansion, or raw candidates if no weights
        // built yet
        List<ServerInfo> pool = weightedList.isEmpty() ? candidates : new ArrayList<>(weightedList);
        int size = pool.size();
        // Walk RR index; try up to size*2+1 slots to skip drained/over-capacity entries
        for (int tries = 0; tries < size * 2 + 1; tries++) {
            int idx = Math.floorMod(rrIndex.getAndIncrement(), size);
            ServerInfo s = pool.get(idx);
            if (isSchedulableNow(s))
                return s;
        }
        // Final fallback: first schedulable candidate
        for (ServerInfo s : candidates)
            if (isSchedulableNow(s))
                return s;
        return null;
    }

    /**
     * Dynamic selection: lowest RTT, with live-client count as tiebreaker.
     * If two servers are within 10 ms of each other, prefer the one with fewer
     * live clients (reported). Falls back to static weighted RR when no RTT
     * data is available yet, logging a warning.
     */
    private ServerInfo selectServerDynamic() {
        List<ServerInfo> candidates = getSchedulableServers();
        if (candidates.isEmpty())
            return null;
        ServerInfo best = null;
        long bestRtt = Long.MAX_VALUE;
        int bestLive = Integer.MAX_VALUE;
        boolean anyRtt = false;
        for (ServerInfo s : candidates) {
            if (!isSchedulableNow(s))
                continue;
            Long rtt = rtts.get(s);
            if (rtt == null)
                continue;
            anyRtt = true;
            int live = liveByServer.getOrDefault(s, new CopyOnWriteArrayList<>()).size();
            // Primary: lowest RTT. Tiebreaker (within 10 ms): fewest live clients.
            boolean betterRtt = rtt < bestRtt - 10;
            boolean tiedFewerLive = Math.abs(rtt - bestRtt) <= 10 && live < bestLive;
            if (best == null || betterRtt || tiedFewerLive) {
                best = s;
                bestRtt = rtt;
                bestLive = live;
            }
        }
        if (!anyRtt) {
            System.out.println("[LB] Dynamic: no RTT data yet — falling back to static weighted RR.");
            return selectServerStatic();
        }
        return best != null ? best : selectServerStatic();
    }

    private List<ServerInfo> getSchedulableServers() {
        List<ServerInfo> snap;
        synchronized (servers) {
            snap = new ArrayList<>(servers);
        }
        snap.removeIf(drained::contains);
        return snap;
    }

    private boolean isSchedulableNow(ServerInfo s) {
        if (drained.contains(s))
            return false;
        if (maxPerServer == Integer.MAX_VALUE)
            return true;
        int live = liveByServer.getOrDefault(s, new CopyOnWriteArrayList<>()).size();
        return live < maxPerServer;
    }

    // =================== RTT refresher ===================
    private void startRttRefresher() {
        rttScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LB-RTT");
            t.setDaemon(true);
            return t;
        });
        rttScheduler.scheduleAtFixedRate(() -> {
            List<ServerInfo> snap;
            synchronized (servers) {
                snap = new ArrayList<>(servers);
            }
            snap.parallelStream().forEach(s -> {
                long t = pingServer(s, Math.max(200, pingIntervalMs / 2));
                if (t >= 0)
                    rtts.put(s, t);
            });
        }, 0, pingIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void setPingInterval(int ms) {
        synchronized (rttLock) {
            pingIntervalMs = Math.max(200, ms);
            if (rttScheduler != null)
                rttScheduler.shutdownNow();
            startRttRefresher();
        }
    }

    private long pingServer(ServerInfo s, int timeoutMs) {
        boolean success = false;
        long result = -1;
        try (Socket sock = new Socket()) {
            sock.connect(new InetSocketAddress(s.address, s.port), timeoutMs);
            sock.setSoTimeout(timeoutMs);
            PrintWriter out = new PrintWriter(sock.getOutputStream(), true);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
            long start = System.nanoTime();
            out.println("ping");
            String resp = in.readLine();
            long end = System.nanoTime();
            if (resp != null && resp.trim().equalsIgnoreCase("pong")) {
                result = (end - start) / 1_000_000;
                success = true;
            }
        } catch (IOException ignored) {
        }
        // Record health history
        Deque<Boolean> hist = pingHistory.computeIfAbsent(s, k -> new ArrayDeque<>());
        synchronized (hist) {
            hist.addLast(success);
            while (hist.size() > HEALTH_WINDOW)
                hist.pollFirst();
        }
        return result;
    }

    /** Returns ping success rate 0-100 for the given server. */
    int healthScore(ServerInfo s) {
        Deque<Boolean> hist = pingHistory.get(s);
        if (hist == null || hist.isEmpty())
            return 100; // no data = assume healthy
        long ok;
        synchronized (hist) {
            ok = hist.stream().filter(b -> b).count();
        }
        return (int) (ok * 100 / hist.size());
    }

    // =================== Helpers ===================
    private void rebuildWeighted() {
        List<ServerInfo> base;
        synchronized (servers) {
            base = new ArrayList<>(servers);
        }
        List<ServerInfo> w = new ArrayList<>();
        for (ServerInfo s : base) {
            int times = Math.max(1, weights.getOrDefault(s, 1));
            for (int i = 0; i < times; i++)
                w.add(s);
        }
        weightedList = new CopyOnWriteArrayList<>(w);
        rrIndex.set(0); // reset RR index whenever server list changes
    }

    private ServerInfo parseServer(String hp) {
        // Use lastIndexOf so IPv6 addresses like [::1]:9000 work correctly
        int colon = hp.lastIndexOf(':');
        if (colon < 0)
            throw new IllegalArgumentException("host:port required");
        return new ServerInfo(hp.substring(0, colon), Integer.parseInt(hp.substring(colon + 1)));
    }

    private void setWeight(ServerInfo s, int w) {
        if (w < 1)
            w = 1;
        if (!servers.contains(s)) {
            System.out.println("No such server registered: " + s);
            return;
        }
        weights.put(s, w);
        rebuildWeighted();
        System.out.println("Weight set: " + s + " -> " + w);
    }

    private void removeServer(ServerInfo s) {
        synchronized (servers) {
            servers.remove(s);
        }
        weights.remove(s);
        drained.remove(s);
        rtts.remove(s);
        liveByServer.remove(s);
        assignedByServer.remove(s);
        pingHistory.remove(s);
        lastSeenAt.remove(s);
        requestCounts.remove(s);
        stickyMap.values().removeIf(v -> v.equals(s));
        rebuildWeighted();
        System.out.println("Removed server: " + s);
    }

    private void drain(ServerInfo s) {
        if (!servers.contains(s)) {
            System.out.println("No such server: " + s);
            return;
        }
        drained.add(s);
        System.out.println("Drained: " + s);
    }

    private void undrain(ServerInfo s) {
        drained.remove(s);
        System.out.println("Undrained: " + s);
    }

    private void drainAll() {
        synchronized (servers) {
            drained.addAll(servers);
        }
        System.out.println("All servers drained.");
    }

    private void undrainAll() {
        drained.clear();
        System.out.println("All servers undrained.");
    }

    public static void main(String[] args) {
        try {
            LoadBalancer lb = new LoadBalancer();
            lb.start();
        } catch (IOException e) {
            System.out.println("Failed to start LoadBalancer: " + e.getMessage());
        }
    }

    // =================== Types ===================
    static class ServerInfo {
        final String address;
        final int port;

        ServerInfo(String addr, int port) {
            this.address = addr;
            this.port = port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (!(o instanceof ServerInfo that))
                return false;
            return port == that.port && Objects.equals(address, that.address);
        }

        @Override
        public int hashCode() {
            return Objects.hash(address, port);
        }

        @Override
        public String toString() {
            return address + ":" + port;
        }
    }

    static class ClientRecord {
        final String clientName, mode, lbSeenRemote;
        final long assignedAt;
        final ServerInfo server;

        ClientRecord(String clientName, String mode, long assignedAt, ServerInfo server, String lbSeenRemote) {
            this.clientName = clientName;
            this.mode = mode;
            this.assignedAt = assignedAt;
            this.server = server;
            this.lbSeenRemote = lbSeenRemote;
        }
    }

    static class LiveClient {
        final String name, ip;
        final long reportedAt;

        LiveClient(String name, String ip, long ts) {
            this.name = name;
            this.ip = ip;
            this.reportedAt = ts;
        }
    }

    // Helper to safely close sockets
    private void closeQuietly(AutoCloseable c) {
        if (c == null)
            return;
        try {
            c.close();
        } catch (Exception ignored) {
        }
    }
}
