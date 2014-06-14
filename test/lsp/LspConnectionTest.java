package lsp;

import static lsp.LspConnection.Actions;

import static org.junit.Assert.*;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class LspConnectionTest {
	int time;
	boolean closed;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
	}

	/*
	 * Este teste confirma se a thread de verificação de status está disparando
	 * as ações. Não há conexão real envolvida.
	 */
	@Test
	public void testStatusChecker() throws InterruptedException {
		// LspConnection com epoch = 2 msec e epochLimit = 3, portanto a thread
		// durará 8 msec e deve incrementar o valor de time a cada 2 msec e
		// alterar closed para true no final.
		time = 0;
		closed = false;
		new LspConnection((short) 1, new LspParams(2, 3), new Actions() {
			public long lastReceiptTime() {
				return 1 / ++time;
			}
			public void closeConnection() {
				closed = true;
			}
		});

		// Ainda não houve incremento
		assertEquals(0, time);
		assertFalse(closed);

		// Ao finalizar
		Thread.sleep(10);
		assertEquals(5, time);	// garante que não houve mais incrementos
		assertTrue(closed);		// garante que a "conexão" foi fechada
	}
}
