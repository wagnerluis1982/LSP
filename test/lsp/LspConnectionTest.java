package lsp;

import static lsp.LspConnection.Actions;

import static org.junit.Assert.*;
import org.junit.Test;

public class LspConnectionTest {
	int time;
	int epoch;
	boolean closed;

	/*
	 * Este teste confirma se a thread de verificação de status está disparando
	 * as ações. Não há conexão real envolvida.
	 */
	@Test
	public void testStatusChecker() throws InterruptedException {
		// LspConnection com epoch = 2 msec e epochLimit = 3, portanto a thread
		// durará 8 msec e deve incrementar o valor de time e decrementar o
		// valor de epoch a cada 2 msec e
		// alterar closed para true no final.
		time = 0;
		epoch = 4;
		closed = false;
		new LspConnection((short) 1, new LspParams(2, 3), new Actions() {
			public long lastReceiptTime() {
				return 1 / ++time;
			}
			public void epochTriggers() {
				epoch--;
			}
			public void closeConnection() {
				closed = true;
			}
		});

		// Ainda não houve incremento
		assertEquals(0, time);
		assertEquals(4, epoch);
		assertFalse(closed);

		// Ao finalizar...
		Thread.sleep(11);
		assertEquals(5, time);	// garante não haver mais incrementos
		assertEquals(0, epoch);	// garante não haver mais decrementos
		assertTrue(closed);		// garante que a "conexão" foi fechada
	}
}
