package tecnico.depchain.depchain_server.hotstuff;

import java.io.IOException;

/**
 * Generates BLS threshold crypto parameters and saves them to a file.
 * Run once before starting replicas.
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
