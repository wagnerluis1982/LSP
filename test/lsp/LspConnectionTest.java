package lsp;

import static org.junit.Assert.*;

import java.net.InetSocketAddress;

import org.junit.Test;

public class LspConnectionTest {
	static final InetSocketAddress ADDR = InetSocketAddress.createUnresolved("", 1);

	int epoch;
	boolean closed;

	/*
	 * Este teste confirma se a thread de verificação de status está disparando
	 * as ações. Não há conexão real envolvida.
	 */
	@Test
	public void testStatusChecker() throws InterruptedException {
		// LspConnection com epoch = 2 msec e epochLimit = 3, portanto a thread
		// durará 8 msec e deve decrementar o valor de epoch a cada 2 msec e
		// alterar closed para true no final.
		epoch = 4;
		closed = false;
		LspConnection conn = new LspConnection((short) 1, 1, ADDR, new LspParams(2, 3),
				new ConnectionTriggers() {
					public void doEpochActions() {
						epoch--;
					}

					public void doCloseConnection() {
						closed = true;
					}
				});

		// Ao iniciar... ainda não houve alterações
		assertEquals(4, epoch);
		assertFalse(closed);

		// Chamando received() manualmente para propósitos do teste
		Thread.sleep(2);
		conn.received();

		// Ao finalizar...
		Thread.sleep(8);
		assertEquals(0, epoch);	// garante não haver mais decrementos
		assertTrue(closed);		// garante que a "conexão" foi fechada
	}
}
