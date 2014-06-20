package lsp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
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
	 * @see uniqueSockId
	 */
	private final ConcurrentMap<Long, LspConnection> connectedSockets = new ConcurrentHashMap<>(16);

	/** Capacidade das filas de entrada e saída em termos de pacotes de 1KB */
	private static final byte QUEUE_ZISE = 50;

	/* Filas de entrada e saída */
	private final BlockingQueue<InternalPack> inputQueue = new ArrayBlockingQueue<>(QUEUE_ZISE, true);;
	private final BlockingQueue<Pack> outputQueue = new ArrayBlockingQueue<>(QUEUE_ZISE, true);

	// Variáveis de controle do servidor
	private final AtomicInteger idCounter = new AtomicInteger();
	private volatile boolean active = true;

	/* Parâmetros do servidor */
	private final LspParams params;

	/* Socket LSP */
	private final LspSocket lspSocket;

	public LspServer(int port, LspParams params) {
		this.lspSocket = new LspSocketImpl(port);
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
		final short connId = pack.getConnId();
		final LspConnection conn = connectionPool.get(connId);
		if (conn != null) {
			boolean success = false;
			try {
				success = outputQueue.offer(pack, 500, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			if (!success) {
				throw new IllegalStateException("Fila de saída cheia");
			}
		}
		throw new ClosedConnectionException(connId);
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
		}
		throw new ClosedConnectionException(connId);
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
	 * Número único gerado a partir de um endereço IP e uma porta
	 *
	 * Esse atributo só é usado pelo servidor
	 */
	private static long uniqueSockId(SocketAddress sockAddr) {
		final InetSocketAddress addr = (InetSocketAddress) sockAddr;
		final int ip = addr.getAddress().hashCode();
		final int port = addr.getPort();
		return (ip & 0xffff_ffffL) << 16 | (short) port;
	}

	/**
	 * Serviço de entrada e saída do servidor.
	 */
	private final class LspSocketImpl extends LspSocket {
		LspSocketImpl(final int port) {
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
				final long sockId = uniqueSockId(sockAddr);

				// A abertura de novas conexões é feita a seguir. A condição
				// garante não abrir nova conexão se esta já está aberta
				LspConnection conn = connectedSockets.get(sockId);
				if (conn == null) {
					final short newId = (short) idCounter.incrementAndGet();
					final ConnectionActionsImpl actions = new ConnectionActionsImpl();

					// Adicionando a conexão ao pool de conexão
					conn = new LspConnection(newId, sockId, params, actions);
					connectionPool.put(newId, conn);
					connectedSockets.put(sockId, conn);
					sendAck(newId, (short) 0);

					// Anexando conexão ao objeto actions
					actions.setConnection(conn);
				} else {
					conn.messageReceived();
				}
			}
		}

		@Override
		void receiveData(final SocketAddress sockAddr, final ByteBuffer buf) {
			LspConnection conn = getConnection(sockAddr, buf);
			if (conn != null) {
				conn.messageReceived();
				short seqNum = buf.getShort();
				byte[] payload = getPayload(buf);
				try {
					inputQueue.offer(new InternalPack(conn.getId(), seqNum,
							payload), 500, TimeUnit.MILLISECONDS);
					sendAck(conn.getId(), seqNum);
					conn.dataReceived(seqNum);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		void receiveAck(final SocketAddress sockAddr, final ByteBuffer buf) {
			final LspConnection conn = getConnection(sockAddr, buf);
			if (conn != null) {
				conn.messageReceived();
				// Se o número de sequência passado for a da mensagem aguardando
				// então remove-o da fila de espera
				final short seqNum = buf.getShort();
				conn.ackSentMessage(seqNum);
			}
		}

		private LspConnection getConnection(final SocketAddress sockAddr, final ByteBuffer buf) {
			final long sockId = uniqueSockId(sockAddr);
			final LspConnection conn = connectedSockets.get(sockId);

			// Descarta o pacote se não há conexão aberta com o remetente ou se
			// o id recebido não corresponde ao id registrado com esse socket.
			if (conn != null && conn.getId() != buf.getShort()) {
				return conn;
			}

			return null;
		}
	}

	private final class ConnectionActionsImpl implements ConnectionActions {
		private LspConnection conn;

		@Override
		public void epochTriggers() {
			resendData();
			resendAckConnect();
			resendAckData();
		}

		@Override
		public void closeConnection() {
			closeConn(conn.getId());
		}

		private void resendData() {
			InternalPack message = conn.getSentMessage();
			if (message != null) {
				lspSocket.sendData(message);
			}
		}

		private void resendAckConnect() {
			if (conn.lastReceivedTime() == -1) {
				lspSocket.sendAck(conn.getId(), (short) 0);
			}
		}

		private void resendAckData() {
			if (conn.lastReceivedSequence() != -1) {
				lspSocket.sendAck(conn.getId(), conn.lastReceivedSequence());
			}
		}

		private void setConnection(LspConnection conn) {
			this.conn = conn;
		}
	}
}
