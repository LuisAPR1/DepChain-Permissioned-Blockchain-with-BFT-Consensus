package tecnico.depchain.depchain_server.blockchain;

import java.math.BigInteger;
import java.util.List;

public class ExecutionPayload {
	String parentHash;
	String stateRoot;
	String transactionRoot;
	String receiptRoot;
	String logsBloom;
	String prevRandao;
	BigInteger number;
	BigInteger gasLimit;
	BigInteger gasUsed;
	BigInteger timestamp;
	String extraData;
	BigInteger baseFeePerGas;
	String blockHash;

	List<Transaction> transactions;
}
