package lsp;

interface ConnectionActions {
	/**
	 * Callback representando as ações a serem disparadas a cada época
	 */
	void epochTriggers();

	/**
	 * Callback representando as ações de fechamento da conexão
	 */
	void closeConnection();
}