package net.shasankp000.FunctionCaller;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;



public class ToolVerifiers {

    /** goTo: compares final position vs. input coordinates */
    public static boolean verifyGoTo(Map<String, String> params, String output) {
        try {
            Pattern pattern = Pattern.compile("x:(-?\\d+)\\s+y:(-?\\d+)\\s+z:(-?\\d+)");
            Matcher matcher = pattern.matcher(output);
            if (!matcher.find()) return false;

            double outX = Double.parseDouble(matcher.group(1));
            double outY = Double.parseDouble(matcher.group(2));
            double outZ = Double.parseDouble(matcher.group(3));

            double targetX = Double.parseDouble(params.getOrDefault("x", "0"));
            double targetY = Double.parseDouble(params.getOrDefault("y", "0"));
            double targetZ = Double.parseDouble(params.getOrDefault("z", "0"));

            double distSq = Math.pow(outX - targetX, 2)
                    + Math.pow(outY - targetY, 2)
                    + Math.pow(outZ - targetZ, 2);

            return distSq <= 9.0; // 3 block tolerance
        } catch (Exception e) {
            return false;
        }
    }

    /** detectBlocks: output contains detected block coordinates */
    public static boolean verifyDetectBlocks(Map<String, String> params, String output) {
        try {
            Pattern pattern = Pattern.compile("found at (\\-?\\d+)\\s+(\\-?\\d+)\\s+(\\-?\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(output.toLowerCase());

            return matcher.find();
        } catch (Exception e) {
            return false;
        }
    }

    /** turn: output must contain the direction input */
    public static boolean verifyTurn(Map<String, String> params, String output) {
        String expected = params.getOrDefault("direction", "").toLowerCase();
        return output.toLowerCase().contains("facing " + expected);
    }

    /** mineBlock: check for success indicators */
    public static boolean verifyMineBlock(Map<String, String> params, String output) {
        String lower = output.toLowerCase();
        return lower.contains("success")
                || lower.contains("mined")
                || lower.contains("complete")
                || lower.contains("done");
    }

    /** getOxygenLevel: extract integer from output */
    public static boolean verifyGetOxygenLevel(Map<String, String> params, String output) {
        return verifySimpleIntMetric(output, "oxygen");
    }

    /** getHungerLevel: extract integer from output */
    public static boolean verifyGetHungerLevel(Map<String, String> params, String output) {
        return verifySimpleIntMetric(output, "hunger");
    }

    /** getHealthLevel: extract float from output */
    public static boolean verifyGetHealthLevel(Map<String, String> params, String output) {
        try {
            Pattern pattern = Pattern.compile("([0-9]+(\\.[0-9]+)?)");
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                double val = Double.parseDouble(matcher.group(1));
                return val >= 0;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /** Utility: shared integer stat extraction */
    private static boolean verifySimpleIntMetric(String output, String keyword) {
        try {
            Pattern pattern = Pattern.compile(keyword + ".*?(\\d+)", Pattern.CASE_INSENSITIVE);
            Matcher matcher = pattern.matcher(output);
            if (matcher.find()) {
                int val = Integer.parseInt(matcher.group(1));
                return val >= 0;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }
}
