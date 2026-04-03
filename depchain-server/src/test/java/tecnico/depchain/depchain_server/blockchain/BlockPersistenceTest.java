package tecnico.depchain.depchain_server.blockchain;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tecnico.depchain.depchain_common.blockchain.Transaction;

/**
 * Test Class 4: Block Persistence (save/load round-trip)
 *
 * Covers:
 *  - Empty block save/load round-trip
 *  - Block with transactions round-trip
 *  - Block with world state (EOA + contract) round-trip
 *  - Block hash determinism
 *  - Potential Gson dual-registration bug for Transaction serializer/deserializer
 */
public class BlockPersistenceTest {

    @TempDir
    Path tempDir;

    private BlockPersister persister;

    private final Address ALICE = Address.fromHexString("0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    private final Address BOB   = Address.fromHexString("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");

    @BeforeEach
    public void setup() {
        persister = new BlockPersister(tempDir);
    }

    // ── Empty block round-trip ──────────────────────────────────────────

    @Test
    public void testEmptyBlockSaveLoad() throws IOException {
        Block original = new Block("0xprevhash", new ArrayList<>(), new TreeMap<>());

        persister.saveBlock(original, 0);
        Block loaded = persister.loadBlock(0);

        assertNotNull(loaded, "Loaded block should not be null");
        assertEquals(original.getPreviousBlockHash(), loaded.getPreviousBlockHash(),
                "Previous block hash should survive round-trip");
        assertTrue(loaded.getTransactions().isEmpty(),
                "Empty block should have no transactions after loading");
        assertTrue(loaded.getState().isEmpty(),
                "Empty block should have no state after loading");
    }

    @Test
    public void testBlockHashPreservedAfterSaveLoad() throws IOException {
        Block original = new Block("0xprevhash", new ArrayList<>(), new TreeMap<>());
        String originalHash = original.getBlockHash();
        assertNotNull(originalHash, "Block hash should be calculated on construction");

        persister.saveBlock(original, 1);
        Block loaded = persister.loadBlock(1);

        assertEquals(originalHash, loaded.getBlockHash(),
                "Block hash should be preserved after save/load");
    }

    // ── Block with transactions ─────────────────────────────────────────

    @Test
    public void testBlockWithTransactionsSaveLoad() throws IOException {
        Transaction tx1 = new Transaction(0, ALICE, BOB, Wei.of(10), 21000, Wei.of(5000), null);
        Transaction tx2 = new Transaction(1, ALICE, BOB, Wei.of(20), 50000, Wei.of(1000),
                Bytes.fromHexString("0xdeadbeef"));

        List<Transaction> txs = new ArrayList<>();
        txs.add(tx1);
        txs.add(tx2);

        Block original = new Block("0xabc", txs, new TreeMap<>());

        persister.saveBlock(original, 2);
        Block loaded = persister.loadBlock(2);

        assertNotNull(loaded);
        assertEquals(2, loaded.getTransactions().size(),
                "Should have 2 transactions after loading");

        Transaction loadedTx1 = loaded.getTransactions().get(0);
        assertEquals(0, loadedTx1.nonce(), "tx1 nonce should be 0");
        assertEquals(ALICE, loadedTx1.from(), "tx1 from should be ALICE");
        assertEquals(BOB, loadedTx1.to(), "tx1 to should be BOB");
        assertEquals(Wei.of(10), loadedTx1.gasPrice(), "tx1 gasPrice should be 10");
        assertEquals(21000, loadedTx1.gasLimit(), "tx1 gasLimit should be 21000");
        assertEquals(Wei.of(5000), loadedTx1.value(), "tx1 value should be 5000");

        Transaction loadedTx2 = loaded.getTransactions().get(1);
        assertEquals(1, loadedTx2.nonce(), "tx2 nonce should be 1");
        assertEquals(Bytes.fromHexString("0xdeadbeef"), loadedTx2.data(),
                "tx2 data should survive round-trip");
    }

    /**
     * Contract creation tx (to=null) should round-trip correctly.
     */
    @Test
    public void testContractCreationTxSaveLoad() throws IOException {
        Transaction tx = new Transaction(0, ALICE, null, Wei.of(10), 100000,
                Wei.ZERO, Bytes.fromHexString("0x600060006000"));

        List<Transaction> txs = new ArrayList<>();
        txs.add(tx);
        Block original = new Block("0xdef", txs, new TreeMap<>());

        persister.saveBlock(original, 3);
        Block loaded = persister.loadBlock(3);

        Transaction loadedTx = loaded.getTransactions().get(0);
        assertNull(loadedTx.to(), "Contract creation tx should have null 'to' after loading");
        assertEquals(ALICE, loadedTx.from());
        assertNotNull(loadedTx.data(), "Init code should survive round-trip");
    }

    // ── Block with world state ──────────────────────────────────────────

    @Test
    public void testBlockWithEOAStateSaveLoad() throws IOException {
        TreeMap<String, AccountState> state = new TreeMap<>();
        state.put(ALICE.toHexString(), new AccountState("1000000", 5, null, new TreeMap<>()));
        state.put(BOB.toHexString(), new AccountState("500000", 0, null, new TreeMap<>()));

        Block original = new Block("0x123", new ArrayList<>(), state);

        persister.saveBlock(original, 4);
        Block loaded = persister.loadBlock(4);

        assertEquals(2, loaded.getState().size(), "Should have 2 accounts in state");

        AccountState aliceState = loaded.getState().get(ALICE.toHexString());
        assertNotNull(aliceState, "Alice should be in state");
        assertEquals("1000000", aliceState.getBalance(), "Alice balance should be 1000000");
        assertEquals(5, aliceState.getNonce(), "Alice nonce should be 5");

        AccountState bobState = loaded.getState().get(BOB.toHexString());
        assertNotNull(bobState, "Bob should be in state");
        assertEquals("500000", bobState.getBalance());
        assertEquals(0, bobState.getNonce());
    }

    @Test
    public void testBlockWithContractStateSaveLoad() throws IOException {
        TreeMap<String, AccountState> state = new TreeMap<>();
        TreeMap<String, String> storage = new TreeMap<>();
        storage.put("0x0000000000000000000000000000000000000000000000000000000000000000", "0x48656c6c6f");
        storage.put("0x0000000000000000000000000000000000000000000000000000000000000001", "0x576f726c64");

        AccountState contractState = new AccountState("0", 1, "0x608060405234801561000f575f5ffd", storage);

        Address contractAddr = Address.fromHexString("0x1234567890abcdef1234567890abcdef12345678");
        state.put(contractAddr.toHexString(), contractState);

        Block original = new Block("0x456", new ArrayList<>(), state);

        persister.saveBlock(original, 5);
        Block loaded = persister.loadBlock(5);

        AccountState loadedContract = loaded.getState().get(contractAddr.toHexString());
        assertNotNull(loadedContract, "Contract should be in loaded state");
        assertTrue(loadedContract.isContract(), "Should be identified as contract");
        assertEquals("0x608060405234801561000f575f5ffd", loadedContract.getCode(),
                "Contract code should survive round-trip");
        assertEquals(2, loadedContract.getStorage().size(),
                "Contract storage should have 2 slots");
        assertEquals("0x48656c6c6f",
                loadedContract.getStorage().get(
                        "0x0000000000000000000000000000000000000000000000000000000000000000"),
                "Storage slot 0 should survive round-trip");
    }

    // ── Block hash determinism ──────────────────────────────────────────

    @Test
    public void testBlockHashIsDeterministic() {
        TreeMap<String, AccountState> state = new TreeMap<>();
        state.put(ALICE.toHexString(), new AccountState("1000", 0, null, new TreeMap<>()));

        Transaction tx = new Transaction(0, ALICE, BOB, Wei.of(10), 21000, Wei.of(100), null);
        List<Transaction> txs = new ArrayList<>();
        txs.add(tx);

        Block block1 = new Block("0xprev", txs, state);
        Block block2 = new Block("0xprev", txs, state);

        assertEquals(block1.getBlockHash(), block2.getBlockHash(),
                "Two blocks with identical content should produce the same hash");
    }

    @Test
    public void testBlockHashChangesWithDifferentState() {
        Transaction tx = new Transaction(0, ALICE, BOB, Wei.of(10), 21000, Wei.of(100), null);
        List<Transaction> txs = new ArrayList<>();
        txs.add(tx);

        TreeMap<String, AccountState> state1 = new TreeMap<>();
        state1.put(ALICE.toHexString(), new AccountState("1000", 0, null, new TreeMap<>()));

        TreeMap<String, AccountState> state2 = new TreeMap<>();
        state2.put(ALICE.toHexString(), new AccountState("2000", 0, null, new TreeMap<>()));

        Block block1 = new Block("0xprev", txs, state1);
        Block block2 = new Block("0xprev", txs, state2);

        assertNotEquals(block1.getBlockHash(), block2.getBlockHash(),
                "Blocks with different state should have different hashes");
    }

    // ── File creation verification ──────────────────────────────────────

    @Test
    public void testSaveBlockCreatesFile() throws IOException {
        Block block = new Block("0x0", new ArrayList<>(), new TreeMap<>());
        Path filePath = persister.saveBlock(block, 42);

        assertTrue(Files.exists(filePath), "Block file should exist after save");
        assertTrue(filePath.toString().endsWith("block_42.json"),
                "File should be named block_42.json");
        assertTrue(Files.size(filePath) > 0, "File should not be empty");
    }

    @Test
    public void testLoadNonExistentBlockThrows() {
        assertThrows(IOException.class, () -> persister.loadBlock(999),
                "Loading non-existent block should throw IOException");
    }

    // ── JSON string serialization ───────────────────────────────────────

    @Test
    public void testToJsonProducesValidJson() {
        Block block = new Block("0xprev", new ArrayList<>(), new TreeMap<>());
        String json = BlockPersister.toJson(block);

        assertNotNull(json, "toJson should not return null");
        assertTrue(json.contains("block_hash") || json.contains("blockHash"),
                "JSON should contain block hash field");
        assertTrue(json.contains("previous_block_hash") || json.contains("previousBlockHash"),
                "JSON should contain previous block hash field");
    }
}
