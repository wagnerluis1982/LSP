package lsp;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class LspClient {
	private final LspSocketImpl lspSocket;
	private final LspConnectionImpl conn;

	private volatile boolean active = true;

	public LspClient(String host, int port, LspParams params) throws IOException {
		SocketAddress sockAddr = InetSocketAddress.createUnresolved(host, port);

		lspSocket = new LspSocketImpl(0);
		short connId = lspSocket.connect(sockAddr);

		conn = new LspConnectionImpl(connId, sockAddr, params);
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
		return lspSocket.input().getPayload();
	}

	/**
	 * Envia uma mensagen para o lado servidor como um vetor de bytes. Devolve
	 * exceção se a conexão for perdida .
	 */
	public void write(byte[] payload) {
		checkActive();

		InternalPack p = new InternalPack(conn, payload);
		lspSocket.output(p);
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

	/* Definição devido ao sombreamento de close na classe aninhada LspConnectionImpl */
	private void closeConn() {
		close();
	}

	private class LspSocketImpl extends LspSocket {
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

	private final class LspConnectionImpl extends LspConnection {
		LspConnectionImpl(short id, SocketAddress sockAddr, LspParams params) {
			super(id, sockAddr, params);
		}

		@Override
		void callEpochTriggers() {
			resendConnect();
			resendData();
			resendAckData();
			sendAck0();
		}

		@Override
		void callCloseConnection() {
			closeConn();
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
