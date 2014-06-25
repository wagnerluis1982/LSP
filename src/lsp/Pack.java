package lsp;

public class Pack {
	private short connId;
	private final byte[] payload;

	public Pack(short connId, byte[] payload) {
		this.connId = connId;
		this.payload = payload;
	}

	public short getConnId() {
		return connId;
	}

	public void setConnId(short connId) {
		this.connId = connId;
	}

	public byte[] getPayload() {
		return payload;
	}
}
