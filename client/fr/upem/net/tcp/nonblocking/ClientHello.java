package fr.upem.net.tcp.nonblocking;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;

public class ClientHello {
	public static void main(String[] args) throws IOException {
		SocketChannel sc = SocketChannel.open();
		try {
			sc.connect(new InetSocketAddress(args[0], Integer.parseInt(args[1])));
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println("Usage example : ClientTest localhost 7777");
			return;
		}
		Charset cs = Charset.forName("UTF-8");
		Runnable writer = new Runnable() {
			@Override
			public void run() {
				try {
					//ByteBuffer bbmsg = cs.encode("Hello !");
					ByteBuffer bbmsg = Charset.forName("utf-8").encode("Hello !");
					ByteBuffer bbsize = ByteBuffer.allocate(Integer.BYTES);
					bbsize.putInt(bbmsg.limit());
					bbsize.flip();
					ByteBuffer bb = ByteBuffer.allocate(bbmsg.capacity()+bbsize.capacity());
					bb.put(bbsize);
					//bb.putInt(3);
					bb.put(bbmsg);
					bb.flip();
					while (true) {
						sc.write(bb);
						bb.flip();
						Thread.sleep(1500);
					}
				} catch (IOException | InterruptedException e) {
					System.out.println();
					System.err.println("Server is down!");
					System.exit(0);
				}
			}
		};
		
		Runnable reader = new Runnable() {
			@Override
			public void run() {
				ByteBuffer bb = ByteBuffer.allocate(7+Integer.BYTES);
				try {
					while (sc.read(bb) != -1) {
						bb.flip();
						int size = bb.getInt();
						System.out.println("taille: "+size);
						System.out.println(cs.decode(bb));
						bb.clear();
						Thread.sleep(2000);
					}
				} catch (IOException | InterruptedException e) {
					System.out.println();
					System.err.println("Server is down!");
					System.exit(0);
				}
				System.out.println("Server is done!");
			}
		};
		
		new Thread(reader).start();
		new Thread(writer).start();
	}
}
