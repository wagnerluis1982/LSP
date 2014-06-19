package lsp.helpers;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.ByteBuffer;

import lsp.InternalPack;

/**
 * Serviço de entrada e saída de pacotes. Classe abstrata.
 *
 * @author Wagner Macedo
 */
public abstract class ServiceHelper {
	protected static final byte CONNECT = 0;
	protected static final byte DATA = 1;
	protected static final byte ACK = 2;

	private static final byte[] PAYLOAD_NIL = new byte[0];

	private final int port;
	private final Thread thread;

	private DatagramSocket socket;

	protected ServiceHelper(int port) {
		this.port = port;
		this.thread = new Thread(new SvcTask());
	}

	/**
	 * Indica até quando o serviço de entrada será executado. Esse método deve
	 * ser implementado em uma subclasse.
	 *
	 * @return false para parar o serviço
	 */
	protected abstract boolean isActive();

	/** Tratamento de um pacote do tipo CONNECT recebido */
	protected abstract void receiveConnect(DatagramPacket pack, ByteBuffer buf);

	/** Tratamento de um pacote do tipo DATA recebido */
	protected abstract void receiveData(DatagramPacket pack, ByteBuffer buf);

	/** Tratamento de um pacote do tipo ACK recebido */
	protected abstract void receiveAck(DatagramPacket pack, ByteBuffer buf);

	/**
	 * Processamento de cada pacote UDP recebido.
	 *
	 * @param pack Pacote enviado pelo serviço
	 */
	private final void receivePacket(final DatagramPacket pack) {
		final ByteBuffer buf = ByteBuffer.wrap(pack.getData(), 0,
				pack.getLength()).asReadOnlyBuffer();
		final short msgType = buf.getShort();

		switch (msgType) {
		case CONNECT:
			receiveConnect(pack, buf);
			break;
		case DATA:
			receiveData(pack, buf);
			break;
		case ACK:
			receiveAck(pack, buf);
			break;
		}
	}

	private final void send(final short msgType, final short connId, final short seqNum, final byte[] payload) {
		ByteBuffer buf = ByteBuffer.allocate(6 + payload.length);
		buf.putShort(msgType).putShort(connId).putShort(seqNum).put(payload);

		DatagramPacket packet = new DatagramPacket(buf.array(), buf.capacity());
		try {
			socket.send(packet);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public final void sendConnect(final short connId, final short seqNum) {
		send(CONNECT, connId, seqNum, PAYLOAD_NIL);
	}

	public final void sendData(final short connId, final short seqNum, final byte[] payload) {
		send(DATA, connId, seqNum, payload);
	}

	public final void sendData(final InternalPack p) {
		sendData(p.getConnId(), p.getSeqNum(), p.getPayload());
	}

	public final void sendAck(final short connId, final short seqNum) {
		send(ACK, connId, seqNum, PAYLOAD_NIL);
	}

	public final void start() {
		thread.start();
	}

	/** Helper para obter um array de bytes com o resto do {@link ByteBuffer} */
	protected static final byte[] getPayload(final ByteBuffer buf) {
		byte[] bs = new byte[buf.remaining()];
		for (int i = 0; i < bs.length; i++) {
			bs[i] = buf.get();
		}

		return bs;
	}

	private final class SvcTask implements Runnable {
		@Override
		public void run() {
			try {
				socket = new DatagramSocket(port);
			} catch (SocketException e) {
				throw new RuntimeException(e);
			}

			// Configuração do pacote de entrada
			byte[] bs = new byte[1024];
			DatagramPacket packet = new DatagramPacket(bs, bs.length);

			// Recebe pacotes até o servidor ser encerrado
			while (isActive()) {
				try {
					socket.receive(packet);
					receivePacket(packet);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}
