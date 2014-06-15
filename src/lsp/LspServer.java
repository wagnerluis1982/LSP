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

import static lsp.LspConnection.Actions;

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
		InputProcessor inputProc = new InputProcessor();
		new Thread(inputProc).start();
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
		LspConnection conn = connections.remove(connId);
		conn.close();

		// Remove o IP da lista de conexões
		remoteHosts.remove(conn.host);
	}

	/**
	 * Encerra todas as conexões ativas e a atividade do servidor. Isso inclui o
	 * encerramento do processador de entradas.
	 */
	public void closeAll() {
		connections.clear();
		this.active = false;
	}

	/**
	 * Processador de entradas do servidor.
	 */
	private final class InputProcessor implements Runnable {
		private static final char CONNECT = 0;
		private static final char DATA = 1;
		private static final char ACK = 2;

		public void run() {
			DatagramSocket socket = null;
			try {
				socket = new DatagramSocket(port);
			} catch (SocketException e) {
				e.printStackTrace();
			}

			while (active) {
				try {
					DatagramPacket packet = new DatagramPacket(new byte[1000], 1000);
					socket.receive(packet);
					processPacket(packet);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		private void processPacket(DatagramPacket p) {
			ByteBuffer buf = ByteBuffer.wrap(p.getData());
			short msgType = buf.getShort();

			switch (msgType) {
			case CONNECT:
				if (buf.getShort() == 0 && buf.getShort() == 0) {
					int host = p.getAddress().hashCode();
					if (!remoteHosts.contains(host)) {
						final short newId = (short) idCounter.incrementAndGet();
						final ServerActions actions = new ServerActions(newId);
						connections.put(newId, new LspConnection(newId, host, params, actions));
						remoteHosts.add(host);
					}
				}
				break;
			case DATA:
				break;
			case ACK:
				break;
			}
		}
	}

	private final class ServerActions implements Actions {
		private final short newId;

		ServerActions(short newId) {
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
