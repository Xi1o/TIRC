package fr.upem.net.tcp.main;

import java.io.IOException;
import java.util.Scanner;

import fr.upem.net.tcp.nonblocking.Server;

public class MainServer {
	public static void main(String[] args) throws NumberFormatException {
		if (args.length != 1) {
			Server.usage();
			return;
		}
		Thread threadServer = new Thread(() -> {
			try {
				Server server = new Server(Integer.parseInt(args[0]));
				server.launch();
			} catch (NumberFormatException nfe) {
				Server.usage();
			} catch (IOException ioe) {
				System.err.println(ioe);
			}
			return;
		});
		threadServer.start();

		Scanner scanner = new Scanner(System.in);
		while (scanner.hasNextLine()) {

		}
		threadServer.interrupt();
		scanner.close();
	}
}
