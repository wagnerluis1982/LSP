package lsp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.TimeoutException;

public class LspClient {
	private final LspSocket lspSocket;
	private final LspConnection conn;
	private final LspParams params;

	private volatile boolean active = true;
	private volatile boolean markClosed = false;

	public static final short LEN_PAYLOAD = LspSocket.LEN_PAYLOAD;

	public LspClient(String host, int port, LspParams params) throws IOException, TimeoutException {
		SocketAddress sockAddr = new InetSocketAddress(host, port);
		params = (params == null) ? LspParams.defaultParams() : params;

		lspSocket = new LspSocketImpl(0);
		try {
			conn = lspSocket.connect(sockAddr, params, new ClientTriggers());
			this.params = params;
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
	 * Devolve um vetor de bytes de uma mensagem enviada pelo lado servidor.
	 * Devolve null se a conexão for perdida.
	 */
	public byte[] read() {
		try {
			checkActive();
			return lspSocket.receive().getPayload();
		} catch (ClosedConnectionException | NullPointerException e) {
			return null;
		}
	}

	/**
	 * Envia uma mensagen para o lado servidor como um vetor de bytes. Devolve
	 * exceção se a conexão for perdida.
	 */
	public void write(byte[] payload) {
		checkActive();

		Pack p = new Pack(conn.getId(), payload);
		lspSocket.send(p);
		conn.incSendMissing();
	}

	/**
	 * Encerra a conexão.
	 */
	public void close() {
		checkActive();

		// Marca a conexão como fechada e se não há mensagens para serem
		// enviadas, encerra realmente a conexão.
		markClosed = true;
		conn.close(false);
		if (conn.getSendMissing() == 0) {
			realClose();
			return;
		}

		while (!conn.isInterrupted()) {
			try {
				Thread.sleep(params.getEpoch());
			} catch (InterruptedException e) {
				realClose();
				return;
			}
		}
	}

	private void realClose() {
		this.active = false;
		this.conn.close();
		this.lspSocket.close();
	}

	private void checkActive() {
		if (!active || markClosed)
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
			Helpers.resendData(lspSocket, conn);
			Helpers.resendAck(lspSocket, conn);
		}

		@Override
		public void doCloseConnection() {
			realClose();
		}
	}
}
