import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Server {
    // Configuration constants
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int MAX_FILE_SIZE_MB = 100;
    private static final String ALLOWED_FILES_DIR = "served_files";

    private static final String DEFAULT_LB_HOST = "localhost";
    private static final int DEFAULT_LB_REG_PORT = 11115;

    private static DatagramSocket udpSocket;
    private static volatile boolean running = true;
    private static ExecutorService clientExecutor;

    public static void main(String[] args) throws IOException {
        int tcpPort = 0; // 0 => ephemeral
        int udpPort = 0; // 0 => ephemeral
        String lbHost = DEFAULT_LB_HOST;
        int lbRegPort = DEFAULT_LB_REG_PORT;
        int maxClients = 50; // bounded thread pool size
        int streamLimit = 0; // 0 = unlimited UDP ticks

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (("--port".equalsIgnoreCase(a) || "-p".equalsIgnoreCase(a)) && i + 1 < args.length) {
                tcpPort = Integer.parseInt(args[++i]);
            } else if (("--udp-port".equalsIgnoreCase(a) || "-u".equalsIgnoreCase(a)) && i + 1 < args.length) {
                udpPort = Integer.parseInt(args[++i]);
            } else if ("--lb-host".equalsIgnoreCase(a) && i + 1 < args.length) {
                lbHost = args[++i];
            } else if (("--lb-port".equalsIgnoreCase(a) || "--lb-reg-port".equalsIgnoreCase(a))
                    && i + 1 < args.length) {
                lbRegPort = Integer.parseInt(args[++i]);
            } else if ("--max-clients".equalsIgnoreCase(a) && i + 1 < args.length) {
                maxClients = Integer.parseInt(args[++i]);
            } else if ("--stream-limit".equalsIgnoreCase(a) && i + 1 < args.length) {
                streamLimit = Integer.parseInt(args[++i]);
            }
        }
        final int finalStreamLimit = streamLimit;

        ServerSocket serverSocket = new ServerSocket(tcpPort);
        serverSocket.setReuseAddress(true);
        serverSocket.setSoTimeout(1000); // allow graceful shutdown checks
        tcpPort = serverSocket.getLocalPort();

        udpSocket = new DatagramSocket(udpPort);
        udpSocket.setReuseAddress(true);
        udpSocket.setSoTimeout(1000);
        udpPort = udpSocket.getLocalPort();

        clientExecutor = Executors.newFixedThreadPool(maxClients);

        final String finalLbHost = lbHost;
        final int finalLbRegPort = lbRegPort;
        final int finalTcpPort = tcpPort;

        // Shutdown hook: signal stop, notify LB, close resources
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            running = false;
            sendLeave(finalLbHost, finalLbRegPort, finalTcpPort);
            clientExecutor.shutdownNow();
            try {
                serverSocket.close();
            } catch (Exception ignored) {
            }
            if (udpSocket != null && !udpSocket.isClosed())
                udpSocket.close();
        }));

        registerWithLoadBalancer(lbHost, lbRegPort, tcpPort); // initial registration
        System.out.println("Server listening on TCP " + tcpPort + " and UDP " + udpPort
                + " (LB " + lbHost + ":" + lbRegPort + ")");

        // Persistent re-registration thread: re-attempts !join if LB restarts
        Thread reRegThread = new Thread(() -> {
            long backoff = 5_000;
            while (running) {
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    break;
                }
                if (!running)
                    break;
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress(finalLbHost, finalLbRegPort), 3000);
                    s.setSoTimeout(3000);
                    PrintWriter pw = new PrintWriter(s.getOutputStream(), true);
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream()));
                    pw.println("!join -v dynamic " + finalTcpPort);
                    String resp = br.readLine();
                    if ("!ack".equals(resp)) {
                        System.out.println("[Server] Re-registered with LB (heartbeat join OK)");
                        backoff = 10_000; // slow down after success
                    }
                } catch (IOException e) {
                    backoff = 5_000; // faster retry if LB is down
                }
            }
        }, "Srv-ReReg-" + finalTcpPort);
        reRegThread.setDaemon(true);
        reRegThread.start();

        // start reporter thread (every ~2s)
        ServerReporter reporter = new ServerReporter(lbHost, lbRegPort, tcpPort);
        Thread repThread = new Thread(reporter, "Srv-Reporter-" + tcpPort);
        repThread.setDaemon(true);
        repThread.start();

        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // Use bounded pool — prevents unbounded thread creation under load
                clientExecutor.submit(new ClientHandler(clientSocket, reporter, finalStreamLimit));
            } catch (java.net.SocketTimeoutException ste) {
                // loop to check running flag
            } catch (IOException e) {
                if (running)
                    System.out.println("Accept error: " + e.getMessage());
            }
        }
    }

    /**
     * Register with the LB, retrying up to 5 times with 2 s delay between attempts.
     */
    private static void registerWithLoadBalancer(String lbHost, int lbRegPort, int tcpPort) {
        for (int attempt = 1; attempt <= 5; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(lbHost, lbRegPort), 5000);
                socket.setSoTimeout(5000);
                try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                    out.println("!join -v dynamic " + tcpPort);
                    String response = in.readLine();
                    if ("!ack".equals(response)) {
                        System.out.println("Registered with Load Balancer on TCP port " + tcpPort);
                        return;
                    } else {
                        System.out.println("LB did not ack (response=" + response + "), attempt " + attempt);
                    }
                }
            } catch (IOException e) {
                System.out.println("Registration attempt " + attempt + " failed: " + e.getMessage());
            }
            if (attempt < 5) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
        System.out.println("Could not register with LB after 5 attempts. Continuing anyway.");
    }

    /**
     * Best-effort: inform the LB that this server is leaving so it removes it
     * immediately.
     */
    private static void sendLeave(String lbHost, int lbRegPort, int tcpPort) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(lbHost, lbRegPort), 3000);
            socket.setSoTimeout(3000);
            try (PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
                out.println("!leave " + tcpPort);
                in.readLine(); // consume "!bye"
            }
        } catch (IOException ignored) {
            // Best-effort; LB will detect departure via failed pings anyway
        }
    }

    // ======= Reporter (keeps a live set of clients and periodically reports to LB)
    // =======
    static class ServerReporter implements Runnable {
        private final String lbHost;
        private final int lbPort;
        private final int tcpPort;
        private final Set<ClientKey> live = ConcurrentHashMap.newKeySet();

        ServerReporter(String lbHost, int lbPort, int tcpPort) {
            this.lbHost = lbHost;
            this.lbPort = lbPort;
            this.tcpPort = tcpPort;
        }

        void onConnect(Socket s) {
            live.add(ClientKey.fromSocket(s));
        }

        void onHello(Socket s, String name) {
            ClientKey k = ClientKey.fromSocket(s);
            live.remove(k);
            live.add(new ClientKey(name, k.ip, k.port));
        }

        void onDisconnect(Socket s) {
            live.remove(ClientKey.fromSocket(s));
        }

        @Override
        public void run() {
            try {
                while (running) {
                    // Build "!report <tcpPort> clients <n> <name>@<ip> ..."
                    StringBuilder sb = new StringBuilder();
                    sb.append("!report ").append(tcpPort).append(" clients ").append(live.size());
                    for (ClientKey k : live) {
                        String name = (k.name == null || k.name.isEmpty()) ? "unknown" : k.name;
                        sb.append(' ').append(name).append('@').append(k.ip);
                    }
                    try (Socket s = new Socket(lbHost, lbPort);
                            PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
                        out.println(sb.toString());
                    } catch (IOException ignored) {
                    }
                    Thread.sleep(2000);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        static class ClientKey {
            final String name;
            final String ip;
            final int port; // remote port (for uniqueness)

            ClientKey(String name, String ip, int port) {
                this.name = name;
                this.ip = ip;
                this.port = port;
            }

            static ClientKey fromSocket(Socket s) {
                String remote = String.valueOf(s.getRemoteSocketAddress()); // "/ip:port"
                String ip;
                int p;
                int slash = remote.indexOf('/');
                int colon = remote.lastIndexOf(':');
                if (slash >= 0 && colon > slash) {
                    ip = remote.substring(slash + 1, colon);
                    p = Integer.parseInt(remote.substring(colon + 1));
                } else {
                    ip = remote;
                    p = -1;
                }
                return new ClientKey("", ip, p);
            }

            @Override
            public boolean equals(Object o) {
                if (this == o)
                    return true;
                if (!(o instanceof ClientKey))
                    return false;
                ClientKey that = (ClientKey) o;
                return port == that.port && Objects.equals(ip, that.ip);
            }

            @Override
            public int hashCode() {
                return Objects.hash(ip, port);
            }
        }
    }

    // ======= Client handler =======
    private static class ClientHandler implements Runnable {
        private final Socket clientSocket;
        private final ServerReporter reporter;
        private final int streamLimit; // 0 = unlimited

        private String clientName = "";
        private InetAddress clientUdpAddr = null;
        private int clientUdpPort = -1;

        private volatile boolean streaming = false;
        private Thread streamThread = null;

        ClientHandler(Socket socket, ServerReporter reporter, int streamLimit) {
            this.clientSocket = socket;
            this.reporter = reporter;
            this.streamLimit = streamLimit;
            reporter.onConnect(socket);
        }

        public void run() {
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.UTF_8));
                    PrintWriter out = new PrintWriter(
                            new OutputStreamWriter(clientSocket.getOutputStream(), StandardCharsets.UTF_8), true)) {

                String inputLine;
                while ((inputLine = in.readLine()) != null) {
                    String[] commands = inputLine.trim().split("\\s+");
                    if (commands.length == 0)
                        continue;
                    String cmd = commands[0].toLowerCase();

                    switch (cmd) {
                        case "hello":
                            if (commands.length > 1) {
                                clientName = commands[1];
                                out.println("Hello, " + clientName + "!");
                                reporter.onHello(clientSocket, clientName);
                            } else {
                                out.println("Hello received without name. Use: hello <name>");
                            }
                            break;

                        case "udp":
                            if (commands.length > 1) {
                                try {
                                    clientUdpPort = Integer.parseInt(commands[1]);
                                    clientUdpAddr = clientSocket.getInetAddress();
                                    out.println(
                                            "UDP registered: " + clientUdpAddr.getHostAddress() + ":" + clientUdpPort);
                                } catch (NumberFormatException e) {
                                    out.println("Bad UDP port");
                                }
                            } else {
                                out.println("Usage: udp <port>");
                            }
                            break;

                        case "stream":
                            if (clientUdpAddr == null || clientUdpPort <= 0) {
                                out.println("No UDP registration. Use: udp <port> first.");
                                break;
                            }
                            if (streaming) {
                                out.println("Already streaming.");
                                break;
                            }
                            streaming = true;
                            out.println("Streaming started to " + clientUdpAddr.getHostAddress() + ":" + clientUdpPort);
                            startStreamingInBackground(out);
                            break;

                        case "cancel":
                            if (streaming) {
                                streaming = false;
                                if (streamThread != null) {
                                    streamThread.interrupt();
                                    streamThread = null;
                                }
                                out.println("Streaming stop requested.");
                            } else {
                                out.println("Not streaming.");
                            }
                            break;

                        case "ping":
                            out.println("pong"); // LB RTT check
                            break;

                        case "time":
                            out.println(new Date());
                            break;

                        case "ls":
                            out.println(ls());
                            break;

                        case "pwd":
                            out.println(pwd());
                            break;

                        case "sendfile":
                            handleSendFile(commands, out);
                            break;

                        case "compute":
                            handleCompute(commands, out);
                            break;

                        case "quit":
                            out.println("Bye.");
                            clientSocket.close();
                            return;

                        default:
                            out.println("Unknown command");
                            break;
                    }
                }
            } catch (IOException e) {
                // client dropped — normal exit
            } finally {
                try {
                    clientSocket.close();
                } catch (IOException ignore) {
                }
                streaming = false;
                if (streamThread != null) {
                    streamThread.interrupt();
                    streamThread = null;
                }
                reporter.onDisconnect(clientSocket);
            }
        }

        private void startStreamingInBackground(PrintWriter out) {
            final int limit = streamLimit;
            streamThread = new Thread(() -> {
                try {
                    int i = 0;
                    // limit == 0 means unlimited; otherwise stop after 'limit' ticks
                    while (streaming && (limit == 0 || i < limit)) {
                        i++;
                        byte[] buffer = ("tick " + System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8);
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length, clientUdpAddr, clientUdpPort);
                        synchronized (udpSocket) {
                            udpSocket.send(packet);
                        }
                        Thread.sleep(2000);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (IOException e) {
                    System.out.println("Streaming error: " + e.getMessage());
                } finally {
                    streaming = false;
                    streamThread = null;
                    try {
                        out.println("Streaming ended.");
                    } catch (Exception ignore) {
                    }
                }
            }, "Stream-" + (clientName.isEmpty() ? String.valueOf(clientSocket.getPort()) : clientName));
            streamThread.setDaemon(true);
            streamThread.start();
        }

        private static String ls() {
            File dir = new File(".");
            StringBuilder sb = new StringBuilder();
            File[] filesList = dir.listFiles();
            if (filesList != null) {
                for (File file : filesList)
                    sb.append(file.getName()).append(" ");
            }
            return sb.toString().trim();
        }

        private static String pwd() {
            return System.getProperty("user.dir");
        }

        // Base64 line protocol — keeps all bytes text-safe over the plaintext socket
        private void handleSendFile(String[] commands, PrintWriter out) {
            boolean verbose = commands.length > 2 && "-v".equalsIgnoreCase(commands[1]);
            String fileName = verbose ? commands[2] : (commands.length > 1 ? commands[1] : null);

            if (fileName == null) {
                out.println("Usage: sendfile [-v] <filename>");
                return;
            }

            // Security: reject path traversal
            if (fileName.contains("..") || fileName.contains("\\") || fileName.contains("/")) {
                out.println("ERROR Invalid filename");
                return;
            }

            File serveDir = new File(ALLOWED_FILES_DIR);
            if (!serveDir.exists() && !serveDir.mkdirs()) {
                out.println("ERROR Server configuration error");
                return;
            }

            File file = new File(serveDir, fileName);
            if (!file.exists() || !file.isFile()) {
                out.println("ERROR File not found");
                return;
            }

            // Size limit check
            long maxBytes = MAX_FILE_SIZE_MB * 1024 * 1024L;
            if (file.length() > maxBytes) {
                out.println("ERROR File too large (max " + MAX_FILE_SIZE_MB + "MB)");
                return;
            }

            // Stream transfer: send chunks as Base64 lines, terminated by ENDFILE
            out.println("FILE " + file.getName() + " " + file.length());
            Base64.Encoder encoder = Base64.getEncoder();

            try (InputStream fis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] buf = new byte[8192]; // Daha büyük buffer
                int n;
                while ((n = fis.read(buf)) >= 0) {
                    if (n == 0)
                        continue;
                    String b64 = encoder.encodeToString(n == buf.length ? buf : Arrays.copyOf(buf, n));
                    out.println(b64);
                    if (verbose) {
                        System.out.println("Sent chunk (" + n + " bytes) of " + file.getName());
                    }
                }
            } catch (IOException e) {
                out.println("ERROR Sending file: " + e.getMessage());
                return;
            }

            out.println("ENDFILE");
        }

        private static void handleCompute(String[] commands, PrintWriter out) {
            if (commands.length > 1) {
                try {
                    int seconds = Integer.parseInt(commands[1]);
                    compute(seconds);
                    out.println("Computed for " + seconds + " seconds");
                } catch (NumberFormatException e) {
                    out.println("Bad duration");
                }
            } else {
                out.println("Usage: compute <seconds>");
            }
        }

        /**
         * Actual CPU-busy loop so the server's RTT rises under load,
         * making dynamic routing meaningfully prefer lower-load servers.
         */
        private static void compute(int seconds) {
            long end = System.currentTimeMillis() + seconds * 1000L;
            long dummy = 0;
            while (System.currentTimeMillis() < end) {
                for (int i = 0; i < 100_000; i++)
                    dummy += i;
            }
            // Prevent dead-code elimination by the JIT
            if (dummy == 0)
                System.out.print("");
        }
    }
}
