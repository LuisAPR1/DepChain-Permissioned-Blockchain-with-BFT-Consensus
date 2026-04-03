package tecnico.depchain.depchain_common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

/**
 * Reply message from server to client containing the current nonce
 * for a requested account. Used for client crash recovery.
 */
public class NonceReplyMessage implements Serializable {
    private final long nonce;

    public NonceReplyMessage(long nonce) {
        this.nonce = nonce;
    }

    public long getNonce() {
        return nonce;
    }

    public byte[] serialize() {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try {
            ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
            objectStream.writeObject(this);
            objectStream.flush();
            byteStream.flush();
        } catch (IOException e) {
            return null;
        }
        return byteStream.toByteArray();
    }

    public static NonceReplyMessage deserialize(byte[] data) {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
        try {
            ObjectInputStream objectStream = new ObjectInputStream(byteStream);
            return (NonceReplyMessage) objectStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }
}