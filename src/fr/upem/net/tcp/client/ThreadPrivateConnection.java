package fr.upem.net.tcp.client;

import static fr.upem.net.tcp.client.ScReaders.readByte;
import static fr.upem.net.tcp.client.ScReaders.readInt;
import static fr.upem.net.tcp.client.ScReaders.readString;

import java.awt.Color;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ThreadPrivateConnection implements Runnable {
	private final SocketChannel sc;
	private final String nickname;
	private final ByteBuffer bbin = ByteBuffer.allocate(Client.BUFSIZ);
	private final ClientGUI clientGUI;
	private final Client client;

	/**
	 * Constructor.
	 * 
	 * @param sc
	 *            {@link SocketChannel} to monitor
	 * @param nickname
	 *            of client to monitor
	 * @param clientGUI
	 *            GUI where to print
	 */
	public ThreadPrivateConnection(SocketChannel sc, String nickname, ClientGUI clientGUI,
			Client client) {
		this.sc = sc;
		this.nickname = nickname;
		this.clientGUI = clientGUI;
		this.client = client;
	}

	/**
	 * If opcode 11, a private message was received.
	 * 
	 * @param sc
	 *            {@link SocketChannel} where message was received from
	 * @param bb
	 *            {@link ByteBuffer} to save output to
	 * @param nickname
	 *            of client who sent private message
	 * @throws IOException
	 *             if some I/O error occurs
	 */
	private void receivedPrivateMessage(SocketChannel sc, ByteBuffer bb, String nickname)
			throws IOException {
		int msgSize = readInt(sc, bb);
		String msg = readString(sc, bb, msgSize, Client.CS_MESSAGE);
		clientGUI.println("*" + nickname + "* " + msg, Color.orange);
	}

	@Override
	public void run() {
		while (!Thread.interrupted()) {
			try {
				byte opcode = readByte(sc, bbin);
				switch (opcode) {
				case 11:
					receivedPrivateMessage(sc, bbin, nickname);
					break;
				case 12:
					clientGUI.println(nickname + " has closed private connection.", Color.blue);
					client.removePrivateConnection(nickname);
					return;
				default:
					System.err.println("Unknown opcode: " + opcode);
					clientGUI.println("Private connection lost with " + nickname, Color.red);
					client.removePrivateConnection(nickname);
					return;
				}
			} catch (IOException ioe) {
				if (!Thread.interrupted()) {
					clientGUI.println("Private connection lost with " + nickname, Color.red);
				} else {
					clientGUI.println("Private connection closed with " + nickname, Color.blue);
				}
				client.removePrivateConnection(nickname);
				return;
			}
		}
	}
}