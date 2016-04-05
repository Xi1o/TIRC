package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Objects;
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
	private static final int BUFSIZ = 1024;
	private static final int MAXNICKNAME = 10;
	private static final Logger LOGGER = Logger.getLogger(Client.class.getName());
	private final SocketChannel sc;
	private final ByteBuffer bbin;
	private final ByteBuffer bbout;
	private final Charset csNickname = Charset.forName("ASCII");
	private final Charset csMessage = Charset.forName("UTF-8");
	private final String nickname;
	private final int listenport;
	private int numberConnected;

	// TODO complete all op codes received by clients
	private enum OpCode {
		EMPTY, CO_REQ_REP, JOIN_NOTIF, NICK_PCK, PUB_MSG_NOTIF;
	}

	private Client(SocketChannel sc, ByteBuffer bbin, ByteBuffer bbout, String nickname, int listenport)
			throws SecurityException, IOException {
		this.sc = sc;
		this.bbin = bbin;
		this.bbout = bbout;
		this.nickname = nickname;
		this.listenport = listenport;
		initLogger();
	}

	private void initLogger() throws IOException {
		FileHandler fh = new FileHandler("client.log", true); // appends logs to
																// file
		fh.setFormatter(new SimpleFormatter()); // simple text style
		LOGGER.setLevel(Level.ALL); // only output specified and more important
									// levels
		LOGGER.addHandler(fh); // add FileHandler to the logger
		LOGGER.setUseParentHandlers(false); // logs dont output on standard
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
		return new Client(sc, bbin, bbout, nickname, listenport);
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
		bbin.limit(Byte.BYTES);
		if (!readFully()) {
			LOGGER.severe("Failed to read."); // TODO
		}
		bbin.flip();
		return bbin.get();
	}

	private int readInt() throws IOException {
		bbin.limit(Integer.BYTES);
		if (!readFully()) {
			LOGGER.severe("Failed to read."); // TODO
		}
		bbin.flip();
		return bbin.getInt();
	}
	
	// TODO : complete all op codes
	private OpCode readOpCode() throws IOException {
		switch (readByte()) {
		case 1:
			return OpCode.CO_REQ_REP;
		case 2:
			return OpCode.JOIN_NOTIF;
		case 5:
			return OpCode.PUB_MSG_NOTIF;
		default:
			return OpCode.EMPTY; // should not happen
		}
	}

	private void requestConnection() {
		ByteBuffer bbNickname = csNickname.encode(nickname);
		bbout.put((byte) 0);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
		bbout.putInt(listenport);
	}

	private static void usage() {
		System.out.println("Client [host] [port] [nickname] [listenport]");
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
		if (OpCode.CO_REQ_REP != readOpCode()) {
			// TODO
			return false;
		}
		bbin.clear();
		byte code = readByte();
		if (code == 0) {
			bbin.clear();
			numberConnected = readInt();
			System.out.println("You are connected as " + nickname + ".");
			System.out.println(numberConnected + " person(s) connected.");
			LOGGER.info("Successfully connected to server as" + nickname);
			return true;
		} else if (code == 1) {
			System.out.println("Your nickname is already taken.");
			return false;
		} else {
			System.out.println("Your nickname is too long.");
			return false;
		}
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 4) {
			usage();
			return;
		}

		Client client = create(new InetSocketAddress(args[0], Integer.parseInt(args[1])), args[2],
				Integer.parseInt(args[3]));
		if (!client.logMeIn()) {
			return;
		}
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {

		}
	}
}
