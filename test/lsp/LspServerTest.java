package lsp;

import static org.junit.Assert.*;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class LspServerTest {
	private static final short CONNECT = 0;
	private static final short DATA = 1;
	private static final short ACK = 2;

	private static LspServer server;
	private static int port;
	private static DatagramSocket sock;
	private static DatagramPacket pack;
	private static short connId;

	@BeforeClass
	public static void setUpBeforeClass() throws Exception {
		server = new LspServer(0, new LspParams(500, 4));
		port = server.getPort();

		sock = new DatagramSocket();
		pack = createPacket();
		connId = connectServer();
	}

	@AfterClass
	public static void tearDownAfterClass() throws Exception {
		server.closeAll();
	}

	@Test
	public void testAcceptingConnection() throws Exception {
		DatagramSocket sock = new DatagramSocket();
		DatagramPacket pack = createPacket();

		ShortBuffer buf = connectServer(sock, pack);
		assertEquals(ACK, buf.get());
		assertNotEquals(0, buf.get());
		assertEquals(0, buf.get());
	}

	public void testRead() {
		fail("Not yet implemented");
	}

	public void testWrite() {
		fail("Not yet implemented");
	}

	public void testCloseConn() {
		fail("Not yet implemented");
	}

	public void testCloseAll() {
		fail("Not yet implemented");
	}

	private static ShortBuffer connectServer(DatagramSocket sock, DatagramPacket pack) throws IOException {
		ShortBuffer buf = ByteBuffer.wrap(pack.getData()).asShortBuffer();
		buf.put(new short[] {CONNECT, 0, 0});
		sock.send(pack);
		sock.receive(pack);

		return ByteBuffer.wrap(pack.getData()).asShortBuffer();
	}

	private static short connectServer() throws IOException {
		return connectServer(sock, pack).get(1);
	}

	private static DatagramPacket createPacket() throws Exception {
		return new DatagramPacket(new byte[1024], 1024, InetAddress.getLocalHost(), port);
	}
}
