# Test Summary — DepChain

## Mandatory Requirements 

### Stage 1
- Authenticated Perfect Links over UDP (FairLoss → Stubborn → APL with DH)
- Basic HotStuff consensus with leader rotation + QC
- Crash fault detection (timeout-based)
- Byzantine fault tolerance for blockchain members
- Tests for Byzantine scenarios including message manipulation

### Stage 2
- Native DepCoin transfers between accounts
- Smart contract execution via Besu EVM
- Byzantine client tolerance (non-negative balances, non-repudiation, no unauthorized modification)
- Transaction ordering by gas fees
- Genesis block with IST Coin ERC-20 (2 decimals, 100M supply)
- Frontrunning-resistant approve() — explicitly required
- Gas mechanism (gas_price × min(gas_limit, gas_used))
- Block persistence + world state snapshots
- Tests demonstrating Byzantine robustness including frontrunning attacks

---

## Recommended Tests: 45 (from 111)

### 1. BlockPersistenceTest — 3 tests (from 11)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testBlockWithTransactionsSaveLoad | Block persistence (Stage 2 Step 3)   
| ✅ | testBlockWithContractStateSaveLoad | World state incl. contract storage in blocks   
| ✅ | testBlockHashIsDeterministic | Block integrity / determinism   


### 2. ByzantineClientTest — 5 tests (from 13)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testValidSignatureVerifies | Non-repudiation   
| ✅ | testSpoofedSenderDetected | No unauthorized modification   
| ✅ | testOverspendRejected | Non-negative balances   
| ✅ | testNonceReplayRejected | Replay protection   
| ✅ | testDoubleSpendAtExecutionLevel | Double-spend prevention   


### 3. GenesisAndEVMTest — 6 tests (from 9)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testGenesisLoadsSuccessfully | Genesis block (Stage 2 Step 3)   
| ✅ | testGenesisCreatesEOAsWithCorrectBalances | Genesis state with balances   
| ✅ | testGenesisDeploysISTCoinContract | IST Coin deployment (mandatory)   
| ✅ | testISTCoinBalanceOfReturnsCorrectSupply | 100M supply (mandatory)   
| ✅ | testWorldStateSnapshotIncludesAllAccounts | World state snapshots   
| ✅ | testWorldStateRestoreRoundTrip | World state restore   


### 4. ISTCoinFrontrunningTest — 5 tests (from 8)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testDecimalsReturns2 | "2 decimals" (explicitly mandated)   
| ✅ | testTransferTokens | ERC-20 transfer   
| ✅ | testApproveFromNonZeroToNonZeroReverts | Frontrunning protection (mandatory)   
| ✅ | testApproveToZeroThenNewValueSucceeds | Frontrunning safe path   
| ✅ | testTransferFromWithAllowance | ERC-20 transferFrom   

### 5. MempoolTest — 4 tests (from 12)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testSingleSenderNonceOrdering | Tx nonce ordering   
| ✅ | testMultiSenderGasPriceOrdering | "Highest fee first" (mandatory)   
| ✅ | testGasLimitCap | Gas mechanism   
| ✅ | testOnBlockCommittedRemovesExecutedTxs | Mempool cleanup after decide   


### 6. TransactionRunnerTest — 5 tests (from 13)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testFirstTransactionNonceZeroShouldSucceed | Basic tx execution   
| ✅ | testTransferDebitsCreditCorrectly | DepCoin transfers (mandatory)   
| ✅ | testMinterReceivesGasFee | Gas mechanism (mandatory)   
| ✅ | testInsufficientBalanceRejected | Non-negative balances   
| ✅ | testContractCreationNonceZero | Contract deployment   


### 7. CryptoServiceTest — 3 tests (from 6)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testSignAndVerifyEd25519 | PKI / authentication   
| ✅ | testBLSAggregationAndThresholdVerification | Threshold signatures   
| ✅ | testQCThresholdIntegration | QC with Ed25519 + BLS   


### 8. HotStuffStep3Test — 3 tests (from 8)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testStubbornLinkReliableDelivery | Link stack (Stage 1 Step 2)   
| ✅ | testAuthPerfectLinkDelivery | APL with DH handshake (mandatory)   
| ✅ | testAuthPerfectLinkRejectsWrongKey | Authentication / Byzantine detection   


### 9. HotStuffStep4Test — 5 tests (from 20)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testTreeNodeHashDeterministic | Consensus data integrity   
| ✅ | testTreeNodeExtendsFromParent | Chain extension logic   
| ✅ | testMessageSerializeDeserialize | Message format correctness   
| ✅ | testQCHasQuorum | Quorum logic (n−f)   
| ✅ | testQCVerifyEd25519 | QC signature verification   


### 10. HotStuffStep5Test — 3 tests (from 7)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testConsensusDecidesBlock | Full 3-phase consensus works   
| ✅ | testOnDecideDoesNotFireOnTimeout | onDecide correctness   
| ✅ | testMultipleConsecutiveDecisions | Leader rotation + consecutive views   


### 11. HotStuffStep6Test — 3 tests (from 4)

| | Test | Requirement | Verdict |
|---|------|-------------|---------|
| ✅ | testConsensusSurvivesOneCrashedReplica | Crash fault tolerance (mandatory)   
| ✅ | testCrashedReplicaDoesNotDecide | Crash safety guarantee   
| ✅ | testSilentByzantineDoesNotPreventConsensus | BFT (mandatory)   



