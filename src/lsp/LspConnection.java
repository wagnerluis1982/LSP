package lsp;

/**
 * Representa uma conexão LSP.
 *
 * @author Wagner Macedo
 */
class LspConnection {
	private final short id;
	private final long sockId;

	private volatile boolean closed;
	private volatile long lastMsgTime;

	/**
	 * Constrói um objeto {@link LspConnection}
	 *
	 * @param id Identificador da conexão
	 * @param sockId Número IP associado à conexão
	 * @param params Parâmetros de temporização da conexão
	 * @param actions Callbacks usados na verificação da conexão.
	 */
	LspConnection(short id, long sockId, LspParams params, Actions actions) {
		if (params == null || actions == null)
			throw new NullPointerException("Nenhum parâmetro pode ser nulo");

		this.id = id;
		this.sockId = sockId;
		this.closed = false;
		this.lastMsgTime = System.currentTimeMillis();

		// Inicia a thread para monitorar o status da conexão
		Runnable checker = new StatusChecker(params, actions);
		new Thread(checker).start();
	}

	short getId() {
		return this.id;
	}

	long getSockId() {
		return sockId;
	}

	public long getLastMsgTime() {
		return lastMsgTime;
	}

	public void setLastMsgTime(long lastMsgTime) {
		this.lastMsgTime = lastMsgTime;
	}

	void close() {
		this.closed = true;
	}

	interface Actions {
		/**
		 * Callback representando as ações a serem disparadas a cada época
		 */
		void epochTriggers();

		/**
		 * Callback representando as ações de fechamento da conexão
		 */
		void closeConnection();
	}

	/**
	 * Monitoramento da conexão LSP. Verifica se está ativa. Este processo é
	 * feito através de callbacks definidos em uma instância de {@link Actions}.
	 */
	private final class StatusChecker implements Runnable {
		private final LspParams params;
		private final Actions actions;

		private StatusChecker(LspParams params, Actions actions) {
			this.params = params;
			this.actions = actions;
		}

		@Override
		public void run() {
			// Obtém o horário da última mensagem recebida em milisegundos
			long lastTime = lastMsgTime;

			// Obtém parâmetros da conexão
			int limit = params.getEpochLimit();
			final int epoch = params.getEpoch();

			// Monitora a conexão continuamente até que o limite de épocas seja
			// atingido ou a conexão seja fechada
			while (!closed && limit-- > 0) {
				sleep(epoch);

				// Dispara as ações da época
				actions.epochTriggers();

				// Reinicia contagem de épocas se houve mensagens recebidas
				// desde a última época
				final long time = lastMsgTime;
				if (time != lastTime) {
					lastTime = time;
					limit = params.getEpochLimit();
				}
			}

			// Encerra formalmente a conexão
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
