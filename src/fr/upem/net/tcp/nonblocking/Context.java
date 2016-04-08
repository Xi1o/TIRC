package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import fr.upem.net.tcp.reader.CommandReader;

public class Context {
	private static final int BUFSIZ = 4096;
	private final Server server;
	private final SocketChannel sc;
	private SelectionKey key;
	private final ByteBuffer bbin;
	private final ByteBuffer bbout;
	private boolean isClosed;
	private final Queue<ByteBuffer> queue;
	private CommandReader commandReader;
	private final HashMap<Byte, Runnable> commands = new HashMap<>();
	private String nickname;
	private ByteBuffer bbNickname;
	private int port;
	private boolean isRegistered = false;

	private Context(ByteBuffer bbin, ByteBuffer bbout, Queue<ByteBuffer> queue, Server server,
			SocketChannel sc) {
		this.bbin = bbin;
		this.bbout = bbout;
		this.queue = queue;
		this.sc = sc;
		this.server = server;
		initCommands();
		commandReader = new CommandReader(bbin, Collections.unmodifiableMap(commands));
	}

	public static Context create(Server server, SocketChannel sc) {
		ByteBuffer bbin = ByteBuffer.allocate(BUFSIZ);
		ByteBuffer bbout = ByteBuffer.allocate(BUFSIZ);
		Queue<ByteBuffer> queue = new LinkedList<>();
		return new Context(bbin, bbout, queue, server, sc);
	}

	private void initCommands() {
		commands.put((byte) 0, () -> registerNickname());
		commands.put((byte) 4, () -> receivedMessage());
	}

	public void setSelectionKey(SelectionKey key) {
		this.key = key;
	}

	public void setPort(int port) {
		this.port = port;
	}

	public void doRead() throws IOException {
		if (-1 == sc.read(bbin)) {
			Server.silentlyClose(sc);
			unregister();
			return;
		}
		switch (commandReader.process()) {
		case ERROR:
			System.err.println("client error");
			Server.silentlyClose(sc);
			unregister();
			return;
		case DONE:
			// process();
			break;
		case REFILL:
			// nothing
			break;
		default:
			throw new IllegalStateException("this case should never happen");
		}
		updateInterestOps();
	}

	public void doWrite() throws IOException {
		if (!queue.isEmpty()) {
			int size = queue.peek().position();
			if (bbout.remaining() >= size) {
				ByteBuffer bb = queue.poll();
				bb.flip();
				bbout.put(bb);
			}
		}
		bbout.flip();
		if (-1 == sc.write(bbout) || isClosed) {
			Server.silentlyClose(sc);
			unregister();
			return;
		}
		bbout.clear();
		updateInterestOps();
	}

	private void updateInterestOps() {
		if (!key.isValid()) {
			return;
		}
		int newInterestOps = 0;
		if (bbout.position() > 0 || !queue.isEmpty()) {
			newInterestOps |= SelectionKey.OP_WRITE;
		}
		if (!isClosed && bbin.hasRemaining()) {
			newInterestOps |= SelectionKey.OP_READ;
		}
		key.interestOps(newInterestOps);
	}

	public void registerMessage(ByteBuffer bbmsg) {
		queue.offer(bbmsg);
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}

	private void registerNickname() {
		nickname = (String) commandReader.get();
		port = (int) commandReader.get();
		if (nickname.length() > Server.MAX_NICKSIZ) {
			Server.silentlyClose(sc);
			isClosed = true;
			return;
		}
		if (server.registerClient(nickname, this)) {
			confirmConnection();
			bbNickname = Server.NICKNAME_CHARSET.encode(nickname);
			bbNickname.compact(); // always end of data
			isRegistered = true;
		} else {
			refuseConnection();
			isClosed = true;
		}
	}
	
	private void unregister() {
		if(isRegistered) {
			server.unregisterClient(nickname);
		}
	}

	private void receivedMessage() {
		ByteBuffer bbmsg = (ByteBuffer) commandReader.get();
		bbmsg.flip();
		bbNickname.flip();
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + bbNickname.remaining()
				+ Integer.BYTES + bbmsg.remaining());
		bb.put((byte) 5);
		bb.putInt(bbNickname.remaining());
		bb.put(bbNickname);
		bb.putInt(bbmsg.remaining());
		bb.put(bbmsg);
		server.sendMessage(bb);
	}

	private void confirmConnection() {
		bbout.put((byte) 1);
		bbout.put((byte) 0);
		bbout.putInt(server.getNumberConnected());
	}

	private void refuseConnection() {
		bbout.put((byte) 1);
		bbout.put((byte) 1);
		bbout.putInt(server.getNumberConnected());
	}

	public void clientHasJoined(String nickname) {
		ByteBuffer bblogin = Server.NICKNAME_CHARSET.encode(nickname);
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + bblogin.remaining());
		bb.put((byte) 2);
		bb.putInt(bblogin.remaining());
		bb.put(bblogin);
		registerMessage(bb);
	}

	public void connectedClients(Set<String> nicknames) {
		ArrayList<ByteBuffer> list = new ArrayList<>();
		for (String nickname : nicknames) {
			// TODO
		}
		// ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES +
		// nicknames.size());
	}
}