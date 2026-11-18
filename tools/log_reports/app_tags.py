#!/usr/bin/env python3
"""The app's own log tags, written into docs/supabase/log_reports.sql.

A sent log is the whole process's logcat: the app's lines among Android's, the
phone maker's and the libraries'. The database's analysis (analyze_log_report)
calls a tag's errors a fault only when the tag is one the app's own code logs
with; this finds those tags in the source and writes them between the
`app-tags` marks of the SQL file.

    python3 tools/log_reports/app_tags.py          # rewrite the list
    python3 tools/log_reports/app_tags.py --check  # say whether it is current

Run it after adding a log tag (LogReportTest fails until the list has it), and
have the SQL file run on the project again when it matters: until then the new
tag's errors are listed under a report, greyed, and never its headline.
"""
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SOURCES = os.path.join(ROOT, "app", "src", "main")
SQL = os.path.join(ROOT, "docs", "supabase", "log_reports.sql")
BEGIN = "    -- app-tags:begin (written by tools/log_reports/app_tags.py, never by hand)\n"
END = "    -- app-tags:end\n"
# Code that is not the app's: its tags would only be noise from someone else's library.
NOT_OURS = ("third_party", os.sep + "vendor", "projectm-assets", os.path.join("cpp", "archive", "libarchive"))
KINDS = (".kt", ".java", ".cpp", ".cc", ".c", ".h", ".hpp")

PATTERNS = (
    re.compile(r'\bLog\.(?:v|d|i|w|e|wtf)\(\s*"([^"$\\]+)"'),                  # Log.d("Tag", …)
    # const val TAG = "Tag", LENS_TAG = "Tag", kTag[] = "Tag": TAG as a whole word of the name (KEY_STAGE is not one)
    re.compile(r'\b(?:(?:\w+_)?TAG(?:_\w+)?|k\w*Tag)\s*(?::\s*String\s*)?(?:\[\]\s*)?=\s*"([^"$\\]+)"'),
    re.compile(r'#\s*define\s+(?:\w+_)?TAG(?:_\w+)?\s+"([^"\\]+)"'),             # #define LOG_TAG "Tag"
    re.compile(r'__android_log_(?:print|write|vprint)\(\s*\w+\s*,\s*"([^"\\]+)"'),
)
# A tag as logcat can carry one; anything else matched was not a tag (a view tag, a test tag).
TAG = re.compile(r"[A-Za-z][A-Za-z0-9_.\-]{1,39}")


def find():
    tags = set()
    for folder, _, names in os.walk(SOURCES):
        if any(part in folder for part in NOT_OURS):
            continue
        for name in names:
            if not name.endswith(KINDS):
                continue
            with open(os.path.join(folder, name), encoding="utf-8", errors="ignore") as f:
                text = f.read()
            for pattern in PATTERNS:
                tags.update(t for t in pattern.findall(text) if TAG.fullmatch(t))
    return sorted(tags, key=lambda t: (t.lower(), t))


def block(tags):
    lines, line = [], "       "
    for tag in tags:
        word = " '%s'," % tag
        if len(line) + len(word) > 112:
            lines.append(line)
            line = "       "
        line += word
    lines.append(line.rstrip(","))
    return BEGIN + "    own constant text[] := array[\n" + "\n".join(lines) + "\n    ];\n" + END


def main():
    with open(SQL, encoding="utf-8") as f:
        sql = f.read()
    start, end = sql.index(BEGIN), sql.index(END) + len(END)
    wanted = sql[:start] + block(find()) + sql[end:]
    if "--check" in sys.argv:
        if wanted != sql:
            print("docs/supabase/log_reports.sql: the app's tags are out of date; run tools/log_reports/app_tags.py")
            return 1
        print("the app's tags are current")
        return 0
    if wanted != sql:
        with open(SQL, "w", encoding="utf-8") as f:
            f.write(wanted)
    print("%d tags" % len(find()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
