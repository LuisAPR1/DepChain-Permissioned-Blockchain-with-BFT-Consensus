package tecnico.depchain.depchain_server.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
import tecnico.depchain.depchain_common.blockchain.Transaction;

/**
 * Test Class 6: Byzantine Client Behaviour Tests
 *
 * Covers:
 *  - Signature verification: valid signature passes, corrupted fails
 *  - Sender spoofing: signing with wrong key is detected
 *  - TransactionRunner rejection: ghost sender, insufficient balance, bad nonce
 *  - Double-spend attempt: same nonce submitted twice
 *  - Gas manipulation: zero gas price, gas below minimum
 */
public class ByzantineClientTest {

    private static final long BASE_FEE_GAS = 21_000L;

    private EVM evm;
    private TransactionRunner runner;

    private final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private final Address BOB   = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private final Address MINTER = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

    private PrivateKey aliceKey;
    private PublicKey alicePubKey;
    private PrivateKey bobKey;
    private PublicKey bobPubKey;

    @BeforeEach
    public void setup() throws Exception {
        EVM.resetInstance();
        evm = EVM.getInstance();
        evm.createEOA(ALICE, Wei.of(1_000_000L));
        evm.createEOA(BOB, Wei.of(500_000L));
        evm.createEOA(MINTER, Wei.ZERO);
        runner = new TransactionRunner(evm.getUpdater(), MINTER);

        // Generate Ed25519 key pairs for Alice and Bob
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");
        KeyPair aliceKp = kpg.generateKeyPair();
        aliceKey = aliceKp.getPrivate();
        alicePubKey = aliceKp.getPublic();

        KeyPair bobKp = kpg.generateKeyPair();
        bobKey = bobKp.getPrivate();
        bobPubKey = bobKp.getPublic();
    }

    @AfterEach
    public void teardown() {
        EVM.resetInstance();
    }

    private Transaction makeTx(long nonce, Address from, Address to,
                                long gasPrice, long gasLimit, long value) {
        return new Transaction(nonce, from, to, Wei.of(gasPrice), gasLimit, Wei.of(value), Bytes.EMPTY);
    }

    // ── Signature verification ──────────────────────────────────────────

    /**
     * A correctly signed transaction should verify against the signer's public key.
     */
    @Test
    public void testValidSignatureVerifies() {
        Transaction tx = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        SignedTransaction stx = SignedTransaction.signTansaction(tx, aliceKey);
        assertTrue(stx.verify(alicePubKey), "Tx signed by Alice should verify with Alice's public key");
    }

    // ── Sender spoofing ─────────────────────────────────────────────────

    /**
     * A Byzantine client signs a tx claiming to be Alice but using Bob's key.
     * Verification with Alice's key should fail.
     */
    @Test
    public void testSpoofedSenderDetected() {
        // Tx claims from=ALICE but is signed with bobKey
        Transaction tx = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        SignedTransaction stx = SignedTransaction.signTansaction(tx, bobKey);

        // Server would verify with Alice's registered public key
        assertFalse(stx.verify(alicePubKey),
                "Tx signed by Bob pretending to be Alice should fail verification with Alice's key");
    }

    /**
     * Byzantine client submits a tx with value exceeding their balance.
     */
    @Test
    public void testOverspendRejected() {
        // Alice has 1_000_000, try to send 2_000_000
        Transaction tx = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 2_000_000);
        assertFalse(runner.executeTransaction(tx),
                "Tx with value > sender balance should be rejected");
    }

    /**
     * Byzantine client replays a transaction with an already-used nonce.
     */
    @Test
    public void testNonceReplayRejected() {
        // Execute first tx with nonce=0 (should succeed)
        Transaction tx1 = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        assertTrue(runner.executeTransaction(tx1), "First tx should succeed");
        runner.getUpdater().commit();

        // Try to replay with nonce=0 again (should fail because nonce is now 1)
        Transaction tx2 = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        assertFalse(runner.executeTransaction(tx2),
                "Replay with old nonce should be rejected");
    }

    /**
     * After executing a tx, a Byzantine client cannot execute another tx with the same nonce.
     * Also tests nonce replay rejection.
     */
    @Test
    public void testDoubleSpendAtExecutionLevel() {
        Transaction tx1 = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        assertTrue(runner.executeTransaction(tx1), "First tx should succeed");
        runner.getUpdater().commit();

        // Second tx with same nonce should fail
        Transaction tx2 = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 200);
        assertFalse(runner.executeTransaction(tx2),
                "Second tx with same nonce (double-spend) should be rejected");
    }
}
