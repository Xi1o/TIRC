package fr.upem.net.tcp.client;

import static fr.upem.net.tcp.client.ScReaders.readByte;
import static fr.upem.net.tcp.client.ScReaders.readInt;
import static fr.upem.net.tcp.client.ScReaders.readString;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class ThreadPrivateConnection implements Runnable {
		private final SocketChannel sc;
		private final String nickname;
		private final ByteBuffer bbin = ByteBuffer.allocate(Client.BUFSIZ);
		private final ClientGUI clientGUI;

		public ThreadPrivateConnection(SocketChannel sc, String nickname, ClientGUI clientGUI) {
			this.sc = sc;
			this.nickname = nickname;
			this.clientGUI = clientGUI;
		}
		
		// Opcode 11
		private void receivedPrivateMessage(SocketChannel sc, ByteBuffer bb, String nickname)
				throws IOException {
			int msgSize = readInt(sc, bb);
			String msg = readString(sc, bb, msgSize, Client.CS_MESSAGE);
			clientGUI.println("*"+ nickname +"* "+msg);
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
						clientGUI.println(nickname + " has closed private connection.");
						return;
					default:
						System.err.println("Unknown opcode: " + opcode);
						clientGUI.println("Private connection lost with " + nickname);
						return;
					}
				} catch (IOException ioe) {
					clientGUI.println("Private connection lost with " + nickname);
					return;
				}
			}
		}
	}