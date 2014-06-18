package lsp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

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
	 * @param id
	 *            Identificador da conexão
	 * @param sockAddr
	 *            Número IP e porta associados à conexão. Útil somente quando
	 *            {@link LspConnection} é instanciado pelo servidor.
	 * @param params
	 *            Parâmetros de temporização da conexão
	 * @param actions
	 *            Callbacks usados na verificação da conexão.
	 */
	LspConnection(short id, SocketAddress sockAddr, LspParams params, Actions actions) {
		if (params == null || actions == null)
			throw new NullPointerException("Nenhum parâmetro pode ser nulo");

		this.id = id;
		this.sockId = uniqueSockId(sockAddr);
		this.closed = false;
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
	LspConnection(short id, LspParams params, Actions actions) {
		this(id, null, params, actions);
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

	private long uniqueSockId(SocketAddress sockAddr) {
		if (sockAddr == null)
			return -1;

		final InetSocketAddress addr = (InetSocketAddress) sockAddr;
		final int ip = addr.getAddress().hashCode();
		final int port = addr.getPort();
		return (ip & 0xffff_ffffL) << 16 | (short) port;
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
