package tecnico.depchain.depchain_server.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Test Class 1: Genesis Loading & EVM World-State Tests
 *
 * Covers:
 *  - Genesis EOA creation with correct balances
 *  - Genesis contract deployment (IST Coin)
 *  - IST Coin balanceOf via EVM call
 *  - World state snapshot completeness
 *  - World state snapshot/restore round-trip
 *
 * Expected failures (bugs to fix):
 *  - testGenesisLoadsSuccessfully: TransactionRunner nonce check off-by-one
 *    (tx.nonce() != sender.getNonce() + 1 should be tx.nonce() != sender.getNonce())
 *  - testGenesisDeploysISTCoinContract: same root cause
 *  - testISTCoinBalanceOfReturnsCorrectSupply: depends on contract deployment
 */
public class GenesisAndEVMTest {

    private static final String GENESIS_PATH = "../genesis.json";

    // Addresses from genesis.json
    private static final Address ADMIN = Address.fromHexString("0x1111111111111111111111111111111111111111");
    private static final Address USER  = Address.fromHexString("0x2222222222222222222222222222222222222222");

    // Expected balances from genesis.json (in Wei)
    private static final BigInteger ADMIN_INITIAL_BALANCE = new BigInteger("1000000000000000000000");
    private static final BigInteger USER_INITIAL_BALANCE  = new BigInteger("500000000000000000000");

    private EVM evm;

    @BeforeEach
    public void setup() {
        EVM.resetInstance();
        evm = EVM.getInstance();
    }

    @AfterEach
    public void teardown() {
        EVM.resetInstance();
    }

    // ════════════════════════════════════════════════════════════════════
    //  Genesis Loading Tests
    // ════════════════════════════════════════════════════════════════════

    /**
     * Genesis loading must complete without exceptions.
     * Currently FAILS due to nonce off-by-one in TransactionRunner.
     */
    @Test
    public void testGenesisLoadsSuccessfully() {
        assertDoesNotThrow(() -> GenesisLoader.loadGenesis(GENESIS_PATH),
                "GenesisLoader.loadGenesis should complete without throwing");
    }

    /**
     * After genesis, the admin account should exist and have its initial balance
     * (minus gas for the contract deployment).
     */
    @Test
    public void testGenesisCreatesEOAsWithCorrectBalances() throws Exception {
        GenesisLoader.loadGenesis(GENESIS_PATH);

        // Admin account must exist
        assertNotNull(evm.getUpdater().getAccount(ADMIN),
                "Admin account (0x1111...) should exist after genesis");

        // User account must exist with exact initial balance (no outgoing txs)
        assertNotNull(evm.getUpdater().getAccount(USER),
                "User account (0x2222...) should exist after genesis");
        assertEquals(Wei.of(USER_INITIAL_BALANCE),
                evm.getUpdater().getAccount(USER).getBalance(),
                "User balance should be exactly " + USER_INITIAL_BALANCE);

        // Admin balance: GenesisLoader sets minter=sender, so gas fees are recycled back.
        // Admin balance should equal initial balance (gas paid = gas received as minter).
        Wei adminBalance = evm.getUpdater().getAccount(ADMIN).getBalance();
        assertTrue(adminBalance.compareTo(Wei.ZERO) > 0,
                "Admin should have positive balance after genesis");
        assertEquals(Wei.of(ADMIN_INITIAL_BALANCE), adminBalance,
                "Admin balance should equal initial (minter=sender in genesis, gas fees recycled)");
    }

    /**
     * After genesis, the IST Coin contract should be deployed at the deterministic
     * address derived from admin + nonce 0.
     */
    @Test
    public void testGenesisDeploysISTCoinContract() throws Exception {
        GenesisLoader.loadGenesis(GENESIS_PATH);

        // Contract address = keccak256(rlp(sender, nonce)) for admin with nonce 0
        Address istCoinAddress = Address.contractAddress(ADMIN, 0L);

        assertNotNull(evm.getUpdater().getAccount(istCoinAddress),
                "IST Coin contract account should exist at " + istCoinAddress.toHexString());
        assertTrue(evm.getUpdater().getAccount(istCoinAddress).hasCode(),
                "IST Coin contract should have deployed bytecode");
    }

    /**
     * After genesis, calling balanceOf(admin) on the IST Coin contract should
     * return the full supply: 100,000,000 * 10^2 = 10,000,000,000 base units.
     */
    @Test
    public void testISTCoinBalanceOfReturnsCorrectSupply() throws Exception {
        GenesisLoader.loadGenesis(GENESIS_PATH);

        Address istCoinAddress = Address.contractAddress(ADMIN, 0L);

        // Encode balanceOf(address) call: selector 0x70a08231 + padded admin address
        String selector = "70a08231";
        String paddedAddress = String.format("%64s",
                ADMIN.toUnprefixedHexString()).replace(' ', '0');
        Bytes callData = Bytes.fromHexString(selector + paddedAddress);

        // Use EVMExecutor for a read-only call (no gas cost, no state mutation)
        EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);

        // Tracer to capture return data
        class ReturnDataTracer implements org.hyperledger.besu.evm.tracing.OperationTracer {
            private Bytes outputData = Bytes.EMPTY;
            @Override
            public void traceContextExit(org.hyperledger.besu.evm.frame.MessageFrame frame) {
                if (frame.getMessageFrameStack().isEmpty() || frame.getDepth() == 0) {
                    this.outputData = frame.getOutputData();
                }
            }
            public Bytes getOutputData() { return outputData; }
        }

        ReturnDataTracer tracer = new ReturnDataTracer();
        executor.tracer(tracer);
        executor.baseFee(Wei.ZERO);
        executor.gasLimit(1_000_000L);
        executor.gasPriceGWei(Wei.ZERO);
        executor.sender(ADMIN);
        executor.receiver(istCoinAddress);
        executor.code(evm.getUpdater().getAccount(istCoinAddress).getCode());
        executor.callData(callData);
        executor.worldUpdater(evm.getUpdater().updater());

        executor.execute();

        Bytes output = tracer.getOutputData();
        assertNotNull(output, "balanceOf output should not be null");
        assertFalse(output.isEmpty(), "balanceOf should return non-empty bytes");

        BigInteger tokenBalance = new BigInteger(1, output.toArray());
        // 100 million IST Coins with 2 decimal places = 10,000,000,000 base units
        BigInteger expectedTokens = new BigInteger("10000000000");
        assertEquals(expectedTokens, tokenBalance,
                "Total IST Coin supply should be 10,000,000,000 base units (100M * 10^2)");
    }

    // ════════════════════════════════════════════════════════════════════
    //  EVM World-State Snapshot Tests (no genesis dependency)
    // ════════════════════════════════════════════════════════════════════

    /**
     * getWorldState() should include all accounts created via createEOA.
     */
    @Test
    public void testWorldStateSnapshotIncludesAllAccounts() {
        Address addr1 = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Address addr2 = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        evm.createEOA(addr1, Wei.of(1000));
        evm.createEOA(addr2, Wei.of(2000));

        TreeMap<String, AccountState> state = evm.getWorldState();

        assertEquals(2, state.size(), "World state should contain exactly 2 accounts");
        assertTrue(state.containsKey(addr1.toHexString()),
                "State should include addr1");
        assertTrue(state.containsKey(addr2.toHexString()),
                "State should include addr2");
        assertEquals("1000", state.get(addr1.toHexString()).getBalance(),
                "addr1 balance should be 1000");
        assertEquals("2000", state.get(addr2.toHexString()).getBalance(),
                "addr2 balance should be 2000");
    }

    /**
     * setWorldState(getWorldState()) should preserve balances and nonces after
     * a full reset-and-restore cycle.
     */
    @Test
    public void testWorldStateRestoreRoundTrip() {
        Address addr1 = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        Address addr2 = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

        evm.createEOA(addr1, Wei.of(5000));
        evm.createEOA(addr2, Wei.of(9000));

        // Snapshot current state
        TreeMap<String, AccountState> snapshot = evm.getWorldState();

        // Reset EVM completely and restore
        EVM.resetInstance();
        evm = EVM.getInstance();
        evm.setWorldState(snapshot);

        // Verify balances survived the round-trip
        assertNotNull(evm.getUpdater().get(addr1), "addr1 should exist after restore");
        assertNotNull(evm.getUpdater().get(addr2), "addr2 should exist after restore");
        assertEquals(Wei.of(5000), evm.getUpdater().get(addr1).getBalance(),
                "addr1 balance should survive snapshot/restore");
        assertEquals(Wei.of(9000), evm.getUpdater().get(addr2).getBalance(),
                "addr2 balance should survive snapshot/restore");
        assertEquals(0, evm.getUpdater().get(addr1).getNonce(),
                "addr1 nonce should survive snapshot/restore");
        assertEquals(0, evm.getUpdater().get(addr2).getNonce(),
                "addr2 nonce should survive snapshot/restore");
    }

}
