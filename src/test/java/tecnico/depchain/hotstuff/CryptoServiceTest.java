package tecnico.depchain.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.weavechain.curve25519.EdwardsPoint;
import com.weavechain.curve25519.Scalar;
import com.weavechain.sig.ThresholdSigEd25519Params;

import tecnico.depchain.hotstuff.Message.MsgType;

/**
 * Tests for the cryptographic infrastructure (Step 5 foundation):
 * - Ed25519 individual signatures (CryptoService)
 * - Threshold Ed25519 signatures (ThresholdCrypto)
 */
class CryptoServiceTest {

	private static final int N = 4;
	private static final int F = 1;
	private static final int THRESHOLD = 2 * F + 1; // k = 2f+1 = 3

	private static List<KeyPair> keyPairs;
	private static List<PublicKey> publicKeys;
	private static CryptoService[] cryptoServices;

	@BeforeAll
	static void setup() throws Exception {
		keyPairs = CryptoService.generateKeyPairs(N);
		publicKeys = CryptoService.extractPublicKeys(keyPairs);
		cryptoServices = new CryptoService[N];
		for (int i = 0; i < N; i++) {
			cryptoServices[i] = new CryptoService(i, keyPairs.get(i), publicKeys);
		}
	}

	// ==================== Ed25519 Individual Signatures ====================

	@Test
	void testSignAndVerify() {
		byte[] data = "Hello HotStuff".getBytes();
		byte[] signature = cryptoServices[0].sign(data);

		assertNotNull(signature);
		assertTrue(signature.length > 0);
		assertTrue(cryptoServices[1].verify(0, data, signature),
				"Replica 1 should verify replica 0's signature");
	}

	@Test
	void testVerifyRejectsWrongSender() {
		byte[] data = "Hello HotStuff".getBytes();
		byte[] signature = cryptoServices[0].sign(data);

		assertFalse(cryptoServices[1].verify(2, data, signature),
				"Should reject: signature is from replica 0, not replica 2");
	}

	@Test
	void testVerifyRejectsTamperedData() {
		byte[] data = "Hello HotStuff".getBytes();
		byte[] signature = cryptoServices[0].sign(data);

		byte[] tampered = "Hello Tampered".getBytes();
		assertFalse(cryptoServices[1].verify(0, tampered, signature),
				"Should reject: data was tampered");
	}

	@Test
	void testVerifyRejectsTamperedSignature() {
		byte[] data = "Hello HotStuff".getBytes();
		byte[] signature = cryptoServices[0].sign(data);

		signature[0] ^= 0xFF;
		assertFalse(cryptoServices[1].verify(0, data, signature),
				"Should reject: signature was tampered");
	}

	@Test
	void testVerifyRejectsNullSignature() {
		byte[] data = "Hello HotStuff".getBytes();
		assertFalse(cryptoServices[0].verify(0, data, null));
	}

	@Test
	void testVerifyRejectsInvalidSenderId() {
		byte[] data = "Hello HotStuff".getBytes();
		byte[] signature = cryptoServices[0].sign(data);

		assertFalse(cryptoServices[0].verify(-1, data, signature));
		assertFalse(cryptoServices[0].verify(N, data, signature));
	}

	@Test
	void testVoteSignAndVerify() {
		byte[] nodeHash = new byte[32];
		nodeHash[0] = 42;

		byte[] signature = cryptoServices[2].signVote(MsgType.PREPARE, 5, nodeHash);
		assertTrue(cryptoServices[0].verifyVote(2, MsgType.PREPARE, 5, nodeHash, signature),
				"Valid vote signature should verify");
	}

	@Test
	void testVoteSignatureRejectsWrongType() {
		byte[] nodeHash = new byte[32];
		byte[] signature = cryptoServices[2].signVote(MsgType.PREPARE, 5, nodeHash);

		assertFalse(cryptoServices[0].verifyVote(2, MsgType.PRE_COMMIT, 5, nodeHash, signature),
				"Should reject: wrong message type");
	}

	@Test
	void testVoteSignatureRejectsWrongView() {
		byte[] nodeHash = new byte[32];
		byte[] signature = cryptoServices[2].signVote(MsgType.PREPARE, 5, nodeHash);

		assertFalse(cryptoServices[0].verifyVote(2, MsgType.PREPARE, 6, nodeHash, signature),
				"Should reject: wrong view number");
	}

	@Test
	void testVoteSignatureRejectsWrongNode() {
		byte[] nodeHash = new byte[32];
		byte[] signature = cryptoServices[2].signVote(MsgType.PREPARE, 5, nodeHash);

		byte[] differentHash = new byte[32];
		differentHash[0] = 99;
		assertFalse(cryptoServices[0].verifyVote(2, MsgType.PREPARE, 5, differentHash, signature),
				"Should reject: wrong node hash");
	}

	@Test
	void testAllReplicasCanSignAndCrossVerify() {
		byte[] data = "cross-verify test".getBytes();

		for (int signer = 0; signer < N; signer++) {
			byte[] signature = cryptoServices[signer].sign(data);
			for (int verifier = 0; verifier < N; verifier++) {
				assertTrue(cryptoServices[verifier].verify(signer, data, signature),
						"Replica " + verifier + " should verify replica " + signer + "'s signature");
			}
		}
	}

	// ==================== Threshold Signatures - Centralized ====================

	@Test
	void testThresholdSignCentralizedAndVerify() throws Exception {
		ThresholdSigEd25519Params params = ThresholdCrypto.generateParams(THRESHOLD, N);
		ThresholdCrypto tc = new ThresholdCrypto(0, params, THRESHOLD, N);

		Set<Integer> signers = Set.of(0, 1, 2);
		String message = "threshold-test-message";
		byte[] signature = tc.signCentralized(message, signers);

		assertNotNull(signature);
		assertTrue(signature.length > 0);
		assertTrue(tc.verify(message, signature),
				"Threshold signature should verify with the shared public key");
	}

	@Test
	void testThresholdSignatureRejectsWrongMessage() throws Exception {
		ThresholdSigEd25519Params params = ThresholdCrypto.generateParams(THRESHOLD, N);
		ThresholdCrypto tc = new ThresholdCrypto(0, params, THRESHOLD, N);

		byte[] signature = tc.signCentralized("correct message", Set.of(0, 1, 2));

		assertFalse(tc.verify("wrong message", signature),
				"Threshold signature should not verify with wrong message");
	}

	@Test
	void testThresholdDifferentQuorumsBothVerify() throws Exception {
		ThresholdSigEd25519Params params = ThresholdCrypto.generateParams(THRESHOLD, N);
		ThresholdCrypto tc = new ThresholdCrypto(0, params, THRESHOLD, N);

		String message = "quorum-test";

		byte[] sig1 = tc.signCentralized(message, Set.of(0, 1, 2));
		byte[] sig2 = tc.signCentralized(message, Set.of(1, 2, 3));

		assertTrue(tc.verify(message, sig1), "Signature from quorum {0,1,2} should verify");
		assertTrue(tc.verify(message, sig2), "Signature from quorum {1,2,3} should verify");
	}

	@Test
	void testThresholdShareExtraction() throws Exception {
		ThresholdSigEd25519Params params = ThresholdCrypto.generateParams(THRESHOLD, N);
		List<ThresholdCrypto.ReplicaShare> shares = ThresholdCrypto.extractShares(params, N);

		assertEquals(N, shares.size());
		for (int i = 0; i < N; i++) {
			assertNotNull(shares.get(i).getPrivateShare());
		}
	}

	// ==================== Threshold Signatures - Distributed ====================

	@Test
	void testThresholdDistributedSigningFlow() throws Exception {
		ThresholdSigEd25519Params params = ThresholdCrypto.generateParams(THRESHOLD, N);
		List<ThresholdCrypto.ReplicaShare> shares = ThresholdCrypto.extractShares(params, N);
		byte[] publicKey = params.getPublicKey();

		ThresholdCrypto[] replicas = new ThresholdCrypto[N];
		for (int i = 0; i < N; i++) {
			replicas[i] = new ThresholdCrypto(i, publicKey,
					shares.get(i).getPrivateShare(),
					THRESHOLD, N);
		}

		Set<Integer> signers = Set.of(0, 1, 2);
		String message = "distributed-signing-test";

		// Step 1: each signer computes their nonce commitment (R_i)
		List<ThresholdCrypto.NonceCommitment> commitments = new ArrayList<>();
		for (int id : signers) {
			commitments.add(replicas[id].computeCommitment(message));
		}

		// Step 2: leader aggregates R_i commitments into R
		List<EdwardsPoint> Ris = new ArrayList<>();
		for (var c : commitments)
			Ris.add(c.getCommitmentPoint());
		EdwardsPoint R = replicas[0].aggregateCommitments(Ris);

		// Step 3: leader computes the challenge k
		Scalar k = replicas[0].computeChallenge(R, message);

		// Step 4: each signer computes their partial signature using retained nonce
		List<Scalar> partialSigs = new ArrayList<>();
		int idx = 0;
		for (int id : signers) {
			Scalar ri = commitments.get(idx).getNonceScalar();
			partialSigs.add(replicas[id].computePartialSignature(ri, k, signers));
			idx++;
		}

		// Step 5: leader combines into threshold signature
		byte[] signature = replicas[0].combineSignatures(R, partialSigs);

		assertNotNull(signature);
		assertTrue(replicas[3].verify(message, signature),
				"Distributed threshold signature should verify (even by non-signer replica 3)");
	}

	// ==================== QC Threshold Signature Integration ====================

	@Test
	void testQCThresholdSignatureViaLeaderCentralized() throws Exception {
		ThresholdSigEd25519Params params = ThresholdCrypto.generateParams(THRESHOLD, N);
		ThresholdCrypto tc = new ThresholdCrypto(0, params, THRESHOLD, N);
		byte[] thresholdPubKey = params.getPublicKey();

		TreeNode node = new TreeNode(new byte[32], "qc-threshold-test");
		QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 5, node);

		for (int i = 0; i < THRESHOLD; i++) {
			byte[] sig = cryptoServices[i].signVote(MsgType.PREPARE, 5, node.getHash());
			qc.addVote(i, sig);
		}
		assertTrue(qc.verify(cryptoServices[0], THRESHOLD),
				"QC should verify with individual Ed25519 signatures");

		byte[] voteData = CryptoService.buildVoteData(MsgType.PREPARE, 5, node.getHash());
		byte[] thresholdSig = tc.signCentralized(voteData, qc.getVoterIds());
		qc.setThresholdSignature(thresholdSig);

		assertTrue(qc.verifyThreshold(thresholdPubKey),
				"QC threshold signature should verify with the shared public key");
	}

	@Test
	void testQCThresholdSignatureRejectsWrongData() throws Exception {
		ThresholdSigEd25519Params params = ThresholdCrypto.generateParams(THRESHOLD, N);
		ThresholdCrypto tc = new ThresholdCrypto(0, params, THRESHOLD, N);
		byte[] thresholdPubKey = params.getPublicKey();

		TreeNode node = new TreeNode(new byte[32], "qc-wrong-data");
		QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 5, node);

		byte[] differentVoteData = CryptoService.buildVoteData(MsgType.COMMIT, 99, node.getHash());
		byte[] wrongSig = tc.signCentralized(differentVoteData, Set.of(0, 1, 2));
		qc.setThresholdSignature(wrongSig);

		assertFalse(qc.verifyThreshold(thresholdPubKey),
				"QC threshold signature should fail for mismatched vote data");
	}
}
