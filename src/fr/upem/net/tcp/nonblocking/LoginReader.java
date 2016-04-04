package fr.upem.net.tcp.nonblocking;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;

public class LoginReader implements Reader {
	private enum State {
		USERNAME, PORT;
	}

	private static final Charset charset = Charset.forName("ascii");
	private State state;
	private final ByteBuffer bb;
	private final StringReader stringReader;
	private String nickname;
	private int port;

	private void processInt() {
		bb.flip();
		port = bb.getInt();
		bb.compact();
	}

	public LoginReader(ByteBuffer bb) {
		state = State.USERNAME;
		this.bb = bb;
		stringReader = new StringReader(bb);
	}

	@Override
	public Status process() {
		switch (state) {
		case USERNAME:
			Status status = stringReader.process();
			if (status != Status.DONE) {
				return status;
			}
			ByteBuffer bbNickname = (ByteBuffer) stringReader.getBB();
			bbNickname.flip();
			nickname = charset.decode(bbNickname).toString();
			state = State.PORT;
		case PORT:
			processInt();
			if (port < 0 || port > 65535) {
				return Status.ERROR;
			}
			break;
		default:
			throw new IllegalStateException("should not be here");
		}
		return Status.DONE;
	}

	public int getPort() {
		return port;
	}

	public String getNickname() {
		return nickname;
	}

	@Override
	public void reset() {
		state = State.USERNAME;
	}

}
