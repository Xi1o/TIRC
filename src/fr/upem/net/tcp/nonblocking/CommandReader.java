package fr.upem.net.tcp.nonblocking;

import java.nio.ByteBuffer;
import java.util.Objects;


public class CommandReader implements Reader {
	private enum State {
		OPCODE, COMMAND;
	}

	private State state;
	private final ByteBuffer bb;
	byte opcode;
	private final LoginReader loginReader;

	public CommandReader(ByteBuffer bb) {
		state = State.OPCODE;
		this.bb = Objects.requireNonNull(bb);
		loginReader = new LoginReader(bb);
	}

	@Override
	public Status process() {
		switch (state) {
		case OPCODE:
			if (bb.position() < Integer.BYTES) {
				return Status.REFILL;
			}
			bb.flip();
			opcode = bb.get();
			bb.compact();
			state = State.COMMAND;
		case COMMAND:
			return processCommand();
		default:
			throw new IllegalStateException("should not be here");
		}
	}

	public byte getOpcode() {
		return opcode;
	}

	@Override
	public void reset() {
		state = State.OPCODE;
	}

	private Status processCommand() {
		switch(opcode){
		case 0:
			Status status = loginReader.process();
			return status;
		}
		return Status.DONE;
	}
	
	public String getNickname() {
		return loginReader.getNickname();
	}
	
	public int getPort() {
		return loginReader.getPort();
	}
}
