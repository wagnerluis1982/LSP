package lsp;

class LspConnection {
	private final int id;

	LspConnection(int id, LspParams params, Actions actions) {
		this.id = id;

		// Inicia thread que verifica status da conexão
		new Thread(new StatusChecker(params, actions)).start();
	}

	int getId() {
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
	 * Verifica e fecha a conexão LSP, ambos através de {@link Actions}.
	 *
	 * @author Wagner Macedo
	 */
	private class StatusChecker implements Runnable {
		private final Actions delegate;
		private final LspParams params;

		public StatusChecker(LspParams params, Actions delegate) {
			this.params = params;
			this.delegate = delegate;
		}

		@Override
		public void run() {
			long lastTime = delegate.lastReceiptTime();
			int limit = params.getEpochLimit();
			final int epoch = params.getEpoch();

			while (limit-- > 0) {
				try {
					Thread.sleep(epoch);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
				final long time = delegate.lastReceiptTime();
				if (time != lastTime) {
					lastTime = time;
					limit = params.getEpochLimit();
				}
			}
			delegate.closeConnection();
		}
	}
}
