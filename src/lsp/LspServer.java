package lsp;

import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Servidor LSP.
 *
 * @author Wagner Macedo
 */
public class LspServer {
	/** Pool de conexões inicialmente com capacidade para 16 conexões */
	private final ConcurrentMap<Short, LspConnection> connectionPool = new ConcurrentHashMap<>(16);

	/**
	 * Pool de conexões rastreáveis pelo id do socket. Essa estrutura ajuda a
	 * garantir que haja somente uma conexão por socket remoto.
	 *
	 * @see LspConnection.uniqueSockId
	 */
	private final ConcurrentMap<Long, LspConnection> connectedSockets = new ConcurrentHashMap<>(16);

	/* Referências das filas de entrada e saída */
	private final BlockingQueue<InternalPack> inputQueue;
	private final BlockingQueue<InternalPack> outputQueue;

	// Variáveis de controle do servidor
	private final AtomicInteger idCounter = new AtomicInteger();
	private volatile boolean active = true;

	/* Parâmetros do servidor */
	private final LspParams params;

	/* Socket LSP */
	private final LspSocket lspSocket;

	public LspServer(int port, LspParams params) throws SocketException {
		this.lspSocket = new LspSocketImpl(port);
		this.params = params;

		this.inputQueue = lspSocket.inputQueue();
		this.outputQueue = lspSocket.outputQueue();
	}

	/**
	 * Lê dados da fila de entrada do servidor. Se não houver dados recebidos,
	 * bloqueia o chamador até que dados sejam recebidos. Os dados estão
	 * encapsulados pela classe Pack.
	 *
	 * @throws ClosedConnectionException
	 *             se o servidor não estiver ativo
	 * @throws IllegalStateException
	 *             se a fila de saída estiver cheia
	 */
	public Pack read() {
		checkActive();
		try {
			return inputQueue.take();
		} catch (InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}

	/**
	 * Envia dados para um determinado cliente.
	 *
	 * @throws ClosedConnectionException
	 *             se a conexão estiver encerrada
	 */
	public void write(Pack pack) {
		checkActive();
		final LspConnection conn = connectionPool.get(pack.getConnId());

		if (conn == null) {
			throw new ClosedConnectionException(pack.getConnId());
		}

		InternalPack p = new InternalPack(pack);
		if (!outputQueue.offer(p)) {
			throw new IllegalStateException("Fila de saída cheia");
		}
	}

	/**
	 * Encerra uma conexão com o identificador connId.
	 *
	 * Não é possível chamar read, write ou closeConn para a mesma connId depois
	 * de chamar esse método.
	 *
	 * @throws ClosedConnectionException
	 *             se a conexão estiver encerrada.
	 */
	public void closeConn(short connId) {
		/* FIXME: O encerramento da conexão deve ser em duas partes
		 *     1) Bloquear a conexão para recebimentos
		 *     2) Aguardar a fila de saída da conexão se esvaziar
		 */
		checkActive();

		// Encerra a conexão formalmente e remove da lista de conexões e do
		// conjunto de sockets.
		LspConnection conn = connectionPool.remove(connId);
		if (conn != null) {
			conn.close();
			connectedSockets.remove(conn.getSockId());
		} else {
			throw new ClosedConnectionException(connId);
		}
	}

	/**
	 * Encerra todas as conexões ativas e a atividade do servidor. Isso inclui o
	 * encerramento do processador de entradas.
	 */
	public void closeAll() {
		// Marca servidor como desligado
		this.active = false;

		// Fecha todas as conexões (em paralelo)
		for (final LspConnection conn : this.connectionPool.values()) {
			new Thread() {
				public void run() {
					conn.close();
				};
			}.start();
		}

		// Limpeza de memória
		this.connectionPool.clear();
		this.connectedSockets.clear();
	}

	private void checkActive() {
		if (!active)
			throw new ClosedConnectionException();
	}

	/**
	 * Socket LSP. Serve para as entradas e saídas do servidor.
	 */
	private final class LspSocketImpl extends LspSocket {
		LspSocketImpl(final int port) throws SocketException {
			super(port);
		}

		@Override
		boolean isActive() {
			return active;
		}

		@Override
		void receiveConnect(final SocketAddress sockAddr, final ByteBuffer buf) {
			// Somente serão aceitos pedidos de conexão bem formados, isto é,
			// aqueles em que Connection ID e Sequence Number são iguais a zero
			if (buf.getInt() == 0) {
				final long sockId = LspConnection.uniqueSockId(sockAddr);

				// A abertura de novas conexões é feita a seguir. A condição
				// garante não abrir nova conexão se esta já está aberta
				LspConnection conn = connectedSockets.get(sockId);
				if (conn == null) {
					final short newId = (short) idCounter.incrementAndGet();

					// Adicionando a conexão ao pool de conexão
					conn = new LspConnectionImpl(newId, sockId, sockAddr, params);
					connectionPool.put(newId, conn);
					connectedSockets.put(sockId, conn);
					sendAck(conn, (short) 0);
				}

				// Mesmo recebendo o pedido de conexão do mesmo socket remoto,
				// deve ser avisado que a conexão recebeu uma mensagem.
				else {
					conn.received();
				}
			}
		}

		LspConnection usedConnection(short connId) {
			return connectionPool.get(connId);
		}
	}

	private final class LspConnectionImpl extends LspConnection {
		LspConnectionImpl(short id, long sockId, SocketAddress sockAddr, LspParams params) {
			super(id, sockId, sockAddr, params);
		}

		@Override
		void callEpochTriggers() {
			resendData();
			resendAckConnect();
			resendAckData();
		}

		@Override
		void callCloseConnection() {
			closeConn(getId());
		}

		private void resendData() {
			InternalPack pack = this.sent();
			if (pack != null) {
				lspSocket.sendData(pack);
			}
		}

		private void resendAckConnect() {
			if (this.receivedTime() == -1) {
				lspSocket.sendAck(this, (short) 0);
			}
		}

		private void resendAckData() {
			short seqNum = this.receivedSeqNum();
			if (seqNum != -1) {
				lspSocket.sendAck(this, seqNum);
			}
		}
	}
}
