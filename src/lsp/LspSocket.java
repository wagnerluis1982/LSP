package lsp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

	/* Lock para garantir que apenas uma thread envie pacotes */
	private final Object sendLock = new Object();

	/* Usados no pedido de conexão a um servidor LSP partindo desse socket */
	private final ExecutorService executorService;
	private ConnectTask connTask;
	private final Lock connLock;

	/** Socket de comunicação em uso */
	private final DatagramSocket socket;
	private final int port;

	/**
	 * Inicia um LspSocket
	 *
	 * @param port Porta onde o socket estará vinculado
	 * @param queueSize Tamanho inicial das filas de entrada e saída
	 * @throws SocketException
	 */
	LspSocket(int port, int queueSize) throws IOException {
		// Cria o socket e as filas
		this.socket = new DatagramSocket(port);
		this.port = this.socket.getLocalPort();
		this.inputQueue = new LinkedBlockingQueue<>(queueSize);
		this.outputQueue = new LinkedBlockingDeque<>(queueSize);

		// Inicializa atributos para uso na conexão a um servidor LSP
		this.executorService = Executors.newSingleThreadExecutor();
		this.connLock = new ReentrantLock();

		// Inicia as threads
		new Thread(new InputTask()).start();
		new Thread(new OutputTask()).start();
	}

	/**
	 * Inicia um LspSocket
	 *
	 * @param port Porta onde o socket estará vinculado
	 * @throws SocketException
	 */
	LspSocket(int port) throws IOException {
		this(port, QUEUE_ZISE);
	}

	/**
	 * Indica até quando o serviço será executado. Esse método deve ser
	 * implementado em uma subclasse.
	 *
	 * @return false para parar o serviço
	 */
	abstract boolean isActive();

	final short connect(SocketAddress sockAddr, long timeout) throws TimeoutException {
		synchronized (connLock) {
			// Inicia uma nova tarefa de conexão
			connTask = new ConnectTask(sockAddr, connLock.newCondition());
			Future<Short> call = executorService.submit(connTask);

			try {
				return call.get(timeout, TimeUnit.MILLISECONDS);
			} catch (InterruptedException | ExecutionException e) {
				connTask.ackCondition.signal();  // libera a thread
				throw new RuntimeException(e);
			} finally {
				connTask = null;
			}
		}
	}

	/** Estabelece conexão com um servidor LSP aguardando até 10 segundos */
	final short connect(SocketAddress sockAddr) throws TimeoutException {
		return connect(sockAddr, 10000);
	}

	/**
	 * Processamento de cada pacote UDP recebido.
	 *
	 * @param pack Pacote enviado pelo serviço
	 * @throws IOException
	 */
	private void dgramReceive(final DatagramPacket pack) throws IOException {
		this.socket.receive(pack);
		final ByteBuffer buf = ByteBuffer.wrap(pack.getData(), 0,
				pack.getLength()).asReadOnlyBuffer();
		final short msgType = buf.getShort();

		switch (msgType) {
		case CONNECT:
			dgramReceiveConnect(pack.getSocketAddress(), buf.slice());
			break;
		case DATA:
			dgramReceiveData(pack.getSocketAddress(), buf.slice());
			break;
		case ACK:
			dgramReceiveAck(pack.getSocketAddress(), buf.slice());
			break;
		}
	}

	/**
	 * Tratamento de um pacote do tipo CONNECT recebido
	 *
	 * Esse método deve ser sobrescrito pelo servidor
	 */
	void dgramReceiveConnect(final SocketAddress sockAddr, final ByteBuffer buf) {
	}

	/** Tratamento de um pacote do tipo DATA recebido */
	void dgramReceiveData(final SocketAddress sockAddr, final ByteBuffer buf) {
		LspConnection conn = usedConnection(sockAddr, buf.getShort());
		if (conn != null) {
			short seqNum = buf.getShort();
			byte[] payload = payload(buf);
			InternalPack pack = new InternalPack(conn, seqNum, payload);

			// Se a mensagem foi enfileirada, envia o ACK e informa o número
			// de sequência à conexão (usado nos disparos da época).
			if (inputQueue.offer(pack)) {
				dgramSendAck(pack);
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
	void dgramReceiveAck(final SocketAddress sockAddr, final ByteBuffer buf) {
		final short connId = buf.getShort();
		final LspConnection conn = usedConnection(sockAddr, connId);

		// Se o connId é válido, reconhece a mensagem
		if (conn != null) {
			final short seqNum = buf.getShort();
			conn.ack(seqNum);
		}

		// Senão verifica se há uma tentativa de conexão em curso. Caso
		// positivo, verifica também se id não é 0, número de sequência é 0,
		// conferindo antes se o ACK vem do socket remoto correto
		else if (connTask != null && connId != 0 && buf.getShort() == 0
				&& sockAddr.equals(connTask.sockAddr)) {
			connTask.ack(connId);
		}
	}

	private void dgramSend(final SocketAddress sockAddr, final short msgType,
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

	private void dgramSend(final short msgType, final LspConnection conn,
			final short seqNum, final byte[] payload) {
		dgramSend(conn.getSockAddr(), msgType, conn.getId(), seqNum, payload);
	}

	final void dgramSendData(final LspConnection conn, final short seqNum, final byte[] payload) {
		dgramSend(DATA, conn, seqNum, payload);
	}

	final void dgramSendData(final InternalPack p) {
		dgramSendData(p.getConnection(), p.getSeqNum(), p.getPayload());
	}

	final void dgramSendAck(final LspConnection conn, final short seqNum) {
		dgramSend(ACK, conn, seqNum, PAYLOAD_NIL);
	}

	final void dgramSendAck(final InternalPack p) {
		dgramSendAck(p.getConnection(), p.getSeqNum());
	}

	/** Helper para obter um array de bytes com o resto do {@link ByteBuffer} */
	static final byte[] payload(final ByteBuffer buf) {
		byte[] bs = new byte[buf.remaining()];
		for (int i = 0; i < bs.length; i++) {
			bs[i] = buf.get();
		}

		return bs;
	}

	/**
	 * Obtém o objeto {@link LspConnection} em uso
	 *
	 * @param connId Id de conexão para validar/pesquisar
	 * @return Uma instância de {@link LspConnection} ou null
	 */
	abstract LspConnection usedConnection(short connId);

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

	/** Recebe um pacote da fila de entrada */
	InternalPack receive() {
		try {
			return inputQueue.take();
		} catch (InterruptedException e) {
			e.printStackTrace();
			return null;
		}
	}

	/** Insere um pacote na fila de saída */
	void send(InternalPack p) {
		if (!outputQueue.offer(p))
			throw new IllegalStateException("Fila de saída cheia");;
	}

	int getPort() {
		return this.port;
	}

	private final class ConnectTask implements Callable<Short> {
		private SocketAddress sockAddr;
		private Condition ackCondition;
		private short connId;

		ConnectTask(SocketAddress sockAddr, Condition condition) {
			this.sockAddr = sockAddr;
			this.ackCondition = condition;
		}

		@Override
		public Short call() throws Exception {
			// Envia requisição de conexão
			dgramSend(sockAddr, CONNECT, (short) 0, (short) 0, PAYLOAD_NIL);

			// Aguarda pelo ACK da conexão
			ackCondition.awaitUninterruptibly();

			return connId;
		}

		void ack(short connId) {
			this.connId = connId;
			ackCondition.notify();
		}
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
					dgramReceive(pack);
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

		private void sendNextData() throws InterruptedException {
			// Obtém o próximo pacote de dados da fila
			final InternalPack p = outputQueue.take();

			// Se já houver uma conexão associada ao pacote, envia-o e encerra
			if (p.getConnection() != null) {
				dgramSendData(p);
				return;
			}

			// Se o id de conexão é diferente da conexão em uso, encerra
			LspConnection conn = usedConnection(p.getConnId());
			if (conn == null) {
				return;
			}

			// Tenta associar o pacote à conexão. Se sucesso, envia esse pacote
			if (conn.sent(p)) {
				dgramSendData(p);
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
	}
}
