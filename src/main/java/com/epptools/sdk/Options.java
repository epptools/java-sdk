package com.epptools.sdk;

import com.epptools.sdk.exception.ValidationException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads and vets the option maps the command methods take.
 *
 * An option map is convenient and, without a check, silent: a key that is misspelled, in the wrong case, or left
 * over from an older version is simply never read. The command still goes out, the registry still answers 1000,
 * and the part you asked for is missing - "secdns" for "secDNS" registers the domain UNSIGNED, "nameServer" for
 * "nameservers" registers it with no delegation. Nothing in the response says so, because as far as the registry
 * is concerned you never asked.
 *
 * So an unrecognised key is refused before the frame is built, with the closest known key named. The cost is that
 * a key this library does not understand raises instead of being quietly ignored, which is the point.
 *
 * Not part of the public API; it may change between releases.
 */
public final class Options {
    private Options() {
    }

    /**
     * Rewrite the plain-word spelling of an option key to the one frames are built from.
     *
     * Why two spellings exist: rem, chg and remAll are EPP's own abbreviations, and EPP abbreviates because it is
     * XML on a wire budget. An option map has no wire budget, and a reader who has not memorised RFC 5731 cannot
     * tell chg from a typo or guess that rem is not short for remark. So remove, change and removeAll are accepted
     * everywhere the short forms are, and are what the documentation shows.
     *
     * The short forms are not deprecated and will not be removed. {@link #check} refuses an unrecognised key
     * instead of ignoring it, which is the right behaviour and the reason this library catches secdns for secDNS,
     * but it also means dropping a spelling turns working code into an exception. Both stay.
     *
     * The plain word wins when a caller passes both, which is the only ordering that lets a codebase migrate one
     * call at a time rather than in a flag day. Passing both is not an error: they are two names for one key.
     *
     * @param aliases plain word to the spelling frames are built from
     * @return a new map; the caller's is left alone
     */
    public static Map<String, Object> canonicalise(Map<String, Object> options, Map<String, String> aliases) {
        if (options == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>(options);
        for (Map.Entry<String, String> alias : aliases.entrySet()) {
            if (out.containsKey(alias.getKey())) {
                out.put(alias.getValue(), out.remove(alias.getKey()));
            }
        }
        return out;
    }

    /**
     * Read an option that has more than one reasonable spelling, in the order given.
     *
     * Used where both spellings are defensible rather than where one is a mistake: nameservers is one word in DNS
     * usage, and nameServers is what the rest of this option set's camelCase leads you to type. Guessing either
     * should work; guessing something this library does not understand should not, which is what {@link #check}
     * is for.
     */
    public static Object pick(Map<String, Object> options, String... names) {
        if (options == null) {
            return null;
        }
        for (String name : names) {
            Object value = options.get(name);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    /** Refuse any key in the map that this library does not build a frame from. */
    public static void check(Map<String, Object> given, Collection<String> known, String context) {
        if (given == null) {
            return;
        }
        List<String> unknown = new ArrayList<>();
        for (String key : given.keySet()) {
            if (!known.contains(key)) {
                unknown.add(key);
            }
        }
        if (unknown.isEmpty()) {
            return;
        }
        Collections.sort(unknown);

        List<String> details = new ArrayList<>();
        for (String key : unknown) {
            String suggestion = closest(key, known);
            details.add(suggestion == null ? "'" + key + "'" : "'" + key + "' (did you mean '" + suggestion + "'?)");
        }
        List<String> accepted = new ArrayList<>(known);
        Collections.sort(accepted);

        throw new ValidationException(context + " does not accept " + String.join(", ", details)
                + ". Accepted: " + String.join(", ", accepted) + ".");
    }

    /**
     * The known key a misspelling most likely meant, or null when nothing is close enough.
     *
     * Case and separators are ignored first, so "secdns" finds secDNS and "auth_info" finds authInfo - the two
     * mistakes a key typed from memory in some other naming convention actually makes.
     */
    static String closest(String key, Collection<String> known) {
        String target = normalise(key);
        for (String candidate : known) {
            if (normalise(candidate).equals(target)) {
                return candidate;
            }
        }

        // Two letters swapped is the commonest typo of all and edit distance scores it 2, which the threshold
        // below rejects for a short key - "yeras" would otherwise be reported with no suggestion at all. Same
        // letters in a different order is a transposition and nothing else.
        char[] sortedTarget = target.toCharArray();
        Arrays.sort(sortedTarget);
        for (String candidate : known) {
            char[] sorted = normalise(candidate).toCharArray();
            Arrays.sort(sorted);
            if (Arrays.equals(sortedTarget, sorted)) {
                return candidate;
            }
        }

        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String candidate : known) {
            int distance = editDistance(target, normalise(candidate));
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }

        // Beyond a third of the key's length the suggestion is noise that sends the reader looking in the wrong
        // place, which is worse than offering none.
        return bestDistance <= Math.max(1, target.length() / 3) ? best : null;
    }

    private static String normalise(String key) {
        // Locale.ROOT: the option names are ASCII identifiers, and under a Turkish default the folding puts a
        // dotless i in authInfo and secDNS, so "auth_info" no longer matches authInfo and the reader is told
        // their key is unknown with no suggestion at all - on the JVM locale, not on what they typed.
        return key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
    }

    static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            int[] current = new int[b.length() + 1];
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[b.length()];
    }
}
