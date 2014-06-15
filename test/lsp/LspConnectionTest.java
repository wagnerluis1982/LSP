package lsp;

import static lsp.LspConnection.Actions;

import static org.junit.Assert.*;
import org.junit.Test;

public class LspConnectionTest {
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
		LspConnection conn = new LspConnection((short) 1, 0, new LspParams(2, 3),
				new Actions() {
					public void epochTriggers() {
						epoch--;
					}
					public void closeConnection() {
						closed = true;
					}
				});

		// Ao iniciar... ainda não houve alterações
		assertEquals(4, epoch);
		assertFalse(closed);

		// Manipulando o atributo lastMessageTime para propósitos do teste
		Thread.sleep(2);
		conn.lastMessageTime = System.currentTimeMillis();

		// Ao finalizar...
		Thread.sleep(8);
		assertEquals(0, epoch);	// garante não haver mais decrementos
		assertTrue(closed);		// garante que a "conexão" foi fechada
	}
}
