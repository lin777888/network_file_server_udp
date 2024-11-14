import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.*;

public class Server {
    // Server settings
    private static final int PORT = 12345; // Port number for server to listen on
    private static final int MAX_PACKET_SIZE = 504; // Max size of each UDP packet
    private static final int HEADER_SIZE = 9; // Header size for each packet
    private static final int CHUNK_SIZE = MAX_PACKET_SIZE - HEADER_SIZE; // Data chunk size in each
                                                                         // packet
    private static final int TIMEOUT_MS = 1000; // Timeout for socket receive operations
    private static final String DIRECTORY = "."; // Directory containing files for transfer

    // Packet types for different message types in the protocol
    private static final byte TYPE_DATA = 1;
    private static final byte TYPE_ACK = 2;
    private static final byte TYPE_REQUEST = 3;
    private static final byte TYPE_INDEX = 4;
    private static final byte TYPE_INDEX_DATA = 5;
    private static final byte TYPE_NONEXIST = 6;

    // Stores active file transfer sessions for clients
    private static final ConcurrentHashMap<String, TransferSession> activeSessions =
            new ConcurrentHashMap<>();

    private static class TransferSession {
        private final InetAddress clientAddress;
        private final int clientPort;
        private FileInputStream fileStream;
        private byte[] data;
        private int currentSequence;
        private int currentPosition;
        private final int totalLength;
        private final boolean isIndex;
        private long lastActivityTime;

        // Constructor for file transfer sessions
        public TransferSession(String filename, InetAddress clientAddress, int clientPort,
                FileInputStream fileStream) {
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.fileStream = fileStream;
            this.currentSequence = 0;
            this.lastActivityTime = System.currentTimeMillis();
            this.isIndex = false;
            this.totalLength = 0;
            this.currentPosition = 0;
        }

        // Constructor for index data transfer sessions
        public TransferSession(byte[] indexData, InetAddress clientAddress, int clientPort) {
            this.clientAddress = clientAddress;
            this.clientPort = clientPort;
            this.data = indexData;
            this.currentSequence = 0;
            this.currentPosition = 0;
            this.totalLength = indexData.length;
            this.lastActivityTime = System.currentTimeMillis();
            this.isIndex = true;
        }
    }

    // Handles a client's packet based on its type
    private static void handleClient(DatagramSocket socket, DatagramPacket packet) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(packet.getData(), 0, packet.getLength());
            byte type = buffer.get();

            String clientId = packet.getAddress().getHostAddress() + ":" + packet.getPort();

            switch (type) {
                case TYPE_REQUEST -> handleFileRequest(socket, packet, buffer, clientId);
                case TYPE_INDEX -> handleIndexRequest(socket, packet);
                case TYPE_ACK -> handleAcknowledgment(socket, clientId, buffer.getInt());
                default -> System.err.println("Unknown packet type: " + type);
            }
        } catch (Exception e) {
            System.err.println("Error handling client request: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Handles a file request from the client
    private static void handleFileRequest(DatagramSocket socket, DatagramPacket packet,
            ByteBuffer buffer, String clientId) throws IOException {
        byte[] filenameBytes = new byte[buffer.remaining()];
        buffer.get(filenameBytes);
        String filename = new String(filenameBytes).trim();
        System.out.println("Received request for file: " + filename + " from " + clientId);

        File file = new File(DIRECTORY, filename);
        if (!file.exists() || !file.isFile()) {
            System.out.println("File not found: " + filename);
            sendNonexistResponse(socket, packet.getAddress(), packet.getPort());
            return;
        }

        FileInputStream fis = new FileInputStream(file);
        TransferSession session =
                new TransferSession(filename, packet.getAddress(), packet.getPort(), fis);
        activeSessions.put(clientId, session);

        sendNextChunk(socket, session);
    }

    // Handles an index request from the client, sending a list of available files
    private static void handleIndexRequest(DatagramSocket socket, DatagramPacket packet)
            throws IOException {
        String clientId = packet.getAddress().getHostAddress() + ":" + packet.getPort();
        System.out.println("Received index request from " + clientId);

        byte[] indexData = generateFileIndex();
        if (indexData.length == 0) {
            sendErrorResponse(socket, packet.getAddress(), packet.getPort());
            return;
        }

        TransferSession session =
                new TransferSession(indexData, packet.getAddress(), packet.getPort());
        activeSessions.put(clientId, session);

        sendNextChunk(socket, session);
    }

    // Generates an index of available text files in the directory
    private static byte[] generateFileIndex() {
        File directory = new File(DIRECTORY);
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".txt"));

        if (files == null || files.length == 0) {
            return new byte[0];
        }

        StringBuilder indexBuilder = new StringBuilder();
        for (File file : files) {
            indexBuilder.append(file.getName()).append("\n");
        }

        return indexBuilder.toString().getBytes();
    }

    // Handles acknowledgments from the client to continue file transfer
    private static void handleAcknowledgment(DatagramSocket socket, String clientId,
            int sequenceNumber) {
        TransferSession session = activeSessions.get(clientId);
        if (session != null) {
            session.lastActivityTime = System.currentTimeMillis();
            if (sequenceNumber == session.currentSequence) {
                if (session.isIndex) {
                    session.currentPosition +=
                            Math.min(CHUNK_SIZE, session.totalLength - session.currentPosition);
                }
                session.currentSequence++;
                try {
                    // Test for resending requests
                    // if (sequenceNumber == 1) {
                    // try {
                    // Thread.sleep(10000);
                    // } catch (InterruptedException e) {
                    // System.err.println("Sleep interrupted: " + e.getMessage());
                    // Thread.currentThread().interrupt(); // Restore the interrupted status
                    // }
                    // }

                    sendNextChunk(socket, session);
                } catch (IOException e) {
                    System.err.println("Error sending next chunk: " + e.getMessage());
                    cleanupSession(clientId);
                }
            }
        }
    }

    // Sends the next chunk of data to the client
    private static void sendNextChunk(DatagramSocket socket, TransferSession session)
            throws IOException {
        if (session.isIndex) {
            sendNextIndexChunk(socket, session);
        } else {
            sendNextFileChunk(socket, session);
        }
    }

    // Sends the next chunk of index data to the client
    private static void sendNextIndexChunk(DatagramSocket socket, TransferSession session)
            throws IOException {
        int remaining = session.totalLength - session.currentPosition;
        if (remaining <= 0) {
            sendEOF(socket, session);
            cleanupSession(session.clientAddress.getHostAddress() + ":" + session.clientPort);
            return;
        }

        int chunkSize = Math.min(CHUNK_SIZE, remaining);
        byte[] chunk = Arrays.copyOfRange(session.data, session.currentPosition,
                session.currentPosition + chunkSize);

        ByteBuffer chunkBuffer = ByteBuffer.allocate(HEADER_SIZE + chunkSize);
        chunkBuffer.put(TYPE_INDEX_DATA);
        chunkBuffer.putInt(session.currentSequence);
        chunkBuffer.putInt(chunkSize);
        chunkBuffer.put(chunk);

        DatagramPacket sendPacket = new DatagramPacket(chunkBuffer.array(), chunkBuffer.position(),
                session.clientAddress, session.clientPort);

        socket.send(sendPacket);
        System.out.println("Sent index chunk " + session.currentSequence + " to "
                + session.clientAddress.getHostAddress() + ":" + session.clientPort);
    }

    // Sends the next chunk of file data to the client
    private static void sendNextFileChunk(DatagramSocket socket, TransferSession session)
            throws IOException {
        byte[] fileBuffer = new byte[CHUNK_SIZE];
        int bytesRead = session.fileStream.read(fileBuffer);

        if (bytesRead == -1) {
            sendEOF(socket, session);
            cleanupSession(session.clientAddress.getHostAddress() + ":" + session.clientPort);
            return;
        }

        ByteBuffer chunkBuffer = ByteBuffer.allocate(HEADER_SIZE + bytesRead);
        chunkBuffer.put(TYPE_DATA);
        chunkBuffer.putInt(session.currentSequence);
        chunkBuffer.putInt(bytesRead);
        chunkBuffer.put(fileBuffer, 0, bytesRead);

        DatagramPacket sendPacket = new DatagramPacket(chunkBuffer.array(), chunkBuffer.position(),
                session.clientAddress, session.clientPort);

        socket.send(sendPacket);
        System.out.println("Sent file chunk " + session.currentSequence + " to "
                + session.clientAddress.getHostAddress() + ":" + session.clientPort);
    }

    // Sends an end-of-file marker to the client
    private static void sendEOF(DatagramSocket socket, TransferSession session) throws IOException {
        ByteBuffer eofBuffer = ByteBuffer.allocate(9);
        eofBuffer.put(session.isIndex ? TYPE_INDEX_DATA : TYPE_DATA);
        eofBuffer.putInt(-1);
        eofBuffer.putInt(0);

        DatagramPacket eofPacket = new DatagramPacket(eofBuffer.array(), eofBuffer.position(),
                session.clientAddress, session.clientPort);
        socket.send(eofPacket);
        System.out.println("Sent EOF marker to " + session.clientAddress.getHostAddress() + ":"
                + session.clientPort);
    }

    // Sends an error response to the client if the index is empty
    private static void sendErrorResponse(DatagramSocket socket, InetAddress address, int port)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put(TYPE_DATA);
        buffer.put((byte) -1);

        DatagramPacket errorPacket =
                new DatagramPacket(buffer.array(), buffer.position(), address, port);
        socket.send(errorPacket);
        System.out.println("Sent error response to " + address.getHostAddress() + ":" + port);
    }

    // Sends a nonexist response if the requested file is not found
    private static void sendNonexistResponse(DatagramSocket socket, InetAddress address, int port)
            throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2);
        buffer.put(TYPE_NONEXIST);
        buffer.put((byte) -1);

        DatagramPacket errorPacket =
                new DatagramPacket(buffer.array(), buffer.position(), address, port);
        socket.send(errorPacket);
        System.out
                .println("Sent nonexist file response to " + address.getHostAddress() + ":" + port);
    }

    // Cleans up session resources after transfer completion
    private static void cleanupSession(String clientId) {
        TransferSession session = activeSessions.remove(clientId);
        if (session != null && !session.isIndex) {
            try {
                session.fileStream.close();
            } catch (IOException e) {
                System.err.println("Error closing file stream: " + e.getMessage());
            }
        }
    }

    public static void main(String[] args) {
        // Create a thread pool to handle client requests concurrently
        ExecutorService threadPool = Executors.newCachedThreadPool();

        try (DatagramSocket serverSocket = new DatagramSocket(PORT)) {
            System.out.println("UDP Server running on port " + PORT);
            serverSocket.setSoTimeout(TIMEOUT_MS);

            while (true) {
                byte[] receiveBuffer = new byte[MAX_PACKET_SIZE];
                DatagramPacket receivePacket =
                        new DatagramPacket(receiveBuffer, receiveBuffer.length);

                try {
                    serverSocket.receive(receivePacket);
                    threadPool.execute(() -> handleClient(serverSocket, receivePacket));
                } catch (SocketTimeoutException e) {
                    continue; // Continue listening after timeout
                }
            }
        } catch (IOException e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            threadPool.shutdown();
        }
    }
}
