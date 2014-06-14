package lsp;

public class LspConnection {
	private final int id;

	public LspConnection(int id, LspParams params, Delegate delegate) {
		this.id = id;

		// Inicia thread que verifica status da conexão
		new Thread(new StatusChecker(params, delegate)).start();
	}

	public int getId() {
		return this.id;
	}

	/**
	 * Verifica e fecha a conexão LSP, ambos através de {@link Delegate}.
	 *
	 * @author Wagner Macedo
	 */
	private class StatusChecker implements Runnable {
		private final Delegate delegate;
		private final LspParams params;

		public StatusChecker(LspParams params, Delegate delegate) {
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
