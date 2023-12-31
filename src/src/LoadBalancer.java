import annotations.IgnoreCoverage;
import utility.LamportClock;
import utility.SocketClient;
import utility.SocketCommunicator;
import utility.SocketServer;
import utility.http.HTTPRequest;
import utility.http.HTTPResponse;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.*;


public class LoadBalancer extends SocketServer {
    private final ScheduledExecutorService heartbeatPool =
            Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService connectionPool = Executors.newCachedThreadPool();
    private final int HEARTBEAT_SCHEDULE = Integer.parseInt(config.get(
            "HEARTBEAT_SCHEDULE", "30000"));
    private final List<ServerInfo> registry = new ArrayList<>();
    ScheduledFuture<?> heartbeatFuture;
    private int newPort;
    private AggregationServer builtinServer;
    private ServerInfo leader;

    /**
     * Construct a load balancer
     *
     * @param port - port to bind to
     * @throws IOException if port is occupied
     */
    public LoadBalancer(int port) throws IOException {
        super(port);
        newPort = port + 1;
        startBuiltInServer();
        setLeader("127.0.0.1", newPort);
        run();
    }

    @IgnoreCoverage
    public static void main(String[] args) throws IOException {
        int port = getPort(args);
        SocketServer server = new LoadBalancer(port);
        server.start();
        server.close();
    }

    /**
     * Get the built in agg server
     *
     * @return the built in aggregation server
     */
    public AggregationServer getBuiltinServer() {
        return builtinServer;
    }

    /**
     * Get the ServerInfo object of the current leader
     *
     * @return ServerInfo object of the current leader
     */
    public ServerInfo getLeader() {
        return leader;
    }

    /**
     * Check if the server list contains a server identified by hostname and port
     *
     * @param hostname aggregation server hostname
     * @param port     aggregation server port
     * @return true if the load balancer is connected to the server, otherwise false
     */
    public boolean contains(String hostname, int port) {
        return registry.contains(new ServerInfo(hostname, port));
    }


    /**
     * Add a new agg server to the list.
     *
     * @param hostname aggregation server hostname
     * @param port     aggregation server port
     */
    public void addServer(String hostname, int port) {
        logger.info("Adding new host to registry: " + hostname + ":" + port);
        if (isAlive(hostname, port))
            registry.add(new ServerInfo(hostname, port));
    }

    /**
     * Select a new leader from the pool of connected agg servers.
     * <p>
     * The load balancer sends a heart beat message to each server sequentially. The first server that
     * response with a healthy status is set to be the leader. If no server is alive, a new built in server
     * is set.
     */
    public synchronized void electLeader() throws IOException {
        logger.info("Electing new leader among connected servers");
        for (ServerInfo info : registry) {
            if (isAlive(info.hostname, info.port)) {
                leader = info;
                logger.info("Success: Selecting: " + info.hostname + ":" + info.port);
                return;
            }
        }
        logger.info("Not connecting to external server, creating a self-managed " +
                "server.");
        startBuiltInServer();
        setLeader("127.0.0.1", newPort);
    }

    /**
     * Set the server identified by hostname and port to be the leader
     *
     * @param hostname aggregation server hostname
     * @param port     aggregation server port
     */
    public synchronized void setLeader(String hostname, int port) {
        ServerInfo info = new ServerInfo(hostname, port);
        if (!contains(hostname, port)) {
            addServer(hostname, port);
        }
        leader = info;
    }

    /**
     * Send an empty GET message to check whether the server is alive.
     *
     * @param hostname aggregation server hostname
     * @param port     aggregation server port
     * @return true if a 2xx code is received
     */
    public boolean isAlive(String hostname, int port) {
        try {
            GETClient.main((hostname + ":" + port).split(" "));
            logger.info("Success: server is healthy " + hostname + ":" + port);
            return true;
        } catch (IOException e) {
            logger.info("Failed: unable to get heartbeat from: " + hostname + ":" + port);
            return false;
        }
    }

    /**
     * Pre-start hook: every 30 seconds or HEARTBEAT_SCHEDULE, the load balancer
     * sends a heart beat check to the current leader. If the current leader is dead,
     * it initiates a procedure to elect a new leader.
     */
    @IgnoreCoverage
    protected void pre_start_hook() {
        super.pre_start_hook();
        heartbeatFuture = heartbeatPool.scheduleWithFixedDelay(() -> {
            if (!isAlive(leader.hostname, leader.port)) {
                try {
                    electLeader();
                } catch (IOException e) {
                    logger.info("Pre-Start-Hook fails for class LoadBalancer. " +
                            "Message: " + e);
                }
            }
        }, HEARTBEAT_SCHEDULE, HEARTBEAT_SCHEDULE, TimeUnit.MILLISECONDS);
    }

    /**
     * Start-hook: when a new connection is received, create a new thread to handle the connection.
     * If an exception is encountered (pool is shutdown), the start procedure is shutdown.
     */
    @Override
    protected void start_hook() {
        try {
            super.start_hook();
            Socket clientSocket = serverSocket.accept();
            connectionPool.execute(new ClientHandler(
                    clientSocket,
                    clock,
                    new PrintWriter(clientSocket.getOutputStream(), true),
                    new BufferedReader(new InputStreamReader(clientSocket.getInputStream()))));
        } catch (IOException e) {
            logger.info("ERROR: start_hook LoadBalancer error: " + e);
            setStartBreakSignal(true);
        }
    }

    /**
     * Start a local built-in server. Built-in server is set to use the port
     * from the port of the load balancer + 1. When a port is occupied, this process is repeated until sucessful.
     */
    private void startBuiltInServer() {
        try {
            builtinServer = new AggregationServer(newPort);
            new Thread(() -> builtinServer.start()).start();
            addServer("127.0.0.1", newPort);
        } catch (IOException | ClassNotFoundException e) {
            newPort += 1;
            startBuiltInServer();
        }
    }

    /**
     * Close all thread pools
     */
    @Override
    protected void pre_close_hook() {
        super.pre_close_hook();
        if (heartbeatFuture != null)
            heartbeatFuture.cancel(true);
        logger.info("Closing load balancer connection pool");
        connectionPool.shutdownNow();
        logger.info("Closing load balancer heartbeat pool");
        heartbeatPool.shutdownNow();
        if (builtinServer != null && builtinServer.isUp())
            builtinServer.close();
    }

    /**
     * Auxiliary Server Info class. Is a data class containing the server hostname and port
     */
    @IgnoreCoverage
    public static class ServerInfo {
        private final String hostname;
        private final int port;
        private final int hashCode;

        public ServerInfo(String hostname, int port) {
            this.hostname = hostname;
            this.port = port;
            this.hashCode = Objects.hash(this.hostname, this.port);
        }

        public String getHostname() {
            return hostname;
        }

        public int getPort() {
            return port;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ServerInfo that = (ServerInfo) o;
            return hostname.equals(that.hostname) && port == that.port;
        }

        public int hashCode() {
            return hashCode;
        }
    }

    /**
     * Client Handling class that is used when a thread is spawn to handle client's request.
     */
    public class ClientHandler extends SocketCommunicator implements Runnable {

        SocketClient serverInterface;

        public ClientHandler(
                Socket clientSocket,
                LamportClock clock,
                PrintWriter out,
                BufferedReader in
        ) throws IOException {
            super(clientSocket, clock, out, in, "server");
            // Create connection to leader server
            serverInterface =
                    GETClient.from_args((leader.hostname + ":" + leader.port).split(
                            " "));

        }


        /**
         * Redirect the current request to the leader and receives a response.
         * If retry limit exceeds, send a 500 Internal Server Error Message
         *
         * @param request
         */
        public void handleRequest(String request) {
            try {
                serverInterface.send(HTTPRequest.fromMessage(request));
                HTTPResponse response =
                        HTTPResponse.fromMessage(serverInterface.receive());
                send(response);
            } catch (IOException e) {
                logger.info("Error: server error.");
                HTTPResponse response =
                        new HTTPResponse("1.1").setStatusCode("500").setReasonPhrase(
                                "Internal Server Error");
                send(response);
            }
        }

        @Override
        public void run() {

            while (true) {
                try {
                    String request = receive();
                    if (request == null)
                        break;
                    handleRequest(request);
                } catch (IOException e) {
                    logger.info("ERROR: runtime error: " + e);
                    break;
                }
            }
            try {
                serverInterface.close();
                close();
            } catch (IOException e) {
                logger.info("ERROR: unable to close server socket");
            }
        }
    }
}
