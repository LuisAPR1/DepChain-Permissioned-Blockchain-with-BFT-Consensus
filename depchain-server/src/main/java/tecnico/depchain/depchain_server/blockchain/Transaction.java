package tecnico.depchain.depchain_server.blockchain;

import java.io.Serializable;
import java.math.BigInteger;

public record Transaction(
		BigInteger nonce,
		String to,
		BigInteger value,
		BigInteger gasLimit,
		BigInteger maxFeePerGas,
		BigInteger maxPriorityFeePerGas,
		byte[] data,
		BigInteger v,
		BigInteger r,
		BigInteger s) implements Serializable {
}
