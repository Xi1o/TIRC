package fr.upem.net.tcp.nonblocking.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.Objects;

public class Client {
	private static final int BUFSIZ = 1024;
	private static final int MAXNICKNAME = 10;
	private final SocketChannel sc;
	private final ByteBuffer bbin;
	private final ByteBuffer bbout;
	private final Charset csNickname;
	private final Charset csMessage;
	private final String nickname;
	private final int listenport;
	private int numberConnected;

	private Client(SocketChannel sc, ByteBuffer bbin, ByteBuffer bbout, Charset csNickname,
			Charset csMessage, String nickname, int listenport) {
		this.sc = sc;
		this.bbin = bbin;
		this.bbout = bbout;
		this.csNickname = csNickname;
		this.csMessage = csMessage;
		this.nickname = nickname;
		this.listenport = listenport;
	}

	public static Client create(InetSocketAddress host, Charset csMessage, String nickname,
			int listenport) throws IOException {
		Objects.requireNonNull(host);
		Objects.requireNonNull(csMessage);
		Objects.requireNonNull(nickname);
		if (listenport < 0 || listenport > 65535) {
			throw new IllegalArgumentException("Port must be valid: " + listenport);
		}
		ByteBuffer bbin = ByteBuffer.allocate(BUFSIZ);
		ByteBuffer bbout = ByteBuffer.allocate(BUFSIZ);
		Charset csNickname = Charset.forName("ascii");
		SocketChannel sc = SocketChannel.open();
		sc.connect(host);
		return new Client(sc, bbin, bbout, csNickname, csMessage, nickname, listenport);
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
			//TODO
			System.err.println("not readfully :(");
		}
		bbin.flip();
		return bbin.get();
	}
	
	private int readInt() throws IOException {
		bbin.limit(Integer.BYTES);
		if (!readFully()) {
			//TODO
			System.err.println("not readfully :(");
		}
		bbin.flip();
		return bbin.getInt();
	}

	private void requestConnection() {
		ByteBuffer bbNickname = csNickname.encode(nickname);
		bbout.put((byte) 0);
		bbout.putInt(bbNickname.remaining());
		bbout.put(bbNickname);
		bbout.putInt(listenport);
	}

	private static void usage() {
		System.out.println("Client host port nickname listenport");
	}

	public boolean logmein() throws IOException {
		requestConnection();
		bbout.flip();
		sc.write(bbout);
		if ((byte) 1 != readByte()) {
			// TODO
			return false;
		}
		bbin.clear();
		if ((byte) 0 == readByte()) {
			bbin.clear();
			numberConnected = readInt();
			System.out.println("You are connected.");
			System.out.println(numberConnected + " persons connected.");
			return true;
		} else {
			System.out.println("Nickname already taken.");
			return false;
		}
	}

	public static void main(String[] args) throws IOException {
		if (args.length != 4) {
			usage();
			return;
		}

		Client client = create(new InetSocketAddress(args[0], Integer.parseInt(args[1])),
				Charset.forName("utf-8"), args[2], Integer.parseInt(args[3]));
		if (!client.logmein()) {
			return;
		}
		try {
			Thread.sleep(10000);
		} catch (InterruptedException e) {
		
		}
	}
}
