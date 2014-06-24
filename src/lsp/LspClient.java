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
		try {
			conn = lspSocket.connect(sockAddr, params, new ClientTriggers());
		} catch (TimeoutException e) {
			lspSocket.close();
			throw e;
		}
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

		InternalPack p = new InternalPack(conn.getId(), payload);
		lspSocket.send(p);
	}

	/**
	 * Encerra a conexão.
	 */
	private void close(boolean checked) {
		if (checked) {
			checkActive();
		}
		this.active = false;
		this.conn.close();
		this.lspSocket.close();
	}

	public void close() {
		close(true);
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
			if (conn != null && conn.getId() == connId) {
				return conn;
			} else {
				return null;
			}
		}
	}

	private final class ClientTriggers implements ConnectionTriggers {
		@Override
		public void doEpochActions() {
			resendData();
			resendAckData();
			sendAck0();
		}

		@Override
		public void doCloseConnection() {
			close(false);
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
