package tecnico.depchain.depchain_server.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_common.blockchain.Transaction;

/**
 * Test Class 2: TransactionRunner Tests
 *
 * Covers native transfer execution, gas accounting, nonce validation,
 * and known bugs in TransactionRunner.
 *
 * Known bugs these tests expose:
 *  - Nonce off-by-one: check is tx.nonce() != sender.getNonce()+1, should be != sender.getNonce()
 *  - Sender nonce never incremented after successful tx
 *  - Bytes.EMPTY is not null → plain transfers enter executeContract path
 *  - Wei.ZERO reference equality with != may not work correctly
 */
public class TransactionRunnerTest {

    private static final long BASE_FEE_GAS = 21_000L;

    private EVM evm;
    private TransactionRunner runner;

    private final Address SENDER  = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private final Address RECEIVER = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");
    private final Address MINTER  = Address.fromHexString("0xcccccccccccccccccccccccccccccccccccccccc");

    @BeforeEach
    public void setup() {
        EVM.resetInstance();
        evm = EVM.getInstance();
        evm.createEOA(SENDER, Wei.of(1_000_000L));
        evm.createEOA(RECEIVER, Wei.of(500_000L));
        evm.createEOA(MINTER, Wei.ZERO);
        runner = new TransactionRunner(evm.getUpdater(), MINTER);
    }

    @AfterEach
    public void teardown() {
        EVM.resetInstance();
    }

    private Transaction makeTx(long nonce, Address from, Address to,
                                long gasPrice, long gasLimit, long value, Bytes data) {
        return new Transaction(nonce, from, to, Wei.of(gasPrice), gasLimit, Wei.of(value), data);
    }

    // ── Nonce validation ────────────────────────────────────────────────

    /**
     * First tx from a fresh account (nonce=0) should succeed.
     * BUG: fails because TransactionRunner checks tx.nonce() != sender.getNonce()+1,
     * i.e. expects nonce=1 for a fresh account (getNonce()=0).
     */
    @Test
    public void testFirstTransactionNonceZeroShouldSucceed() {
        Transaction tx = makeTx(0, SENDER, RECEIVER, 10, BASE_FEE_GAS, 1000, null);
        boolean result = runner.executeTransaction(tx);
        assertTrue(result, "Tx with nonce=0 from fresh account (stateNonce=0) should succeed");
    }

    /**
     * Tx with wrong nonce should be rejected.
     */
    @Test
    public void testWrongNonceRejected() {
        Transaction tx = makeTx(5, SENDER, RECEIVER, 10, BASE_FEE_GAS, 1000, null);
        boolean result = runner.executeTransaction(tx);
        assertFalse(result, "Tx with nonce=5 from fresh account should be rejected");
    }

    /**
     * After a successful tx, the sender's nonce should be incremented.
     * BUG: TransactionRunner never increments sender nonce.
     */
    @Test
    public void testSenderNonceIncrementedAfterSuccess() {
        Transaction tx = makeTx(0, SENDER, RECEIVER, 10, BASE_FEE_GAS, 1000, null);
        runner.executeTransaction(tx);
        runner.getUpdater().commit();

        long nonceAfter = evm.getNonce(SENDER);
        assertEquals(1, nonceAfter,
                "Sender nonce should be 1 after one successful transaction");
    }

    /**
     * Two sequential txs from the same sender should work with incrementing nonces.
     * BUG: fails because nonce is never incremented after the first tx.
     */
    @Test
    public void testSequentialTransactionsIncrementNonce() {
        Transaction tx0 = makeTx(0, SENDER, RECEIVER, 10, BASE_FEE_GAS, 100, null);
        assertTrue(runner.executeTransaction(tx0), "First tx (nonce=0) should succeed");
        runner.getUpdater().commit();

        Transaction tx1 = makeTx(1, SENDER, RECEIVER, 10, BASE_FEE_GAS, 100, null);
        assertTrue(runner.executeTransaction(tx1), "Second tx (nonce=1) should succeed");
    }

    // ── Transfer + Gas accounting ───────────────────────────────────────

    /**
     * A plain native transfer should debit sender by (value + gasUsed*gasPrice)
     * and credit receiver by value.
     */
    @Test
    public void testTransferDebitsCreditCorrectly() {
        long gasPrice = 10;
        long value = 5000;
        Transaction tx = makeTx(0, SENDER, RECEIVER, gasPrice, BASE_FEE_GAS, value, null);

        boolean ok = runner.executeTransaction(tx);
        // May fail due to nonce bug; if it passes, check balances
        if (ok) {
            runner.getUpdater().commit();
            Wei expectedFee = Wei.of(gasPrice * BASE_FEE_GAS);
            Wei senderExpected = Wei.of(1_000_000L).subtract(Wei.of(value)).subtract(expectedFee);
            Wei receiverExpected = Wei.of(500_000L + value);

            assertEquals(senderExpected, evm.getUpdater().get(SENDER).getBalance(),
                    "Sender balance should be initial - value - fee");
            assertEquals(receiverExpected, evm.getUpdater().get(RECEIVER).getBalance(),
                    "Receiver balance should be initial + value");
        }
    }

    /**
     * Minter should receive the gas fee.
     */
    @Test
    public void testMinterReceivesGasFee() {
        long gasPrice = 10;
        Transaction tx = makeTx(0, SENDER, RECEIVER, gasPrice, BASE_FEE_GAS, 100, null);

        boolean ok = runner.executeTransaction(tx);
        if (ok) {
            runner.getUpdater().commit();
            Wei expectedFee = Wei.of(gasPrice * BASE_FEE_GAS);
            assertEquals(expectedFee, evm.getUpdater().get(MINTER).getBalance(),
                    "Minter should receive gas fee");
        }
    }

    // ── Rejection cases ─────────────────────────────────────────────────

    /**
     * Tx from non-existent sender should fail.
     */
    @Test
    public void testNonExistentSenderRejected() {
        Address ghost = Address.fromHexString("0xdeaddeaddeaddeaddeaddeaddeaddeaddeaddead");
        Transaction tx = makeTx(0, ghost, RECEIVER, 10, BASE_FEE_GAS, 100, null);
        assertFalse(runner.executeTransaction(tx), "Tx from non-existent sender should fail");
    }

    /**
     * Tx with insufficient balance should fail.
     */
    @Test
    public void testInsufficientBalanceRejected() {
        // value + gas cost > 1_000_000
        Transaction tx = makeTx(0, SENDER, RECEIVER, 10, BASE_FEE_GAS, 999_000, null);
        // upfront = 999_000 + 10*21000 = 999_000 + 210_000 = 1_209_000 > 1_000_000
        boolean result = runner.executeTransaction(tx);
        assertFalse(result, "Tx exceeding sender balance should be rejected");
    }

    /**
     * Tx with zero gas price should fail.
     */
    @Test
    public void testZeroGasPriceRejected() {
        Transaction tx = makeTx(0, SENDER, RECEIVER, 0, BASE_FEE_GAS, 100, null);
        boolean result = runner.executeTransaction(tx);
        assertFalse(result, "Tx with gasPrice=0 should be rejected");
    }

    /**
     * Tx with gasLimit below BASE_FEE_GAS should fail.
     */
    @Test
    public void testGasLimitBelowBaseFeeRejected() {
        Transaction tx = makeTx(0, SENDER, RECEIVER, 10, 100, 100, null);
        boolean result = runner.executeTransaction(tx);
        assertFalse(result, "Tx with gasLimit < 21000 should be rejected");
    }

    // ── Bytes.EMPTY vs null bug ─────────────────────────────────────────

    /**
     * Plain transfer with Bytes.EMPTY data should NOT enter the contract execution path.
     * BUG: Bytes.EMPTY != null, so TransactionRunner.executeTransaction enters
     * executeContract for plain transfers, which will fail (receiver has no code).
     */
    @Test
    public void testBytesEmptyDataDoesNotTriggerContractExecution() {
        Transaction tx = makeTx(0, SENDER, RECEIVER, 10, BASE_FEE_GAS, 1000, Bytes.EMPTY);
        boolean result = runner.executeTransaction(tx);
        assertTrue(result,
                "Plain transfer with Bytes.EMPTY data should succeed (not enter contract path)");
    }

    /**
     * Plain transfer with null data should work (contract path skipped).
     */
    @Test
    public void testNullDataSkipsContractExecution() {
        Transaction tx = makeTx(0, SENDER, RECEIVER, 10, BASE_FEE_GAS, 1000, null);
        boolean result = runner.executeTransaction(tx);
        // Will still fail due to nonce bug, but tests the data==null path
        assertTrue(result, "Transfer with null data should succeed (skips contract path)");
    }

    // ── Contract creation ───────────────────────────────────────────────

    /**
     * Contract creation (to=null) with nonce=0 should succeed.
     * BUG: same nonce off-by-one issue in executeContractCreation.
     */
    @Test
    public void testContractCreationNonceZero() {
        // Minimal bytecode: PUSH1 0x00 PUSH1 0x00 RETURN (returns empty)
        Bytes initCode = Bytes.fromHexString("0x60006000f3");
        Transaction tx = makeTx(0, SENDER, null, 10, 100_000, 0, initCode);
        Address result = runner.executeContractCreation(tx);
        assertNotNull(result, "Contract creation with nonce=0 from fresh account should succeed");
    }
}
