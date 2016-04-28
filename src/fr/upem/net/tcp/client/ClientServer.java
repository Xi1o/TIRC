package fr.upem.net.tcp.client;

import static fr.upem.net.tcp.client.ScReaders.readByte;
import static fr.upem.net.tcp.client.ScReaders.readInt;
import static fr.upem.net.tcp.client.ScReaders.readLong;
import static fr.upem.net.tcp.client.ScReaders.readString;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ClientServer {
	private static final Logger LOGGER = Logger.getLogger("ClientLogger");
	private static final int MAX_THREADS = 10;
	private final ServerSocketChannel serverSocketChannel;
	private final SocketChannel[] scs;
	private final Thread[] threads;
	private final Object lock = new Object();
	private final ConcurrentHashMap<String, Long> privateConnectionsId = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<SocketChannel, String> nicknamesConnected = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, SocketChannel> socketChannelClients = new ConcurrentHashMap<>();
	private ClientGUI clientGUI;

	/* Core */

	private ClientServer(ServerSocketChannel serverSocketChannel, SocketChannel[] scs,
			Thread[] threads) {
		this.serverSocketChannel = serverSocketChannel;
		this.scs = scs;
		this.threads = threads;
	}

	public static ClientServer create(int port) throws IOException {
		ServerSocketChannel serverSocketChannel = ServerSocketChannel.open();
		serverSocketChannel.bind(new InetSocketAddress(port));
		SocketChannel[] scs = new SocketChannel[MAX_THREADS];
		Thread[] threads = new Thread[MAX_THREADS];
		return new ClientServer(serverSocketChannel, scs, threads);
	}

	public void setUI(ClientGUI clientGUI) {
		this.clientGUI = clientGUI;
	}

	private void run() {
		int id = Integer.parseInt(Thread.currentThread().getName());
		try {
			while (!Thread.interrupted()) {
				try {
					// accept thread-safe
					SocketChannel client = serverSocketChannel.accept();
					synchronized (lock) {
						scs[id] = client;
					}
					LOGGER.info("Connection accepted with" + Client.remoteAddressToString(client));
					try {
						serve(client);
					} catch (AsynchronousCloseException ace) {
						// client timeout continue
						continue;
						// disconnected with client
					} catch (IOException ioe) {
						LOGGER.log(Level.WARNING, ioe.toString(), ioe);
					} catch (InterruptedException ie) {
						LOGGER.log(Level.INFO, "Server interrupted: " + ie.toString(), ie);
						return;
					} finally {
						clientGUI.println("Private connection closed.");
						silentlyClose(client);
					}
				} catch (ClosedChannelException ace) {
					return;
				}

			}
		} catch (IOException e) {
			return;
		}
	}

	private void serve(SocketChannel sc) throws IOException, InterruptedException {
		ByteBuffer bbin = ByteBuffer.allocate(Client.BUFSIZ);
		if (!authentication(sc, bbin)) {
			clientGUI.println("Could not authentificate client");
			LOGGER.warning(
					Client.remoteAddressToString(sc) + ": attempted to connected with false token");
			return;
		}
		String nicknameServed = nicknamesConnected.get(sc);
		if (null == nicknameServed) {
			LOGGER.warning("Unknown client attempted to connect.");
			return;
		}
		boolean hasClosed = false;
		while (true) {
			try {
				byte opcode = readByte(sc, bbin);
				switch (opcode) {
				case 11:
					receivedMessage(sc, bbin, nicknameServed);
					break;
				case 12:
					hasClosed = true;
					clientGUI.println(nicknameServed + " has closed private connection.");
					return;
				default:
					LOGGER.warning("Unknown opcode: " + opcode + " from " + nicknameServed);
					return;
				}
			} catch (IOException ioe) {
				if (!hasClosed) {
					clientGUI.println("Lost private connection with " + nicknameServed);
					LOGGER.warning("Lost private connection with " + nicknameServed);
					throw ioe;
				}
			}
		}
	}

	public void closePrivateConnection(String nickname) throws IOException {
		SocketChannel sc = socketChannelClients.get(nickname);
		socketChannelClients.remove(nickname);
		nicknamesConnected.remove(sc);
		silentlyClose(sc);
	}

	private static void silentlyClose(SocketChannel sc) {
		if (sc != null) {
			try {
				sc.close();
			} catch (IOException e) {
				// Do nothing
			}
		}
	}

	public void shutdownNow() throws IOException {
		for (Thread t : threads) {
			t.interrupt();
		}
		serverSocketChannel.close();
	}

	public void launch() throws IOException {
		for (int i = 0; i < MAX_THREADS; i++) {
			Thread t = new Thread(() -> run());
			t.setName(i + "");
			threads[i] = t;
		}
		for (int i = 0; i < MAX_THREADS; i++) {
			threads[i].start();
		}
	}

	public boolean registerClient(String nickname, long id) {
		if (null != privateConnectionsId.putIfAbsent(nickname, id)) {
			return false;
		}
		return true;
	}

	public boolean sendMessage(String toNickname, ByteBuffer bbmsg) throws IOException {
		SocketChannel sc = socketChannelClients.get(toNickname);
		if (null == sc) {
			return false;
		}
		bbmsg.flip();
		sc.write(bbmsg);
		return true;
	}

	public boolean isConnected(String clientNickname) {
		return socketChannelClients.containsKey(clientNickname);
	}

	/* Request from client */

	private boolean authentication(SocketChannel sc, ByteBuffer bb) throws IOException {
		byte opcode = readByte(sc, bb);
		if (opcode != (byte) 10) {
			LOGGER.warning("Not expected opcode: " + opcode);
		}
		int nicknameSize = readInt(sc, bb);
		String clientNickname = readString(sc, bb, nicknameSize, Client.CS_NICKNAME);
		long id = readLong(sc, bb);
		long givenId = privateConnectionsId.getOrDefault(clientNickname, (long) 0);
		privateConnectionsId.remove(clientNickname); // no more needed
		if ((long) 0 == givenId || givenId != id) {
			LOGGER.warning("Wrong token given from: " + clientNickname);
			return false;
		}
		nicknamesConnected.put(sc, clientNickname);
		synchronized (lock) {
			socketChannelClients.put(clientNickname, sc);
		}
		clientGUI.println("Private connection established with " + clientNickname + ".");
		clientGUI.println("To communicate with him privately use: /w " + clientNickname);
		return true;
	}
	/* handle opcode */

	// Opcode 11
	private void receivedMessage(SocketChannel sc, ByteBuffer bb, String nickname)
			throws IOException {
		int msgSize = readInt(sc, bb);
		String msg = readString(sc, bb, msgSize, Client.CS_MESSAGE);
		clientGUI.println("*" + nickname + "* " + msg);
	}
}
