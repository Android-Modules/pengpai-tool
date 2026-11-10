package io.github.dalong.hyperhome;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Search only describes destinations; it never reads or changes preference values. */
final class SubmoduleIndex {
    record Entry(String id,String title,String path,String aliases,String route) {}
    private record Match(Entry entry, int score) {}
    private final List<Entry> entries;

    SubmoduleIndex(List<Entry> additional) { entries=List.copyOf(additional); }

    List<Entry> entries() { return entries; }

    List<Entry> search(String input) {
        String query = normalize(input);
        if (query.isEmpty()) return List.of();
        String[] terms = query.split(" ");
        ArrayList<Match> result = new ArrayList<>();
        for (Entry item : entries) {
            String title = normalize(item.title), corpus = normalize(item.title + " " + item.path + " " + item.aliases);
            boolean matches = true;
            for (String term : terms) if (!corpus.contains(term)) { matches = false; break; }
            if (!matches) continue;
            int score = title.equals(query) ? 1000 : title.startsWith(query) ? 600 : title.contains(query) ? 400 : 0;
            for (String term : terms) if (title.contains(term)) score += 20;
            result.add(new Match(item, score));
        }
        result.sort(Comparator.comparingInt(Match::score).reversed());
        return result.stream().map(Match::entry).toList();
    }
    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replace("wi-fi", "wifi")
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
