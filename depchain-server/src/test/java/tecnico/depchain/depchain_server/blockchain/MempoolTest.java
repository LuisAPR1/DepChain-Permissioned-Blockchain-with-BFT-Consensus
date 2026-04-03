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

    // ── Basic add/size ──────────────────────────────────────────────────

    @Test
    public void testAddTransactionIncreasesSize() {
        assertEquals(0, mempool.totalSize(), "Empty mempool should have size 0");

        mempool.addTransaction(makeSigned(0, ALICE, BOB, 10, GAS_LIMIT, 100));
        assertEquals(1, mempool.totalSize(), "Size should be 1 after one add");

        mempool.addTransaction(makeSigned(1, ALICE, BOB, 10, GAS_LIMIT, 100));
        assertEquals(2, mempool.totalSize(), "Size should be 2 after two adds");
    }

    @Test
    public void testAddDuplicateNonceOverwrites() {
        mempool.addTransaction(makeSigned(0, ALICE, BOB, 10, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(0, ALICE, BOB, 20, GAS_LIMIT, 200));
        assertEquals(1, mempool.totalSize(),
                "Same sender+nonce should overwrite, not duplicate");
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

    /**
     * Interleaving: Bob has higher gasPrice for nonce=0, but Alice has higher for nonce=1.
     * After Bob's nonce=0 is selected, next round compares Alice nonce=0 (10) vs Bob nonce=1 (5).
     */
    @Test
    public void testInterleavedGasPriceSelection() {
        mempool.addTransaction(makeSigned(0, ALICE, CAROL, 10, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(1, ALICE, CAROL, 80, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(0, BOB, CAROL, 50, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(1, BOB, CAROL, 5, GAS_LIMIT, 100));

        List<Transaction> top = mempool.getTopTransactions(Long.MAX_VALUE);
        assertEquals(4, top.size(), "Should return all 4 txs");

        // Round 1: heads are Alice(0,gp=10) vs Bob(0,gp=50) → Bob(0) wins
        assertEquals(BOB, top.get(0).from(), "Round 1: Bob nonce=0 (gp=50) wins");
        assertEquals(0, top.get(0).nonce());

        // Round 2: heads are Alice(0,gp=10) vs Bob(1,gp=5) → Alice(0) wins
        assertEquals(ALICE, top.get(1).from(), "Round 2: Alice nonce=0 (gp=10) wins");
        assertEquals(0, top.get(1).nonce());

        // Round 3: heads are Alice(1,gp=80) vs Bob(1,gp=5) → Alice(1) wins
        assertEquals(ALICE, top.get(2).from(), "Round 3: Alice nonce=1 (gp=80) wins");
        assertEquals(1, top.get(2).nonce());

        // Round 4: only Bob(1) left
        assertEquals(BOB, top.get(3).from(), "Round 4: Bob nonce=1 remaining");
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

    /**
     * If a sender's head tx exceeds remaining gas, the entire sender queue is removed.
     */
    @Test
    public void testSenderQueueRemovedWhenHeadExceedsGas() {
        // Alice has a big tx (gasLimit=40000) then a small one (21000)
        mempool.addTransaction(makeSigned(0, ALICE, BOB, 50, 40_000, 100));
        mempool.addTransaction(makeSigned(1, ALICE, BOB, 50, GAS_LIMIT, 100));
        // Bob has a small tx
        mempool.addTransaction(makeSigned(0, BOB, CAROL, 10, GAS_LIMIT, 100));

        // Gas limit = 50000: Alice nonce=0 (gp=50, gas=40000) selected first → 40000.
        // Next round: Alice nonce=1 (gas=21000) needs 40000+21000=61000 > 50000 → Alice removed.
        // Bob nonce=0 (gas=21000) needs 40000+21000=61000 > 50000 → Bob also removed.
        List<Transaction> top = mempool.getTopTransactions(50_000);
        assertEquals(1, top.size(), "Only Alice's first tx should fit");
        assertEquals(ALICE, top.get(0).from());
    }

    // ── getTopTransactions is non-destructive ───────────────────────────

    @Test
    public void testGetTopTransactionsIsNonDestructive() {
        mempool.addTransaction(makeSigned(0, ALICE, BOB, 10, GAS_LIMIT, 100));
        mempool.addTransaction(makeSigned(1, ALICE, BOB, 10, GAS_LIMIT, 100));

        mempool.getTopTransactions(Long.MAX_VALUE);
        assertEquals(2, mempool.totalSize(),
                "getTopTransactions should not remove txs from the mempool");

        List<Transaction> top2 = mempool.getTopTransactions(Long.MAX_VALUE);
        assertEquals(2, top2.size(),
                "Second call should return same results");
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

    @Test
    public void testOnBlockCommittedRemovesSenderQueueWhenEmpty() {
        SignedTransaction stx = makeSigned(0, ALICE, BOB, 10, GAS_LIMIT, 100);
        mempool.addTransaction(stx);

        mempool.onBlockCommitted(List.of(stx.tx()));
        assertEquals(0, mempool.totalSize(), "Mempool should be empty after committing sole tx");
    }

    // ── Empty mempool edge cases ────────────────────────────────────────

    @Test
    public void testGetTopTransactionsOnEmptyMempool() {
        List<Transaction> top = mempool.getTopTransactions(Long.MAX_VALUE);
        assertTrue(top.isEmpty(), "Empty mempool should return empty list");
    }

    @Test
    public void testOnBlockCommittedOnEmptyMempool() {
        Transaction tx = new Transaction(0, ALICE, BOB, Wei.of(10), GAS_LIMIT, Wei.of(100), null);
        assertDoesNotThrow(() -> mempool.onBlockCommitted(List.of(tx)),
                "onBlockCommitted on empty mempool should not throw");
    }
}
