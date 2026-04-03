package tecnico.depchain.depchain_server.hotstuff;

import java.io.IOException;

/**
 * Standalone dealer utility that generates BLS threshold crypto parameters
 * and saves them to a file. This must be run ONCE before starting the replicas
 * so all replicas share the same pairing, generator, keys, and shares.
 *
 * Usage: java ThresholdParamsDealer <numMembers> <outputFilePath>
 *
 * Example: java ThresholdParamsDealer 4 threshold-params.dat
 */
public class ThresholdParamsDealer {

	public static void main(String[] args) throws IOException {
		if (args.length < 2) {
			System.err.println("Usage: java ThresholdParamsDealer <numMembers> <outputFilePath>");
			System.exit(1);
		}

		int numMembers = Integer.parseInt(args[0]);
		String outputPath = args[1];

		int f = (numMembers - 1) / 3;
		int threshold = numMembers - f; // quorum size (2f + 1)

		System.out.println("Generating BLS threshold parameters...");
		System.out.println("  Members: " + numMembers);
		System.out.println("  Threshold (n-f): " + threshold);

		ThresholdCrypto.DealerParams params = ThresholdCrypto.generateParams(threshold, numMembers);
		params.saveToFile(outputPath);

		System.out.println("Threshold params saved to " + outputPath);
		System.out.println("All replicas must load this same file at startup.");
	}
}
