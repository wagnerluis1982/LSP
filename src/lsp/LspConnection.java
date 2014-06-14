package lsp;

class LspConnection {
	private final short id;

	LspConnection(short id, LspParams params, Actions actions) {
		this.id = id;

		if (params == null || actions == null)
			throw new NullPointerException("Nenhum parâmetro pode ser nulo");

		// Aciona o verificador de status da conexão
		statusChecker(params, actions);
	}

	short getId() {
		return this.id;
	}

	interface Actions {
		/**
		 * Callback para obter o momento da última mensagem recebida. O momento
		 * deve ser calculado com System.currentTimeMillis()
		 */
		long lastReceiptTime();

		/**
		 * Callback para fechar a conexão
		 */
		void closeConnection();
	}

	/**
	 * Aciona uma thread que verifica se a conexão LSP ainda está ativa. Isto é
	 * feito através de callbacks definidos em uma instância de {@link Actions}.
	 *
	 * @param params Parâmetros de temporização da conexão
	 * @param actions Callbacks usados na verificação da conexão.
	 */
	private void statusChecker(final LspParams params, final Actions actions) {
		new Thread() {
			public void run() {
				long lastTime = actions.lastReceiptTime();
				int limit = params.getEpochLimit();
				final int epoch = params.getEpoch();

				while (limit-- > 0) {
					try {
						Thread.sleep(epoch);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					final long time = actions.lastReceiptTime();
					if (time != lastTime) {
						lastTime = time;
						limit = params.getEpochLimit();
					}
				}
				actions.closeConnection();
			};
		}.start();
	}
}
