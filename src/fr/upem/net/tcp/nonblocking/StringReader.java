package fr.upem.net.tcp.nonblocking;

import java.nio.ByteBuffer;

public class StringReader implements Reader {
	private enum State {
		READINT, READSTR;
	}

	private final ByteBuffer bb;
	private State state = State.READINT;
	private int size;
	private final ByteBuffer bbstr = ByteBuffer.allocate(Server.MAX_MSGSIZ);

	public StringReader(ByteBuffer bb) {
		this.bb = bb;
	}

	private void processInt() {
		bb.flip();
		size = bb.getInt();
		bb.compact();
	}

	private void processString() {
		bbstr.clear();
		bb.flip();
		int oldlimit = bb.limit();
		bb.limit(size);
		bbstr.put(bb);
		bb.limit(oldlimit);
		bb.compact();
	}

	@Override
	public Status process() {
		switch (state) {
		case READINT:
			if (bb.position() < Integer.BYTES) {
				return Status.REFILL;
			}
			processInt();
			if (size <= 0 || size > Server.MAX_MSGSIZ) {
				return Status.ERROR;
			}
			state = State.READSTR;
			//no break !
		case READSTR:
			if (bb.position() < size) {
				return Status.REFILL;
			}
			processString();
			state = State.READINT;
			break;
		default:
			throw new IllegalStateException("this case should never happen");
		}
		return Status.DONE;
	}

	@Override
	public Object get() {
		return bbstr;
	}

	@Override
	public void reset() {
		state = State.READINT;
	}

}
