package lsp;

import static org.junit.Assert.*;
import static lsp.TestUtil.*;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class LspServerTest {
	private static LspServer server;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		server = new LspServer(0, new LspParams(500, 4));
		port = server.getPort();

		sock = new DatagramSocket();
		connId = connectServer();
		seqNum = 0;
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		server.closeAll();
	}

	@Test
	public void testAcceptingConnection() throws Exception {
		DatagramSocket sock = new DatagramSocket();

		ShortBuffer buf = connectServer(sock);
		assertEquals(ACK, buf.get());
		assertNotEquals(0, buf.get());
		assertEquals(0, buf.get());
	}

	@Test
	public void testRead() throws Exception {
		byte[] payload = "Hello, server!".getBytes();

		DatagramPacket pack = createPacket(DATA, payload);
		sock.send(pack);

		Pack recv = server.read();
		assertArrayEquals(payload, recv.getPayload());
	}

	@Test
	public void testWrite() throws Exception {
		String payload = "Holla, client!";
		server.write(new Pack(connId, payload.getBytes()));

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
