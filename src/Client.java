import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.*;

public class Client {
    // Client configuration constants
    private static final int MAX_PACKET_SIZE = 508; // Max size of each UDP packet
    private static final int TIMEOUT_MS = 5000; // Timeout for socket receive operations
    private static final int MAX_RETRIES = 5; // Maximum retry attempts for lost packets

    // Packet types for client-server communication
    private static final byte TYPE_DATA = 1;
    private static final byte TYPE_ACK = 2;
    private static final byte TYPE_REQUEST = 3;
    private static final byte TYPE_INDEX = 4;
    private static final byte TYPE_INDEX_DATA = 5;
    private static final byte TYPE_NONEXIST = 6;

    // Sends a request to the server for an index of available files
    private static void requestFileIndex(DatagramSocket socket, InetAddress serverAddress,
            int portNumber) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1);
        buffer.put(TYPE_INDEX); // Indicate this is an index request

        DatagramPacket indexRequest =
                new DatagramPacket(buffer.array(), buffer.position(), serverAddress, portNumber);
        socket.send(indexRequest); // Send the index request to the server

        ByteArrayOutputStream indexData = new ByteArrayOutputStream(); // To store received index
                                                                       // data
        int expectedSequence = 0; // Sequence number expected in order
        int retryCount = 0; // Retry counter

        while (retryCount < MAX_RETRIES) {
            byte[] receiveBuffer = new byte[MAX_PACKET_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);

            try {
                socket.receive(receivePacket); // Receive packet from the server
                ByteBuffer received =
                        ByteBuffer.wrap(receivePacket.getData(), 0, receivePacket.getLength());
                byte type = received.get(); // Read the type of the received packet

                if (type == TYPE_INDEX_DATA) {
                    int sequenceNumber = received.getInt(); // Sequence number of the packet
                    if (sequenceNumber == -1) { // End of index data
                        break;
                    }

                    int dataLength = received.getInt(); // Length of data in the packet
                    if (sequenceNumber == expectedSequence) {
                        byte[] chunk = new byte[dataLength];
                        received.get(chunk);
                        indexData.write(chunk); // Append received chunk to index data

                        // Send ACK for the received packet
                        ByteBuffer ackBuffer = ByteBuffer.allocate(5);
                        ackBuffer.put(TYPE_ACK);
                        ackBuffer.putInt(sequenceNumber);

                        DatagramPacket ackPacket = new DatagramPacket(ackBuffer.array(),
                                ackBuffer.position(), serverAddress, portNumber);
                        socket.send(ackPacket);

                        expectedSequence++; // Increment expected sequence for the next packet
                        retryCount = 0; // Reset retry count on successful receive
                    } else {
                        System.out.println("Received out-of-order index chunk: " + sequenceNumber);
                    }
                }
            } catch (SocketTimeoutException e) {
                retryCount++; // Increment retry count on timeout
                System.out.println("Timeout waiting for index data. Retry " + retryCount + " of "
                        + MAX_RETRIES);
                if (retryCount < MAX_RETRIES) {
                    // Resend ACK for the last successfully received packet
                    if (expectedSequence > 0) {
                        ByteBuffer ackBuffer = ByteBuffer.allocate(5);
                        ackBuffer.put(TYPE_ACK);
                        ackBuffer.putInt(expectedSequence - 1);

                        DatagramPacket ackPacket = new DatagramPacket(ackBuffer.array(),
                                ackBuffer.position(), serverAddress, portNumber);
                        socket.send(ackPacket);
                    }
                }
            }
        }

        if (retryCount >= MAX_RETRIES) {
            System.out
                    .println("Failed to receive complete index after " + MAX_RETRIES + " retries");
        } else {
            System.out.println("Available files:\n" + new String(indexData.toByteArray()));
        }
    }

    // Sends a file request to the server and saves the received file data
    private static void requestFile(DatagramSocket socket, InetAddress serverAddress,
            int portNumber, String filename, String outputFileName) throws IOException {
        ByteBuffer requestBuffer = ByteBuffer.allocate(filename.length() + 1);
        requestBuffer.put(TYPE_REQUEST); // Indicate this is a file request
        requestBuffer.put(filename.getBytes());

        DatagramPacket requestPacket = new DatagramPacket(requestBuffer.array(),
                requestBuffer.position(), serverAddress, portNumber);
        socket.send(requestPacket); // Send file request to the server

        try (FileOutputStream fos = new FileOutputStream(outputFileName)) {
            int expectedSequence = 0; // Expected sequence number
            int retryCount = 0; // Retry counter

            while (retryCount < MAX_RETRIES) {
                byte[] receiveBuffer = new byte[MAX_PACKET_SIZE];
                DatagramPacket receivePacket =
                        new DatagramPacket(receiveBuffer, receiveBuffer.length);

                try {
                    socket.receive(receivePacket); // Receive packet from the server
                    ByteBuffer received =
                            ByteBuffer.wrap(receivePacket.getData(), 0, receivePacket.getLength());

                    byte type = received.get();
                    if (type == TYPE_NONEXIST) { // File not found on server
                        System.out.println("File does not exist.");
                        return;
                    }

                    if (type != TYPE_DATA) { // Unexpected packet type
                        System.out.println("Received unexpected packet type");
                        continue;
                    }

                    int sequenceNumber = received.getInt(); // Sequence number of the packet
                    if (sequenceNumber == -1) { // End of file data
                        break;
                    }

                    int dataLength = received.getInt();

                    if (sequenceNumber == expectedSequence) {
                        byte[] fileData = new byte[dataLength];
                        received.get(fileData);
                        fos.write(fileData); // Write received data to the output file

                        // Send ACK for the received packet
                        ByteBuffer ackBuffer = ByteBuffer.allocate(5);
                        ackBuffer.put(TYPE_ACK);
                        ackBuffer.putInt(sequenceNumber);

                        DatagramPacket ackPacket = new DatagramPacket(ackBuffer.array(),
                                ackBuffer.position(), serverAddress, portNumber);
                        socket.send(ackPacket);

                        expectedSequence++;
                        retryCount = 0; // Reset retry count on successful receive
                    } else {
                        System.out.println("Received out-of-order packet: " + sequenceNumber);
                    }
                } catch (SocketTimeoutException e) {
                    retryCount++; // Increment retry count on timeout
                    System.out.println("Timeout waiting for file data. Retry " + retryCount + " of "
                            + MAX_RETRIES);
                    if (retryCount < MAX_RETRIES) {
                        // Resend ACK for the last successfully received packet
                        if (expectedSequence > 0) {
                            ByteBuffer ackBuffer = ByteBuffer.allocate(5);
                            ackBuffer.put(TYPE_ACK);
                            ackBuffer.putInt(expectedSequence - 1);

                            DatagramPacket ackPacket = new DatagramPacket(ackBuffer.array(),
                                    ackBuffer.position(), serverAddress, portNumber);
                            socket.send(ackPacket);
                        }
                    }
                }
            }

            if (retryCount >= MAX_RETRIES) {
                System.out.println(
                        "Failed to receive complete file after " + MAX_RETRIES + " retries");
            } else {
                System.out.println("File transfer complete: " + outputFileName);
            }
        }
    }

    public static void main(String[] args) {
        // Check command-line arguments for server host and client number
        if (args.length != 3) {
            System.err.println("Usage: java Client <host> <port number> <client_number>");
            System.exit(1);
        }

        String clientNumber = args[2];
        String outputFileName = clientNumber + "_test.txt"; // Output file name based on client
                                                            // number

        try (DatagramSocket clientSocket = new DatagramSocket()) {
            InetAddress serverAddress = InetAddress.getByName(args[0]); // Resolve server address
            int portNumber = Integer.parseInt(args[1]);
            clientSocket.setSoTimeout(TIMEOUT_MS); // Set socket timeout for retries
            Scanner scanner = new Scanner(System.in);

            while (true) {
                System.out.print("Enter command (index, get <filename>, or exit): ");
                String command = scanner.nextLine().trim();

                if (command.equalsIgnoreCase("exit")) { // Exit command
                    break;
                } else if (command.equalsIgnoreCase("index")) { // Request for file index
                    requestFileIndex(clientSocket, serverAddress, portNumber);
                } else if (command.toLowerCase().startsWith("get ")) { // Request for specific file
                    String filename = command.substring(4).trim();
                    requestFile(clientSocket, serverAddress, portNumber, filename, outputFileName);
                } else {
                    System.out.println("Invalid command"); // Invalid command handling
                }
            }
        } catch (IOException e) {
            System.err.println("Client error: " + e.getMessage());
        }
    }
}
