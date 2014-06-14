package lsp;

public interface Delegate {
	/**
	 * Callback para obter o momento da última mensagem recebida. O momento deve
	 * ser calculado com System.currentTimeMillis()
	 */
	long lastReceiptTime();

	/**
	 * Callback para fechar a conexão
	 */
	void closeConnection();
}
