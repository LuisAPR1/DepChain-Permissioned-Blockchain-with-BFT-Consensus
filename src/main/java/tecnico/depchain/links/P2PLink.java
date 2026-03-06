package tecnico.depchain.links;

import java.util.function.BiConsumer;

public abstract class P2PLink {
	protected BiConsumer<byte[], P2PLink> rxHandler;

	public P2PLink(BiConsumer<byte[], P2PLink> rxHandler)
	{
		this.rxHandler = rxHandler;
	}

	public abstract void Transmit(byte[] data);
}
