package tecnico.depchain.depchain_server.blockchain;

import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;
import org.hyperledger.besu.evm.EvmSpecVersion;
import org.hyperledger.besu.evm.account.Account;
import org.hyperledger.besu.evm.account.MutableAccount;
import org.hyperledger.besu.evm.fluent.EVMExecutor;
import org.hyperledger.besu.evm.worldstate.WorldUpdater;

public class TransactionRunner {
	private WorldUpdater updater;
	private Address minter;

	// Gas is a unit of computational effort, not a value in Wei (currency). The final cost is gas * gasPrice. Wei unit used later on 
	private final long BASE_FEE_GAS = 21_000L;

	public TransactionRunner(WorldUpdater updater, Address minter) {
		this.updater = updater;
		this.minter = minter;
	}

	public WorldUpdater getUpdater() {
		return updater;
	}

	public boolean executeTransaction(Transaction tx) {
		Account destination = updater.get(tx.to());
		if (destination.hasCode())
			return executeContract(tx);
		else
			return executeTransfer(tx);
	}

	private boolean executeTransfer(Transaction tx) {
		MutableAccount sender = updater.getAccount(tx.from());
		MutableAccount receiver = updater.getAccount(tx.to());
		MutableAccount minterAccount = updater.getAccount(minter);

		long gasLimit = tx.gasLimit();
		if (gasLimit < BASE_FEE_GAS || tx.gasPrice().isZero()) return false;

		Wei upfrontCost = tx.value().add(tx.gasPrice().multiply(Wei.of(gasLimit)));
		if (sender.getBalance().lessThan(upfrontCost)) {
			return false;
		}

		// Deduct upfront cost
		sender.setBalance(sender.getBalance().subtract(upfrontCost));

		// Transfer value to receiver
		receiver.setBalance(receiver.getBalance().add(tx.value()));

		// Calculate Gas Used and Fees
		long gasUsed = BASE_FEE_GAS;
		Wei actualFee = tx.gasPrice().multiply(Wei.of(gasUsed));
		Wei refund = tx.gasPrice().multiply(Wei.of(gasLimit - gasUsed));

		// Refund unused gas to sender
		sender.setBalance(sender.getBalance().add(refund));
		
		// Give actual fee to minter
		minterAccount.setBalance(minterAccount.getBalance().add(actualFee));

		return true;
	}

	private boolean executeContract(Transaction tx) {
		MutableAccount sender = updater.getAccount(tx.from());
		MutableAccount receiver = updater.getAccount(tx.to());
		MutableAccount minterAccount = updater.getAccount(minter);

		long gasLimit = tx.gasLimit();
		if (gasLimit < BASE_FEE_GAS || tx.gasPrice().isZero()) return false;

		Wei upfrontCost = tx.value().add(tx.gasPrice().multiply(Wei.of(gasLimit)));
		if (sender.getBalance().lessThan(upfrontCost)) {
			return false;
		}

		// Deduct upfront cost
		sender.setBalance(sender.getBalance().subtract(upfrontCost));

		// Smart contract invocations can also transfer native value manually
		receiver.setBalance(receiver.getBalance().add(tx.value()));

		// Actual execution on the EVM
		GasTracer tracer = execute(tx.from(), tx.to(), receiver.getCode(), tx.data(), tx.gasPrice(), gasLimit);
		
		long remainingGas = tracer.getRemainingGas();
		if (remainingGas < 0) remainingGas = 0;

		// Se a EVM abortou (ex: revert, falha no access control, out of gas)
		if (!tracer.isSuccess()) {
			// Reverter a transferência do tx.value() que fizemos manualmente
			receiver.setBalance(receiver.getBalance().subtract(tx.value()));
			// Devolvemos apenas o tx.value() ao sender. O custo do gás falhado continua a ser cobrado!
			sender.setBalance(sender.getBalance().add(tx.value()));
		}

		long gasUsed = gasLimit - remainingGas;
		Wei actualFee = tx.gasPrice().multiply(Wei.of(gasUsed));
		Wei refund = tx.gasPrice().multiply(Wei.of(remainingGas));

		// Refund unused gas to sender
		sender.setBalance(sender.getBalance().add(refund));
		
		// Give actual fee to minter
		minterAccount.setBalance(minterAccount.getBalance().add(actualFee));

		return true;
	}

	private static class GasTracer implements org.hyperledger.besu.evm.tracing.OperationTracer {
		private long remainingGas = 0;
		private boolean success = false;
		@Override
		public void traceContextExit(org.hyperledger.besu.evm.frame.MessageFrame frame) {
			if (frame.getMessageFrameStack().isEmpty() || frame.getDepth() == 0) {
				this.remainingGas = frame.getRemainingGas();
				this.success = frame.getState() == org.hyperledger.besu.evm.frame.MessageFrame.State.COMPLETED_SUCCESS;
			}
		}
		public long getRemainingGas() { return remainingGas; }
		public boolean isSuccess() { return success; }
	}

	private GasTracer execute(Address sender, Address receiver, Bytes code, Bytes selectorArgs, Wei gasPrice, long gasLimit) {

		EVMExecutor executor = EVMExecutor.evm(EvmSpecVersion.CANCUN);

		// Use custom tracer to capture gas
		GasTracer tracer = new GasTracer();
		executor.tracer(tracer);

		// Set configurations
		executor.baseFee(Wei.ZERO); 
		executor.gasLimit(gasLimit);
		executor.gasPriceGWei(gasPrice);

		// Set code, function and args
		executor.code(code);
		executor.callData(selectorArgs);

		// Other sets
		executor.sender(sender);
		executor.receiver(receiver);
		executor.worldUpdater(updater);

		// Run actual contract code
		executor.execute();
		return tracer;
	}
}
