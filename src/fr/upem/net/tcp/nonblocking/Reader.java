package fr.upem.net.tcp.nonblocking;

public interface Reader {
	
	enum Status {
		DONE, REFILL, ERROR;
	}

	public Status process();
	
	public Object get();
	
	public void reset();
}
