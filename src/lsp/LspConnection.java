package lsp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Representa uma conexão LSP.
 *
 * @author Wagner Macedo
 */
class LspConnection {
	private final short id;
	private final long sockId;
	private final ConnectionTriggers triggers;

	private volatile boolean closed;
	private volatile boolean markClosed;
	private volatile short seqNum;
	private volatile long receivedTime;
	private volatile short receivedSeqNum;
	private final AtomicInteger sendMissing;
	private final Object lock = new Object();

	private volatile InternalPack dataMessage;
	private final SocketAddress sockAddr;
	private final Thread statusThread;

	/**
	 * Constrói um objeto {@link LspConnection}
	 *
	 * @param id
	 *            Identificador da conexão
	 * @param sockId
	 *            Número IP e porta associados à conexão
	 * @param sockAddr
	 *            IP e porta vinculados à essa conexão
	 * @param params
	 *            Parâmetros de temporização da conexão
	 */
	LspConnection(short id, long sockId, SocketAddress sockAddr, LspParams params, ConnectionTriggers triggers) {
		if (sockAddr == null || params == null)
			throw new NullPointerException("Nenhum parâmetro pode ser nulo");

		this.id = id;
		this.sockId = sockId;
		this.sockAddr = sockAddr;
		this.triggers = triggers;
		this.closed = false;
		this.seqNum = 0;
		this.receivedTime = -1;
		this.receivedSeqNum = -1;
		this.sendMissing = new AtomicInteger(0);

		this.statusThread = new Thread(new StatusChecker(params));
		this.statusThread.start();
	}

	/**
	 * Constrói um objeto {@link LspConnection}
	 *
	 * @param id
	 *            Identificador da conexão
	 * @param sockAddr
	 *            IP e porta vinculados à essa conexão
	 * @param params
	 *            Parâmetros de temporização da conexão
	 */
	LspConnection(short id, SocketAddress sockAddr, LspParams params, ConnectionTriggers triggers) {
		this(id, uniqueSockId(sockAddr), sockAddr, params, triggers);
	}

	short getId() {
		return this.id;
	}

	/**
	 * Número único gerado a partir de um endereço IP e uma porta
	 *
	 * Esse método só é usado pelo servidor
	 */
	static long uniqueSockId(SocketAddress sockAddr) {
		final InetSocketAddress addr = (InetSocketAddress) sockAddr;
		final int ip = addr.getAddress().hashCode();
		final int port = addr.getPort();
		return (ip & 0xffff_ffffL) << 16 | (short) port;
	}

	/**
	 * Número único gerado a partir de um endereço IP e uma porta
	 *
	 * Esse atributo só é usado pelo servidor
	 */
	long getSockId() {
		return this.sockId;
	}

	SocketAddress getSockAddr() {
		return this.sockAddr;
	}

	/**
	 * Aumenta em um o número de mensagens na fila, mas faltam enviar. Isso é
	 * controlado externamente.
	 */
	void incSendMissing() {
		this.sendMissing.incrementAndGet();
	}

	/**
	 * Número de mensagens na fila, mas faltam enviar. Valor controlado
	 * externamente.
	 */
	int getSendMissing() {
		return this.sendMissing.intValue();
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
	boolean sent(InternalPack pack) {
		synchronized (lock) {
			if (this.dataMessage == null) {
				pack.setConnection(this);
				pack.setSeqNum(++seqNum);
				this.dataMessage = pack;
				return true;
			}
		}

		return false;
	}

	/** Informa que o ACK do número de sequência informado foi recebido */
	void ack(short seqNum) {
		// Atualiza o momento de recebimento
		received();

		synchronized (lock) {
			// Marca dados como recebidos, se o número de sequência é igual ao atual
			if (this.seqNum == seqNum) {
				this.dataMessage = null;
			}

			// Diminuição da quantidade de mensagens faltando entregar.
			this.sendMissing.decrementAndGet();
		}
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

	boolean isInterrupted() {
		return this.closed;
	}

	boolean isClosed() {
		return this.closed || this.markClosed;
	}

	void close() {
		close(true);
	}

	void close(boolean interrupt) {
		if (interrupt) {
			this.statusThread.interrupt();
			this.closed = true;
		} else {
			this.markClosed = true;
		}
	}

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
				try {
					Thread.sleep(epoch);
				} catch (InterruptedException e) {
					return;
				}

				// Dispara as ações da época
				triggers.doEpochActions();

				// Reinicia contagem de épocas se houve mensagens recebidas
				// desde a última época
				final long time = receivedTime;
				if (time != lastTime) {
					lastTime = time;
					limit = params.getEpochLimit();
				}
			}

			// Encerra formalmente a conexão
			triggers.doCloseConnection();
		}
	}
}
