import utility.SocketClient;
import utility.domain.ContentServerInformation;
import utility.domain.ContentServerParser;
import utility.http.HTTPRequest;
import utility.http.HTTPResponse;
import utility.weatherJson.Parser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;

public class ContentServer extends SocketClient {
    private final String fileName;


    public ContentServer(
            Socket clientSocket,
            PrintWriter out,
            BufferedReader in,
            String hostname,
            int port,
            String fileName) throws SocketException {
        super(clientSocket, out, in);
        this.hostname = hostname;
        this.port = port;
        this.fileName = fileName;
    }

    /**
     * Create a ContentServer object from arguments from the CLI
     *
     * @param argv CLI argument
     * @return ContentServer object
     * @throws IOException if connection to host cannot be established
     */
    public static ContentServer from_args(String[] argv) throws IOException {
        ContentServerParser parser = new ContentServerParser();
        ContentServerInformation info = parser.parse(argv);
        Socket clientSocket = new Socket(info.hostname, info.port);
        BufferedReader in =
                new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
        PrintWriter out = new PrintWriter(clientSocket.getOutputStream(), true);
        return new ContentServer(clientSocket, out, in, info.hostname, info.port,
                info.fileName);
    }

    public static void main(String[] argv) throws IOException {
        ContentServer client = ContentServer.from_args(argv);
        client.run();
    }

    /**
     * Get the content to be sent in body.
     * <p>
     * The server reads the local weather data file and load to memory
     *
     * @return weather data in json format
     * @throws IOException if file is not found
     */
    private String getBody() throws IOException {
        Parser parser = new Parser();
        parser.parseFile(Paths.get(fileName));
        return parser.toString();
    }

    /**
     * Generate a PUT request to send to agg server
     *
     * @return HTTPRequest PUT request with json file content
     * @throws IOException if file is not found
     */
    public HTTPRequest formatPUTMessage() throws IOException {
        HTTPRequest request = new HTTPRequest("1.1")
                .setMethod("PUT")
                .setURI("/" + fileName)
                .setHeader("Host", getHostname() + ":" + getPort())
                .setHeader("Accept", "application/json")
                .setHeader("Content-Type", "application/json");
        String body = getBody();
        request.setHeader("Content-Length", String.valueOf(body.length()));
        request.setBody(body);
        return request;
    }


    /**
     * Create an empty GET request
     *
     * @return HTTPRequest empty GET request
     */
    public HTTPRequest formatGETMessage() {
        //Empty GET
        HTTPRequest request = new HTTPRequest("1.1").setMethod("GET");
        request.setURI("/");

        //Add Hostname
        request.setHeader("Host", getHostname() + ":" + getPort());

        //Accept Json
        request.setHeader("Accept", "application/json");
        return request;
    }

    /**
     * Sends GET request to get timestamp, then send PUT request
     *
     * @throws IOException if connection issues encountered
     */
    public void run() throws IOException {
        HTTPRequest requestGET = formatGETMessage();
        send(requestGET);
        while (true) {
            String response = receive();
            if (response != null) { // Comm is still maintained
                HTTPResponse httpResponse = HTTPResponse.fromMessage(response);
                // ACK for GET message
                if (Objects.equals(httpResponse.statusCode, "204")) {
                    HTTPRequest requestPUT = formatPUTMessage();
                    send(requestPUT);
                } else { // Close connection when PUT ACK is received
                    break;
                }
            } else
                break; // Break if serverside connection is terminated
        }
        close();
    }
}
