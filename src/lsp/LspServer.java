package lsp;

import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import lsp.helpers.ServiceHelper;

/**
 * Servidor LSP.
 *
 * @author Wagner Macedo
 */
public class LspServer {
	private final int port;
	private final LspParams params;

	/** Pool de conexões */
	private final ConcurrentMap<Short, LspConnection> connections;

	/**
	 * Conjunto de sockets conectados. Garante que haja apenas uma conexão por
	 * socket remoto
	 */
	private final ConcurrentMap<Long, LspConnection> connectedSockets;

	/** Capacidade das filas de entrada e saída em termos de pacotes de 1KB */
	private static final byte QUEUE_ZISE = 50;

	/* Filas de entrada e de saída */
	private final BlockingQueue<InternalPack> inputQueue;
	private final BlockingQueue<Pack> outputQueue;
	private final ServiceHelper service;

	// Variáveis de controle do servidor
	private AtomicInteger idCounter;
	private volatile boolean active;

	public LspServer(int port, LspParams params) {
		this.port = port;
		this.params = params;

		// Cria o pool de conexões, inicialmente com capacidade para 16 conexões
		this.connections = new ConcurrentHashMap<>(16);

		// Inicialização do conjunto de sockets
		this.connectedSockets = new ConcurrentHashMap<>(16);

		// Cria filas de entrada e saída
		this.inputQueue = new ArrayBlockingQueue<>(QUEUE_ZISE, true);
		this.outputQueue = new ArrayBlockingQueue<>(QUEUE_ZISE, true);

		// Inicialização das variáveis de controle
		this.idCounter = new AtomicInteger();
		this.active = true;

		// Inicia o serviço de entradas e saídas do servidor
		service = new Service(this.port);
		service.start();
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
		final LspConnection conn = connections.get(connId);
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
		LspConnection conn = connections.remove(connId);
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
		for (final LspConnection conn : this.connections.values()) {
			new Thread() {
				public void run() {
					conn.close();
				};
			}.start();
		}

		// Limpeza de memória
		this.connections.clear();
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
	private final class Service extends ServiceHelper {
		Service(final int port) {
			super(port);
		}

		@Override
		protected boolean isActive() {
			return active;
		}

		@Override
		protected void receiveConnect(final DatagramPacket pack, final ByteBuffer buf) {
			// Somente serão aceitos pedidos de conexão bem formados, isto é,
			// aqueles em que Connection ID e Sequence Number são iguais a zero
			if (buf.getInt() == 0) {
				final long sockId = uniqueSockId(pack.getSocketAddress());

				// A abertura de novas conexões é feita a seguir. A condição
				// garante não abrir nova conexão se esta já está aberta
				LspConnection conn = connectedSockets.get(sockId);
				if (conn == null) {
					final short newId = (short) idCounter.incrementAndGet();
					final ConnectionActionsImpl actions = new ConnectionActionsImpl();

					// Adicionando a conexão ao pool de conexão
					conn = new LspConnection(newId, sockId, params, actions);
					connections.put(newId, conn);
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
		protected void receiveData(final DatagramPacket pack, final ByteBuffer buf) {
			LspConnection conn = getConnection(pack, buf);
			if (conn != null) {
				conn.messageReceived();
				short seqNum = buf.getShort();
				byte[] payload = getPayload(buf);
				try {
					inputQueue.offer(new InternalPack(conn.getId(), seqNum,
							payload), 500, TimeUnit.MILLISECONDS);
					sendAck(conn.getId(), seqNum);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}

		@Override
		protected void receiveAck(final DatagramPacket pack, final ByteBuffer buf) {
			final LspConnection conn = getConnection(pack, buf);
			if (conn != null) {
				conn.messageReceived();
				// Se o número de sequência passado for a da mensagem aguardando
				// então remove-o da fila de espera
				final short seqNum = buf.getShort();
				conn.ackSentMessage(seqNum);
			}
		}

		private LspConnection getConnection(final DatagramPacket pack, final ByteBuffer buf) {
			final long sockId = uniqueSockId(pack.getSocketAddress());
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
				service.sendData(message);
			}
		}

		private void resendAckConnect() {
			if (conn.lastReceivedTime() == -1) {
				service.sendAck(conn.getId(), (short) 0);
			}
		}

		private void resendAckData() {
			// TODO Auto-generated method stub
		}

		private void setConnection(LspConnection conn) {
			this.conn = conn;
		}
	}
}
