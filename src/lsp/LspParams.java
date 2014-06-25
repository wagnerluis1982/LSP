package lsp;

public class LspParams {
	private final int epoch;
	private final int epochLimit;

	public LspParams(int epoch, int epochLimit) {
		this.epoch = epoch;
		this.epochLimit = epochLimit;
	}

	public int getEpoch() {
		return epoch;
	}

	public int getEpochLimit() {
		return epochLimit;
	}
}
