// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.util.*;

/** Presentation of the retained legacy guide; complete source text stays in captured reports. */
final class ReferenceSections {
    static final class Section {
        final String title, body;
        final boolean observed;

        Section(String title, String body, boolean observed) {
            this.title = title;
            this.body = body;
            this.observed = observed;
        }
    }

    static List<Section> parse(String content) {
        List<Section> sections = new ArrayList<>();
        for (String block : content.split("\\n\\s*\\n")) {
            String text = block.trim();
            if (text.isEmpty()) continue;
            int line = text.indexOf('\n');
            String first = line < 0 ? text : text.substring(0, line);
            boolean heading =
                    first.length() <= 120
                            && (first.matches("[A-Z][A-Z0-9 /()—–:.,\\-]*")
                                    || first.toLowerCase(Locale.ROOT)
                                            .contains("direct observation"));
            String title = heading ? first : "Notes and context";
            if (first.replace(" ", "").equals("FILE101")) {
                if (line < 0) continue;
                title = "File mechanisms and reference";
            }
            boolean observed =
                    first.toLowerCase(Locale.ROOT).contains("direct observation")
                            || first.endsWith(" FOUND");
            sections.add(
                    new Section(
                            title,
                            heading && line >= 0 ? text.substring(line + 1) : text,
                            observed));
        }
        return Collections.unmodifiableList(sections);
    }

    private ReferenceSections() {}
}
