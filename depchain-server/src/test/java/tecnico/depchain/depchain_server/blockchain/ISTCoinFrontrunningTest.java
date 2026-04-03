package tecnico.depchain.depchain_server.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_common.blockchain.Transaction;

/**
 * IST Coin ERC-20 and frontrunning protection tests.
 */
public class ISTCoinFrontrunningTest {

    private static final String GENESIS_PATH = "../genesis.json";
    private static final long GAS_LIMIT = 200_000L;
    private static final long GAS_PRICE = 10L;

    private static final Address ADMIN = Address.fromHexString("0x1111111111111111111111111111111111111111");
    private static final Address USER  = Address.fromHexString("0x2222222222222222222222222222222222222222");

    // ERC-20 function selectors
    private static final String SEL_DECIMALS      = "313ce567";
    private static final String SEL_BALANCE_OF     = "70a08231";
    private static final String SEL_TRANSFER       = "a9059cbb";
    private static final String SEL_APPROVE        = "095ea7b3";
    private static final String SEL_ALLOWANCE      = "dd62ed3e";
    private static final String SEL_TRANSFER_FROM  = "23b872dd";

    private EVM evm;
    private Address istCoin;

    @BeforeEach
    public void setup() throws Exception {
        EVM.resetInstance();
        evm = EVM.getInstance();
        GenesisLoader.loadGenesis(GENESIS_PATH);
        istCoin = Address.contractAddress(ADMIN, 0L);
    }

    @AfterEach
    public void teardown() {
        EVM.resetInstance();
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String pad32(Address addr) {
        return String.format("%64s", addr.toUnprefixedHexString()).replace(' ', '0');
    }

    private static String pad32(long value) {
        return String.format("%064x", value);
    }

    /** Execute a read-only EVM call and return the output bytes. */
    private Bytes viewCall(Address caller, Bytes callData) {
        EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);

        class ReturnTracer implements org.hyperledger.besu.evm.tracing.OperationTracer {
            Bytes output = Bytes.EMPTY;
            @Override
            public void traceContextExit(org.hyperledger.besu.evm.frame.MessageFrame frame) {
                if (frame.getMessageFrameStack().isEmpty() || frame.getDepth() == 0) {
                    this.output = frame.getOutputData();
                }
            }
        }

        ReturnTracer tracer = new ReturnTracer();
        executor.tracer(tracer);
        executor.baseFee(Wei.ZERO);
        executor.gasLimit(1_000_000L);
        executor.gasPriceGWei(Wei.ZERO);
        executor.sender(caller);
        executor.receiver(istCoin);
        executor.code(evm.getUpdater().getAccount(istCoin).getCode());
        executor.callData(callData);
        executor.worldUpdater(evm.getUpdater().updater());
        executor.execute();

        return tracer.output;
    }

    private BigInteger balanceOf(Address account) {
        Bytes callData = Bytes.fromHexString(SEL_BALANCE_OF + pad32(account));
        Bytes output = viewCall(account, callData);
        return output.isEmpty() ? BigInteger.ZERO : new BigInteger(1, output.toArray());
    }

    private BigInteger allowance(Address owner, Address spender) {
        Bytes callData = Bytes.fromHexString(SEL_ALLOWANCE + pad32(owner) + pad32(spender));
        Bytes output = viewCall(owner, callData);
        return output.isEmpty() ? BigInteger.ZERO : new BigInteger(1, output.toArray());
    }

    /** Execute a state-changing transaction through TransactionRunner. */
    private boolean execTx(long nonce, Address from, Address to,
                            Bytes data, long value) {
        TransactionRunner runner = new TransactionRunner(evm.getUpdater(), ADMIN);
        Transaction tx = new Transaction(nonce, from, to,
                Wei.of(GAS_PRICE), GAS_LIMIT, Wei.of(value), data);
        boolean ok = runner.executeTransaction(tx);
        if (ok) runner.getUpdater().commit();
        return ok;
    }

    // ── ERC-20 basic tests ──────────────────────────────────────────────

    @Test
    public void testDecimalsReturns2() {
        Bytes callData = Bytes.fromHexString(SEL_DECIMALS);
        Bytes output = viewCall(ADMIN, callData);
        assertFalse(output.isEmpty(), "decimals() should return data");
        BigInteger decimals = new BigInteger(1, output.toArray());
        assertEquals(BigInteger.valueOf(2), decimals, "IST Coin should have 2 decimals");
    }

    // ── ERC-20 transfer ─────────────────────────────────────────────────

    @Test
    public void testTransferTokens() {
        long amount = 5000;
        // Admin nonce after genesis contract creation is 1
        Bytes callData = Bytes.fromHexString(SEL_TRANSFER + pad32(USER) + pad32(amount));
        boolean ok = execTx(1, ADMIN, istCoin, callData, 0);
        assertTrue(ok, "Token transfer should succeed");

        BigInteger userBal = balanceOf(USER);
        assertEquals(BigInteger.valueOf(amount), userBal,
                "User should have received " + amount + " tokens");

        BigInteger adminBal = balanceOf(ADMIN);
        BigInteger expectedAdmin = new BigInteger("10000000000").subtract(BigInteger.valueOf(amount));
        assertEquals(expectedAdmin, adminBal,
                "Admin balance should decrease by transferred amount");
    }

    // ── Frontrunning Protection ─────────────────────────────────────────

    /**
     * Attempting to change allowance from non-zero to a different non-zero value
     * should fail (the contract reverts).
     */
    @Test
    public void testApproveFromNonZeroToNonZeroReverts() {
        // First approve: 0 → 1000 (should succeed)
        Bytes approve1 = Bytes.fromHexString(SEL_APPROVE + pad32(USER) + pad32(1000));
        assertTrue(execTx(1, ADMIN, istCoin, approve1, 0),
                "First approve (0→1000) should succeed");

        // Verify allowance is 1000
        assertEquals(BigInteger.valueOf(1000), allowance(ADMIN, USER));

        // Second approve: 1000 → 2000 (should revert due to frontrunning protection)
        Bytes approve2 = Bytes.fromHexString(SEL_APPROVE + pad32(USER) + pad32(2000));
        // The tx will "succeed" at the runner level (gas charged) but the EVM revert
        // means the approve state change is not applied
        execTx(2, ADMIN, istCoin, approve2, 0);

        // Allowance should remain 1000 (the revert rolled back the approve)
        BigInteger finalAllowance = allowance(ADMIN, USER);
        assertEquals(BigInteger.valueOf(1000), finalAllowance,
                "Allowance should remain 1000 — frontrunning protection blocked the change");
    }

    /**
     * Correct pattern: approve to 0 first, then approve to new value.
     */
    @Test
    public void testApproveToZeroThenNewValueSucceeds() {
        // Step 1: approve 0 → 1000
        Bytes approve1 = Bytes.fromHexString(SEL_APPROVE + pad32(USER) + pad32(1000));
        assertTrue(execTx(1, ADMIN, istCoin, approve1, 0),
                "approve(0→1000) should succeed");
        assertEquals(BigInteger.valueOf(1000), allowance(ADMIN, USER));

        // Step 2: approve 1000 → 0 (allowed by frontrunning protection: amount == 0)
        Bytes approveZero = Bytes.fromHexString(SEL_APPROVE + pad32(USER) + pad32(0));
        assertTrue(execTx(2, ADMIN, istCoin, approveZero, 0),
                "approve(1000→0) should succeed");
        assertEquals(BigInteger.ZERO, allowance(ADMIN, USER));

        // Step 3: approve 0 → 2000 (allowed: currentAllowance == 0)
        Bytes approve2 = Bytes.fromHexString(SEL_APPROVE + pad32(USER) + pad32(2000));
        assertTrue(execTx(3, ADMIN, istCoin, approve2, 0),
                "approve(0→2000) should succeed");
        assertEquals(BigInteger.valueOf(2000), allowance(ADMIN, USER),
                "Allowance should be 2000 after zero-then-set pattern");
    }

    // ── transferFrom ────────────────────────────────────────────────────

    @Test
    public void testTransferFromWithAllowance() {
        long approveAmount = 5000;
        long transferAmount = 3000;

        // Admin approves USER to spend 5000 tokens
        Bytes approveCall = Bytes.fromHexString(SEL_APPROVE + pad32(USER) + pad32(approveAmount));
        assertTrue(execTx(1, ADMIN, istCoin, approveCall, 0),
                "approve should succeed");

        // USER calls transferFrom(admin, user, 3000)
        Bytes transferFromCall = Bytes.fromHexString(
                SEL_TRANSFER_FROM + pad32(ADMIN) + pad32(USER) + pad32(transferAmount));
        boolean ok = execTx(0, USER, istCoin, transferFromCall, 0);
        assertTrue(ok, "transferFrom within allowance should succeed");

        // User should have 3000 tokens
        assertEquals(BigInteger.valueOf(transferAmount), balanceOf(USER),
                "User should have received tokens via transferFrom");

        // Remaining allowance should be 2000
        BigInteger remaining = allowance(ADMIN, USER);
        assertEquals(BigInteger.valueOf(approveAmount - transferAmount), remaining,
                "Allowance should decrease by transferred amount");
    }
}
