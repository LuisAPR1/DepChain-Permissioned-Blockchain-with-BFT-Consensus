package tecnico.depchain.links;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.util.function.BiConsumer;
import java.net.DatagramSocket;
import java.io.IOException;
import java.net.DatagramPacket;

public class FairLossLink implements P2PLink, Runnable {
	private DatagramSocket sock;
	private Thread receiverThread;
	private InetSocketAddress remote;

	private BiConsumer<byte[], P2PLink> rxHandler = null;

	public FairLossLink(InetSocketAddress local, InetSocketAddress remote) throws SocketException {
		this.remote = remote;

		sock = new DatagramSocket(local); // FIXME: Never closed

		// Start receiver thread
		receiverThread = new Thread(this);
		receiverThread.start();
	}

	public void SetHandler(BiConsumer<byte[], P2PLink> rxHandler) {
		this.rxHandler = rxHandler;
	}

	public void Transmit(byte[] data) {
		var packet = new DatagramPacket(data, data.length, remote);
		try {
			sock.send(packet);
		} catch (IOException e) {
			// Ignore
		}
	}

	// Receiver thread
	public void run() {
		byte[] rxBuffer = new byte[64 * 1024];
		DatagramPacket packet = new DatagramPacket(rxBuffer, rxBuffer.length);
		while (true) {
			packet.setLength(rxBuffer.length);
			try {
				sock.receive(packet);
			} catch (IOException e) {
				// Ignore
				continue;
			}

			if (rxHandler != null) {
				byte[] received = new byte[packet.getLength()];
				System.arraycopy(rxBuffer, 0, received, 0, packet.getLength());
				rxHandler.accept(received, this);
			}
		}
	}
}
