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
	private volatile boolean markClosed;

	/* Parâmetros do servidor */
	private final LspParams params;

	/* Socket LSP */
	private final LspSocket lspSocket;
	private final int port;

	public LspServer(int port, LspParams params) throws IOException {
		this.lspSocket = new LspSocketImpl(port);
		this.port = this.lspSocket.getPort();
		this.params = params == null ? LspParams.defaultParams() : params;
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
		if (conn == null || conn.isClosed()) {
			throw new ClosedConnectionException(pack.getConnId());
		}

		lspSocket.send(pack);
		conn.incSendMissing();
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
		checkActive();

		final LspConnection conn = connectionPool.get(connId);
		if (conn == null) {
			throw new ClosedConnectionException(connId);
		}

		// Marca a conexão como fechada e se não há mensagens para serem
		// enviadas, encerra a conexão formalmente e remove da lista de conexões
		// e do conjunto de sockets.
		conn.close(false);
		if (conn.getSendMissing() == 0) {
			realCloseConn(connId, conn);
			return;
		}

		while (!conn.isInterrupted()) {
			try {
				Thread.sleep(params.getEpoch());
			} catch (InterruptedException e) {
				return;
			}
		}
	}

	private void realCloseConn(short connId, final LspConnection conn) {
		conn.close();
		connectionPool.remove(connId);
		connectedSockets.remove(conn.getSockId());
	}

	/**
	 * Encerra todas as conexões ativas e a atividade do servidor. Isso inclui o
	 * encerramento do processador de entradas.
	 */
	public void closeAll() {
		// Marca servidor como fechado para novas entradas
		this.markClosed = true;

		// Marca todas as conexões como fechadas (em paralelo)
		for (final LspConnection conn : connectionPool.values()) {
			new Thread() {
				public void run() {
					conn.close(false);
				};
			}.start();
		}

		// Aguarda o pool de conexão se esvaziar
		while (!connectionPool.isEmpty()) {
			try {
				Thread.sleep(params.getEpoch());
			} catch (InterruptedException e) {
				break;
			}
		}

		// Marca servidor como inativo e fecha socket lsp
		this.active = false;
		this.lspSocket.close();

		// Limpeza de memória
		this.connectionPool.clear();
		this.connectedSockets.clear();
	}

	private void checkActive() {
		if (!active || markClosed)
			throw new ClosedConnectionException();
	}

	public int getPort() {
		return this.port;
	}

	private Short newConnId() {
		synchronized (idCounter) {
			// Se a quantidade de conexões já é o máximo suportado não vale a
			// pena pesquisar por um id livre
			if (connectionPool.size() == 65535) {
				return null;
			}

			// Pesquisa por um id livre
			while (true) {
				final short id = (short) idCounter.incrementAndGet();
				if (id == 0) {
					continue;
				}
				if (!connectionPool.containsKey(id)) {
					return id;
				}
			}
		}
	}

	private final class LspSocketImpl extends LspSocket {
		LspSocketImpl(final int port) throws IOException {
			super(port);
		}

		@Override
		boolean isActive() {
			return active;
		}

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
					// Verifica se há espaço no pool para mais conexões
					final Short newId = newConnId();
					if (newId == null) {
						return;
					}

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
			Helpers.resendData(lspSocket, bindedConn);
			Helpers.resendAck(lspSocket, bindedConn);
		}

		@Override
		public void doCloseConnection() {
			realCloseConn(bindedConn.getId(), bindedConn);
		}
	}
}
