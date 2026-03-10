# DepChain - Stage 1

## Highly Dependable Systems (2025-2026)

This project implements **DepChain** (Dependable Chain), a simplified permissioned (closed membership) blockchain system with dependability guarantees. The first stage focuses on the **consensus layer**, using the **Basic HotStuff Algorithm** as its foundation.

---

## Architecture & Modules

The implementation is structured into layered modules, separating client logic, server/blockchain node logic, and shared resources based on abstractions (e.g., Authenticated Perfect Links, Quorum Certificates).

* **`depchain-server`**: Implements the BFT Consensus logic (Basic HotStuff), including cryptography (PKI & Threshold Signatures), leader rotation, view changes, and an append-only memory array simulating the blockchain storage.
* **`depchain-client`**: Client library responsible for submitting transactions/requests (simple strings) to the service via an upcall interface and receiving execution confirmations.
* **`depchain-common`**: Shared models and utilities for both server and client modules.

### Core Assumptions
1. **Static Membership**: Leader and blockchain members are known to all prior to system startup.
2. **PKI & Threshold Cryptography**: Utilizes pre-distributed public/private key pairs and JPBC for BLS threshold signatures to construct Quorum Certificates (QC).
3. **UDP Networking**: The basic communication mimics an unreliable network (Fair Loss Links). Abstractions handle retries, delays, and duplicate mechanisms on top of UDP.

---

## Security & Dependability Mechanisms

To tolerate a subset of malicious or arbitrary-behaving replicas (Byzantine faults) across an unreliable network, the following protections are integrated:

* **Strict Message Authentication**: All nodes sign their votes/messages using Ed25519 PKI. The platform ignores any messages with tampering, invalid signatures, or out-of-range sender IDs.
* **Threshold Signatures (BLS/JPBC)**: Quorum Certificates natively require \( n - f \) valid partial signatures from distinct replicas to form a valid threshold signature, preventing a single compromised node from forging a QC.
* **Byzantine Filtering & Equivocation Protection**: 
  * Replicas ignore old views, silently dropped messages, and invalid/spoofed sender IDs.
  * The network identifies leader equivocacy (sending conflicting messages to different replicas) and times out to trigger a view change instead of achieving conflicting decisions.
  * Ensures safety guarantees remain strict: an attack mixing Byzantine behavior and crash faults will halt progress to preserve safety (`n=4, f=1` resulting in no decision if valid quorum is unmet).

---

## Build Instructions and Setup

The project is built with **Java 8+** and uses **Maven** for dependency management. External libraries necessary for the project (such as the JPBC library for threshold signatures) are managed and downloaded automatically by Maven during compilation.

To compile the entire project from the source code and ensure the self-contained ZIP has all libraries properly installed, run the following command in the project root:

```bash
mvn clean install
```

To compile without executing the test suite, use:
```bash
mvn clean install -DskipTests
```

---

## Execution Instructions (Demos and Tests)

The system includes a suite of automated tests (`JUnit 5`) that serve as the main demonstration applications required for evaluation. These tests cover system correctness, security threats, and fault tolerance.

### 1. Run all tests
To execute the complete suite, which evaluates consensus, crash faults, Byzantine behavior injections, and client-server communication:
```bash
mvn test
```

### 2. Run Demos by Steps
The tests are divided according to the project assignment steps. Each suite can be executed individually to observe specific scenarios:

* **Step 3 - Basic Blockchain Appends (No Faults)**
  ```bash
  mvn test -pl depchain-server -Dtest="HotStuffStep3Test"
  ```
* **Step 4 - Crash Tolerant (Timeout based)**
  ```bash
  mvn test -pl depchain-server -Dtest="HotStuffStep4Test"
  ```
* **Step 5 - Byzantine Fault Tolerance (Security Demos)**
  ```bash
  mvn test -pl depchain-server -Dtest="HotStuffStep5Test"
  ```
* **Step 6 - Client Interaction & Service Integration**
  ```bash
  mvn test -pl depchain-server -Dtest="HotStuffStep6Test"
  ```

---

## Fault Injection Guide (Byzantine Simulation)

To fulfill the specification requirements regarding changing how messages are delivered or the behavior of Byzantine nodes, the project includes an API designed to allow the testing of edge cases and robustness guarantees.

The fault injection infrastructure is accessible in the test code and can be modified. There are two primary ways to inject and alter behaviors:

**1. Through the Outgoing Message Filter (`setOutgoingFilter`)**:
In the `HotStuff` class, the system has a functional interface that intercepts all messages a node attempts to send to the network. To test new scenarios in a test file like `HotStuffStep5Test`, this filter can be used:

```java
replicas[byzantineId].setOutgoingFilter((dest, msg) -> {
    // To Delay (Slow Network Simulation):
    // Thread.sleep(2000); 
    
    // To Drop (Simulate Packet Loss):
    // return null; 
    
    // To Corrupt / Forge data in signatures, QCs, and Sender IDs:
    // return new Message(MsgType.PREPARE, msg.getViewNumber(), falseId, corruptedSig, ...);
});
```

**2. Through the Assignment of Invalid Cryptographic Keys**:
To validate the exclusion of impostors in threshold signatures and the base consensus mechanism, the utility method `createReplicasWithByzantine` deliberately starts replicas with incorrect/mismatched *Ed25519* key pairs.

The implemented methods in `depchain-server/src/test/java/tecnico/depchain/depchain_server/hotstuff/HotStuffStep5Test.java` demonstrate these techniques mitigating Byzantine faults, including leader equivocation and the sending of forged Quorum Certificates.
