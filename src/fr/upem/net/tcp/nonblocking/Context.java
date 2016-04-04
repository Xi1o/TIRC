package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

public class Context {
	private static final int BUFSIZ = 1024;
	private final Server server;
	private final SocketChannel sc;
	private SelectionKey key;
	private final ByteBuffer bbin;
	private final ByteBuffer bbout;
	private boolean isClosed;
	private final Queue<ByteBuffer> queue;
	private CommandReader commandReader;
	private String nickname;
	private int port;

	private Context(ByteBuffer bbin, ByteBuffer bbout, Queue<ByteBuffer> queue,
			CommandReader commandReader, Server server, SocketChannel sc) {
		this.bbin = bbin;
		this.bbout = bbout;
		this.queue = queue;
		this.commandReader = commandReader;
		this.sc = sc;
		this.server = server;
	}

	public static Context create(Server server, SocketChannel sc) {
		ByteBuffer bbin = ByteBuffer.allocate(BUFSIZ);
		ByteBuffer bbout = ByteBuffer.allocate(BUFSIZ);
		Queue<ByteBuffer> queue = new LinkedList<>();
		CommandReader commandReader = new CommandReader(bbin);
		return new Context(bbin, bbout, queue, commandReader, server, sc);
	}

	public void setSelectionKey(SelectionKey key) {
		this.key = key;
	}

	public void setPort(int port) {
		this.port = port;
	}

	private void process() {
		switch (commandReader.getOpcode()) {
		case 0:
			nickname = commandReader.getNickname();
			port = commandReader.getPort();
			if (server.registerClient(nickname, this)) {
				confirmConnection();
			} else {
				refuseConnection();
				isClosed = true;
			}
			break;
		default:
			throw new IllegalStateException("should not be here");
		}

	}

	public void doRead() throws IOException {
		if (-1 == sc.read(bbin)) {
			Server.silentlyClose(sc);
			server.unregisterClient(nickname);
			return;
		}
		switch (commandReader.process()) {
		case ERROR:
			Server.silentlyClose(sc);
			server.unregisterClient(nickname);
			return;
		case DONE:
			process();
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
			if (bbout.remaining() >= Integer.BYTES + size) {
				bbout.putInt(size);
				ByteBuffer bb = queue.poll();
				bb.flip();
				bbout.put(bb);
			}
		}
		bbout.flip();
		if (-1 == sc.write(bbout)) {
			return;
		}
		if(isClosed) {
			Server.silentlyClose(sc);
			return;
		}
		bbout.clear();
		updateInterestOps();
	}

	private void updateInterestOps() {
		if(!key.isValid()) {
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
}