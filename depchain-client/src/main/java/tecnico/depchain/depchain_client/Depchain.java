package tecnico.depchain.depchain_client;

import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.crypto.SecretKey;

import tecnico.depchain.depchain_common.broadcasts.BestEffortBroadcast;
import tecnico.depchain.depchain_common.messages.ConfirmMessage;
import tecnico.depchain.depchain_common.messages.StringMessage;

enum RequestStatus {
	SENT,
	ACCEPTED,
	REJECTED
}

public class Depchain {
	private BestEffortBroadcast broadcast;
	private Map<Long, RequestStatus> pendingMessages = new HashMap<>();

	public Depchain(List<InetSocketAddress> locals, SecretKey ownKey, List<InetSocketAddress> remotes, List<SecretKey> remoteKeys)
		throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		broadcast = new BestEffortBroadcast(this::rxHandler, this::rxHandler, locals, ownKey, remotes, remoteKeys);
	}

	public boolean AppendString(String content) {
		StringMessage msg = new StringMessage(content);
		Long seqNum = msg.getSeqNum();
		synchronized (pendingMessages)
		{ pendingMessages.put(seqNum, RequestStatus.SENT); }

		broadcast.broadcast(msg.serialize());

		//FIXME: Stuck forever if confirmation never arrives
		//Wait for reply msg
		do {
			try
			{ pendingMessages.wait(); }
			catch (InterruptedException e)
			{ /* Ignore */ }
		} while (pendingMessages.get(seqNum) == RequestStatus.SENT);

		boolean accepted = pendingMessages.get(seqNum) == RequestStatus.ACCEPTED;

		synchronized (pendingMessages)
		{ pendingMessages.remove(seqNum); }

		return accepted;
	}

	private void rxHandler(byte[] data, InetSocketAddress remote) {
		//HACK: Assumes all incoming messages are ConfirmMessage

		ConfirmMessage msg = ConfirmMessage.deserialize(data);
		synchronized (pendingMessages)
		{ pendingMessages.replace(msg.getSeqNum(), msg.getAccepted() ? RequestStatus.ACCEPTED : RequestStatus.REJECTED); }
		pendingMessages.notifyAll();
	}
}
