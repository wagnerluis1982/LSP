package lsp;

interface ConnectionTriggers {
	/**
	 * Callback representando as ações a serem disparadas a cada época
	 */
	void doEpochActions();

	/**
	 * Callback representando as ações de fechamento da conexão
	 */
	void doCloseConnection();
}
