package lsp;

/**
 * Representa uma conexão LSP.
 *
 * @author Wagner Macedo
 */
class LspConnection {
	private final short id;

	/**
	 * Constrói um objeto {@link LspConnection}
	 *
	 * @param id Identificador da conexão
	 * @param params Parâmetros de temporização da conexão
	 * @param actions Callbacks usados na verificação da conexão.
	 */
	LspConnection(short id, LspParams params, Actions actions) {
		this.id = id;

		if (params == null || actions == null)
			throw new NullPointerException("Nenhum parâmetro pode ser nulo");

		// Inicia a thread para monitorar o status da conexão
		Runnable checker = new StatusChecker(params, actions);
		new Thread(checker).start();
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
		 * Callback representando as ações a serem disparadas a cada época
		 */
		void epochTriggers();

		/**
		 * Callback para fechar a conexão
		 */
		void closeConnection();
	}

	/**
	 * Monitoramento da conexão LSP. Verifica se está ativa. Este processo é
	 * feito através de callbacks definidos em uma instância de {@link Actions}.
	 */
	private static final class StatusChecker implements Runnable {
		private final LspParams params;
		private final Actions actions;

		private StatusChecker(LspParams params, Actions actions) {
			this.params = params;
			this.actions = actions;
		}

		@Override
		public void run() {
			long lastTime = actions.lastReceiptTime();
			int limit = params.getEpochLimit();
			final int epoch = params.getEpoch();

			while (limit-- > 0) {
				sleep(epoch);

				// Dispara as ações da época
				actions.epochTriggers();

				// Reinicia contagem de épocas se houve mensagens recebidas
				// desde a última época
				final long time = actions.lastReceiptTime();
				if (time != lastTime) {
					lastTime = time;
					limit = params.getEpochLimit();
				}
			}

			// O limite de épocas foi atingido, então encerra a conexão
			actions.closeConnection();
		}

		/* Sleep sem lançamento de exceção */
		private void sleep(long millis) {
			try {
				Thread.sleep(millis);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
}
