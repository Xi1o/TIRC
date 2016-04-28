package fr.upem.net.tcp.client;

import static fr.upem.net.tcp.client.ScReaders.readAddress;
import static fr.upem.net.tcp.client.ScReaders.readByte;
import static fr.upem.net.tcp.client.ScReaders.readInt;
import static fr.upem.net.tcp.client.ScReaders.readLong;
import static fr.upem.net.tcp.client.ScReaders.readString;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Random;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Class used as a client using the TIRC protocol.
 * 
 * @author Cheneau & Lee
 *
 */
public class Client {
	private static final Logger LOGGER = Logger.getLogger("ClientLogger");
	private FileHandler fh;
	public static final int BUFSIZ = 4096;
	public static final int MAX_NICKLEN = 15;
	private static final int MAX_MSGSIZ = 2048;
	public static final Charset CS_NICKNAME = Charset.forName("ASCII");
	public static final Charset CS_MESSAGE = Charset.forName("UTF-8");
	/** Time before sending a keep alive packet */
	private static final int KEEP_ALIVE_DELAY = 2000;
	private final SocketChannel sc;
	private final ByteBuffer bbin;
	private final ByteBuffer bbout;
	private final String nickname;
	private final int listenport;
	private int numberConnected;
	private Thread serverThread;
	private Thread keepAliveThread;
	/** Handler call right function depending on opcode. */
	private final HashMap<Byte, Handeable> handler = new HashMap<>();
	/** Thread associated to the nickname in private connection */
	private final HashMap<String, Thread> privateConnectionThreads = new HashMap<>();
	/** Set of nicknames of connected clients. */
	private final HashSet<String> connectedNicknames = new HashSet<>();
	/** Nicknames in private connections with client's server */
	private final HashMap<String, SocketChannel> privateConnections = new HashMap<>();
	/** User has close client */
	private boolean hasQuit;
	private final ClientGUI clientGUI = new ClientGUI(this);
	/** Used to generate a token for private communication */
	private final Random randomId = new Random();
	/** Server where client listen for private connection */
	private final ClientServer clientServer;

	@FunctionalInterface
	private interface Handeable {
		public void handle() throws IOException;
	}

	/* Core */

	private Client(SocketChannel sc, ByteBuffer bbin, ByteBuffer bbout, String nickname,
			ClientServer clientServer, int listenport) throws SecurityException, IOException {
		this.sc = sc;
		this.bbin = bbin;
		this.bbout = bbout;
		this.nickname = nickname;
		this.clientServer = clientServer;
		this.clientServer.setUI(clientGUI);
		this.listenport = listenport;
	}

	/**
	 * Creates a client.
	 * 
	 * @param host
	 *            the address of the host
	 * @param nickname
	 *            the nickname of the client user
	 * @param listenport
	 *            the port where the client is listening for private
	 *            communication
	 * @return a new client.
	 * @throws IOException
	 */
	public static Client create(InetSocketAddress host, String nickname, int listenport)
			throws IOException {
		Objects.requireNonNull(host);
		Objects.requireNonNull(nickname);
		if (listenport < 0 || listenport > 65535) {
			throw new IllegalArgumentException("Listening port is not valid: " + listenport);
		}
		ByteBuffer bbin = ByteBuffer.allocate(BUFSIZ);
		ByteBuffer bbout = ByteBuffer.allocate(BUFSIZ);
		SocketChannel sc = SocketChannel.open();
		sc.connect(host);
		ClientServer clientServer = ClientServer.create(listenport);
		Client client = new Client(sc, bbin, bbout, nickname, clientServer, listenport);
		client.initHandles();
		return client;
	}

	/**
	 * Initialization of map, associate each opcode with the function that will
	 * handle it.
	 */
	private void initHandles() {
		handler.put((byte) 2, () -> clientHasJoined());
		handler.put((byte) 3, () -> connectedClients());
		handler.put((byte) 5, () -> receivedMessage());
		handler.put((byte) 7, () -> confirmPrivateConnection());
		handler.put((byte) 9, () -> answerPrivateConnection());
		handler.put((byte) 17, () -> clientHasLeft());
	}

	/**
	 * Close connection with server and interrupt threads.
	 * 
	 * @throws IOException
	 */
	private void close() throws IOException {
		// clientGUI.exit();
		keepAliveThread.interrupt();
		clientServer.shutdownNow();
		serverThread.interrupt();
		sc.close();
		for (String key : privateConnectionThreads.keySet()) {
			privateConnectionThreads.get(key).interrupt();
		}
	}

	/**
	 * Print usage.
	 */
	public static void usage() {
		System.out.println("Client host port nickname listenport");
	}

	/**
	 * Launch client: handle received packets / read thread / start server
	 * thread
	 * 
	 * @throws IOException
	 *             if some I/O error occurs with logs
	 */
	public void launch() throws IOException {
		fh = new FileHandler("./Clientlogs", true);
		LOGGER.addHandler(fh);
		LOGGER.setLevel(Level.ALL);
		SimpleFormatter formatter = new SimpleFormatter();
		fh.setFormatter(formatter);

		serverThread = new Thread(() -> {
			try {
				clientServer.launch();
			} catch (IOException ioe) {
				LOGGER.log(Level.SEVERE, ioe.toString(), ioe);
			}
		});
		serverThread.start();

		keepAliveThread = new Thread(() -> {
			ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES);
			bb.put((byte) 18);
			while (!Thread.interrupted()) {
				try {
					bb.flip();
					sc.write(bb);
				} catch (IOException ioe) {
					LOGGER.log(Level.SEVERE, ioe.toString(), ioe);
					return;
				}
				try {
					Thread.sleep(KEEP_ALIVE_DELAY);
				} catch (InterruptedException ie) {
					if (!hasQuit) {
						LOGGER.log(Level.SEVERE, ie.toString(), ie);
					}
					return;
				}
			}
		});
		keepAliveThread.start();

		try {
			while (true) {
				Byte opcode = readByte(sc, bbin);
				handler.getOrDefault(opcode, () -> error()).handle();
			}
		} catch (IOException ioe) {
			if (!hasQuit) {
				LOGGER.log(Level.SEVERE, ioe.toString(), ioe);
			}
		}
	}

	/* User's input */

	/**
	 * Interprets commands and regular messages.
	 * 
	 * @param input
	 *            The input line to be processed.
	 * @throws IOException
	 *             If a I/O error occurs while interpreting the /quit command.
	 */
	public void processInput(String input) throws IOException {
		bbout.clear();
		String[] argsInput = parseInput(input);
		switch (argsInput[0]) { // switch for commands
		case "/quit":
			packetDisconnect();
			hasQuit = true;
			break;
		case "/connected":
			printConnectedClients();
			break;
		case "/private":
			if (!hasAtLeastArgs(argsInput, 2) || !isConnectedClient(argsInput[1])) {
				break;
			}
			String toNickname = argsInput[1];
			if (toNickname.equals(nickname)) {
				clientGUI.println("Cannot request a private communication with yourself.");
				break;
			}
			if (isPrivateConnected(toNickname)) {
				clientGUI.println("You're already connected with " + toNickname + ".");
				break;
			}
			packetClientInfoRequest(toNickname);
			// Remember that you requested a private connection
			privateConnections.put(toNickname, null);
			break;
		case "/w":
			if (!hasAtLeastArgs(argsInput, 3)) {
				break;
			}
			toNickname = argsInput[1];
			if (toNickname.equals(nickname)) {
				clientGUI.println("Cannot send a private message to yourself.");
				break;
			}
			String msg = String.join(" ", Arrays.copyOfRange(argsInput, 2, argsInput.length));
			if (!sendPrivateMessage(toNickname, msg)) {
				clientGUI.println(
						"You must request a private connection before: /private " + toNickname);
				bbout.clear();
				break;
			}
			clientGUI.println("*" + nickname + "* " + msg);
			bbout.clear();
			break;
		case "/q":
			if (!hasAtLeastArgs(argsInput, 2) || !isConnectedClient(argsInput[1])) {
				break;
			}
			sendPrivateDisconnection(argsInput[1]);
			bbout.clear();
			break;
		default:
			String command = argsInput[0];
			if (command.startsWith("/")) {
				clientGUI.println("Unknown command: " + argsInput[0]);
				break;
			}
			packetMessage(command);
			break;
		}
		// sending packet
		if (bbout.position() == 0) {
			return; // if output buffer is empty return;
		}
		bbout.flip();
		try {
			sc.write(bbout);
		} catch (IOException ioe) {
			LOGGER.log(Level.SEVERE, ioe.toString(), ioe);
		}
		if (hasQuit) {
			LOGGER.info("Has quit");
			clientGUI.exit();
			close();
		}
	}

	private boolean hasAtLeastArgs(String[] args, int expectedArgsLength) {
		if (args.length < expectedArgsLength || args[1].equals("")) {
			usageCommand(args[0]);
			return false;
		}
		return true;
	}

	private void usageCommand(String command) {
		clientGUI.println("Insufficient arguments for command " + command);
	}

	private String[] parseInput(String input) {
		if (input.startsWith("/")) { // if it's a command
			// args[0] contains "/command" and the rest = arguments
			return input.split(" ");
		}
		// if it's not a command, args[0] will contain the whole input
		String[] args = { input };
		return args;
	}

	private void printConnectedClients() {
		clientGUI.println("Connected: ");
		connectedNicknames.forEach(n -> clientGUI.println("\t" + n));
	}

	/* Request to server */

	/**
	 * Performs the connection of the client to the server.
	 * 
	 * @return {@code true} if successfully connected, {@code false} otherwise
	 * @throws IOException
	 *             If some other I/O error occurs.
	 */
	public boolean logMeIn() throws IOException {
		packetRequestConnection();
		bbout.flip();
		sc.write(bbout);
		if (1 != readByte(sc, bbin)) {
			return false;
		}
		byte code = readByte(sc, bbin);
		if (code == 0) {
			numberConnected = readInt(sc, bbin);
			clientGUI.println("You are connected as " + nickname + ".");
			clientGUI.println(numberConnected + " person(s) connected.");
			return true;
		} else {
			clientGUI.println("Your nickname is already taken.");
			return false;
		}
	}

	/**
	 * Accept a private connection request.
	 * 
	 * @param nickname
	 *            The nickname of accepted client.
	 * @throws IOException
	 *             If some other I/O error occurs.
	 */
	private void acceptPrivateConnection(String nickname) throws IOException {
		long id;
		do {
			id = randomId.nextLong();
		} while (id == (long) 0);
		if (!clientServer.registerClient(nickname, id)) {
			clientGUI.println("You are already connected with " + nickname + ".");
			return;
		}
		packetAcceptPrivateCommunication(nickname, id);
		bbout.flip();
		sc.write(bbout);
	}

	/**
	 * Refuse a private connection request.
	 * 
	 * @param nickname
	 *            The nickname of refused client.
	 * @throws IOException
	 *             If some other I/O error occurs.
	 */
	private void refusePrivateConnection(String nickname) throws IOException {
		packetRefusePrivateCommunication(nickname);
		bbout.flip();
		sc.write(bbout);
	}

	/* Request to another client */

	private boolean writePrivate(String toNickname) throws IOException {
		SocketChannel clientSc = privateConnections.get(toNickname);
		// If could not find socket channel try with server
		if (null == clientSc) {
			return clientServer.sendMessage(toNickname, bbout);
		}
		bbout.flip();
		clientSc.write(bbout);
		return true;
	}

	private void clientGiveId(SocketChannel sc, long id) throws IOException {
		packetClientGiveId(id);
		bbout.flip();
		sc.write(bbout);
	}

	private boolean sendPrivateMessage(String toNickname, String msg) throws IOException {
		packetSendPrivateMessage(msg);
		return writePrivate(toNickname);
	}

	private boolean sendPrivateDisconnection(String toNickname) throws IOException {
		packetSendPrivateDisconnection();
		if (!writePrivate(toNickname)) {
			clientGUI.println("No private connection with " + toNickname + ".");
			return false;
		}
		privateDisconnect(toNickname);
		return true;
	}

	/* Packet builder */

	/* Client to server packet */

	/**
	 * Packet request connection with server
	 */
	private void packetRequestConnection() {
		bbout.clear();
		ByteBuffer bbNickname = CS_NICKNAME.encode(nickname);
		bbout.put((byte) 0);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
		bbout.putInt(listenport);
	}

	/**
	 * Packet public message
	 * 
	 * @param msg
	 *            Message to send to server.
	 */
	private void packetMessage(String msg) {
		bbout.clear();
		ByteBuffer bbmsg = CS_MESSAGE.encode(msg);
		bbmsg.limit((bbmsg.limit() > MAX_MSGSIZ) ? MAX_MSGSIZ - 1 : bbmsg.limit());
		bbout.put((byte) 4);
		bbout.putInt(bbmsg.limit());
		bbout.put(bbmsg);
	}

	/**
	 * Packet disconnect with server
	 */
	private void packetDisconnect() {
		bbout.clear();
		bbout.put((byte) 16);
	}

	/**
	 * Packet request a client ip address and listening port.
	 * 
	 * @param nickname
	 *            The nickname of wanted client's information.
	 */
	private void packetClientInfoRequest(String nickname) {
		bbout.clear();
		ByteBuffer bbNickname = CS_NICKNAME.encode(nickname);
		bbout.put((byte) 6);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
	}

	/**
	 * Packet accept a private communication
	 * 
	 * @param nickname
	 *            The nickname of accepted client.
	 * @param id
	 *            The id that client will need to send to prove his identity.
	 */
	private void packetAcceptPrivateCommunication(String nickname, Long id) {
		bbout.clear();
		ByteBuffer bbNickname = CS_NICKNAME.encode(nickname);
		bbout.put((byte) 8);
		bbout.put((byte) 0);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
		bbout.putLong(id);
	}

	/**
	 * Packet refuse a private communication.
	 * 
	 * @param nickname
	 *            The nickname of refuse client.
	 */
	private void packetRefusePrivateCommunication(String nickname) {
		bbout.clear();
		ByteBuffer bbNickname = CS_NICKNAME.encode(nickname);
		bbout.put((byte) 8);
		bbout.put((byte) 1);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
	}

	/* Client to client packet */

	/**
	 * Packet send given id to the client's server to prove identity.
	 * 
	 * @param id
	 *            The id given.
	 */
	private void packetClientGiveId(long id) {
		bbout.clear();
		ByteBuffer bbNickname = CS_NICKNAME.encode(nickname);
		bbout.put((byte) 10);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
		bbout.putLong(id);
	}

	private void packetSendPrivateMessage(String msg) {
		bbout.clear();
		ByteBuffer bbmsg = CS_MESSAGE.encode(msg);
		int msgSize = bbmsg.remaining();
		bbout.put((byte) 11);
		bbout.putInt(msgSize);
		bbout.put(bbmsg);
	}

	private void packetSendPrivateDisconnection() {
		bbout.clear();
		bbout.put((byte) 12);
	}

	/* Commands */

	// Opcode unknown
	private void error() {
		LOGGER.severe("Unknown opcode from server.");
	}

	// Opcode 2
	private void clientHasJoined() throws IOException {
		int size = readInt(sc, bbin);
		String nickname = readString(sc, bbin, size, CS_NICKNAME);
		connectedNicknames.add(nickname);
		clientGUI.println(nickname + " has joined.");
	}

	// Opcode 3
	private void connectedClients() throws IOException {
		int nb = readInt(sc, bbin);
		for (int i = 0; i < nb; i++) {
			int size = readInt(sc, bbin);
			String nickname = readString(sc, bbin, size, CS_NICKNAME);
			connectedNicknames.add(nickname);
		}
	}

	// Opcode 5
	private void receivedMessage() throws IOException {
		int nicknameSize = readInt(sc, bbin);
		String nickname = readString(sc, bbin, nicknameSize, CS_NICKNAME);
		int msgSize = readInt(sc, bbin);
		String msg = readString(sc, bbin, msgSize, CS_MESSAGE);
		clientGUI.println("<" + nickname + ">" + " " + msg);
	}

	// Opcode 7
	private void confirmPrivateConnection() throws IOException {
		int nicknameSize = readInt(sc, bbin);
		String nickname = readString(sc, bbin, nicknameSize, CS_NICKNAME);
		clientGUI.println(
				nickname + " has requested a private communication with you.\n" + "Accept ? (y/n)");
		String input = "y"; // TODO get input from clientGui
		if (input.equals("y")) {
			acceptPrivateConnection(nickname);
		} else {
			refusePrivateConnection(nickname);
		}
	}

	// Opcode 9
	private void answerPrivateConnection() throws IOException {
		byte accept = readByte(sc, bbin);
		int nicknameSize = readInt(sc, bbin);
		String nickname = readString(sc, bbin, nicknameSize, CS_NICKNAME);
		if (accept == (byte) 1) {
			clientGUI.println(nickname + " has refused private communication.");
			return;
		}
		byte ipv = readByte(sc, bbin);
		byte[] addr;
		if (ipv == (byte) 4) {
			addr = readAddress(sc, bbin, true);
		} else if (ipv == (byte) 6) {
			addr = readAddress(sc, bbin, false);
		} else {
			throw new IllegalStateException("wrong ip version " + ipv);
		}
		InetAddress inet = InetAddress.getByAddress(addr);
		int port = readInt(sc, bbin);
		long id = readLong(sc, bbin);
		privateConnect(nickname, inet, port, id);
	}

	// Opcode 16
	private void clientHasLeft() throws IOException {
		int size = readInt(sc, bbin);
		String nickname = readString(sc, bbin, size, CS_NICKNAME);
		connectedNicknames.remove(nickname);
		clientGUI.println(nickname + " has left.");
	}

	/* Other */

	private boolean isConnectedClient(String nickname) {
		if (!connectedNicknames.contains(nickname)) {
			clientGUI.println("Unknown nickname: " + nickname);
			return false;
		}
		return true;
	}

	private void privateConnect(String clientNickname, InetAddress iaServer, int port, long id) {
		if (!privateConnections.containsKey(clientNickname)) {
			LOGGER.warning(iaServer + " confirmed a private connection that was not requested");
			return;
		}
		InetSocketAddress server = new InetSocketAddress(iaServer, port);
		try {
			SocketChannel clientSc = SocketChannel.open(server);
			LOGGER.info("Connected with " + clientNickname + " at " + iaServer + ":" + port);
			clientGiveId(clientSc, id);
			addSocketChannelReader(clientSc, clientNickname);
			privateConnections.put(clientNickname, clientSc);
			clientGUI.println("Private connection established with " + clientNickname + ".");
			clientGUI.println("To communicate with him privately use: /w " + clientNickname);
		} catch (IOException ioe) {
			LOGGER.log(Level.WARNING, "Could not connect to " + clientNickname + ": " + ioe, ioe);
			return;
		}
	}

	private boolean isPrivateConnected(String clientNickname) {
		return (privateConnections.containsKey(clientNickname)
				|| clientServer.isConnected(clientNickname));
	}

	private void privateDisconnect(String clientNickname) throws IOException {
		SocketChannel clientSc = privateConnections.get(clientNickname);
		if (null != clientSc) {
			privateConnections.remove(clientNickname);
			clientSc.close();
			return;
		}
		clientServer.closePrivateConnection(clientNickname);
	}

	private void addSocketChannelReader(SocketChannel sc, String clientNickname) {
		Runnable r = new ThreadPrivateConnection(sc, clientNickname, clientGUI);
		Thread t = new Thread(r);
		t.start();
		privateConnectionThreads.put(clientNickname, t);
		LOGGER.info("New private connection thread running with " + clientNickname);
	}

	/**
	 * Return {@code String} representation of a {@link SocketChannel}.
	 * 
	 * @param socketChannel
	 *            to convert in {@code String}
	 * @return {@code String} of the {@link SocketChannel}
	 */
	public static String remoteAddressToString(SocketChannel socketChannel) {
		try {
			return socketChannel.getRemoteAddress().toString();
		} catch (IOException ioe) {
			return "???";
		}
	}
}