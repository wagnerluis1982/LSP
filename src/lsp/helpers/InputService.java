package lsp.helpers;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * Serviço de entrada de pacotes. Classe abstrata.
 *
 * @author Wagner Macedo
 */
public abstract class InputService {
	protected static final byte CONNECT = 0;
	protected static final byte DATA = 1;
	protected static final byte ACK = 2;

	private final int port;
	private final Thread thread;

	protected InputService(int port) {
		this.port = port;
		this.thread = new Thread(new SvcThread());
	}

	/**
	 * Indica até quando o serviço de entrada será executado. Esse método deve
	 * ser implementado em uma subclasse.
	 *
	 * @return false para parar o serviço
	 */
	protected abstract boolean isActive();

	/**
	 * Processamento de cada pacote UDP recebido.
	 *
	 * @param pack Pacote enviado pelo serviço
	 */
	protected abstract void processPacket(DatagramPacket pack);

	public final void start() {
		thread.start();
	}

	private final class SvcThread implements Runnable {
		@Override
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
			while (isActive()) {
				try {
					socket.receive(packet);
					processPacket(packet);
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}
	}
}