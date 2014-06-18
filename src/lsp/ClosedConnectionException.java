package lsp;

public class ClosedConnectionException extends RuntimeException {

	ClosedConnectionException(short connId) {
		super("Conexão id=" + connId + " não está aberta");
	}

	public ClosedConnectionException() {
		super();
	}

}
