package tecnico.depchain.depchain_server.blockchain;

import java.io.Serializable;

/**
 * DTO representing the state of a single account in the World State snapshot
 * persisted within each Block.
 *
 * For EOAs: balance + nonce are the full picture.
 * For Contract Accounts: balance + nonce + codeHash capture the integrity of
 * the deployed code, while the actual storage (where ERC-20 balances live)
 * is maintained in the live EVM world state. The codeHash serves as a
 * fingerprint to detect tampering without bloating the JSON.
 */
public class AccountState implements Serializable {

    private String balance;   // Decimal string (Wei)
    private long nonce;
    private String codeHash;  // SHA-256 hex of the contract bytecode; null for EOAs

    public AccountState() {}

    public AccountState(String balance, long nonce, String codeHash) {
        this.balance = balance;
        this.nonce = nonce;
        this.codeHash = codeHash;
    }

    public String getBalance() { return balance; }
    public void setBalance(String balance) { this.balance = balance; }

    public long getNonce() { return nonce; }
    public void setNonce(long nonce) { this.nonce = nonce; }

    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AccountState that = (AccountState) o;
        return nonce == that.nonce
                && java.util.Objects.equals(balance, that.balance)
                && java.util.Objects.equals(codeHash, that.codeHash);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(balance, nonce, codeHash);
    }

    @Override
    public String toString() {
        return "AccountState{balance='" + balance + "', nonce=" + nonce +
                ", codeHash='" + codeHash + "'}";
    }
}
