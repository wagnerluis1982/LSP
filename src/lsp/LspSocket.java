package lsp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

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
	private final BlockingQueue<Pack> outputQueue;

	private final Thread thread = new Thread(new SvcTask());
	private final Object sendLock = new Object();

	private DatagramSocket socket;

	/**
	 * Inicia um LspSocket
	 *
	 * @param port Porta onde o socket estará vinculado
	 * @throws RuntimeException
	 */
	LspSocket(int port) {
		this(port, QUEUE_ZISE);
	}

	/**
	 * Inicia um LspSocket
	 *
	 * @param port Porta onde o socket estará vinculado
	 * @param queueSize Tamanho inicial das filas de entrada e saída
	 * @throws RuntimeException
	 */
	LspSocket(int port, int queueSize) {
		try {
			this.socket = new DatagramSocket(port);
		} catch (SocketException e) {
			throw new RuntimeException(e);
		}
		this.inputQueue = new ArrayBlockingQueue<>(queueSize, true);
		this.outputQueue = new ArrayBlockingQueue<>(queueSize, true);
		this.thread.start();
	}

	/**
	 * Indica até quando o serviço será executado. Esse método deve ser
	 * implementado em uma subclasse.
	 *
	 * @return false para parar o serviço
	 */
	abstract boolean isActive();

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

	/** Tratamento de um pacote do tipo CONNECT recebido */
	abstract void receiveConnect(SocketAddress sockAddr, ByteBuffer buf);

	/** Tratamento de um pacote do tipo DATA recebido */
	abstract void receiveData(SocketAddress sockAddr, ByteBuffer buf);

	/** Tratamento de um pacote do tipo ACK recebido */
	abstract void receiveAck(SocketAddress sockAddr, ByteBuffer buf);

	private void send(final short msgType, final short connId, final short seqNum, final byte[] payload) {
		ByteBuffer buf = ByteBuffer.allocate(6 + payload.length);
		buf.putShort(msgType).putShort(connId).putShort(seqNum).put(payload);

		DatagramPacket packet = new DatagramPacket(buf.array(), buf.capacity());
		try {
			synchronized (sendLock) {
				socket.send(packet);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	final void sendConnect(final short connId, final short seqNum) {
		send(CONNECT, connId, seqNum, PAYLOAD_NIL);
	}

	final void sendData(final short connId, final short seqNum, final byte[] payload) {
		send(DATA, connId, seqNum, payload);
	}

	final void sendData(final InternalPack p) {
		sendData(p.getConnId(), p.getSeqNum(), p.getPayload());
	}

	final void sendAck(final short connId, final short seqNum) {
		send(ACK, connId, seqNum, PAYLOAD_NIL);
	}

	final void sendAck(final InternalPack p) {
		sendAck(p.getConnId(), p.getSeqNum());
	}

	/** Helper para obter um array de bytes com o resto do {@link ByteBuffer} */
	static final byte[] payload(final ByteBuffer buf) {
		byte[] bs = new byte[buf.remaining()];
		for (int i = 0; i < bs.length; i++) {
			bs[i] = buf.get();
		}

		return bs;
	}

	BlockingQueue<InternalPack> inputQueue() {
		return this.inputQueue;
	}

	BlockingQueue<Pack> outputQueue() {
		return this.outputQueue;
	}

	private final class SvcTask implements Runnable {
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
}
