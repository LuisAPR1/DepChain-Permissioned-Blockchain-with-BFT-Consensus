package tecnico.depchain.links;

import java.util.function.BiConsumer;

public interface P2PLink {
	public void SetHandler(BiConsumer<byte[], P2PLink> rxHandler);

	public abstract void Transmit(byte[] data);
}
