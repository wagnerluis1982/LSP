package lsp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;

import lsp.LspConnection.Actions;

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

	/** Conjunto de hosts conectados. Garante haver apenas uma conexão por host */
	private final ConcurrentSkipListSet<Integer> remoteHosts;

	// Variáveis de controle do servidor
	private AtomicInteger idCounter;
	private volatile boolean active;

	public LspServer(int port, LspParams params) {
		this.port = port;
		this.params = params;

		// Cria o pool de conexões, inicialmente com capacidade para 16 conexões
		this.connections = new ConcurrentHashMap<>(16);

		// Inicialização dos conjuntos de hosts
		this.remoteHosts = new ConcurrentSkipListSet<>();

		// Inicialização das variáveis de controle
		this.idCounter = new AtomicInteger();
		this.active = true;

		// Inicia o processador de entradas do servidor
		InputService inputSvc = new InputService();
		new Thread(inputSvc).start();
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
	public void closeConn(int connId) {
		// Encerra a conexão formalmente e remove da lista de conexões.
		LspConnection conn = this.connections.remove(connId);
		conn.close();

		// Remove o IP do conjunto de hosts
		this.remoteHosts.remove(conn.getHost());
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
		this.remoteHosts.clear();
	}

	/**
	 * Processador de entradas do servidor.
	 */
	private final class InputService implements Runnable {
		private static final char CONNECT = 0;
		private static final char DATA = 1;
		private static final char ACK = 2;

		public void run() {
			// Abre um socket UDP vinculado à porta solicitada
			DatagramSocket socket = null;
			try {
				socket = new DatagramSocket(port);
			} catch (SocketException e) {
				e.printStackTrace();
			}

			// Configuração do pacote de entrada
			byte[] bs = new byte[1000];
			DatagramPacket packet = new DatagramPacket(bs, bs.length);

			// Recebe pacotes até o servidor ser encerrado
			while (active) {
				try {
					socket.receive(packet);
					processPacket(packet);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		private void processPacket(final DatagramPacket pack) {
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
			if (buf.getShort() == 0 && buf.getShort() == 0){
				// Número IP em formato decimal
				final int host = pack.getAddress().hashCode();

				// A abertura de novas conexões é feita a seguir. A condição
				// garante não abrir mais de uma conexão para o mesmo host.
				if (!remoteHosts.contains(host)) {
					final short newId = (short) idCounter.incrementAndGet();
					final Actions actions = new ConnectionActions(newId);
					connections.put(newId, new LspConnection(newId, host, params, actions));
					remoteHosts.add(host);
				}
			}
		}

		private void doData(final DatagramPacket pack, final ByteBuffer buf) {

		}

		private void doAck(final DatagramPacket pack, final ByteBuffer buf) {

		}
	}

	private final class ConnectionActions implements Actions {
		private final short newId;

		private ConnectionActions(short newId) {
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
