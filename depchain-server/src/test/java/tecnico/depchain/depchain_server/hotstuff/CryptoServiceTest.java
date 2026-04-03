package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_server.hotstuff.Message.MsgType;

public class CryptoServiceTest {

	private static final int N = 4;
	private static final int F = 1;
	private static final int THRESHOLD = 2 * F + 1;

	private static List<KeyPair> keyPairs;
	private static List<PublicKey> publicKeys;
	private static CryptoService[] cryptoServices;

	private static ThresholdCrypto.DealerParams dealerParams;
	private static ThresholdCrypto[] thresholdCryptos;

	@BeforeAll
	static void setup() throws Exception {
		keyPairs = CryptoService.generateKeyPairs(N);
		publicKeys = CryptoService.extractPublicKeys(keyPairs);
		cryptoServices = new CryptoService[N];

		dealerParams = ThresholdCrypto.generateParams(THRESHOLD, N);
		thresholdCryptos = new ThresholdCrypto[N];

		for (int i = 0; i < N; i++) {
			cryptoServices[i] = new CryptoService(i, keyPairs.get(i), publicKeys);
			thresholdCryptos[i] = new ThresholdCrypto(i, THRESHOLD,
					dealerParams.pairingParamsStr, dealerParams.generator,
					dealerParams.globalPublicKey, dealerParams.privateShares.get(i),
					dealerParams.publicShares);
		}
	}

	@Test
	void testSignAndVerifyEd25519() {
		byte[] data = "Hello HotStuff".getBytes();
		byte[] signature = cryptoServices[0].sign(data);
		assertTrue(cryptoServices[1].verify(0, data, signature));
	}

	@Test
	void testBLSAggregationAndThresholdVerification() {
		byte[] data = "Aggregate Test".getBytes();

		Map<Integer, byte[]> partialSigs = new HashMap<>();
		for (int i = 0; i < THRESHOLD; i++) {
			partialSigs.put(i, thresholdCryptos[i].signPartial(data));
		}

		byte[] thresholdSig = thresholdCryptos[0].aggregateShares(partialSigs);
		assertNotNull(thresholdSig);

		assertTrue(thresholdCryptos[1].verifyThreshold(data, thresholdSig));
	}

	@Test
	void testQCThresholdIntegration() {
		TreeNode node = new TreeNode(new byte[32], new tecnico.depchain.depchain_server.blockchain.Block());
		QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 5, node);

		byte[] voteData = CryptoService.buildVoteData(MsgType.PREPARE, 5, node.getHash());

		for (int i = 0; i < THRESHOLD; i++) {
			byte[] sig = thresholdCryptos[i].signPartial(voteData);
			qc.addVote(i, sig);
		}

		byte[] thresholdSig = thresholdCryptos[0].aggregateShares(qc.getSignatures());
		qc.setThresholdSignature(thresholdSig);

		assertTrue(qc.verifyThreshold(thresholdCryptos[1], dealerParams.globalPublicKey));
	}
}
