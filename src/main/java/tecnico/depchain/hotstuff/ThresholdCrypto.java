package tecnico.depchain.hotstuff;

import java.io.IOException;
import java.io.Serializable;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.weavechain.curve25519.EdwardsPoint;
import com.weavechain.curve25519.InvalidEncodingException;
import com.weavechain.curve25519.Scalar;
import com.weavechain.sig.ThresholdSigEd25519;
import com.weavechain.sig.ThresholdSigEd25519Params;

import tecnico.depchain.DepchainUtils;

/**
 * Wraps the weavechain threshold-sig (Ed25519) library for HotStuff QC signatures.
 *
 * <p>Threshold (k,n) signature scheme: k = 2f+1 partial signatures from n replicas
 * can be combined into a single compact signature verifiable by anyone with the
 * shared public key.
 *
 * <p>The library uses Schnorr-based Ed25519 threshold signatures. Nonces are
 * generated freshly during each signing operation (gatherRi populates them).
 * This class supports both centralized signing (all shares in one place) and
 * distributed signing (per-replica operations).
 */
public class ThresholdCrypto {
	private final ThresholdSigEd25519 scheme;
	private final byte[] publicKey;
	private final int replicaId;
	private final int threshold;
	private final int numReplicas;

	// Per-replica key material (for distributed signing)
	private final Scalar myPrivateShare;

	// Full params (for centralized signing only)
	private ThresholdSigEd25519Params fullParams;

	/**
	 * Per-replica constructor: each replica only knows its own private share
	 * and the shared public key.
	 */
	public ThresholdCrypto(int replicaId, byte[] publicKey,
			Scalar privateShare, int threshold, int numReplicas) {
		this.replicaId = replicaId;
		this.publicKey = publicKey;
		this.myPrivateShare = privateShare;
		this.threshold = threshold;
		this.numReplicas = numReplicas;
		this.scheme = new ThresholdSigEd25519(threshold, numReplicas);
		this.fullParams = null;
	}

	/**
	 * Full-params constructor: used for centralized signing (tests / demo).
	 */
	public ThresholdCrypto(int replicaId, ThresholdSigEd25519Params params,
			int threshold, int numReplicas) {
		this.replicaId = replicaId;
		this.publicKey = params.getPublicKey();
		this.myPrivateShare = params.getPrivateShares().get(replicaId);
		this.threshold = threshold;
		this.numReplicas = numReplicas;
		this.scheme = new ThresholdSigEd25519(threshold, numReplicas);
		this.fullParams = params;
	}

	/**
	 * Whether this instance can perform centralized signing (has all key shares).
	 */
	public boolean canSignCentralized() { return fullParams != null; }

	// ========== Setup ==========

	/**
	 * Generate fresh threshold signature parameters for all replicas.
	 */
	public static ThresholdSigEd25519Params generateParams(int threshold, int numReplicas)
			throws Exception {
		ThresholdSigEd25519 tsg = new ThresholdSigEd25519(threshold, numReplicas);
		return tsg.generate();
	}

	// ========== Internal helpers ==========

	/**
	 * The library's verify() requires messages >= 32 bytes. We canonicalize all
	 * messages through SHA-256 so any input length works uniformly.
	 */
	private static String hashMessage(String message) {
		byte[] hash = DepchainUtils.sha256(message.getBytes());
		StringBuilder sb = new StringBuilder(hash.length * 2);
		for (byte b : hash) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	/**
	 * Overload for raw byte[] messages (used by HotStuff vote data).
	 */
	private static String hashMessage(byte[] data) {
		byte[] hash = DepchainUtils.sha256(data);
		StringBuilder sb = new StringBuilder(hash.length * 2);
		for (byte b : hash) sb.append(String.format("%02x", b));
		return sb.toString();
	}

	// ========== Centralized signing (all shares available) ==========

	/**
	 * Sign a message using all key shares at once (centralized mode).
	 * Nonces are generated freshly by gatherRi and stored in fullParams.
	 * Requires this instance to have been created with full params.
	 * Synchronized on fullParams because gatherRi is stateful.
	 */
	public byte[] signCentralized(String message, Set<Integer> signerIndices)
			throws NoSuchAlgorithmException, IOException {
		if (fullParams == null)
			throw new IllegalStateException("Centralized signing requires full params");

		synchronized (fullParams) {
			String hashed = hashMessage(message);
			List<EdwardsPoint> Ris = scheme.gatherRi(fullParams, hashed, signerIndices);
			EdwardsPoint R = scheme.computeR(Ris);
			Scalar k = scheme.computeK(publicKey, R, hashed);
			List<Scalar> partialSigs = scheme.gatherSignatures(fullParams, k, signerIndices);
			return scheme.computeSignature(R, partialSigs);
		}
	}

	/**
	 * Sign raw byte data (e.g., vote tuples) using all key shares at once.
	 * Synchronized on fullParams because gatherRi is stateful.
	 */
	public byte[] signCentralized(byte[] data, Set<Integer> signerIndices)
			throws NoSuchAlgorithmException, IOException {
		if (fullParams == null)
			throw new IllegalStateException("Centralized signing requires full params");

		synchronized (fullParams) {
			String hashed = hashMessage(data);
			List<EdwardsPoint> Ris = scheme.gatherRi(fullParams, hashed, signerIndices);
			EdwardsPoint R = scheme.computeR(Ris);
			Scalar k = scheme.computeK(publicKey, R, hashed);
			List<Scalar> partialSigs = scheme.gatherSignatures(fullParams, k, signerIndices);
			return scheme.computeSignature(R, partialSigs);
		}
	}

	// ========== Distributed signing (per-replica methods) ==========

	/**
	 * Step 1 (replica): Compute this replica's nonce commitment R_i for a message.
	 * Returns (r_i scalar, R_i point). The replica must retain r_i for step 4.
	 * R_i is sent to the leader.
	 */
	public NonceCommitment computeCommitment(String message) throws NoSuchAlgorithmException {
		String hashed = hashMessage(message);
		Scalar ri = scheme.computeRi(myPrivateShare, hashed);
		EdwardsPoint Ri = ThresholdSigEd25519.mulBasepoint(ri);
		return new NonceCommitment(ri, Ri);
	}

	/** Overload for raw byte[] vote data. */
	public NonceCommitment computeCommitment(byte[] data) throws NoSuchAlgorithmException {
		String hashed = hashMessage(data);
		Scalar ri = scheme.computeRi(myPrivateShare, hashed);
		EdwardsPoint Ri = ThresholdSigEd25519.mulBasepoint(ri);
		return new NonceCommitment(ri, Ri);
	}

	/**
	 * Step 2 (leader): Aggregate all R_i commitments into R.
	 */
	public EdwardsPoint aggregateCommitments(List<EdwardsPoint> commitments) {
		return scheme.computeR(commitments);
	}

	/**
	 * Step 3 (leader or any party): Compute the challenge k from the aggregated R.
	 */
	public Scalar computeChallenge(EdwardsPoint R, String message) throws NoSuchAlgorithmException {
		return scheme.computeK(publicKey, R, hashMessage(message));
	}

	/** Overload for raw byte[] vote data. */
	public Scalar computeChallenge(EdwardsPoint R, byte[] data) throws NoSuchAlgorithmException {
		return scheme.computeK(publicKey, R, hashMessage(data));
	}

	/**
	 * Step 4 (replica): Compute this replica's partial signature given
	 * the challenge k and the nonce r_i retained from step 1.
	 */
	public Scalar computePartialSignature(Scalar ri, Scalar k, Set<Integer> signerSet) {
		return scheme.computeSignature(replicaId + 1, myPrivateShare, ri, k, signerSet);
	}

	/**
	 * Step 5 (leader): Combine R and all partial signatures into the final threshold signature.
	 */
	public byte[] combineSignatures(EdwardsPoint R, List<Scalar> partialSigs) throws IOException {
		return scheme.computeSignature(R, partialSigs);
	}

	// ========== Verification ==========

	/**
	 * Verify a threshold signature against the shared public key.
	 * Note: the library's verify() takes (publicKey, signatureBytes, messageBytes).
	 */
	public static boolean verify(byte[] publicKey, String message, byte[] signature) {
		try {
			String hashed = hashMessage(message);
			return ThresholdSigEd25519.verify(publicKey, signature, hashed.getBytes());
		} catch (NoSuchAlgorithmException | InvalidEncodingException e) {
			return false;
		}
	}

	/**
	 * Verify raw byte[] message against the shared public key.
	 */
	public static boolean verify(byte[] publicKey, byte[] data, byte[] signature) {
		try {
			String hashed = hashMessage(data);
			return ThresholdSigEd25519.verify(publicKey, signature, hashed.getBytes());
		} catch (NoSuchAlgorithmException | InvalidEncodingException e) {
			return false;
		}
	}

	/**
	 * Verify using this instance's public key (String message).
	 */
	public boolean verify(String message, byte[] signature) {
		return verify(publicKey, message, signature);
	}

	/**
	 * Verify using this instance's public key (byte[] data).
	 */
	public boolean verify(byte[] data, byte[] signature) {
		return verify(publicKey, data, signature);
	}

	// ========== Data types ==========

	/**
	 * Holds the ephemeral nonce scalar r_i and commitment point R_i
	 * produced during distributed signing step 1.
	 */
	public static class NonceCommitment {
		private final Scalar ri;
		private final EdwardsPoint Ri;

		public NonceCommitment(Scalar ri, EdwardsPoint Ri) {
			this.ri = ri;
			this.Ri = Ri;
		}

		public Scalar getNonceScalar() { return ri; }
		public EdwardsPoint getCommitmentPoint() { return Ri; }
	}

	/**
	 * Represents a replica's key share for distribution during setup.
	 */
	public static class ReplicaShare implements Serializable {
		private final Scalar privateShare;

		public ReplicaShare(Scalar privateShare) {
			this.privateShare = privateShare;
		}

		public Scalar getPrivateShare() { return privateShare; }
	}

	/**
	 * Extract per-replica shares from full params (done during setup / key distribution).
	 */
	public static List<ReplicaShare> extractShares(ThresholdSigEd25519Params params, int n) {
		List<ReplicaShare> shares = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			shares.add(new ReplicaShare(params.getPrivateShares().get(i)));
		}
		return shares;
	}

	// ========== Accessors ==========

	public byte[] getPublicKey() { return publicKey; }
	public int getReplicaId() { return replicaId; }
}
