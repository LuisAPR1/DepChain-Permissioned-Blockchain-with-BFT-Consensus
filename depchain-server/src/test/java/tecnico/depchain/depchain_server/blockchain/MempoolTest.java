package tecnico.depchain.depchain_server.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
import tecnico.depchain.depchain_common.blockchain.Transaction;

/**
 * Test Class 3: Mempool Tests
 *
 * Covers:
 *  - addTransaction and totalSize
 *  - getTopTransactions ordering: highest gasPrice first, nonce ordering per sender
 *  - Gas limit cap in getTopTransactions
 *  - onBlockCommitted cleanup
 *  - Multiple senders interleaving
 */
public class MempoolTest {

    private static final long GAS_LIMIT = 21_000L;

    private Mempool mempool;

    private final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private final Address BOB   = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private final Address CAROL = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

    @BeforeEach
    public void setup() {
        mempool = new Mempool();
    }

    private SignedTransaction makeSigned(long nonce, Address from, Address to,
                                          long gasPrice, long gasLimit, long value) {
        Transaction tx = new Transaction(nonce, from, to, Wei.of(gasPrice), gasLimit, Wei.of(value), null);
        return new SignedTransaction(tx, new byte[64]); // dummy signature
    }

    // ── getTopTransactions ordering ─────────────────────────────────────

    /**
     * Single sender with two txs: should return in nonce order regardless of gas price.
     */
    @Test
    public void testSingleSenderNonceOrdering() {
        mempool.addTransaction(makeSigned(1, ALICE, BOB, 50, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(0, ALICE, BOB, 10, GAS_LIMIT, 100));

        List<Transaction> top = mempool.getTopTransactions(Long.MAX_VALUE);
        assertEquals(2, top.size(), "Should return both txs");
        assertEquals(0, top.get(0).nonce(), "First tx should be nonce=0 (lower nonce first)");
        assertEquals(1, top.get(1).nonce(), "Second tx should be nonce=1");
    }

    /**
     * Two senders: the one with higher gasPrice head should come first.
     */
    @Test
    public void testMultiSenderGasPriceOrdering() {
        // Alice: nonce=0, gasPrice=10
        mempool.addTransaction(makeSigned(0, ALICE, CAROL, 10, GAS_LIMIT, 100));
        // Bob: nonce=0, gasPrice=50
        mempool.addTransaction(makeSigned(0, BOB, CAROL, 50, GAS_LIMIT, 100));

        List<Transaction> top = mempool.getTopTransactions(Long.MAX_VALUE);
        assertEquals(2, top.size(), "Should return both txs");
        assertEquals(BOB, top.get(0).from(),
                "Bob's tx (gasPrice=50) should come before Alice's (gasPrice=10)");
        assertEquals(ALICE, top.get(1).from(),
                "Alice's tx should come second");
    }

    // ── Gas limit cap ───────────────────────────────────────────────────

    /**
     * getTopTransactions should stop when cumulative gas exceeds maxGasLimit.
     */
    @Test
    public void testGasLimitCap() {
        // Each tx uses 21000 gas. With limit of 50000, only 2 can fit (2*21000=42000 < 50000, 3*21000=63000 > 50000)
        mempool.addTransaction(makeSigned(0, ALICE, BOB, 10, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(1, ALICE, BOB, 10, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(2, ALICE, BOB, 10, GAS_LIMIT, 100));

        List<Transaction> top = mempool.getTopTransactions(50_000);
        assertEquals(2, top.size(), "Only 2 txs should fit within 50000 gas limit");
    }

    // ── onBlockCommitted cleanup ────────────────────────────────────────

    @Test
    public void testOnBlockCommittedRemovesExecutedTxs() {
        SignedTransaction stx0 = makeSigned(0, ALICE, BOB, 10, GAS_LIMIT, 100);
        SignedTransaction stx1 = makeSigned(1, ALICE, BOB, 10, GAS_LIMIT, 200);
        mempool.addTransaction(stx0);
        mempool.addTransaction(stx1);
        assertEquals(2, mempool.totalSize());

        // Simulate block committing tx with nonce=0
        mempool.onBlockCommitted(List.of(stx0.tx()));
        assertEquals(1, mempool.totalSize(),
                "Should have 1 tx remaining after committing nonce=0");

        // Remaining tx should be nonce=1
        List<Transaction> remaining = mempool.getTopTransactions(Long.MAX_VALUE);
        assertEquals(1, remaining.size());
        assertEquals(1, remaining.get(0).nonce(), "Remaining tx should be nonce=1");
    }

}
