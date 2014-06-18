package lsp;


public class LspClient {
	public LspClient(String host, int port, LspParams params) {

	}

	public LspClient(LspConnection conn) {

	}

	/**
	 * Devolve o Id da conexão
	 */
	public short getConnId() {
		return -1;
	}

	/**
	 * Devolve um vetor de bytes de uma mensagem enviada pelo lado servidor .
	 * Devolve null se a conexão for perdida .
	 */
	public byte[] read() {
		return null;
	}

	/**
	 * Envia uma mensagen para o lado servidor como um vetor de bytes . Devolve
	 * exceção se a conexão for perdida .
	 */
	public void write(byte[] payload) {

	}

	/**
	 * Encerra a conexão.
	 */
	public void close() {

	}
}
