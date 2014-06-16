package lsp;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * @author Wagner Macedo
 */
abstract class InputService {
	static final byte CONNECT = 0;
	static final byte DATA = 1;
	static final byte ACK = 2;

	private final int port;

	InputService(int port) {
		this.port = port;
	}

	abstract boolean isActive();

	abstract void processPacket(DatagramPacket pack);

	final void start() {
		new Thread(new SvcThread()).start();
	}

	private final class SvcThread implements Runnable {
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
