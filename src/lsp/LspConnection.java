package lsp;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Representa uma conexão LSP.
 *
 * @author Wagner Macedo
 */
class LspConnection {
	private final short id;
	private final long sockId;

	private volatile boolean closed;
	private volatile short seqNumber;
	private volatile long lastReceiptTime;
	private final Lock lock;

	private volatile InternalPack message;

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
		this.seqNumber = 0;
		this.lastReceiptTime = -1;
		this.lock = new ReentrantLock();

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

	InternalPack getSentMessage() {
		return this.message;
	}

	boolean setSentMessage(byte[] payload) {
		if (lock.tryLock()) {
			this.message = new InternalPack(id, ++seqNumber, payload);
			return true;
		} else {
			return false;
		}
	}

	void ackSentMessage(short seqNumber) {
		if (this.seqNumber == seqNumber) {
			this.message = null;
			lock.unlock();
		}
	}

	long getLastReceiptTime() {
		return lastReceiptTime;
	}

	void setLastReceiptTime(long lastReceiptTime) {
		this.lastReceiptTime = lastReceiptTime;
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
			long lastTime = lastReceiptTime;

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
				final long time = lastReceiptTime;
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
