package tecnico.depchain.hotstuff;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import tecnico.depchain.hotstuff.Message.MsgType;

/**
 * A Quorum Certificate aggregates (n-f) votes for a specific (type, view, node) tuple.
 *
 * <p>For Byzantine fault tolerance (Step 5), each vote carries an Ed25519 signature
 * over the canonical vote data (type, viewNumber, nodeHash). The QC stores these
 * individual signatures so any replica can independently verify the certificate.
 */
public class QuorumCertificate implements Serializable {
	private MsgType type;
	private int viewNumber;
	private TreeNode node;

	// replicaId → Ed25519 signature over (type, viewNumber, nodeHash)
	private Map<Integer, byte[]> signatures;

	// Threshold (k,n) signature over (type, viewNumber, nodeHash).
	// Created by the leader after collecting n-f individually verified votes.
	private byte[] thresholdSignature;

	public QuorumCertificate(MsgType type, int viewNumber, TreeNode node) {
		this.type = type;
		this.viewNumber = viewNumber;
		this.node = node;
		this.signatures = new HashMap<>();
	}

	/**
	 * Add a verified vote with its signature (Byzantine-tolerant path).
	 * The caller should verify the signature before calling this method.
	 */
	public void addVote(int replicaId, byte[] signature) {
		signatures.put(replicaId, signature);
	}

	public int getVoteCount() {
		return signatures.size();
	}

	public boolean hasQuorum(int quorumSize) {
		return signatures.size() >= quorumSize;
	}

	public Set<Integer> getVoterIds() {
		return signatures.keySet();
	}

	/**
	 * Verify this QC using the best available method:
	 * 1. Threshold signature verification – O(1), as described in the paper's
	 *    tverify scheme (Section 3). Used when the leader has attached a
	 *    threshold signature via tcombine after collecting n-f votes.
	 * 2. Individual Ed25519 signature verification – O(n) fallback when no
	 *    threshold signature is present (e.g., crash-only mode / Step 4).
	 */
	public boolean verify(CryptoService crypto, int quorumSize) {
		if (node == null) return false;

		byte[] thresholdPubKey = crypto.getThresholdPublicKey();
		if (thresholdPubKey != null && thresholdSignature != null
				&& verifyThreshold(thresholdPubKey)) {
			return true;
		}

		return verifyIndividual(crypto, quorumSize);
	}

	/**
	 * Verify all stored individual Ed25519 signatures.
	 * Returns true if at least quorumSize signatures are cryptographically valid.
	 * Null signatures are NOT counted.
	 */
	private boolean verifyIndividual(CryptoService crypto, int quorumSize) {
		byte[] nodeHash = node.getHash();
		int validCount = 0;

		for (var entry : signatures.entrySet()) {
			int voterId = entry.getKey();
			byte[] sig = entry.getValue();

			if (sig == null) continue;

			if (crypto.verifyVote(voterId, type, viewNumber, nodeHash, sig)) {
				validCount++;
			}
		}

		return validCount >= quorumSize;
	}

	public void setThresholdSignature(byte[] sig) { this.thresholdSignature = sig; }
	public byte[] getThresholdSignature() { return thresholdSignature; }

	/**
	 * Verify the threshold signature against the shared public key.
	 * This provides compact O(1) verification as described in the HotStuff paper's
	 * tcombine/tverify scheme (Section 3, Cryptographic primitives).
	 */
	public boolean verifyThreshold(byte[] thresholdPublicKey) {
		if (thresholdSignature == null || node == null || thresholdPublicKey == null) return false;
		byte[] voteData = CryptoService.buildVoteData(type, viewNumber, node.getHash());
		return ThresholdCrypto.verify(thresholdPublicKey, voteData, thresholdSignature);
	}

	public MsgType getType() { return type; }
	public int getViewNumber() { return viewNumber; }
	public TreeNode getNode() { return node; }

	public boolean matchingQC(MsgType type, int viewNumber) {
		return this.type == type && this.viewNumber == viewNumber;
	}
}
