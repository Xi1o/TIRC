package fr.upem.net.tcp.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;

/**
 * Class used as a client using the TIRC protocol.
 * 
 * @author Cheneau & Lee
 *
 */
public class Client {
	private static final int BUFSIZ = 4096;
	public static final int MAX_NICKLEN = 10;
	private static final int MAX_MSGSIZ = 2048;
	private final SocketChannel sc;
	private final ByteBuffer bbin;
	private final ByteBuffer bbout;
	private final Charset csNickname = Charset.forName("ASCII");
	private final Charset csMessage = Charset.forName("UTF-8");
	private final String nickname;
	private final int listenport;
	private int numberConnected;
	private final HashMap<Byte, Handeable> handler = new HashMap<>();
	private Thread reader;
	private Thread mainThread;
	private final HashSet<String> connectedNicknames = new HashSet<>();
	private boolean hasQuit;
	private final ClientGUI clientGUI = new ClientGUI(this);

	@FunctionalInterface
	private interface Handeable {
		public void handle() throws IOException;
	}

	private void initHandles() {
		handler.put((byte) 2, () -> clientHasJoined());
		handler.put((byte) 3, () -> connectedClients());
		handler.put((byte) 5, () -> receiveMessage());
		handler.put((byte) 16, () -> clientHasLeft());
	}

	private Client(SocketChannel sc, ByteBuffer bbin, ByteBuffer bbout, String nickname, int listenport)
			throws SecurityException, IOException {
		this.sc = sc;
		this.bbin = bbin;
		this.bbout = bbout;
		this.nickname = nickname;
		this.listenport = listenport;
		mainThread = Thread.currentThread();
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
	public static Client create(InetSocketAddress host, String nickname, int listenport) throws IOException {
		Objects.requireNonNull(host);
		Objects.requireNonNull(nickname);
		if (listenport < 0 || listenport > 65535) {
			throw new IllegalArgumentException("Listening port is not valid: " + listenport);
		}
		ByteBuffer bbin = ByteBuffer.allocate(BUFSIZ);
		ByteBuffer bbout = ByteBuffer.allocate(BUFSIZ);
		SocketChannel sc = SocketChannel.open();
		sc.connect(host);
		Client client = new Client(sc, bbin, bbout, nickname, listenport);
		client.initHandles();
		return client;
	}

	private boolean readFully() throws IOException {
		while (bbin.hasRemaining()) {
			if (sc.read(bbin) == -1) {
				return false;
			}
		}
		return true;
	}

	private byte readByte() throws IOException {
		bbin.clear();
		bbin.limit(Byte.BYTES);
		if (!readFully()) {
			throw new IOException("connection lost");
		}
		bbin.flip();
		return bbin.get();
	}

	private int readInt() throws IOException {
		bbin.clear();
		bbin.limit(Integer.BYTES);
		if (!readFully()) {
			throw new IOException("connection lost");
		}
		bbin.flip();
		return bbin.getInt();
	}

	private String readString(int size, Charset cs) throws IOException {
		bbin.clear();
		bbin.limit(size);
		if (!readFully()) {
			throw new IOException("connection lost");
		}
		bbin.flip();
		return cs.decode(bbin).toString();
	}

	private void error() {
		System.out.println("[ERROR] Unknown opcode from server.");
	}

	private void clientHasJoined() throws IOException {
		int size = readInt();
		String nickname = readString(size, csNickname);
		connectedNicknames.add(nickname);
		clientGUI.println(nickname + " has joined.");
	}

	private void clientHasLeft() throws IOException {
		int size = readInt();
		String nickname = readString(size, csNickname);
		connectedNicknames.remove(nickname);
		clientGUI.println(nickname + " has left.");
	}

	private void connectedClients() throws IOException {
		int nb = readInt();
		for (int i = 0; i < nb; i++) {
			int size = readInt();
			String nickname = readString(size, csNickname);
			connectedNicknames.add(nickname);
		}
	}

	private void requestConnection() {
		ByteBuffer bbNickname = csNickname.encode(nickname);
		bbout.put((byte) 0);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
		bbout.putInt(listenport);
	}

	/**
	 * Performs the connection of the client to the server.
	 * 
	 * @return true if successfully connected, false otherwise
	 * @throws IOException
	 *             if an I/O error occurs
	 */
	public boolean logMeIn() throws IOException {
		requestConnection();
		bbout.flip();
		sc.write(bbout);
		if (1 != readByte()) {
			return false;
		}
		byte code = readByte();
		if (code == 0) {
			numberConnected = readInt();
			clientGUI.println("You are connected as " + nickname + ".");
			clientGUI.println(numberConnected + " person(s) connected.");
			return true;
		} else {
			clientGUI.println("Your nickname is already taken.");
			return false;
		}
	}

	private ByteBuffer paquetMessage(String msg) {
		ByteBuffer bbmsg = csMessage.encode(msg);
		bbmsg.limit((bbmsg.limit() > MAX_MSGSIZ) ? MAX_MSGSIZ-1 : bbmsg.limit());
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + bbmsg.limit());
		bb.put((byte) 4);
		bb.putInt(bbmsg.limit());
		bb.put(bbmsg);
		return bb;
	}

	private void receiveMessage() throws IOException {
		int nicknameSize = readInt();
		String nickname = readString(nicknameSize, csNickname);
		int msgSize = readInt();
		String msg = readString(msgSize, csMessage);
		clientGUI.println("<" + nickname + ">" + " " + msg);
	}

	private ByteBuffer disconnect() {
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES);
		bb.put((byte) 15);
		return bb;
	}

	private void printConnectedClients() {
		clientGUI.println("Connected: ");
		connectedNicknames.forEach(n -> clientGUI.println("\t" + n));
	}
	
	private void requestPrivateConnection(String nickname) {
		if(!connectedNicknames.contains(nickname)) {
			clientGUI.println("Unknown nickname: "+nickname);
			return;
		}
		ByteBuffer bb = packetRequestPrivateConnection(nickname);
		//TODO finir méthode + implémenter coté serveur
	}
	
	private ByteBuffer packetRequestPrivateConnection(String nickname) {
		ByteBuffer bbNickname = csNickname.encode(nickname);
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + bbNickname.remaining());
		bb.put((byte) 6);
		bb.putInt(bbNickname.remaining());
		bb.put(bbNickname);
		return bb;
	}

	public void launch() {
		reader = new Thread(() -> {
			try {
				while (true) {
					handler.getOrDefault(readByte(), () -> error()).handle();
				}
			} catch (IOException ioe) {
				mainThread.interrupt();
				if (!hasQuit) {
					System.err.println("Connection with server lost.");
				}
				return;
			}
		});
		reader.start();
	}

	public void close() throws IOException {
		sc.close();
		reader.interrupt();
	}

	public static void usage() {
		System.out.println("Client host port nickname listenport");
	}

	/**
	 * Interprets commands and regular messages.
	 * 
	 * @param line
	 *            The line to be processed.
	 * @throws IOException
	 *             If a I/O error occurs while interpreting the /quit command.
	 */
	public void processInput(String line) throws IOException {
		/*
		 * any command which doesn't send packet after the case should return
		 * and not break
		 */
		ByteBuffer bb = null;
		switch (line) {
		case "/quit":
			bb = disconnect();
			hasQuit = true;
			break;
		case "/connected":
			printConnectedClients();
			return; // leave method, don't send packet
		default:
			if(line.startsWith("/w")) {
				String nickname = line.substring(line.indexOf(" "));
				requestPrivateConnection(nickname);
				break;
			}
			bb = paquetMessage(line);
			break;
		}
		// sending packet
		Objects.requireNonNull(bb).flip(); // in case forgot return when needed
		try {
			sc.write(bb);
		} catch (IOException e) {
			System.err.println("Connection lost."); // TODO next?
		}
		if (hasQuit) {
			clientGUI.exit();
		}
	}
}
