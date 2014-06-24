package lsp;

import static org.junit.Assert.*;
import static lsp.TestUtil.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class LspClientTest {
	private static volatile LspClient client;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		sock = new DatagramSocket();
		connId = 1;
		seqNum = 0;

		testEstablishingConnection(sock.getLocalPort());
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		sock.close();
		client.close();
	}

	private static void testEstablishingConnection(final int localPort) throws Exception {
		// Envia requisição de conexão
		Thread connecting = new Thread(new Runnable() {
			public void run() {
				try {
					client = new LspClient("localhost", localPort, new LspParams(500, 4));
				} catch (IOException | TimeoutException e) {
					e.printStackTrace();
				}
			}
		});
		connecting.start();

		// Recebe a requisição
		DatagramPacket p = createPacket();
		sock.receive(p);

		// Testa se a requisição é bem formada
		ByteBuffer buf = ByteBuffer.wrap(p.getData());
		assertEquals(CONNECT, buf.getShort());
		assertEquals(0, buf.getShort());
		assertEquals(0, buf.getShort());

		// Envia ACK da conexão
		p.setSocketAddress(p.getSocketAddress());
		buf.rewind();
		buf.asShortBuffer().put(new short[] {ACK, connId, 0});

		// Espera processo de conexão se completar
		while (connecting.isAlive()) {
			sock.send(p);
			Thread.sleep(250);
		}
		port = p.getPort();
	}

	@Test
	public void testGetConnId() {
		assertEquals(connId, client.getConnId());
	}

	@Test
	public void testRead() throws Exception {
		byte[] payload = "Hello, client!".getBytes();

		DatagramPacket pack = createPacket(DATA, payload);
		sock.send(pack);

		byte[] recv = client.read();
		assertArrayEquals(payload, recv);
	}

	@Test
	public void testWrite() throws Exception {
		String payload = "Holla, server!";
		client.write(payload.getBytes());

		DatagramPacket p = createPacket();
		sock.receive(p);

		ByteBuffer buf = ByteBuffer.wrap(p.getData(), 0, p.getLength());
		assertEquals(DATA, buf.getShort());
		assertEquals(connId, buf.getShort());
		assertEquals(1, buf.getShort());

		byte[] recv = new byte[buf.remaining()];
		buf.get(recv);
		assertEquals(payload, new String(recv));

		p = createPacket(ACK, connId, (short) 1, "".getBytes());
		sock.send(p);
	}
}
