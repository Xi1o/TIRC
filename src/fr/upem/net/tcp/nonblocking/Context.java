package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Queue;

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
	private int privatePort;
	private boolean isRegistered = false;

	/* Core */

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

	public void setSelectionKey(SelectionKey key) {
		this.key = key;
	}

	public ByteBuffer getBbNickname() {
		return bbNickname.asReadOnlyBuffer();
	}

	private void initCommands() {
		commands.put((byte) 0, () -> registerNickname());
		commands.put((byte) 4, () -> receivedMessage());
		commands.put((byte) 6, () -> privateCommunicationRequest());
		commands.put((byte) 8, () -> privateCommunicationAnswer());
		commands.put((byte) 15, () -> disconnect());
	}

	public void doRead() throws IOException {
		if (-1 == sc.read(bbin)) {
			Server.silentlyClose(sc);
			unregister();
			return;
		}
		switch (commandReader.process()) {
		case ERROR:
			Server.silentlyClose(sc);
			unregister();
			return;
		case DONE:
			break;
		case REFILL:
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
		queue.offer(Objects.requireNonNull(bbmsg));
		key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
	}

	private void unregister() {
		Server.silentlyClose(sc);
		key.cancel();
		if (isRegistered) {
			server.unregisterClient(nickname, bbNickname);
		}
	}

	private void confirmConnection(boolean accept) {
		byte confirmationByte = (accept) ? (byte) 0 : 1;
		bbout.put((byte) 1);
		bbout.put((byte) confirmationByte);
		bbout.putInt(server.getNumberConnected());
	}

	/* Commands */

	// Opcode 0
	private void registerNickname() {
		nickname = (String) commandReader.get();
		privatePort = (int) commandReader.get();
		if (nickname.length() > Server.MAX_NICKSIZ) {
			Server.silentlyClose(sc);
			isClosed = true;
			return;
		}
		if (server.registerClient(nickname, this)) {
			confirmConnection(true);
			bbNickname = Server.CHARSET_NICKNAME.encode(nickname);
			bbNickname.compact(); // always end of data
			isRegistered = true;
			ByteBuffer bbmsg = server.getConnectedNicknames();
			registerMessage(bbmsg);
		} else {
			confirmConnection(false);
			isClosed = true;
		}
	}

	// Opcode 4
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

	// Opcode 6
	private void privateCommunicationRequest() {
		ByteBuffer bbDestNickname = (ByteBuffer) commandReader.get();
		bbDestNickname.flip();
		String destNickname = Server.CHARSET_NICKNAME.decode(bbDestNickname).toString();
		server.askPermissionPrivateConnection(nickname, destNickname);
	}

	// Opcode 8
	private void privateCommunicationAnswer() {
		Byte accept = (Byte) commandReader.get();
		String withNickname = (String) commandReader.get();
		if (accept != (byte) 0) { // refuse
			server.refusePrivateConnection(nickname, withNickname);
			return;
		}
		long sessionId = (long) commandReader.get();
		InetAddress inet = sc.socket().getInetAddress();
		server.acceptPrivateConnection(nickname, withNickname, inet, privatePort, sessionId);
	}

	// Opcode 15
	private void disconnect() {
		unregister();
	}

	/* Notification from server */

	public void clientHasJoined(String nickname) {
		ByteBuffer bbNickname = Server.CHARSET_NICKNAME.encode(nickname);
		ByteBuffer bbmsg = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + bbNickname.remaining());
		bbmsg.put((byte) 2);
		bbmsg.putInt(bbNickname.remaining());
		bbmsg.put(bbNickname);
		registerMessage(bbmsg);
	}

	public void clientHasLeft(ByteBuffer bbNickname) {
		bbNickname.flip();
		int size = bbNickname.remaining();
		ByteBuffer bbmsg = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + size);
		bbmsg.put((byte) 16);
		bbmsg.putInt(size);
		bbmsg.put(bbNickname);
		registerMessage(bbmsg);
	}

	public void askPrivateCommunication(String fromNickname) {
		ByteBuffer bbNickname = Server.CHARSET_NICKNAME.encode(fromNickname);
		int size = bbNickname.remaining();
		ByteBuffer bbmsg = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + size);
		bbmsg.put((byte) 7);
		bbmsg.putInt(size);
		bbmsg.put(bbNickname);
		registerMessage(bbmsg);
	}

	public void acceptPrivateCommunication(String fromNickName, InetAddress inet, int port,
			long id) {
		ByteBuffer bbNickname = Server.CHARSET_NICKNAME.encode(fromNickName);
		byte[] addr = inet.getAddress();
		int nicknameSize = bbNickname.remaining();
		int addrSize = addr.length;
		Byte ipVersion = 4;
		if (inet instanceof Inet6Address) {
			ipVersion = 6;
		}
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Byte.BYTES + Integer.BYTES + nicknameSize
				+ Byte.BYTES + addrSize + Integer.BYTES + Long.BYTES);
		bb.put((byte) 9);
		bb.put((byte) 0);
		bb.putInt(nicknameSize);
		bb.put(bbNickname);
		bb.put(ipVersion);
		bb.put(addr);
		bb.putInt(port);
		bb.putLong(port);
		registerMessage(bb);
	}

	public void refusePrivateCommunication(String fromNickName) {
		ByteBuffer bbNickname = Server.CHARSET_NICKNAME.encode(fromNickName);
		int nicknameSize = bbNickname.remaining();
		ByteBuffer bb = ByteBuffer.allocate(Byte.BYTES + Byte.BYTES + Integer.BYTES + nicknameSize);
		bb.put((byte) 9);
		bb.put((byte) 1);
		bb.putInt(nicknameSize);
		bb.put(bbNickname);
		registerMessage(bb);
	}
}
