package fr.upem.net.tcp.client;

import static fr.upem.net.tcp.client.ScReaders.readAddress;
import static fr.upem.net.tcp.client.ScReaders.readByte;
import static fr.upem.net.tcp.client.ScReaders.readInt;
import static fr.upem.net.tcp.client.ScReaders.readLong;
import static fr.upem.net.tcp.client.ScReaders.readString;

import java.awt.Color;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
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
				clientGUI.println("Connection lost with server", Color.red);
				LOGGER.log(Level.SEVERE, ioe.toString(), ioe);
			}
			return;
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
				clientGUI.println("Cannot request a private communication with yourself.",
						Color.red);
				break;
			}
			if (isPrivateConnected(toNickname)) {
				clientGUI.println("You're already connected with " + toNickname + ".", Color.red);
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
				clientGUI.println("Cannot send a private message to yourself.", Color.red);
				break;
			}
			String msg = argsInput[2];
			if (!sendPrivateMessage(toNickname, msg)) {
				clientGUI.println(
						"You must request a private connection before: /private " + toNickname,
						Color.red);
				bbout.clear();
				break;
			}
			clientGUI.println("*" + nickname + "* " + msg, Color.orange);
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
				clientGUI.println("Unknown command: " + argsInput[0], Color.red);
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

	/**
	 * Print usage of a command.
	 * 
	 * @param command
	 *            to print usage
	 */
	private void usageCommand(String command) {
		clientGUI.println("Insufficient arguments for command " + command, Color.red);
	}

	/**
	 * Parse input depending on command.
	 * 
	 * @param input
	 *            to parse
	 * @return array of {@code String} parsed
	 */
	private String[] parseInput(String input) {
		// if it's a command
		if (input.startsWith("/")) {
			// if it's a whisper : /w | nickname | msg
			if (input.startsWith("/w")) {
				return input.split(" ", 3);
			}
			// args[0] contains "/command" and the rest = arguments
			return input.split(" ");
		}
		// if it's not a command, args[0] will contain the whole input
		String[] args = { input };
		return args;
	}

	/**
	 * Print all connected clients
	 */
	private void printConnectedClients() {
		clientGUI.println("Connected: ", Color.blue);
		connectedNicknames.forEach(n -> clientGUI.println("\t" + n, Color.blue));
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
			clientGUI.println("You are connected as " + nickname + ".", Color.blue);
			clientGUI.println(numberConnected + " person(s) connected.", Color.blue);
			return true;
		} else {
			clientGUI.println("Your nickname is already taken.", Color.red);
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
			clientGUI.println("You are already connected with " + nickname + ".", Color.red);
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

	/**
	 * Write the content of {@code ByteBuffer} bbout in private communication.
	 * 
	 * @param toNickname
	 *            nickname of user to write to
	 * @return {@code true} if content sent, {@code false} if could not find the
	 *         user
	 * @throws IOException
	 *             if some I/O error occurs with user
	 */
	private boolean writePrivateRequest(String toNickname) throws IOException {
		SocketChannel clientSc = privateConnections.get(toNickname);
		// If could not find socket channel try with server
		if (null == clientSc) {
			return clientServer.sendMessage(toNickname, bbout);
		}
		bbout.flip();
		clientSc.write(bbout);
		return true;
	}

	/**
	 * Transmit id token to client to authenticate.
	 * 
	 * @param scClient
	 *            {@code SocketChannel} of user to authenticate to
	 * @param id
	 *            given by the user
	 * @throws IOException
	 *             if some I/O error occurs with user
	 */
	private void clientGiveId(SocketChannel scClient, long id) throws IOException {
		packetClientGiveId(id);
		bbout.flip();
		scClient.write(bbout);
	}

	/**
	 * Send a private message
	 * 
	 * @param toNickname
	 *            nickname of user to communicate with
	 * @param msg
	 *            message to send
	 * @return {@code true} if could send message, {@code false} is user was not
	 *         found
	 * @throws IOException
	 *             if some I/O error occurs with user
	 */
	private boolean sendPrivateMessage(String toNickname, String msg) throws IOException {
		packetSendPrivateMessage(msg);
		return writePrivateRequest(toNickname);
	}

	/**
	 * Send a private disconnection message
	 * 
	 * @param toNickname
	 *            nickname of user to disconnect with
	 * @return {@code true} if could send message, {@code false} is user was not
	 *         found
	 * @throws IOException
	 *             if some I/O error occurs with user
	 */
	private boolean sendPrivateDisconnection(String toNickname) throws IOException {
		packetSendPrivateDisconnection();
		if (!writePrivateRequest(toNickname)) {
			clientGUI.println("No private connection with " + toNickname + ".", Color.red);
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

	/**
	 * Packet send a private message.
	 * 
	 * @param msg
	 *            message to send
	 */
	private void packetSendPrivateMessage(String msg) {
		bbout.clear();
		ByteBuffer bbmsg = CS_MESSAGE.encode(msg);
		int msgSize = bbmsg.remaining();
		bbout.put((byte) 11);
		bbout.putInt(msgSize);
		bbout.put(bbmsg);
	}

	/**
	 * Packet disconnect private connection.
	 */
	private void packetSendPrivateDisconnection() {
		bbout.clear();
		bbout.put((byte) 12);
	}

	/* Commands */

	/**
	 * If an unknown opcode was received.
	 */
	private void error() {
		LOGGER.severe("Unknown opcode from server.");
	}

	/**
	 * If opcode 2, a client has joined.
	 * 
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void clientHasJoined() throws IOException {
		int size = readInt(sc, bbin);
		String nickname = readString(sc, bbin, size, CS_NICKNAME);
		connectedNicknames.add(nickname);
		clientGUI.println(nickname + " has joined.", Color.blue);
	}

	/**
	 * If opcode 3, list of connected clients.
	 * 
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void connectedClients() throws IOException {
		int nb = readInt(sc, bbin);
		for (int i = 0; i < nb; i++) {
			int size = readInt(sc, bbin);
			String nickname = readString(sc, bbin, size, CS_NICKNAME);
			connectedNicknames.add(nickname);
		}
	}

	/**
	 * If opcode 5, received a public message
	 * 
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void receivedMessage() throws IOException {
		int nicknameSize = readInt(sc, bbin);
		String nickname = readString(sc, bbin, nicknameSize, CS_NICKNAME);
		int msgSize = readInt(sc, bbin);
		String msg = readString(sc, bbin, msgSize, CS_MESSAGE);
		clientGUI.println("<" + nickname + ">" + " " + msg, Color.black);
	}

	/**
	 * <p>
	 * If opcode 7 a private communication request was made. Wait for user
	 * input.
	 * </p>
	 * 
	 * <p>
	 * If accept send accept private connection packet.
	 * </p>
	 * 
	 * <p>
	 * If refuse send refuse private connection packet.
	 * </p>
	 * 
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void confirmPrivateConnection() throws IOException {
		int nicknameSize = readInt(sc, bbin);
		String nickname = readString(sc, bbin, nicknameSize, CS_NICKNAME);
		clientGUI.println(
				nickname + " has requested a private communication with you.\n" + "Accept ? (y/n)",
				Color.magenta);
		String input = "y"; // TODO get input from clientGui
		if (input.equals("y")) {
			acceptPrivateConnection(nickname);
		} else {
			refusePrivateConnection(nickname);
		}
	}

	/**
	 * <p>
	 * If opcode 9, received an answer for a private connection. <br>
	 * If answer was yes attempt to connect with given informations.
	 * </p>
	 * 
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void answerPrivateConnection() throws IOException {
		byte accept = readByte(sc, bbin);
		int nicknameSize = readInt(sc, bbin);
		String nickname = readString(sc, bbin, nicknameSize, CS_NICKNAME);
		if (accept == (byte) 1) {
			clientGUI.println(nickname + " has refused private communication.", Color.red);
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

	/**
	 * If opcode 16, received a client has left notification.
	 * 
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void clientHasLeft() throws IOException {
		int size = readInt(sc, bbin);
		String nickname = readString(sc, bbin, size, CS_NICKNAME);
		connectedNicknames.remove(nickname);
		clientGUI.println(nickname + " has left.", Color.blue);
	}

	/* Other */

	/**
	 * Check if an user is connected on chat server.
	 * 
	 * @param nickname
	 *            of the user to check
	 * @return {@code true} if user is connected, {@code false} otherwise
	 */
	private boolean isConnectedClient(String nickname) {
		if (!connectedNicknames.contains(nickname)) {
			clientGUI.println("Unknown nickname: " + nickname, Color.red);
			return false;
		}
		return true;
	}

	/**
	 * Establish a private connection.
	 * 
	 * @param clientNickname
	 *            nickname of client trying to establish a private connection to
	 * @param iaServer
	 *            address of server
	 * @param port
	 *            where server listen
	 * @param id
	 *            to send to authenticate
	 */
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
			clientGUI.println("Private connection established with " + clientNickname + ".",
					Color.blue);
			clientGUI.println("To communicate with him privately use: /w " + clientNickname,
					Color.blue);
		} catch (IOException ioe) {
			LOGGER.log(Level.WARNING, "Could not connect to " + clientNickname + ": " + ioe, ioe);
			return;
		}
	}

	/**
	 * Check if a private communication is established with user.
	 * 
	 * @param clientNickname
	 *            nickname of user
	 * @return {@code true} if is in private communication, {@code false}
	 *         otherwise
	 */
	private boolean isPrivateConnected(String clientNickname) {
		return (privateConnections.containsKey(clientNickname)
				|| clientServer.isConnected(clientNickname));
	}

	/**
	 * Disconnect from a private communication
	 * 
	 * @param clientNickname
	 *            nickname of user to disconnect with
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void privateDisconnect(String clientNickname) throws IOException {
		SocketChannel clientSc = privateConnections.get(clientNickname);
		if (null != clientSc) {
			Thread t = privateConnectionThreads.get(clientNickname);
			if (null == t) {
				LOGGER.severe("Missing private connection thread");
				return;
			}
			t.interrupt();
			privateConnections.remove(clientNickname);
			clientSc.close();
			return;
		}
		clientServer.closePrivateConnection(clientNickname);
	}

	/**
	 * Launch a new thread that will monitor private received message and print
	 * them.
	 * 
	 * @param sc
	 *            {@code SocketChannel} to monitor
	 * @param clientNickname
	 *            nickname of user to monitor
	 */
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