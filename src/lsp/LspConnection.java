package lsp;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Representa uma conexão LSP.
 *
 * @author Wagner Macedo
 */
class LspConnection {
	private final short id;
	private final long sockId;

	private volatile boolean closed;
	private final AtomicInteger seqNumber;
	private volatile long lastMsgTime;

	/**
	 * Constrói um objeto {@link LspConnection}
	 *
	 * @param id
	 *            Identificador da conexão
	 * @param sockId
	 *            Número IP e porta associados à conexão. Útil somente quando
	 *            {@link LspConnection} é instanciado pelo servidor.
	 * @param params
	 *            Parâmetros de temporização da conexão
	 * @param actions
	 *            Callbacks usados na verificação da conexão.
	 */
	LspConnection(short id, long sockId, LspParams params, ConnectionActions actions) {
		if (params == null || actions == null)
			throw new NullPointerException("Nenhum parâmetro pode ser nulo");

		this.id = id;
		this.sockId = sockId;
		this.closed = false;
		this.seqNumber = new AtomicInteger();
		this.lastMsgTime = System.currentTimeMillis();

		// Inicia a thread para monitorar o status da conexão
		Runnable checker = new StatusChecker(params, actions);
		new Thread(checker).start();
	}

	/**
	 * Constrói um objeto {@link LspConnection}
	 *
	 * @param id
	 *            Identificador da conexão
	 * @param params
	 *            Parâmetros de temporização da conexão
	 * @param actions
	 *            Callbacks usados na verificação da conexão.
	 */
	LspConnection(short id, LspParams params, ConnectionActions actions) {
		this(id, -1, params, actions);
	}

	short getId() {
		return this.id;
	}

	/**
	 * Número único gerado a partir de um endereço IP e uma porta
	 *
	 * Esse atributo só é usado pelo servidor
	 */
	long getSockId() {
		return this.sockId;
	}

	/** Número de sequência para a próxima mensagem dessa conexão */
	short nextSeqNumber() {
		synchronized (seqNumber) {
			if (seqNumber.incrementAndGet() == 0) {
				seqNumber.incrementAndGet();
			}
			return seqNumber.shortValue();
		}
	}

	/**
	 * Decrementa o número de sequência. Esse método é útil para não perder um
	 * número em que houve falhas no processo. Para evitar números duplicados,
	 * ao usar, coloque o objeto {@link LspConnection} em um bloco synchronized.
	 */
	void decrementSeqNumber() {
		seqNumber.decrementAndGet();
	}

	long getLastMsgTime() {
		return lastMsgTime;
	}

	void setLastMsgTime(long lastMsgTime) {
		this.lastMsgTime = lastMsgTime;
	}

	void close() {
		this.closed = true;
	}

	/**
	 * Monitoramento da conexão LSP. Verifica se está ativa. Este processo é
	 * feito através de callbacks definidos em uma instância de {@link ConnectionActions}.
	 */
	private final class StatusChecker implements Runnable {
		private final LspParams params;
		private final ConnectionActions actions;

		private StatusChecker(LspParams params, ConnectionActions actions) {
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
