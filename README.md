# DepChain

## Highly Dependable Systems (2025-2026)

**DepChain** (Dependable Chain) is a simplified permissioned blockchain system with high dependability guarantees. It implements the **Basic HotStuff** BFT consensus algorithm, a native cryptocurrency (**DepCoin**), an EVM-based smart contract layer (Hyperledger Besu), and a frontrunning-resistant **IST Coin** ERC-20 token.

---

## Architecture & Modules

* **`depchain-server`**: Blockchain replica — HotStuff consensus, EVM transaction execution, block persistence, mempool with gas-price ordering, and crash recovery.
* **`depchain-client`**: Client library — signs and submits transactions (DepCoin transfers / smart contract calls), waits for f+1 matching confirmations from replicas.
* **`depchain-common`**: Shared layer — UDP link stack (FairLossLink → StubbornLink → AuthenticatedPerfectLink with X25519 DH), broadcasts, message types, `Transaction`/`SignedTransaction`, and `Membership` configuration.

### Key Properties
- **Static membership** with pre-distributed Ed25519 keys (PKI).
- **UDP networking** with layered abstractions (Fair Loss → Stubborn → Authenticated Perfect Links).
- **Ephemeral X25519 Diffie-Hellman** handshake (authenticated by Ed25519) derives per-session HMAC-SHA256 keys for link-layer message authentication.
- **BLS threshold signatures** (JPBC) for Quorum Certificates — pre-generated shared params loaded from file.
- **BFT consensus**: tolerates f = ⌊(n−1)/3⌋ Byzantine replicas.

---

## Security & Dependability

- **Message authentication**: All inter-replica messages are HMAC-authenticated via DH-derived keys. Votes are signed with Ed25519.
- **Threshold QCs**: Quorum Certificates require n−f valid partial BLS signatures from distinct replicas.
- **Byzantine client tolerance**: Transactions are Ed25519-signed; replicas verify signatures, reject spoofed senders, overspends, nonce replays, and insufficient gas.
- **Non-negative balances**: Transfer amounts are validated against sender balance before execution.
- **Non-repudiation**: All operations are tied to Ed25519 signatures on the originating account's private key.
- **Gas mechanism**: Transaction fee = gas_price × min(gas_limit, gas_used). Fees go to the block minter. Zero gas_price and insufficient gas_limit are rejected.
- **Frontrunning protection**: The IST Coin ERC-20 `approve()` reverts if the current allowance is non-zero and the new value is also non-zero — the user must first set allowance to 0.

---

## Build Instructions

**Prerequisites**: Java 17+, Maven 3.8+

```bash
# Compile everything (downloads dependencies automatically)
mvn clean install

# Compile without running tests
mvn clean install -DskipTests
```

### Configuration Setup

Before running the system, generate the PKI configuration and threshold crypto parameters:

```bash
# 1. Generate Ed25519 keys + membership config for 4 members + 1 client
mvn exec:java -pl depchain-common \
  -Dexec.mainClass="tecnico.depchain.depchain_common.KeyGenUtil" \
  -Dexec.args="4 1 config.properties"

# 2. Generate shared BLS threshold crypto parameters (run ONCE, shared by all replicas)
mvn exec:java -pl depchain-server \
  -Dexec.mainClass="tecnico.depchain.depchain_server.hotstuff.ThresholdParamsDealer" \
  -Dexec.args="4 threshold-params.dat"
```

This creates:
- `config.properties` — Ed25519 keys, network addresses, and DepChain account addresses for all members and clients.
- `threshold-params.dat` — Shared BLS pairing parameters, generator, global public key, and per-replica private/public shares.

---

## Running the Tests (Demos)

The system includes a JUnit 5 test suite that demonstrates correctness, security, and fault tolerance. These tests serve as the **demo applications** required by the specification.

### Run all tests
```bash
mvn test
```

### Run by area

**Stage 1 — Consensus Layer:**

* **HotStuffStep3Test** — P2P link stack: FairLossLink, StubbornLink, AuthenticatedPerfectLink (DH handshake, HMAC authentication, wrong-key rejection)
  ```bash
  mvn test -pl depchain-server "-Dtest=HotStuffStep3Test"
  ```
* **HotStuffStep4Test** — Consensus data structures: TreeNode hashing/chain extension, Message serialization, QuorumCertificate vote accumulation and Ed25519 verification
  ```bash
  mvn test -pl depchain-server "-Dtest=HotStuffStep4Test"
  ```
* **CryptoServiceTest** — Ed25519 signing, BLS partial/threshold signatures, QC integration
  ```bash
  mvn test -pl depchain-server "-Dtest=CryptoServiceTest"
  ```
* **HotStuffStep5Test** — Full consensus integration: 4 replicas on real UDP, 3-phase PREPARE→PRE-COMMIT→COMMIT→DECIDE, leader rotation, multiple consecutive decisions
  ```bash
  mvn test -pl depchain-server "-Dtest=HotStuffStep5Test"
  ```
* **HotStuffStep6Test** — BFT fault tolerance: consensus survives 1 crashed replica (f=1), crashed replica does not decide, silent Byzantine does not block consensus
  ```bash
  mvn test -pl depchain-server "-Dtest=HotStuffStep6Test"
  ```

**Stage 2 — Transaction Processing Layer:**

* **GenesisAndEVMTest** — Genesis loading, EOA creation with balances, IST Coin contract deployment, balanceOf verification, world state snapshot/restore
  ```bash
  mvn test -pl depchain-server "-Dtest=GenesisAndEVMTest"
  ```
* **TransactionRunnerTest** — Nonce validation, DepCoin transfer debit/credit, gas fee to minter, balance rejection, contract creation
  ```bash
  mvn test -pl depchain-server "-Dtest=TransactionRunnerTest"
  ```
* **MempoolTest** — Nonce ordering, gas-price ordering (highest fee first), gas limit cap, cleanup after block commit
  ```bash
  mvn test -pl depchain-server "-Dtest=MempoolTest"
  ```
* **BlockPersistenceTest** — Block save/load round-trip with transactions, contract state (code + storage), hash determinism
  ```bash
  mvn test -pl depchain-server "-Dtest=BlockPersistenceTest"
  ```
* **ISTCoinFrontrunningTest** — ERC-20 decimals (2), transfer, approve frontrunning guard (non-zero→non-zero reverts), safe approve via zero, transferFrom with allowance
  ```bash
  mvn test -pl depchain-server "-Dtest=ISTCoinFrontrunningTest"
  ```
* **ByzantineClientTest** — Signature verification, spoofed sender detection, overspend rejection, nonce replay rejection, double-spend at execution level
  ```bash
  mvn test -pl depchain-server "-Dtest=ByzantineClientTest"
  ```

---

## Fault Injection

The test infrastructure supports two mechanisms for simulating Byzantine behavior:

**1. Outgoing Message Filter** (`HotStuff.setOutgoingFilter`):
```java
replicas.get(byzantineId).setOutgoingFilter((dest, msg) -> {
    return null; // Drop all outgoing messages (silent Byzantine)
});
```

**2. Invalid Cryptographic Keys**: Tests create replicas with mismatched Ed25519 keys to verify that the authentication layer correctly rejects messages from impostors.
