package fr.upem.net.tcp.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Scanner;
import java.util.UUID;

/**
 * Class used as a client using the TIRC protocol.
 * 
 * @author Cheneau & Lee
 *
 */
public class Client {
	private static final int BUFSIZ = 1024;
	private static final int MAX_NICKLEN = 10;
	private static final int MAX_MSGSIZ = 2048;
	String uniqueID = UUID.randomUUID().toString();
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

	@FunctionalInterface
	private interface Handeable {
		public void handle() throws IOException;
	}

	private void initHandles() {
		handler.put((byte) 2, () -> clientHasJoined());
		handler.put((byte) 3, () -> connectedClients());
		handler.put((byte) 5, () -> receiveMessage());
		handler.put((byte) 16, () -> clientHasLeaved());
	}

	private Client(SocketChannel sc, ByteBuffer bbin, ByteBuffer bbout, String nickname,
			int listenport) throws SecurityException, IOException {
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
		System.out.println(nickname + " has joined.");
	}

	private void clientHasLeaved() throws IOException {
		int size = readInt();
		String nickname = readString(size, csNickname);
		connectedNicknames.remove(nickname);
		System.out.println(nickname + " has leaved.");
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
			System.out.println("You are connected as " + nickname + ".");
			System.out.println(numberConnected + " person(s) connected.");
			return true;
		} else {
			System.out.println("Your nickname is already taken.");
			return false;
		}
	}

	private ByteBuffer paquetMessage(String msg) {
		ByteBuffer bbmsg = csMessage.encode(msg);
		bbmsg.limit((bbmsg.limit() > MAX_MSGSIZ) ? MAX_MSGSIZ : bbmsg.limit());
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
		System.out.println(nickname + ": " + msg);
	}

	private ByteBuffer disconnect() {
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES);
		bb.put((byte) 15);
		return bb;
	}

	private void printConnectedClients() {
		System.out.println("Connected: ");
		connectedNicknames.forEach(n -> System.out.println("\t" + n));
	}

	public void getInput() {
		Scanner scanner = new Scanner(System.in);
		// TODO hasNextLine cannot be interrupted
		while (!hasQuit && scanner.hasNextLine()) {
			String line = scanner.nextLine();
			ByteBuffer bb;
			switch (line) {
			case "/quit":
				bb = disconnect();
				hasQuit = true;
				break;
			case "/connected":
				printConnectedClients();
				continue;
			default:
				bb = paquetMessage(line);
				break;
			}
			bb.flip();
			try {
				sc.write(bb);
			} catch (IOException e) {
				System.err.println("Connection lost.");
				break;
			}
		}
		scanner.close();
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
					System.err.println("Connection with server lost lost.");
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

	private static void usage() {
		System.out.println("Client host port nickname listenport");
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 4) {
			usage();
			return;
		}
		if (args[2].length() > MAX_NICKLEN) {
			System.out.println("Nickname must be " + MAX_NICKLEN + " or less.");
			return;
		}

		Client client = create(new InetSocketAddress(args[0], Integer.parseInt(args[1])), args[2],
				Integer.parseInt(args[3]));

		if (!client.logMeIn()) {
			return;
		}
		client.launch();
		client.getInput();
		client.close();
	}
}
