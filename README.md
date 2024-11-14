
# UDP Server and Client - Instructions and Key Features

## Key Features

1. **Packet Types and Commands**:
   - `TYPE_REQUEST`: Requests a specific file from the server.
   - `TYPE_INDEX`: Requests a directory listing of available `.txt` files.
   - `TYPE_DATA`: Represents chunks of file data sent from the server.
   - `TYPE_ACK`: Acknowledges receipt of a data chunk.
   - `TYPE_NONEXIST`: Indicates that a requested file does not exist on the server.

2. **Transfer Sessions**:
   - The server uses a `TransferSession` class to manage file transfer for each client, 
     with attributes such as `clientAddress`, `clientPort`, `fileStream`, and `currentSequence`.
   - Manages sessions via a `ConcurrentHashMap` to handle multiple client sessions concurrently.

3. **Data Transfer**:
   - Splits files into chunks with headers for tracking (sequence numbers, chunk sizes).
   - Each chunk is transferred via UDP, with retransmissions handled through acknowledgments.

4. **Index Generation**:
   - The server lists available `.txt` files in the directory and sends the list to clients upon request.

5. **EOF and Error Responses**:
   - Sends an EOF packet when file transmission completes.
   - Sends an error response if a requested file is not found or the directory index is empty.

6. **Timeout Handling**:
   - Both client and server set timeouts on their `DatagramSocket` operations to handle retries and temporary disconnections.

7. **Retry Mechanism**:
   - The client has a maximum retry count (`MAX_RETRIES`) to manage packet loss. If packets are lost or time out, 
     the client resends acknowledgments for the last received packet.

---

## Instructions to Compile and Run the UDP Server

### Step 1: Navigate to the `src` Folder

```bash
cd src
```

### Step 2: Compile the `Server` Class

```bash
javac Server.java
```

### Step 3: Run the UDP Server

```bash
java Server
```

- The server will run on the default port `12345` and listen for incoming UDP requests.
- Ensure the directory specified (default is current directory) contains the `.txt` files for the server to serve.

---

## Instructions to Compile and Run the UDP Client

### Step 1: Compile the `Client` Class

```bash
javac Client.java
```

### Step 2: Run the UDP Client

```bash
java Client <server_host> <server_port> <client_number>
```

Replace `<server_host>`, `<server_port>`, and `<client_number>` with appropriate values, for example:

```bash
java Client localhost 12345 1
```

### Client Commands

1. **index**: Requests a list of `.txt` files available on the server.
2. **get <filename>**: Requests a specific file from the server and saves it locally as `<client_number>_test.txt`.
3. **exit**: Terminates the client connection.

---

By following these instructions, you can compile and run the server and client applications, enabling file management over a UDP connection with reliability features for efficient communication.
