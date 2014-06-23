package lsp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeoutException;

public class LspClient {
	private final LspSocket lspSocket;
	private final LspConnection conn;

	private volatile boolean active = true;

	public LspClient(String host, int port, LspParams params) throws IOException, TimeoutException {
		SocketAddress sockAddr = new InetSocketAddress(host, port);

		lspSocket = new LspSocketImpl(0);
		conn = lspSocket.connect(sockAddr, params, new ClientTriggers());
	}

	/**
	 * Devolve o Id da conexão
	 */
	public short getConnId() {
		return conn.getId();
	}

	/**
	 * Devolve um vetor de bytes de uma mensagem enviada pelo lado servidor .
	 * Devolve null se a conexão for perdida .
	 */
	public byte[] read() {
		checkActive();
		return lspSocket.receive().getPayload();
	}

	/**
	 * Envia uma mensagen para o lado servidor como um vetor de bytes. Devolve
	 * exceção se a conexão for perdida .
	 */
	public void write(byte[] payload) {
		checkActive();

		InternalPack p = new InternalPack(conn, payload);
		lspSocket.send(p);
	}

	/**
	 * Encerra a conexão.
	 */
	public void close() {
		checkActive();
		this.active = false;
	}

	private void checkActive() {
		if (!active)
			throw new ClosedConnectionException();
	}

	private final class LspSocketImpl extends LspSocket {
		LspSocketImpl(int port) throws IOException {
			super(port);
		}

		@Override
		boolean isActive() {
			return active;
		}

		@Override
		LspConnection usedConnection(short connId) {
			return conn;
		}
	}

	private final class ClientTriggers implements ConnectionTriggers {
		@Override
		public void doEpochActions() {
			resendConnect();
			resendData();
			resendAckData();
			sendAck0();
		}

		@Override
		public void doCloseConnection() {
			close();
		}

		private void resendConnect() {
			// TODO Auto-generated method stub
		}

		private void resendData() {
			// TODO Auto-generated method stub
		}

		private void resendAckData() {
			// TODO Auto-generated method stub
		}

		private void sendAck0() {
			// TODO Auto-generated method stub
		}
	}
}
