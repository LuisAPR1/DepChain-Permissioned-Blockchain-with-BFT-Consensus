package tecnico.depchain.depchain_server.blockchain;

import com.google.gson.Gson;
import org.apache.tuweni.bytes.Bytes;
import org.hyperledger.besu.datatypes.Address;
import org.hyperledger.besu.datatypes.Wei;

import java.io.FileReader;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

public class GenesisLoader {

    public static class GenesisAccount {
        public String balance;
        public int nonce;
    }

    public static class GenesisTransaction {
        public String from;
        public String to; // can be null
        public long gasLimit;
        public long gasPrice;
        public String value;
        public String data;
    }

    public static class GenesisFile {
        public String block_hash;
        public String previous_block_hash;
        public Map<String, GenesisAccount> state;
        public List<GenesisTransaction> transactions;
    }

    public static void loadGenesis(String filePath) throws IOException {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader(filePath)) {
            GenesisFile genesis = gson.fromJson(reader, GenesisFile.class);
            EVM evm = EVM.getInstance();

            // 1. Carregar o Estado Inicial (EOAs)
            if (genesis.state != null) {
                for (Map.Entry<String, GenesisAccount> entry : genesis.state.entrySet()) {
                    Address address = Address.fromHexString(entry.getKey());
                    // Converter string gigante para Wei (usando notação decimal base 10)
                    Wei balance = Wei.of(new BigInteger(entry.getValue().balance));
                    evm.createEOA(address, balance);
                }
            }

            // Precisamos dum minter (admin) para os blocos virtuais - vamos usar a conta que envia o deploy
            if (genesis.transactions == null || genesis.transactions.isEmpty()) {
                System.out.println("Sem transacoes no Genesis JSON.");
                return;
            }

            Address adminAddress = Address.fromHexString(genesis.transactions.get(0).from);
            
            // 2. Executar a Transação 1 (Access Control)
            GenesisTransaction tx1 = genesis.transactions.get(0);
            TransactionRunner runner1 = new TransactionRunner(evm.getUpdater(), adminAddress); // using admin as minter

            Transaction t1 = new Transaction(
                    BigInteger.ZERO, // nonce
                    adminAddress, 
                    null, // to == null (deploy)
                    Wei.of(tx1.gasPrice), // gasPrice
                    Wei.ZERO, Wei.ZERO, // EIP-1559 fees ignorable for now
                    tx1.gasLimit,
                    Wei.of(new BigInteger(tx1.value)),
                    Bytes.fromHexString(tx1.data),
                    BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO // assinaturas irrelevantes no genesis
            );

            Address accessControlAddress = runner1.executeContractCreation(t1);
            if (accessControlAddress == null) {
                throw new RuntimeException("Falha ao criar o AccessControl no Genesis");
            }
            
            runner1.getUpdater().commit();
            System.out.println("Access Control Contract gerado no endereco: " + accessControlAddress.toHexString());

            // 3. O Truque do Construtor na Transação 2 (IST Coin)
            if (genesis.transactions.size() > 1) {
                GenesisTransaction tx2 = genesis.transactions.get(1);
                
                // Fazer padding do endereço do access control com zeros à esquerda até 32 bytes (64 caracteres hexadecimais)
                String hexAddress = accessControlAddress.toUnprefixedHexString();
                String paddedAddress = String.format("%64s", hexAddress).replace(' ', '0');
                
                // Concatenar ao final do 'data' da transação 2
                String finalData2 = tx2.data + paddedAddress;

                TransactionRunner runner2 = new TransactionRunner(evm.getUpdater(), adminAddress);
                Transaction t2 = new Transaction(
                        BigInteger.ONE, // nonce inc
                        adminAddress,
                        null,
                        Wei.of(tx2.gasPrice),
                        Wei.ZERO, Wei.ZERO,
                        tx2.gasLimit,
                        Wei.of(new BigInteger(tx2.value)),
                        Bytes.fromHexString(finalData2),
                        BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO
                );

                Address istCoinAddress = runner2.executeContractCreation(t2);
                if (istCoinAddress == null) {
                    throw new RuntimeException("Falha ao criar o ISTCoin no Genesis");
                }
                
                runner2.getUpdater().commit();
                System.out.println("IST Coin Contract gerado no endereco: " + istCoinAddress.toHexString());
            }
        }
    }
}
