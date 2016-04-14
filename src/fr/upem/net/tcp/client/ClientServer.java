package fr.upem.net.tcp.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.HashMap;
import java.util.concurrent.ConcurrentHashMap;
import static fr.upem.net.tcp.client.ScReaders.*;

public class ClientServer {
	private static final int MAX_THREADS = 10;
	private final ServerSocketChannel serverSocketChannel;
	private final SocketChannel[] scs;
	private final Thread[] threads;
	private final Object lock = new Object();
	private final ConcurrentHashMap<String, Long> privateConnectionsId = new ConcurrentHashMap<>();
	private final HashMap<SocketChannel, String> nicknamesConnected = new HashMap<>();
	private final HashMap<String, SocketChannel> socketChannelClients = new HashMap<>();
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
					System.out.println("Connection accepted from " + client.getRemoteAddress());
					try {
						serve(client);
					} catch (AsynchronousCloseException ace) {
						// client timeout continue
						continue;
						// disconnected with client
					} catch (IOException ioe) {
						System.err.println("I/O Error while communicating with client... ");
						ioe.printStackTrace();
					} catch (InterruptedException ie) {
						System.out.println("Server interrupted... ");
						ie.printStackTrace();
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
			return;
		}
		String nicknameServed = nicknamesConnected.get(sc);
		if (null == nicknameServed) {
			System.err.println("Unknown connection.");
			return;
		}
		while (true) {
			try {
				byte opcode = readByte(sc, bbin);
				switch (opcode) {
				case 11:
					receivedMessage(sc, bbin, nicknameServed);
					break;
				case 12:
					clientGUI.println(nicknameServed + " has closed private connection.");
					return;
				default:
					System.err.println("Unknown opcode: " + opcode);
					return;
				}
			} catch (IOException ioe) {
				clientGUI.println("Lost private connection with " + nicknameServed);
				throw ioe;
			}
		}
	}
	
	public void closePrivateConnection(String nickname) throws IOException {
		SocketChannel sc = socketChannelClients.get(nickname);
		sc.write(packetSendPrivateDisctonnection());
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
	
	public SocketChannel getSocketChannelFromNickname(String nickname) {
		synchronized (lock) {
			return socketChannelClients.get(nickname);
		}
	}
	
	/* Request from client */

	private boolean authentication(SocketChannel sc, ByteBuffer bb) throws IOException {
		byte opcode = readByte(sc, bb);
		if (opcode != (byte) 10) {
			System.err.println("Not expected opcode " + opcode + ".");
		}
		int nicknameSize = readInt(sc, bb);
		String clientNickname = readString(sc, bb, nicknameSize, Client.CS_NICKNAME);
		long id = readLong(sc, bb);
		long givenId = privateConnectionsId.getOrDefault(clientNickname, (long) 0);
		privateConnectionsId.remove(clientNickname); // no more needed
		if ((long) 0 == givenId || givenId != id) {
			System.out.println(id + " /// " + givenId);
			System.err.println("Authenfication failed: " + clientNickname);
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

	/* Request to client */
	
	private static ByteBuffer packetSendPrivateDisctonnection() {
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES);
		bb.put((byte) 12);
		return bb;
	}

	/* handle opcode */

	// Opcode 11
	private void receivedMessage(SocketChannel sc, ByteBuffer bb, String nickname)
			throws IOException {
		int msgSize = readInt(sc, bb);
		String msg = readString(sc, bb, msgSize, Client.CS_MESSAGE);
		clientGUI.println("*"+ nickname +"* "+msg);
	}
}
