package lsp;

class InternalPack extends Pack {
	private final short seqNum;

	InternalPack(short connId, short seqNum, byte[] payload) {
		super(connId, payload);
		this.seqNum = seqNum;
	}

	InternalPack(Pack pack, short seqNum) {
		this(pack.getConnId(), seqNum, pack.getPayload());
	}

	short getSeqNum() {
		return this.seqNum;
	}
}