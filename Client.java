import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

public class Client {
    // Defaults — override with --lb-host / --lb-port
    private static String lbAddress = "localhost";
    private static int lbPort = 11114;
    private static final int SOCKET_TIMEOUT_MS = 30000;
    private static final int CONNECT_TIMEOUT_MS = 5000;

    private static DatagramSocket udpSocket;
    private static volatile boolean keepListening = true;

    public static void main(String[] args) {
        Thread udpListenerThread = null;
        try {
            udpSocket = new DatagramSocket();
            udpSocket.setSoTimeout(1000);
            udpListenerThread = new Thread(Client::listenForUDP, "Client-UDP");
            udpListenerThread.setDaemon(true);
            udpListenerThread.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                keepListening = false;
                if (udpSocket != null && !udpSocket.isClosed())
                    udpSocket.close();
            }));

            // ── Parse args ────────────────────────────────────────────────────
            String clientName = null;
            String mode = "static";
            String scriptFile = null;

            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (("--name".equalsIgnoreCase(a) || "-n".equalsIgnoreCase(a)) && i + 1 < args.length) {
                    clientName = args[++i];
                } else if (("--mode".equalsIgnoreCase(a) || "-m".equalsIgnoreCase(a)) && i + 1 < args.length) {
                    mode = args[++i].toLowerCase();
                } else if ("--lb-host".equalsIgnoreCase(a) && i + 1 < args.length) {
                    lbAddress = args[++i];
                } else if ("--lb-port".equalsIgnoreCase(a) && i + 1 < args.length) {
                    lbPort = Integer.parseInt(args[++i]);
                } else if ("--script".equalsIgnoreCase(a) && i + 1 < args.length) {
                    scriptFile = args[++i];
                }
            }
            if (clientName == null || clientName.trim().isEmpty()) {
                clientName = "Client-" + UUID.randomUUID().toString().substring(0, 8);
            }
            if (!"dynamic".equalsIgnoreCase(mode) && !"sticky".equalsIgnoreCase(mode)) {
                mode = "static";
            }
            System.out.println("Client: " + clientName + "  mode=" + mode
                    + "  lb=" + lbAddress + ":" + lbPort
                    + (scriptFile != null ? "  script=" + scriptFile : ""));

            // ── Connect with auto-reconnect ────────────────────────────────────
            final String finalClientName = clientName;
            final String finalMode = mode;
            final String finalScript = scriptFile;

            while (keepListening) {
                boolean reconnected = connectAndRun(finalClientName, finalMode, finalScript);
                if (!keepListening)
                    break;
                if (!reconnected) {
                    System.out.println("[Auto-reconnect] Waiting 3 s before retrying LB...");
                    Thread.sleep(3000);
                }
            }

        } catch (Exception e) {
            System.out.println("Error: " + e.getMessage());
        } finally {
            keepListening = false;
            if (udpSocket != null && !udpSocket.isClosed())
                udpSocket.close();
            if (udpListenerThread != null) {
                try {
                    udpListenerThread.join();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    /**
     * Connect to LB, get a server assignment, connect to server, and run
     * until the server drops or the user quits.
     * 
     * @return false if we should stop (user quit), true if we should reconnect.
     */
    private static boolean connectAndRun(String clientName, String mode, String scriptFile)
            throws Exception {

        // Ask LB for server
        String serverAddress;
        try {
            serverAddress = queryLoadBalancer(clientName, mode);
        } catch (IOException e) {
            System.out.println("[Client] Cannot reach LB: " + e.getMessage());
            return true; // trigger reconnect loop
        }

        if ("NO_SERVER_AVAILABLE".equalsIgnoreCase(serverAddress)) {
            System.out.println("[Client] LB says no server available — will retry.");
            Thread.sleep(2000);
            return true;
        }

        System.out.println("[Client] Assigned to server: " + serverAddress);
        int lastColon = serverAddress.lastIndexOf(':');
        String serverHost = serverAddress.substring(0, lastColon);
        int serverPort = Integer.parseInt(serverAddress.substring(lastColon + 1));

        Socket serverSocket = new Socket();
        try {
            serverSocket.connect(new InetSocketAddress(serverHost, serverPort), CONNECT_TIMEOUT_MS);
            serverSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
        } catch (IOException e) {
            System.out.println("[Client] Cannot reach server " + serverAddress + ": " + e.getMessage());
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            return true; // reconnect
        }

        try (Socket s = serverSocket) {
            BufferedReader serverIn = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter serverOut = new PrintWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);

            // Greeting
            serverOut.println("hello " + clientName);
            serverOut.println("udp " + udpSocket.getLocalPort());
            System.out.println("[Client] Connected. Type commands (quit to disconnect).");

            // Server reader thread
            final boolean[] serverDropped = { false };
            Thread serverReader = new Thread(() -> {
                try {
                    String line;
                    while (keepListening && (line = serverIn.readLine()) != null) {
                        if (line.startsWith("FILE ")) {
                            receiveFile(line, serverIn);
                        } else {
                            System.out.println("Server: " + line);
                        }
                    }
                } catch (IOException e) {
                    if (keepListening)
                        System.out.println("[Client] Server connection lost: " + e.getMessage());
                } finally {
                    serverDropped[0] = true;
                }
            }, "Client-ServerReader");
            serverReader.setDaemon(true);
            serverReader.start();

            // Input: script file or stdin
            if (scriptFile != null) {
                try (BufferedReader script = new BufferedReader(new FileReader(scriptFile))) {
                    String line;
                    while (keepListening && (line = script.readLine()) != null && !serverDropped[0]) {
                        line = line.trim();
                        if (line.isEmpty() || line.startsWith("#"))
                            continue;
                        System.out.println("> " + line);
                        serverOut.println(line);
                        if ("quit".equalsIgnoreCase(line)) {
                            keepListening = false;
                            break;
                        }
                        Thread.sleep(200); // small delay between script commands
                    }
                }
            } else {
                try (BufferedReader userIn = new BufferedReader(new InputStreamReader(System.in))) {
                    String userInput;
                    while (keepListening && !serverDropped[0] && (userInput = userIn.readLine()) != null) {
                        serverOut.println(userInput);
                        if ("quit".equalsIgnoreCase(userInput.trim())) {
                            keepListening = false;
                            return false; // user quit — don't reconnect
                        }
                    }
                }
            }
            serverReader.join(2000);
        }

        // If we reach here and keepListening is still true, the server dropped →
        // reconnect
        if (keepListening) {
            System.out.println("[Client] Server dropped. Reconnecting via LB in 2 s...");
            Thread.sleep(2000);
            return true;
        }
        return false;
    }

    /** Query the LB for an available server address ("host:port"). */
    private static String queryLoadBalancer(String clientName, String mode) throws IOException {
        try (Socket lbSocket = new Socket()) {
            lbSocket.connect(new InetSocketAddress(lbAddress, lbPort), CONNECT_TIMEOUT_MS);
            lbSocket.setSoTimeout(SOCKET_TIMEOUT_MS);
            BufferedReader lbIn = new BufferedReader(
                    new InputStreamReader(lbSocket.getInputStream(), StandardCharsets.UTF_8));
            PrintWriter lbOut = new PrintWriter(
                    new OutputStreamWriter(lbSocket.getOutputStream(), StandardCharsets.UTF_8), true);
            lbOut.println("HELLO " + clientName + " " + mode);
            String resp = lbIn.readLine();
            if (resp == null)
                throw new IOException("No response from LB");
            return resp.replace("/", "").trim();
        }
    }

    /** Receive a Base64-encoded file streamed from the server. */
    private static void receiveFile(String header, BufferedReader serverIn) throws IOException {
        String[] parts = header.trim().split("\\s+");
        if (parts.length < 3) {
            System.out.println("Malformed FILE header: " + header);
            return;
        }
        String name = parts[1];
        long size = -1L;
        try {
            size = Long.parseLong(parts[2]);
        } catch (NumberFormatException ignore) {
        }
        String outName = "received_" + name;
        try (FileOutputStream fos = new FileOutputStream(outName)) {
            Base64.Decoder dec = Base64.getDecoder();
            long written = 0;
            while (true) {
                String b64 = serverIn.readLine();
                if (b64 == null) {
                    System.out.println("Unexpected EOF during file transfer.");
                    break;
                }
                if ("ENDFILE".equals(b64))
                    break;
                byte[] chunk = dec.decode(b64);
                fos.write(chunk);
                written += chunk.length;
            }
            fos.flush();
            System.out.println("File received: " + outName + " (" + written + " bytes"
                    + (size >= 0 ? " / expected " + size : "") + ")");
        }
    }

    private static void listenForUDP() {
        try {
            byte[] buffer = new byte[1024];
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            while (keepListening) {
                try {
                    udpSocket.receive(packet);
                    String received = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    System.out.println("UDP: " + received);
                } catch (SocketTimeoutException ste) {
                    // expected — loop to check keepListening
                }
            }
        } catch (SocketException se) {
            // closed during shutdown
        } catch (IOException e) {
            System.out.println("UDP listener error: " + e.getMessage());
        }
    }
}
