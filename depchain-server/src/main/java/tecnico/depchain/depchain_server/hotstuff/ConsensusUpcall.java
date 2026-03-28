package tecnico.depchain.depchain_server.hotstuff;

/**
 * Interface that bridges the HotStuff Consensus engine with the blockchain application layer.
 * This satisfies the "upcall" requirement from the project specification.
 */
public interface ConsensusUpcall {
    //TODO: TRASH everything that uses String commands instead of transaction/blocks
    /**
     * Called when a block reaches the DECIDE phase with certainty.
     * @param payload The original String payload that was proposed.
     */
    void onDecide(String payload);
}
