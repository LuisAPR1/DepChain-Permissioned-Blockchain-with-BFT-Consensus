package tecnico.depchain.depchain_common.messages;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;

import org.hyperledger.besu.datatypes.Address;

/**
 * Request message from client to server asking for the current nonce
 * of a specific account. Used for client crash recovery to sync the
 * internal nonce counter with the committed blockchain state.
 */
public class NonceRequestMessage implements Serializable {
    private final String addressHex;

    public NonceRequestMessage(Address address) {
        this.addressHex = address.toHexString();
    }

    public Address getAddress() {
        return Address.fromHexString(addressHex);
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

    public static NonceRequestMessage deserialize(byte[] data) {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
        try {
            ObjectInputStream objectStream = new ObjectInputStream(byteStream);
            return (NonceRequestMessage) objectStream.readObject();
        } catch (IOException | ClassNotFoundException e) {
            return null;
        }
    }
}