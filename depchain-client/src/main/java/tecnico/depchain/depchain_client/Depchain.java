package tecnico.depchain.depchain_client;

import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
import tecnico.depchain.depchain_common.blockchain.Transaction;
import tecnico.depchain.depchain_common.broadcasts.BestEffortBroadcast;
import tecnico.depchain.depchain_common.messages.ConfirmMessage;
import tecnico.depchain.depchain_common.messages.NonceReplyMessage;
import tecnico.depchain.depchain_common.messages.NonceRequestMessage;
import tecnico.depchain.depchain_common.messages.TransactionMessage;

enum RequestStatus {
	SENT,
	ACCEPTED,
	REJECTED
}

public class Depchain {
	private BestEffortBroadcast broadcast;
	private Map<Long, RequestStatus> pendingMessages = new HashMap<>();
	private Map<Long, java.util.Set<InetSocketAddress>> confirmations = new HashMap<>();

	// Nonce synchronization state
	private Map<Long, java.util.Set<InetSocketAddress>> nonceVotes = new HashMap<>();
	private volatile boolean nonceSyncComplete = false;
	private final Object nonceSyncLock = new Object();

	private int numReplicas;
	private int f;
	private int quorumSize;
	private int clientId;
	private PrivateKey ownKey;
	private String ownAddress; // Hex address "0x..." for the client's EOA

	private long currentNonce = 0;

	public Depchain(int clientId, List<InetSocketAddress> locals, PrivateKey ownKey, List<InetSocketAddress> remotes, List<PublicKey> remoteKeys)
		throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException {
		this.clientId = clientId;
		this.ownKey = ownKey;
		broadcast = new BestEffortBroadcast(this::rxHandler, this::rxHandler, locals, ownKey, remotes, remoteKeys);
		this.numReplicas = remotes.size();
		this.f = (numReplicas - 1) / 3;
		this.quorumSize = f + 1;
	}

	/** Sets this client's EOA address. */
	public void setOwnAddress(String address) {
		this.ownAddress = address;
	}

	/** Sets the current nonce. */
	public void setNonce(long nonce) {
		this.currentNonce = nonce;
	}

	/** Synchronizes nonce from server state. */
	public void syncNonceWithServer(long serverNonce) {
		this.currentNonce = serverNonce;
	}

	/**
	 * Queries replicas for the current nonce, waits for f+1 matching replies.
	 * @return true if nonce was successfully synchronized
	 */
	public boolean syncNonce() {
		if (ownAddress == null) {
			System.err.println("[Depchain Client] Cannot sync nonce: ownAddress not set");
			return false;
		}

		Address address = Address.fromHexString(ownAddress);
		NonceRequestMessage request = new NonceRequestMessage(address);

		synchronized (nonceSyncLock) {
			nonceVotes.clear();
			nonceSyncComplete = false;
		}

		System.out.println("[Depchain Client] Requesting nonce from replicas for address: " + ownAddress);

		broadcast.broadcast(request.serialize());

		// Wait for f+1 matching responses
		long timeoutMs = 5000;
		long deadline = System.currentTimeMillis() + timeoutMs;

		synchronized (nonceSyncLock) {
			while (!nonceSyncComplete && System.currentTimeMillis() < deadline) {
				try {
					nonceSyncLock.wait(deadline - System.currentTimeMillis());
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return false;
				}
			}

			if (nonceSyncComplete) {
				System.out.println("[Depchain Client] Nonce synchronized to: " + currentNonce);
				return true;
			}
		}

		System.err.println("[Depchain Client] Failed to sync nonce: no nonce reached f+1 votes");
		synchronized (nonceSyncLock) {
			for (Map.Entry<Long, java.util.Set<InetSocketAddress>> entry : nonceVotes.entrySet()) {
				System.err.println("  Nonce " + entry.getKey() + ": " + entry.getValue().size() + " votes");
			}
		}
		return false;
	}

	/**
	 * Submits a signed transaction to all replicas and waits for f+1 confirmations.
	 * @return true if accepted by f+1 replicas
	 */
	public boolean submitTransaction(TransactionMessage tx) {
		// Signing is now done inside createTransfer/createContractCall via SignedTransaction.signTransaction().
		// submitTransaction only broadcasts the already-signed message.
		long seqNum = tx.getSeqNum();

		synchronized (pendingMessages) {
			pendingMessages.put(seqNum, RequestStatus.SENT);
			confirmations.put(seqNum, new java.util.HashSet<>());
		}

		long timeoutMs = 2000; // 2 seconds before retrying
		int maxRetries = 5;
		int attempts = 0;

		System.out.println("[Depchain Client] Submitted tx seqNum=" + seqNum
				+ " from=" + tx.getSignedTransaction().tx().from() + " nonce=" + tx.getSignedTransaction().tx().nonce());

		while (attempts < maxRetries) {
			broadcast.broadcast(tx.serialize());
			attempts++;

			synchronized (pendingMessages) {
				try {
					pendingMessages.wait(timeoutMs);
				} catch (InterruptedException e) { /* Ignore */ }

				if (pendingMessages.get(seqNum) != RequestStatus.SENT) {
					break; // Reached ACCEPTED or REJECTED
				}
			}
			if (attempts < maxRetries) {
				System.out.println("[Depchain Client] Timeout waiting for f+1 ConfirmMessages. Retrying tx seqNum=" + seqNum + "...");
			} else {
				System.err.println("[Depchain Client] Max retries reached for tx seqNum=" + seqNum + ". Transaction failed.");
			}
		}

		boolean accepted;
		synchronized (pendingMessages) {
			accepted = pendingMessages.get(seqNum) == RequestStatus.ACCEPTED;
			pendingMessages.remove(seqNum);
			confirmations.remove(seqNum); // Clean up
		}

		return accepted;
	}

	/** Creates and signs a DepCoin transfer transaction. */
	public TransactionMessage createTransfer(String to, String value, long gasLimit, String gasPrice) {
		if (ownAddress == null) throw new IllegalStateException("Call setOwnAddress() before creating transactions");

		Transaction tx = new Transaction(
				currentNonce,
				Address.fromHexString(ownAddress),
				Address.fromHexString(to),
				Wei.of(new BigInteger(gasPrice)),
				gasLimit,
				Wei.of(new BigInteger(value)),
				Bytes.EMPTY);  // no calldata for plain transfers

		SignedTransaction signedTx = SignedTransaction.signTansaction(tx, ownKey);

		TransactionMessage msg = new TransactionMessage(clientId, signedTx);

		currentNonce += 1;
		return msg;
	}

	/** Creates and signs a smart contract call transaction. */
	public TransactionMessage createContractCall(String contractAddress, String callData, long gasLimit, String gasPrice) {
		if (ownAddress == null) throw new IllegalStateException("Call setOwnAddress() before creating transactions");

		Transaction tx = new Transaction(
				currentNonce,
				Address.fromHexString(ownAddress),
				Address.fromHexString(contractAddress),
				Wei.of(new BigInteger(gasPrice)),
				gasLimit,
				Wei.ZERO,  // no value transfer for contract calls
				Bytes.fromHexString(callData));

		SignedTransaction signedTx = SignedTransaction.signTansaction(tx, ownKey);
		TransactionMessage msg = new TransactionMessage(clientId, signedTx);

		currentNonce += 1;
		return msg;
	}

	private void rxHandler(byte[] data, InetSocketAddress remote) {
		ConfirmMessage confirmMsg = ConfirmMessage.deserialize(data);
		if (confirmMsg != null) {
			handleConfirmMessage(confirmMsg, remote);
			return;
		}

		NonceReplyMessage nonceReply = NonceReplyMessage.deserialize(data);
		if (nonceReply != null) {
			handleNonceReply(nonceReply, remote);
			return;
		}
	}

	private void handleConfirmMessage(ConfirmMessage msg, InetSocketAddress remote) {
		Long seqNum = msg.getSeqNum();

		synchronized (pendingMessages) {
			if (pendingMessages.get(seqNum) == RequestStatus.SENT) {
				java.util.Set<InetSocketAddress> confs = confirmations.get(seqNum);
				if (confs != null) {
					confs.add(remote); // Track unique responders

					// If we get f+1 matching responses, we can safely accept/reject
					if (msg.getAccepted() && confs.size() >= quorumSize) {
						pendingMessages.replace(seqNum, RequestStatus.ACCEPTED);
						pendingMessages.notifyAll();
					} else if (!msg.getAccepted() && confs.size() >= quorumSize) {
						pendingMessages.replace(seqNum, RequestStatus.REJECTED);
						pendingMessages.notifyAll();
					}
				}
			}
		}
	}

	private void handleNonceReply(NonceReplyMessage msg, InetSocketAddress remote) {
		synchronized (nonceSyncLock) {
			if (nonceSyncComplete) {
				return; // Already completed, ignore late responses
			}

			long receivedNonce = msg.getNonce();

			java.util.Set<InetSocketAddress> voters = nonceVotes.computeIfAbsent(receivedNonce, k -> new java.util.HashSet<>());

			voters.add(remote);

			if (voters.size() >= quorumSize) {
				this.currentNonce = receivedNonce;
				nonceSyncComplete = true;
				nonceSyncLock.notifyAll();
			}
		}
	}
}

