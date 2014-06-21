package lsp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serviço de entrada e saída de pacotes. Classe abstrata.
 *
 * @author Wagner Macedo
 */
abstract class LspSocket {
	protected static final byte CONNECT = 0;
	protected static final byte DATA = 1;
	protected static final byte ACK = 2;

	private static final byte[] PAYLOAD_NIL = new byte[0];

	/** Capacidade das filas de entrada e saída em termos de pacotes de 1KB */
	private static final byte QUEUE_ZISE = 50;

	/* Filas de entrada e saída */
	private final BlockingQueue<InternalPack> inputQueue;
	private final BlockingDeque<InternalPack> outputQueue;

	private final Object sendLock = new Object();

	/* Usados nos pedidos de conexão partindo desse socket */
	private volatile short reqConnId;
	private volatile SocketAddress reqAddr;
	private volatile Condition connAck;
	private final Lock connLock = new ReentrantLock();

	private final DatagramSocket socket;

	/**
	 * Inicia um LspSocket
	 *
	 * @param port Porta onde o socket estará vinculado
	 * @throws SocketException
	 */
	LspSocket(int port) throws SocketException {
		this(port, QUEUE_ZISE);
	}

	/**
	 * Inicia um LspSocket
	 *
	 * @param port Porta onde o socket estará vinculado
	 * @param queueSize Tamanho inicial das filas de entrada e saída
	 * @throws SocketException
	 */
	LspSocket(int port, int queueSize) throws SocketException {
		this.socket = new DatagramSocket(port);
		this.inputQueue = new LinkedBlockingQueue<>(queueSize);
		this.outputQueue = new LinkedBlockingDeque<>(queueSize);

		// Inicia as threads
		new Thread(new InputTask()).start();
		new Thread(new OutputTask()).start();
	}

	/**
	 * Indica até quando o serviço será executado. Esse método deve ser
	 * implementado em uma subclasse.
	 *
	 * @return false para parar o serviço
	 */
	abstract boolean isActive();

	/**
	 * Obtém o objeto {@link LspConnection} em uso
	 *
	 * @param connId Id de conexão para validar/pesquisar
	 * @return Uma instância de {@link LspConnection} ou null
	 */
	abstract LspConnection usedConnection(short connId);

	final short connect(SocketAddress sockAddr) {
		synchronized (connLock) {
			send(sockAddr, CONNECT, (short) 0, (short) 0, PAYLOAD_NIL);

			// Configura objetos compartilhados
			this.reqAddr = sockAddr;
			this.connAck = connLock.newCondition();

			// Aguarda pela resposta da conexão
			this.connAck.awaitUninterruptibly();
			return this.reqConnId;
		}
	}

	private final void connectAck(SocketAddress sockAddr, short connId) {
		if (connAck != null && reqAddr.equals(sockAddr)) {
			this.reqConnId = connId;
			this.connAck.signal();
			this.connAck = null;
		}
	}

	/**
	 * Processamento de cada pacote UDP recebido.
	 *
	 * @param pack Pacote enviado pelo serviço
	 * @throws IOException
	 */
	private void receive(final DatagramPacket pack) throws IOException {
		this.socket.receive(pack);
		final ByteBuffer buf = ByteBuffer.wrap(pack.getData(), 0,
				pack.getLength()).asReadOnlyBuffer();
		final short msgType = buf.getShort();

		switch (msgType) {
		case CONNECT:
			receiveConnect(pack.getSocketAddress(), buf.slice());
			break;
		case DATA:
			receiveData(pack.getSocketAddress(), buf.slice());
			break;
		case ACK:
			receiveAck(pack.getSocketAddress(), buf.slice());
			break;
		}
	}

	/**
	 * Tratamento de um pacote do tipo CONNECT recebido
	 *
	 * Esse método deve ser sobrescrito pelo servidor
	 */
	void receiveConnect(final SocketAddress sockAddr, final ByteBuffer buf) {
	}

	/** Tratamento de um pacote do tipo DATA recebido */
	void receiveData(final SocketAddress sockAddr, final ByteBuffer buf) {
		LspConnection conn = usedConnection(sockAddr, buf.getShort());
		if (conn != null) {
			short seqNum = buf.getShort();
			byte[] payload = payload(buf);
			InternalPack pack = new InternalPack(conn, seqNum, payload);

			// Se a mensagem foi enfileirada, envia o ACK e informa o número
			// de sequência à conexão (usado nos disparos da época).
			if (inputQueue.offer(pack)) {
				sendAck(pack);
				conn.received(seqNum);
			}

			// Caso contrário, mesmo que a mensagem não possa ser lida,
			// atualiza o momento da última mensagem recebida
			else {
				conn.received();
			}
		}
	}

	/** Tratamento de um pacote do tipo ACK recebido */
	void receiveAck(final SocketAddress sockAddr, final ByteBuffer buf) {
		final short connId = buf.getShort();
		final LspConnection conn = usedConnection(sockAddr, connId);

		// Se o connId é válido, reconhece a mensage
		if (conn != null) {
			final short seqNum = buf.getShort();
			conn.ack(seqNum);
		}

		// Senão, verifica se o número de sequência é 0. Caso positivo, deve se
		// tratar de uma requisição de conexão efetuada por esse socket.
		else if (buf.getShort() == 0) {
			connectAck(sockAddr, connId);
		}
	}

	private void send(final SocketAddress sockAddr, final short msgType,
			final short connId, final short seqNum, final byte[] payload) {
		ByteBuffer buf = ByteBuffer.allocate(6 + payload.length);
		buf.putShort(msgType).putShort(connId).putShort(seqNum).put(payload);

		DatagramPacket packet = new DatagramPacket(buf.array(), buf.capacity());
		packet.setSocketAddress(sockAddr);
		try {
			synchronized (sendLock) {
				socket.send(packet);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void send(final short msgType, final LspConnection conn, final short seqNum, final byte[] payload) {
		send(conn.getSockAddr(), msgType, conn.getId(), seqNum, payload);
	}

	final void sendData(final LspConnection conn, final short seqNum, final byte[] payload) {
		send(DATA, conn, seqNum, payload);
	}

	final void sendData(final InternalPack p) {
		sendData(p.getConnection(), p.getSeqNum(), p.getPayload());
	}

	final void sendAck(final LspConnection conn, final short seqNum) {
		send(ACK, conn, seqNum, PAYLOAD_NIL);
	}

	final void sendAck(final InternalPack p) {
		sendAck(p.getConnection(), p.getSeqNum());
	}

	private void sendNextData() throws InterruptedException {
		// Obtém o próxima pacote de dados da fila
		final InternalPack p = outputQueue.take();

		// Se já houver uma conexão associada ao pacote, envia-o e encerra
		if (p.getConnection() != null) {
			sendData(p);
			return;
		}

		// Se o id de conexão é diferente da conexão em uso, encerra
		LspConnection conn = usedConnection(p.getConnId());
		if (conn == null) {
			return;
		}

		// Tenta associar o pacote à conexão. Se sucesso, envia esse pacote
		if (conn.sent(p)) {
			sendData(p);
			return;
		}

		// Se não foi possível associar à conexão (já havia outro pacote em
		// espera de um ACK) então devolve o pacote à fila (segunda posição)
		synchronized (outputQueue) {
			InternalPack first = outputQueue.poll();
			outputQueue.offerFirst(p);
			if (first != null) {
				outputQueue.offerFirst(first);
			}
		}
	}

	/** Helper para obter um array de bytes com o resto do {@link ByteBuffer} */
	static final byte[] payload(final ByteBuffer buf) {
		byte[] bs = new byte[buf.remaining()];
		for (int i = 0; i < bs.length; i++) {
			bs[i] = buf.get();
		}

		return bs;
	}

	private LspConnection usedConnection(final SocketAddress sockAddr, final short connId) {
		final LspConnection conn = usedConnection(connId);

		// Descarta o pacote se não há conexão aberta com o remetente ou se
		// o id recebido não corresponde ao id registrado com a conexão.
		if (conn != null && conn.getId() == connId
				&& sockAddr.equals(conn.getSockAddr())) {
			return conn;
		}

		return null;
	}

	/** Obtém a fila de entrada do socket */
	BlockingQueue<InternalPack> inputQueue() {
		return this.inputQueue;
	}

	/** Obtém a fila de saída do socket */
	BlockingDeque<InternalPack> outputQueue() {
		return this.outputQueue;
	}

	private final class InputTask implements Runnable {
		@Override
		public void run() {
			// Configuração do pacote de entrada
			byte[] bs = new byte[1024];
			DatagramPacket pack = new DatagramPacket(bs, bs.length);

			// Recebe pacotes até o servidor ser encerrado
			while (isActive()) {
				try {
					receive(pack);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}

	private final class OutputTask implements Runnable {
		@Override
		public void run() {
			// Envia pacotes até o servidor ser encerrado
			while (isActive()) {
				try {
					sendNextData();
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
