package tecnico.depchain.depchain_server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.hyperledger.besu.datatypes.Address;

import tecnico.depchain.depchain_common.DepchainClient;
import tecnico.depchain.depchain_common.DepchainMember;
import tecnico.depchain.depchain_common.Membership;
import tecnico.depchain.depchain_common.blockchain.SignedTransaction;
import tecnico.depchain.depchain_common.blockchain.Transaction;
import tecnico.depchain.depchain_common.links.AuthenticatedPerfectLink;
import tecnico.depchain.depchain_common.messages.ConfirmMessage;
import tecnico.depchain.depchain_common.messages.TransactionMessage;
import tecnico.depchain.depchain_server.blockchain.Block;
import tecnico.depchain.depchain_server.blockchain.BlockPersister;
import tecnico.depchain.depchain_server.blockchain.EVM;
import tecnico.depchain.depchain_server.blockchain.GenesisLoader;
import tecnico.depchain.depchain_server.hotstuff.CryptoService;
import tecnico.depchain.depchain_server.hotstuff.HotStuff;
import tecnico.depchain.depchain_server.hotstuff.ThresholdCrypto;

public class Depchain {
	private static Map<InetSocketAddress, AuthenticatedPerfectLink> links = new HashMap<>();
	private static Map<Transaction, InetSocketAddress> requestSenderMap = new HashMap<>();
	private static Map<Transaction, Long> requestIdMap = new HashMap<>();

	private static HotStuff hotStuff;

	public static void main(String[] args)
			throws SocketException, NoSuchAlgorithmException, InvalidKeyException, IllegalArgumentException, IOException {
		if (args.length < 2) {
			System.err.print("Usage: java <class_path> <replicaID> <configFilePath>");
			System.exit(1);
		}

		int replicaID = Integer.parseInt(args[0]);
		String configPath = args[1];

		// Load membership from static configuration (pre-distributed PKI)
		Membership.loadConfiguration(configPath, replicaID);

		DepchainMember[] members = Membership.getMembers();
		DepchainClient[] clients = Membership.getClients();
		int numReplicas = members.length;
		PrivateKey ownKey = Membership.getOwnPrivateKey();
		List<PublicKey> publicKeys = Membership.getMemberPublicKeys();

		InetSocketAddress local = new InetSocketAddress("0.0.0.0", members[replicaID].getNetAddress().getPort());

		// Build CryptoService from the loaded PKI
		KeyPair ownKeyPair = new KeyPair(publicKeys.get(replicaID), ownKey);
		CryptoService crypto = new CryptoService(replicaID, ownKeyPair, publicKeys);

		Address ownAddress = members[replicaID].getDepchainAddress();

		// Initialize ThresholdCrypto for BFT quorum certificates
		// BFT threshold: f = (n-1)/3, quorum = n - f = 2f + 1
		int f = (numReplicas - 1) / 3;
		int threshold = numReplicas - f;  // quorum size (2f + 1)
		ThresholdCrypto.DealerParams thresholdParams = ThresholdCrypto.generateParams(threshold, numReplicas);
		ThresholdCrypto thresholdCrypto = new ThresholdCrypto(
			replicaID,
			threshold,
			thresholdParams.pairingParamsStr,
			thresholdParams.generator,
			thresholdParams.globalPublicKey,
			thresholdParams.privateShares.get(replicaID),
			thresholdParams.publicShares
		);

		// Initialize block persister for crash recovery
		String blocksDir = "blocks";
		BlockPersister blockPersister = new BlockPersister(Paths.get(blocksDir));

		hotStuff = new HotStuff(replicaID, ownAddress, "localhost", 42069, numReplicas, ownKey, publicKeys, crypto, thresholdCrypto);
		hotStuff.setBlockPersister(blockPersister);
		hotStuff.setOnDecide(Depchain::onDecide);

		// Try to recover from persisted blocks, otherwise start fresh from genesis
		List<Block> recoveredBlocks = loadPersistedBlocks(blockPersister);
		if (!recoveredBlocks.isEmpty()) {
			// Recovery: replay all blocks through EVM to rebuild world state
			System.out.println("[Depchain] Recovering from " + recoveredBlocks.size() + " persisted blocks...");
			hotStuff.recoverFromBlocks(recoveredBlocks);
			System.out.println("[Depchain] Recovery complete. Last block: " + (recoveredBlocks.size() - 1));
		} else {
			// Fresh start: load genesis and create Block 0
			System.out.println("[Depchain] No persisted blocks found. Starting fresh from genesis...");
			String genesisPath = "genesis.json";
			GenesisLoader.loadGenesis(genesisPath);

			// Create genesis block (Block 0) with the initial world state
			Block genesisBlock = new Block(
				null,  // previousBlockHash is null for genesis
				new ArrayList<>(),  // genesis has no regular transactions
				EVM.getInstance().getWorldState()
			);

			// Save genesis block
			Files.createDirectories(Paths.get(blocksDir));
			blockPersister.saveBlock(genesisBlock, 0);

			hotStuff.setGenesisBlock(genesisBlock);
			System.out.println("[Depchain] Genesis block created and saved.");
		}

		hotStuff.start();

		for (DepchainClient cli : clients) {
			InetSocketAddress addr = cli.getNetAddress();
			links.put(addr, new AuthenticatedPerfectLink(Depchain::rxHandler, local, addr, ownKey, cli.getPublicKey()));
		}

		while (true);
		//service.stop();
	}

	/**
	 * Loads all persisted blocks from disk in order (block_0.json, block_1.json, ...).
	 * Returns empty list if no blocks exist or on error.
	 */
	private static List<Block> loadPersistedBlocks(BlockPersister persister) {
		List<Block> blocks = new ArrayList<>();
		int blockNum = 0;
		while (true) {
			try {
				Block block = persister.loadBlock(blockNum);
				if (block == null) break;
				blocks.add(block);
				blockNum++;
			} catch (IOException e) {
				// No more blocks
				break;
			}
		}
		return blocks;
	}

	//Client msg handler
	private static void rxHandler(byte[] data, InetSocketAddress sender) {
		TransactionMessage txMsg = TransactionMessage.deserialize(data);
		if (txMsg == null)
			return;

		SignedTransaction signedTx = txMsg.getSignedTransaction();
		Address senderAddress = signedTx.tx().from();
		if (!signedTx.verify(Membership.getAccountPublicKey(senderAddress)))
			return;

		hotStuff.propose(signedTx);

		requestSenderMap.put(signedTx.tx(), sender);
		requestIdMap.put(signedTx.tx(), txMsg.getSeqNum());
	}

	private static void onDecide(Block blk) {
		for (Transaction tx : blk.getTransactions()) {
			long id = requestIdMap.get(tx);
			InetSocketAddress requester = requestSenderMap.get(tx);
			AuthenticatedPerfectLink link = links.get(requester);

			ConfirmMessage msg = new ConfirmMessage(id, true);
			link.transmit(msg.serialize());
		}
	}
}