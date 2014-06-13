package lsp;

public class LspParams {
	private int epoch;
	private int epochLimit;

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
