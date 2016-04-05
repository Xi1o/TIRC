package fr.upem.net.tcp.nonblocking.server;

public interface Reader {
	
	enum Status {
		DONE, REFILL, ERROR;
	}

	public Status process();
	
	public void reset();
}
