package tecnico.depchain.links;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import org.junit.jupiter.api.Test;

public class AuthenticatedPerfectLinkTest {

	private static final String HOST = "127.0.0.1";

	/** Generate a fresh HMAC-SHA256 key */
	private static SecretKey generateKey() throws Exception {
		KeyGenerator kg = KeyGenerator.getInstance("HmacSHA256");
		return kg.generateKey();
	}

	@Test
	void testSingleMessageDelivery() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18001);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18002);

		// Two shared secrets: one per direction
		SecretKey keyA = generateKey();
		SecretKey keyB = generateKey();

		// A sends with keyA, verifies incoming with keyB
		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(addrA, addrB, keyA, keyB);
		// B sends with keyB, verifies incoming with keyA
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(addrB, addrA, keyB, keyA);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> received = new AtomicReference<>();

		linkB.SetHandler((data, link) -> {
			received.set(data);
			latch.countDown();
		});

		byte[] message = "Hello authenticated world".getBytes();
		linkA.Transmit(message);

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Message should be delivered");
		assertArrayEquals(message, received.get());
	}

	@Test
	void testMultipleMessagesDelivered() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18003);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18004);

		SecretKey keyA = generateKey();
		SecretKey keyB = generateKey();

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(addrA, addrB, keyA, keyB);
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(addrB, addrA, keyB, keyA);

		int messageCount = 5;
		CountDownLatch latch = new CountDownLatch(messageCount);
		java.util.Set<String> receivedMessages = java.util.concurrent.ConcurrentHashMap.newKeySet();

		linkB.SetHandler((data, link) -> {
			receivedMessages.add(new String(data));
			latch.countDown();
		});

		for (int i = 0; i < messageCount; i++) {
			linkA.Transmit(("Msg " + i).getBytes());
		}

		assertTrue(latch.await(10, TimeUnit.SECONDS), "All messages should be delivered");

		for (int i = 0; i < messageCount; i++) {
			assertTrue(receivedMessages.contains("Msg " + i),
					"Should have received 'Msg " + i + "'");
		}
	}

	@Test
	void testBidirectionalCommunication() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18005);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18006);

		SecretKey keyAtoB = generateKey();
		SecretKey keyBtoA = generateKey();

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(addrA, addrB, keyAtoB, keyBtoA);
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(addrB, addrA, keyBtoA, keyAtoB);

		CountDownLatch latchA = new CountDownLatch(1);
		CountDownLatch latchB = new CountDownLatch(1);
		AtomicReference<byte[]> receivedByA = new AtomicReference<>();
		AtomicReference<byte[]> receivedByB = new AtomicReference<>();

		linkA.SetHandler((data, link) -> {
			receivedByA.set(data);
			latchA.countDown();
		});
		linkB.SetHandler((data, link) -> {
			receivedByB.set(data);
			latchB.countDown();
		});

		linkA.Transmit("A says hi".getBytes());
		linkB.Transmit("B says hi".getBytes());

		assertTrue(latchB.await(5, TimeUnit.SECONDS), "B should receive from A");
		assertTrue(latchA.await(5, TimeUnit.SECONDS), "A should receive from B");
		assertArrayEquals("A says hi".getBytes(), receivedByB.get());
		assertArrayEquals("B says hi".getBytes(), receivedByA.get());
	}

	@Test
	void testWrongKeyRejected() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18007);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18008);

		SecretKey keyAtoB = generateKey();
		SecretKey keyBtoA = generateKey();
		SecretKey wrongKey = generateKey(); // B uses wrong key to verify

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(addrA, addrB, keyAtoB, keyBtoA);
		// B verifies with wrongKey instead of keyAtoB → should reject all from A
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(addrB, addrA, keyBtoA, wrongKey);

		CountDownLatch latch = new CountDownLatch(1);

		linkB.SetHandler((data, link) -> {
			latch.countDown(); // Should NOT be called
		});

		linkA.Transmit("This should be rejected".getBytes());

		// Wait a bit and verify nothing was delivered
		assertFalse(latch.await(3, TimeUnit.SECONDS),
				"Message with wrong key should be silently rejected");
	}

	@Test
	void testDeduplication() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18009);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18010);

		SecretKey keyAtoB = generateKey();
		SecretKey keyBtoA = generateKey();

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(addrA, addrB, keyAtoB, keyBtoA);
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(addrB, addrA, keyBtoA, keyAtoB);

		AtomicInteger deliveryCount = new AtomicInteger(0);
		CountDownLatch firstDelivery = new CountDownLatch(1);

		linkB.SetHandler((data, link) -> {
			deliveryCount.incrementAndGet();
			firstDelivery.countDown();
		});

		// Send a single message — stubborn link will retransmit it multiple times
		// but AuthenticatedPerfectLink should deliver it exactly once
		linkA.Transmit("Deliver me once".getBytes());

		assertTrue(firstDelivery.await(5, TimeUnit.SECONDS), "Message should be delivered");

		// Give time for potential duplicate deliveries
		Thread.sleep(1000);

		assertEquals(1, deliveryCount.get(),
				"Message should be delivered exactly once (deduplication)");
	}

	@Test
	void testMessageContentIntegrity() throws Exception {
		InetSocketAddress addrA = new InetSocketAddress(HOST, 18011);
		InetSocketAddress addrB = new InetSocketAddress(HOST, 18012);

		SecretKey keyAtoB = generateKey();
		SecretKey keyBtoA = generateKey();

		AuthenticatedPerfectLink linkA = new AuthenticatedPerfectLink(addrA, addrB, keyAtoB, keyBtoA);
		AuthenticatedPerfectLink linkB = new AuthenticatedPerfectLink(addrB, addrA, keyBtoA, keyAtoB);

		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<byte[]> received = new AtomicReference<>();

		// Send binary data to ensure no corruption through the layers
		byte[] binaryData = new byte[256];
		for (int i = 0; i < 256; i++) {
			binaryData[i] = (byte) i;
		}

		linkB.SetHandler((data, link) -> {
			received.set(data);
			latch.countDown();
		});

		linkA.Transmit(binaryData);

		assertTrue(latch.await(5, TimeUnit.SECONDS), "Binary message should be delivered");
		assertArrayEquals(binaryData, received.get(),
				"Binary content should be preserved through all layers");
	}
}
