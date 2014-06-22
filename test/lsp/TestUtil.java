package lsp;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

public class TestUtil {
	static final short CONNECT = 0;
	static final short DATA = 1;
	static final short ACK = 2;

	static int port;

	static DatagramSocket sock;
	static short connId;
	static short seqNum;

	static ShortBuffer connectServer(DatagramSocket sock) throws Exception {
		DatagramPacket pack = createPacket(CONNECT, (short) 0, (short) 0, "".getBytes());
		ShortBuffer buf = ByteBuffer.wrap(pack.getData()).asShortBuffer();
		buf.put(new short[] {CONNECT, 0, 0});
		sock.send(pack);
		sock.receive(pack);

		buf.rewind();
		buf.limit(pack.getLength()/2);
		return buf;
	}

	static short connectServer() throws Exception {
		return connectServer(sock).get(1);
	}

	static DatagramPacket createPacket(short msgType, short connId, short seqNum, byte[] payload) throws Exception {
		DatagramPacket pack = createPacket();
		ByteBuffer buf = ByteBuffer.wrap(pack.getData());
		buf.asShortBuffer().put(new short[] {msgType, connId, seqNum});
		buf.position(6);
		buf.put(payload);

		pack.setLength(buf.position());
		pack.setAddress(InetAddress.getLocalHost());
		pack.setPort(port);
		return pack;
	}

	static DatagramPacket createPacket(short msgType, byte[] payload) throws Exception {
		return createPacket(msgType, connId, ++seqNum, payload);
	}

	static DatagramPacket createPacket() {
		return new DatagramPacket(new byte[1024], 1024);
	}
}
