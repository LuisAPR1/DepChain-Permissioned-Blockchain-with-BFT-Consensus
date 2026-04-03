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

    /**
     * A transaction signed by Alice should NOT verify against Bob's public key.
     */
    @Test
    public void testSignatureFailsWithWrongKey() {
        Transaction tx = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        SignedTransaction stx = SignedTransaction.signTansaction(tx, aliceKey);
        assertFalse(stx.verify(bobPubKey),
                "Tx signed by Alice should NOT verify with Bob's public key");
    }

    /**
     * A corrupted signature should not verify.
     */
    @Test
    public void testCorruptedSignatureRejected() {
        Transaction tx = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        SignedTransaction stx = SignedTransaction.signTansaction(tx, aliceKey);

        // Corrupt the signature by flipping bits
        byte[] corruptedSig = stx.signature().clone();
        corruptedSig[0] ^= 0xFF;
        corruptedSig[corruptedSig.length - 1] ^= 0xFF;

        SignedTransaction corrupted = new SignedTransaction(tx, corruptedSig);
        assertFalse(corrupted.verify(alicePubKey),
                "Corrupted signature should not verify");
    }

    /**
     * Null signature should not verify.
     */
    @Test
    public void testNullSignatureRejected() {
        Transaction tx = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        SignedTransaction stx = new SignedTransaction(tx, null);
        assertFalse(stx.verify(alicePubKey), "Null signature should not verify");
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

    // ── TransactionRunner rejections (Byzantine at execution level) ─────

    /**
     * Byzantine client creates a tx from a non-existent account.
     */
    @Test
    public void testGhostSenderRejected() {
        Address ghost = Address.fromHexString("0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead");
        Transaction tx = makeTx(0, ghost, BOB, 10, BASE_FEE_GAS, 100);
        assertFalse(runner.executeTransaction(tx),
                "Tx from non-existent sender should be rejected by TransactionRunner");
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
     * Byzantine client skips a nonce (submits nonce=5 when expected is 0).
     */
    @Test
    public void testNonceGapRejected() {
        Transaction tx = makeTx(5, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        assertFalse(runner.executeTransaction(tx),
                "Tx with nonce gap should be rejected");
    }

    /**
     * Byzantine client submits tx with zero gas price.
     */
    @Test
    public void testZeroGasPriceRejected() {
        Transaction tx = makeTx(0, ALICE, BOB, 0, BASE_FEE_GAS, 100);
        assertFalse(runner.executeTransaction(tx),
                "Tx with zero gas price should be rejected");
    }

    /**
     * Byzantine client submits tx with gas limit below BASE_FEE_GAS.
     */
    @Test
    public void testInsufficientGasLimitRejected() {
        Transaction tx = makeTx(0, ALICE, BOB, 10, 100, 100);
        assertFalse(runner.executeTransaction(tx),
                "Tx with gasLimit < 21000 should be rejected");
    }

    // ── Double-spend via mempool ────────────────────────────────────────

    /**
     * Byzantine client submits two txs with the same nonce to the mempool.
     * The second should overwrite the first (mempool is a TreeMap keyed by nonce).
     * When the block is built, only one tx per nonce should execute.
     */
    @Test
    public void testDoubleSpendSameNonceInMempool() {
        Mempool mempool = new Mempool();

        Transaction tx1 = makeTx(0, ALICE, BOB, 10, BASE_FEE_GAS, 100);
        Transaction tx2 = makeTx(0, ALICE, BOB, 20, BASE_FEE_GAS, 200);

        SignedTransaction stx1 = new SignedTransaction(tx1, new byte[64]);
        SignedTransaction stx2 = new SignedTransaction(tx2, new byte[64]);

        mempool.addTransaction(stx1);
        mempool.addTransaction(stx2);

        // TreeMap keyed by nonce → second overwrites first
        assertEquals(1, mempool.totalSize(),
                "Mempool should have only 1 tx (second overwrites first with same nonce)");

        java.util.List<Transaction> top = mempool.getTopTransactions(Long.MAX_VALUE);
        assertEquals(1, top.size());
        assertEquals(Wei.of(20), top.get(0).gasPrice(),
                "The overwritten tx should be the one with gasPrice=20");
    }

    /**
     * After executing a tx, a Byzantine client cannot execute another tx with the same nonce.
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
