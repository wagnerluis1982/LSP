package lsp;

class InternalPack extends Pack {
	private final LspConnection connection;
	private final short seqNum;

	InternalPack(LspConnection conn, short seqNum, byte[] payload) {
		super(conn.getId(), payload);
		this.connection = conn;
		this.seqNum = seqNum;
	}

	LspConnection getConnection() {
		return this.connection;
	}

	short getSeqNum() {
		return this.seqNum;
	}
}
