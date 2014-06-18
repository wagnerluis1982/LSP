package lsp;

import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import lsp.helpers.InputService;

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
	private final ConcurrentSkipListSet<Long> connectedSockets;

	// Variáveis de controle do servidor
	private AtomicInteger idCounter;
	private volatile boolean active;

	public LspServer(int port, LspParams params) {
		this.port = port;
		this.params = params;

		// Cria o pool de conexões, inicialmente com capacidade para 16 conexões
		this.connections = new ConcurrentHashMap<>(16);

		// Inicialização do conjunto de sockets
		this.connectedSockets = new ConcurrentSkipListSet<>();

		// Inicialização das variáveis de controle
		this.idCounter = new AtomicInteger();
		this.active = true;

		// Inicia o processador de entradas do servidor
		InputService inputSvc = new InputServiceImpl(this.port);
		inputSvc.start();
	}

	/**
	 * Lê dados da fila de entrada do servidor. Se não houver dados recebidos,
	 * bloqueia o chamador até que dados sejam recebidos. Os dados estão
	 * encapsulados pela classe Pack.
	 *
	 * @throws ClosedConnectionException
	 *             se a conexão estiver encerrada.
	 */
	public Pack read() {
		return null;
	}

	/**
	 * Envia dados para um determinado cliente.
	 *
	 * @throws ClosedConnectionException
	 *             se a conexão estiver encerrada.
	 */
	public void write(Pack pack) {

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
		// Encerra a conexão formalmente e remove da lista de conexões.
		LspConnection conn = this.connections.remove(connId);
		conn.close();

		// Remove a conexão do conjunto de sockets
		this.connectedSockets.remove(conn.getSockId());
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

	/**
	 * Processador de entradas do servidor.
	 */
	private final class InputServiceImpl extends InputService {
		InputServiceImpl(final int port) {
			super(port);
		}

		@Override
		protected boolean isActive() {
			return active;
		}

		@Override
		protected void processPacket(final DatagramPacket pack) {
			final ByteBuffer buf = ByteBuffer.wrap(pack.getData(), 0, pack.getLength());
			final short msgType = buf.getShort();

			switch (msgType) {
			case CONNECT:
				doConnect(pack, buf);
				break;
			case DATA:
				doData(pack, buf);
				break;
			case ACK:
				doAck(pack, buf);
				break;
			}
		}

		private void doConnect(final DatagramPacket pack, final ByteBuffer buf) {
			// Somente serão aceitos pedidos de conexão válidos, isto é, aqueles
			// em que Connection ID e Sequence Number são iguais a zero
			if (buf.getInt() == 0) {
				// A abertura de novas conexões é feita a seguir. A condição
				// garante não abrir nova conexão se esta já está aberta
				if (!connectedSockets.contains(pack.getSocketAddress())) {
					final short newId = (short) idCounter.incrementAndGet();
					final ConnectionActions actions = new ConnectionActionsImpl(newId);

					LspConnection conn = new LspConnection(newId,
							pack.getSocketAddress(), params, actions);
					connections.put(newId, conn);
					connectedSockets.add(conn.getSockId());
				}
			}
		}

		private void doData(final DatagramPacket pack, final ByteBuffer buf) {
			// TODO Auto-generated method stub
		}

		private void doAck(final DatagramPacket pack, final ByteBuffer buf) {
			// TODO Auto-generated method stub
		}
	}

	private final class ConnectionActionsImpl implements ConnectionActions {
		private final short newId;

		private ConnectionActionsImpl(short newId) {
			this.newId = newId;
		}

		@Override
		public void epochTriggers() {
			resendData();
			resendAckConnect();
			resendAckData();
		}

		@Override
		public void closeConnection() {
			closeConn(newId);
		}

		private void resendData() {
			// TODO Auto-generated method stub
		}

		private void resendAckConnect() {
			// TODO Auto-generated method stub
		}

		private void resendAckData() {
			// TODO Auto-generated method stub
		}
	}
}
