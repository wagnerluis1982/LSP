package lsp;

public class LspConnection {
	private int id;
	private boolean alive;
	private Close close;
	private LspParams params;

	public LspConnection(int id, LspParams params, Close close) {
		this.id = id;
		this.params = params;
		this.close = close;
		this.alive = true;
	}

	public int getId() {
		return this.id;
	}

	public boolean isAlive() {
		return this.alive;
	}
}
