package tecnico.depchain.depchain_server.hotstuff;

import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import tecnico.depchain.depchain_server.blockchain.Block;
import tecnico.depchain.depchain_server.hotstuff.Message.MsgType;

/**
 * Step 4 — Consensus Data Structure Unit Tests
 *
 * Tests TreeNode, Message serialization, and QuorumCertificate
 * with Ed25519 signature verification.
 */
public class HotStuffStep4Test {

    // ── TreeNode ────────────────────────────────────────────────────────

    @Test
    public void testTreeNodeHashDeterministic() {
        Block blk = new Block();
        TreeNode n1 = new TreeNode((TreeNode) null, blk);
        TreeNode n2 = new TreeNode((TreeNode) null, blk);
        assertArrayEquals(n1.getHash(), n2.getHash(),
                "Same parent + same block should produce identical hashes");
    }

    @Test
    public void testTreeNodeExtendsFromParent() {
        Block blk1 = new Block();
        Block blk2 = new Block();
        TreeNode parent = new TreeNode((TreeNode) null, blk1);
        TreeNode child = new TreeNode(parent, blk2);

        assertTrue(child.extendsFrom(parent),
                "Child should extend from its parent");
    }

    // ── Message Serialization ───────────────────────────────────────────

    @Test
    public void testMessageSerializeDeserialize() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        Message msg = new Message(MsgType.PREPARE, 5, 2, node, null);

        byte[] data = msg.serialize();
        assertNotNull(data, "Serialized message should not be null");

        Message restored = Message.deserialize(data);
        assertNotNull(restored);
        assertEquals(MsgType.PREPARE, restored.getType());
        assertEquals(5, restored.getViewNumber());
        assertEquals(2, restored.getSenderId());
        assertNotNull(restored.getTreeNode());
    }

    // ── QuorumCertificate ───────────────────────────────────────────────

    @Test
    public void testQCHasQuorum() {
        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 1, node);

        int quorumSize = 3; // n=4, f=1, quorum = n-f = 3
        assertFalse(qc.hasQuorum(quorumSize));
        qc.addVote(0, new byte[]{1});
        qc.addVote(1, new byte[]{2});
        assertFalse(qc.hasQuorum(quorumSize));
        qc.addVote(2, new byte[]{3});
        assertTrue(qc.hasQuorum(quorumSize),
                "QC should have quorum after 3 votes (n=4, f=1)");
    }

    @Test
    public void testQCVerifyEd25519() throws Exception {
        int n = 4;
        List<KeyPair> keyPairs = CryptoService.generateKeyPairs(n);
        List<PublicKey> pubKeys = CryptoService.extractPublicKeys(keyPairs);
        CryptoService crypto0 = new CryptoService(0, keyPairs.get(0), pubKeys);

        TreeNode node = new TreeNode((TreeNode) null, new Block());
        QuorumCertificate qc = new QuorumCertificate(MsgType.PREPARE, 1, node);

        // Each replica signs the vote
        for (int i = 0; i < 3; i++) {
            CryptoService cs = new CryptoService(i, keyPairs.get(i), pubKeys);
            byte[] sig = cs.signVote(MsgType.PREPARE, 1, node.getHash());
            qc.addVote(i, sig);
        }

        assertTrue(qc.verify(crypto0, null, 3),
                "QC with 3 valid Ed25519 signatures should verify (quorum=3)");
    }

}
