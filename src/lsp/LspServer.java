package lsp;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
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

	// Variáveis de controle do servidor
	private final AtomicInteger idCounter = new AtomicInteger();
	private volatile boolean active = true;

	/* Parâmetros do servidor */
	private final LspParams params;

	/* Socket LSP */
	private final LspSocket lspSocket;
	private final int port;

	public LspServer(int port, LspParams params) throws IOException {
		this.lspSocket = new LspSocketImpl(port);
		this.port = this.lspSocket.getPort();
		this.params = params;
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
		return lspSocket.receive();
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
		lspSocket.send(p);
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

		// Fecha socket lsp
		this.lspSocket.close();

		// Limpeza de memória
		this.connectionPool.clear();
		this.connectedSockets.clear();
	}

	private void checkActive() {
		if (!active)
			throw new ClosedConnectionException();
	}

	public int getPort() {
		return this.port;
	}

	private final class LspSocketImpl extends LspSocket {
		LspSocketImpl(final int port) throws IOException {
			super(port);
		}

		@Override
		boolean isActive() {
			return active;
		}

		/*
		 * TODO: Considerando que pode haver buracos no contador de ID devido a
		 * fechamentos da conexão, é preciso solucionar isso verificando antes
		 * se a posição já existe.
		 */
		@Override
		void dgramReceiveConnect(final SocketAddress sockAddr, final ByteBuffer buf) {
			// Somente serão aceitos pedidos de conexão bem formados, isto é,
			// aqueles em que Connection ID e Sequence Number são iguais a zero
			if (buf.getInt() == 0) {
				final long sockId = LspConnection.uniqueSockId(sockAddr);

				// A abertura de novas conexões é feita a seguir. A condição
				// garante não abrir nova conexão se esta já está aberta
				LspConnection conn = connectedSockets.get(sockId);
				if (conn == null) {
					final short newId = (short) idCounter.incrementAndGet();
					ServerTriggers triggers = new ServerTriggers();

					// Adicionando a conexão ao pool de conexão
					conn = new LspConnection(newId, sockId, sockAddr, params, triggers);
					connectionPool.put(newId, conn);
					connectedSockets.put(sockId, conn);
					dgramSendAck(conn, (short) 0);

					// Adicionando referência da conexão associada a triggers
					triggers.bindedConn = conn;
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

	private final class ServerTriggers implements ConnectionTriggers {
		public LspConnection bindedConn;

		@Override
		public void doEpochActions() {
			resendData();
			resendAck();
		}

		@Override
		public void doCloseConnection() {
			closeConn(bindedConn.getId());
		}

		private void resendData() {
			InternalPack pack = bindedConn.sent();
			if (pack != null) {
				lspSocket.dgramSendData(pack);
			}
		}

		/*
		 * Se foi recebida alguma mensagem de dados, então envia o ACK dessa
		 * mensagem, senão envia envia ACK(seqNum=0)
		 */
		private void resendAck() {
			short seqNum = bindedConn.receivedSeqNum();
			if (seqNum != -1) {
				lspSocket.dgramSendAck(bindedConn, seqNum);
			} else {
				lspSocket.dgramSendAck(bindedConn, (short) 0);
			}
		}
	}
}
