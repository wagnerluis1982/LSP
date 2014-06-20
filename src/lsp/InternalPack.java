package lsp;

class InternalPack extends Pack {
	private LspConnection connection;
	private short seqNum;

	InternalPack(LspConnection conn, short seqNum, byte[] payload) {
		super(conn.getId(), payload);
		this.connection = conn;
		this.seqNum = seqNum;
	}

	InternalPack(LspConnection conn, byte[] payload) {
		this(conn, (short) -1, payload);
	}

	public InternalPack(Pack pack) {
		super(pack.getConnId(), pack.getPayload());
	}

	LspConnection getConnection() {
		return this.connection;
	}

	void setConnection(LspConnection connection) {
		this.connection = connection;
	}

	short getSeqNum() {
		return this.seqNum;
	}

	void setSeqNum(short seqNum) {
		this.seqNum = seqNum;
	}
}
