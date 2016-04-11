package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

public class Server {
	public static final int MAX_NICKSIZ = 15;
	public static final int MAX_MSGSIZ = 2048;
	public static final Charset CHARSET_NICKNAME = Charset.forName("ASCII");
	public static final Charset CHARSET_MSG = Charset.forName("UTF-8");
	private final ServerSocketChannel serverSocketChannel;
	private final Selector selector;
	private final Set<SelectionKey> selectedKeys;
	private final HashMap<String, Context> clients = new HashMap<>();
	private int numberConnected;

	public Server(int port) throws IOException {
		serverSocketChannel = ServerSocketChannel.open();
		serverSocketChannel.bind(new InetSocketAddress(port));
		selector = Selector.open();
		selectedKeys = selector.selectedKeys();
	}

	public void launch() throws IOException {
		serverSocketChannel.configureBlocking(false);
		serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
		Set<SelectionKey> selectedKeys = selector.selectedKeys();
		while (!Thread.interrupted()) {
			printKeys();
			System.out.println("Starting select");
			selector.select();
			System.out.println("Select finished");
			printSelectedKey();
			processSelectedKeys();
			selectedKeys.clear();
		}
	}

	public void sendMessage(ByteBuffer bbmsg) {
		for (SelectionKey key : selector.keys()) {
			Context context = (Context) key.attachment();
			if (context == null) {
				// server key
				continue;
			}
			context.registerMessage(bbmsg);
		}
	}

	private void notifyClientHasJoined(String nickname) {
		for (SelectionKey key : selector.keys()) {
			Context context = (Context) key.attachment();
			if (context == null) {
				// server key
				continue;
			}
			context.clientHasJoined(nickname);
		}
	}

	private void notifyClientHasLeaved(ByteBuffer bbNickname) {
		for (SelectionKey key : selector.keys()) {
			if (key.isValid()) {
				Context context = (Context) key.attachment();
				if (context == null) {
					// server key
					continue;
				}
				context.clientHasLeaved(bbNickname);
			}
		}
	}

	public boolean registerClient(String nickname, Context context) {
		if (null != clients.putIfAbsent(nickname, context)) {
			return false;
		}
		numberConnected++;
		notifyClientHasJoined(nickname);
		return true;
	}

	public void unregisterClient(String nickname, ByteBuffer bbNickname) {
		if (null != clients.remove(nickname)) {
			numberConnected--;
			notifyClientHasLeaved(bbNickname);
		}
	}

	public int getNumberConnected() {
		return numberConnected;
	}

	public ByteBuffer getConnectedNicknames() {
		ArrayList<ByteBuffer> list = new ArrayList<>();
		int totalSize = 0;
		for (SelectionKey key : selector.keys()) {
			Context context = (Context) key.attachment();
			if (context == null) {
				// server key
				continue;
			}
			ByteBuffer bbNickname = context.getBbNickname();
			bbNickname.flip();
			totalSize += bbNickname.remaining();
			list.add(bbNickname);
		}
		ByteBuffer bbmsg = ByteBuffer
				.allocate(Byte.BYTES + Integer.BYTES + Integer.BYTES * list.size() + totalSize);
		bbmsg.put((byte) 3);
		bbmsg.putInt(list.size());
		list.forEach(bb -> {
			bbmsg.putInt(bb.remaining());
			bbmsg.put(bb);
		});
		return bbmsg;
	}

	private void processSelectedKeys() throws IOException {
		for (SelectionKey key : selectedKeys) {
			if (key.isValid() && key.isAcceptable()) {
				doAccept(key);
			}
			try {
				if (key.isValid() && key.isWritable()) {
					doWrite(key);
				}
				if (key.isValid() && key.isReadable()) {
					doRead(key);
				}
			} catch (IOException ioe) {
				SocketChannel sc = (SocketChannel) key.channel();
				silentlyClose(sc);
			}
		}
	}

	private void doAccept(SelectionKey key) throws IOException {
		SocketChannel sc = serverSocketChannel.accept();
		if (sc == null) {
			return;
		}
		sc.configureBlocking(false);
		Context context = Context.create(this, sc);
		SelectionKey clientKey = sc.register(selector, SelectionKey.OP_READ, context);
		context.setSelectionKey(clientKey);
	}

	private void doRead(SelectionKey key) throws IOException {
		Context context = (Context) key.attachment();
		context.doRead();
	}

	private void doWrite(SelectionKey key) throws IOException {
		Context context = (Context) key.attachment();
		context.doWrite();
	}

	public static void silentlyClose(SocketChannel sc) {
		if (sc != null) {
			try {
				sc.close();
			} catch (IOException e) {
				// Do nothing
			}
		}
	}

	private String interestOpsToString(SelectionKey key) {
		if (!key.isValid()) {
			return "CANCELLED";
		}
		int interestOps = key.interestOps();
		ArrayList<String> list = new ArrayList<>();
		if ((interestOps & SelectionKey.OP_ACCEPT) != 0)
			list.add("OP_ACCEPT");
		if ((interestOps & SelectionKey.OP_READ) != 0)
			list.add("OP_READ");
		if ((interestOps & SelectionKey.OP_WRITE) != 0)
			list.add("OP_WRITE");
		return String.join("|", list);
	}

	public void printKeys() {
		Set<SelectionKey> selectionKeySet = selector.keys();
		if (selectionKeySet.isEmpty()) {
			System.out.println("The selector contains no key : this should not happen!");
			return;
		}
		System.out.println("The selector contains:");
		for (SelectionKey key : selectionKeySet) {
			SelectableChannel channel = key.channel();
			if (channel instanceof ServerSocketChannel) {
				System.out.println("\tKey for ServerSocketChannel : " + interestOpsToString(key));
			} else {
				SocketChannel sc = (SocketChannel) channel;
				System.out.println("\tKey for Client " + remoteAddressToString(sc) + " : "
						+ interestOpsToString(key));
			}

		}
	}

	private String remoteAddressToString(SocketChannel sc) {
		try {
			return sc.getRemoteAddress().toString();
		} catch (IOException e) {
			return "???";
		}
	}

	private void printSelectedKey() {
		if (selectedKeys.isEmpty()) {
			System.out.println("There were not selected keys.");
			return;
		}
		System.out.println("The selected keys are :");
		for (SelectionKey key : selectedKeys) {
			SelectableChannel channel = key.channel();
			if (channel instanceof ServerSocketChannel) {
				System.out.println(
						"\tServerSocketChannel can perform : " + possibleActionsToString(key));
			} else {
				SocketChannel sc = (SocketChannel) channel;
				System.out.println("\tClient " + remoteAddressToString(sc) + " can perform : "
						+ possibleActionsToString(key));
			}
		}
	}

	private String possibleActionsToString(SelectionKey key) {
		if (!key.isValid()) {
			return "CANCELLED";
		}
		ArrayList<String> list = new ArrayList<>();
		if (key.isAcceptable())
			list.add("ACCEPT");
		if (key.isReadable())
			list.add("READ");
		if (key.isWritable())
			list.add("WRITE");
		return String.join(" and ", list);
	}

	private static void usage() {
		System.out.println("Usage: Server port");
	}

	public static void main(String[] args) throws NumberFormatException, IOException {
		if (args.length != 1) {
			usage();
			return;
		}
		new Server(Integer.parseInt(args[0])).launch();
	}
}