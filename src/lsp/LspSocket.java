package lsp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
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

	static final short LEN_PACKAGE = 1024;
	static final byte LEN_HEADER = 6;
	static final short LEN_PAYLOAD = LEN_PACKAGE - LEN_HEADER;

	/** Capacidade das filas de entrada e saída em termos de pacotes de 1KB */
	private static final byte QUEUE_ZISE = 50;

	/* Filas de entrada e saída */
	private final BlockingQueue<InternalPack> inputQueue;
	private final BlockingDeque<Pack> outputQueue;

	/* Lock para garantir que apenas uma thread envie pacotes */
	private final Object sendLock = new Object();

	/* Usado no pedido de conexão a um servidor LSP partindo desse socket */
	private volatile ConnectTask connectTask;
	private final Object connectLock = new Object();

	/* Socket de comunicação em uso */
	private final DatagramSocket socket;
	private final int port;

	/* Threads processando entradas e saídas */
	private final Thread inputThread;
	private final Thread outputThread;

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

		// Inicializa thread de entradas
		this.inputThread = new Thread(new InputTask());
		this.inputThread.setDaemon(true);
		this.inputThread.start();

		// Inicializa thread de saídas
		this.outputThread = new Thread(new OutputTask());
		this.outputThread.setDaemon(true);
		this.outputThread.start();
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

	final void close() {
		socket.close();

		// Para todas as threads
		inputThread.interrupt();
		outputThread.interrupt();

		// Limpeza de memória
		inputQueue.clear();
		outputQueue.clear();
	}

	/**
	 * Tenta estabelecer conexão com um servidor LSP, reenviando solicitação a
	 * cada época, até completar o limite da época.
	 */
	final LspConnection connect(SocketAddress sockAddr, LspParams params, ConnectionTriggers triggers) throws TimeoutException {
		// Novo processo de conexão
		final ConnectTask task = new ConnectTask(sockAddr, params);

		try {
			while (isActive()) {
				synchronized (connectLock) {
					// Se não há um processo de conexão em curso, registra um
					if (this.connectTask == null) {
						this.connectTask = task;
					}

					// Havendo um processo de conexão em curso, aguarda uma
					// época e tenta se conectar novamente
					else {
						Thread.sleep(params.getEpoch());
						continue;
					}
				}

				// Executa o processo de conexão
				final ExecutorService exec = Executors.newSingleThreadExecutor();
				final Future<Short> id = exec.submit(task);

				// Se o processo concluir corretamente, uma nova conexão será gerada
				try {
					final short connId = id.get();
					return new LspConnection(connId, sockAddr, params, triggers);
				}

				// Se uma exceção foi lançada, então relança-a contextualmente
				catch (ExecutionException e) {
					if (e.getCause() instanceof TimeoutException) {
						throw (TimeoutException) e.getCause().fillInStackTrace();
					} else {
						throw new RuntimeException(e);
					}
				}

				// Garante a liberação da thread de requisição de conexão e
				// registra que o processo de conexão se encerrou
				finally {
					exec.shutdown();
					this.connectTask = null;
				}
			}
		} catch (InterruptedException e) {}

		return null;
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

		// Só continua se a conexão é válida e não estiver fechada
		if (conn != null && !conn.isClosed()) {
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
		else {
			final ConnectTask task = connectTask;
			if (task != null && connId > 0 && buf.getShort() == 0
					&& sockAddr.equals(task.sockAddr)) {
				task.ack(connId);
			}
		}
	}

	private void dgramSend(final SocketAddress sockAddr, final short msgType,
			final short connId, final short seqNum, final byte[] payload) {
		ByteBuffer buf = ByteBuffer.allocate(LEN_HEADER + payload.length);
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
		if (payload.length > LEN_PAYLOAD) {
			throw new IllegalArgumentException("Payload não pode ser maior que " + LEN_PAYLOAD);
		}

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
	public InternalPack receive() {
		while (isActive()) {
			try {
				InternalPack nextPack = inputQueue.poll(1, TimeUnit.SECONDS);
				if (nextPack != null) {
					return nextPack;
				}
			} catch (InterruptedException e) {
				break;
			}
		}

		return null;
	}

	/** Insere um pacote na fila de saída */
	public void send(Pack p) {
		if (p.getPayload().length > LEN_PAYLOAD) {
			throw new IllegalArgumentException("Payload não pode ser maior que " + LEN_PAYLOAD);
		}

		if (!outputQueue.offer(p)) {
			throw new IllegalStateException("Fila de saída cheia");
		}
	}

	int getPort() {
		return this.port;
	}

	private final class ConnectTask implements Callable<Short> {
		private final SocketAddress sockAddr;
		private final BlockingQueue<Short> result;
		private final LspParams params;

		ConnectTask(SocketAddress sockAddr, LspParams params) {
			this.sockAddr = sockAddr;
			this.params = params;
			this.result = new ArrayBlockingQueue<>(1);
		}

		@Override
		public Short call() throws TimeoutException {
			// Envia e aguarda, durante o tempo das épocas, o ACK da conexão
			int limit = params.getEpochLimit();
			while (isActive() && limit-- > 0) {
				try {
					dgramSend(sockAddr, CONNECT, (short) 0, (short) 0, PAYLOAD_NIL);
					Short id = result.poll(params.getEpoch(), TimeUnit.MILLISECONDS);
					if (id != null) {
						return id;
					}
				} catch (InterruptedException e) {
					return null;
				}
			}

			// Como já passou o tempo definido nos params, então considera
			// que o servidor não está disponível
			throw new TimeoutException("Servidor " + sockAddr + " não responde");
		}

		void ack(short connId) {
			result.offer(connId);
		}
	}

	private final class InputTask implements Runnable {
		@Override
		public void run() {
			// Configuração do pacote de entrada
			byte[] bs = new byte[LEN_PACKAGE];
			DatagramPacket pack = new DatagramPacket(bs, bs.length);

			// Recebe pacotes até o servidor ser encerrado
			while (isActive()) {
				try {
					dgramReceive(pack);
				} catch (IOException e) {
					return;
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
					return;
				}
			}
		}

		private void sendNextData() throws InterruptedException {
			// Obtém o próximo pacote de dados da fila, se houver.
			final Pack p = outputQueue.poll(1, TimeUnit.SECONDS);
			if (p == null) {
				return;
			}

			// Se o id de conexão do pacote é inválido, encerra
			LspConnection conn = usedConnection(p.getConnId());
			if (conn == null) {
				return;
			}

			// Tenta associar o pacote à conexão. Se sucesso, envia esse pacote
			InternalPack sent = conn.sent(p);
			if (sent != null) {
				dgramSendData(sent);
				return;
			}

			// Se não foi possível associar à conexão (já havia outro pacote em
			// espera de um ACK) então devolve o pacote à fila (segunda posição)
			synchronized (outputQueue) {
				Pack first = outputQueue.poll();
				outputQueue.offerFirst(p);
				if (first != null) {
					outputQueue.offerFirst(first);
				}
			}
		}

	}
}
