package lsp;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Representa uma conexão LSP.
 *
 * @author Wagner Macedo
 */
abstract class LspConnection {
	private final short id;
	private final long sockId;

	private volatile boolean closed;
	private volatile short seqNum;
	private volatile long receivedTime;
	private volatile short receivedSeqNum;
	private final Lock lock;

	private volatile InternalPack dataMessage;

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
	LspConnection(short id, long sockId, LspParams params) {
		if (params == null)
			throw new NullPointerException("Nenhum parâmetro pode ser nulo");

		this.id = id;
		this.sockId = sockId;
		this.closed = false;
		this.seqNum = 0;
		this.receivedTime = -1;
		this.receivedSeqNum = -1;
		this.lock = new ReentrantLock();

		// Inicia a thread para monitorar o status da conexão
		Runnable checker = new StatusChecker(params);
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
	LspConnection(short id, LspParams params) {
		this(id, -1, params);
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

	/** Obtém a última mensagem de dados enviada (aguardando ACK) */
	InternalPack sent() {
		return this.dataMessage;
	}

	/**
	 * Informa o payload da última mensagem enviada
	 *
	 * @return Pacote com um novo número de sequência ou null se já há um pacote
	 *         aguardando ACK
	 */
	InternalPack sent(Pack pack) {
		if (lock.tryLock()) {
			this.dataMessage = new InternalPack(pack, ++seqNum);
			return this.dataMessage;
		} else {
			return null;
		}
	}

	/** Informa que o ACK do número de sequência informado foi recebido */
	void ack(short seqNum) {
		// Atualiza o momento de recebimento
		received();

		// Marca dados como recebidos, se o número de sequência é igual ao atual
		if (this.seqNum == seqNum) {
			this.dataMessage = null;
			lock.unlock();
		}
	}

	/**
	 * Última vez que essa conexão recebeu uma mensagem. Esse tempo é gerenciado
	 * externamente através do método received. Se receber -1, quer dizer que
	 * não chegou nenhuma mensagem depois do pedido de conexão.
	 */
	long receivedTime() {
		return receivedTime;
	}

	/**
	 * Número de sequência da última mensagem DATA recebida por essa conexão.
	 * Esse número é gerenciado externamente através do método received(short).
	 * Se receber -1, quer dizer que não chegou nenhuma mensagem depois do
	 * pedido de conexão.
	 */
	short receivedSeqNum() {
		return receivedSeqNum;
	}

	/**
	 * Informa que houve uma mensagem recebida por essa conexão. Esse método
	 * tem como único propósito a atualização do momento de recebimento.
	 */
	void received() {
		this.receivedTime = System.currentTimeMillis();
	}

	/**
	 * Informa o número de sequência em que uma mensagem DATA foi recebida. Esse
	 * método atualiza o último momento de recebimento
	 */
	void received(short seqNum) {
		// Atualiza o momento de recebimento
		received();
		// Altera o número de sequência atual
		this.receivedSeqNum = seqNum;
	}

	void close() {
		this.closed = true;
	}

	/**
	 * Callback representando as ações a serem disparadas a cada época
	 */
	abstract void callEpochTriggers();

	/**
	 * Callback representando as ações de fechamento da conexão
	 */
	abstract void callCloseConnection();

	/**
	 * Monitoramento da conexão LSP. Verifica se está ativa. Este processo é
	 * feito através de callbacks definidos em uma instância de {@link ConnectionActions}.
	 */
	private final class StatusChecker implements Runnable {
		private final LspParams params;

		private StatusChecker(LspParams params) {
			this.params = params;
		}

		@Override
		public void run() {
			// Obtém o horário da última mensagem recebida em milisegundos
			long lastTime = receivedTime;

			// Obtém parâmetros da conexão
			int limit = params.getEpochLimit();
			final int epoch = params.getEpoch();

			// Monitora a conexão continuamente até que o limite de épocas seja
			// atingido ou a conexão seja fechada
			while (!closed && limit-- > 0) {
				sleep(epoch);

				// Dispara as ações da época
				callEpochTriggers();

				// Reinicia contagem de épocas se houve mensagens recebidas
				// desde a última época
				final long time = receivedTime;
				if (time != lastTime) {
					lastTime = time;
					limit = params.getEpochLimit();
				}
			}

			// Encerra formalmente a conexão
			callCloseConnection();
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
