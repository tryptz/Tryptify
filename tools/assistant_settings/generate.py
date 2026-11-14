"""Writes the table of settings the in-app assistant may read and change.

The assistant reaches settings by their DataStore key, and a value written
under the wrong type makes the app crash where that setting is next read. So
the keys and their types are not typed out by hand: they are read here from
PreferencesManager.kt. A key is in the table when it is in SETTINGS_SYNC_KEYS
(the settings that are the listener's choices and hold no sign-in) and is not
left out below. Run it after adding, removing or retyping a synced setting:

    python3 tools/assistant_settings/generate.py

AssistantSettingsTest reads the same source and fails when the table is stale.
"""
import os, re
HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.join(HERE, '..', '..', 'app', 'src', 'main', 'java', 'tf', 'monochrome', 'android')
SRC = open(os.path.join(ROOT, 'data', 'preferences', 'PreferencesManager.kt')).read()

# Whole documents of another editor (a studio's state, a list of servers), and
# addresses and names that are not for an assistant to rewrite.
LEFT_OUT = re.compile(r'_JSON$|_URL$|ENDPOINT|^API_SERVERS$|USERNAME|^DEV_MODE_ENABLED$')

declared = dict(
    (name, (kind, key))
    for name, kind, key in re.findall(r'val (\w+)\s*=\s*(boolean|int|long|float|double|string|stringSet)PreferencesKey\(\s*"([^"]+)"', SRC)
)
block = re.search(r'val SETTINGS_SYNC_KEYS: Set<Preferences\.Key<\*>> = setOf\((.*?)\n        \)', SRC, re.S).group(1)
block = re.sub(r'//[^\n]*', '', block)
names = re.findall(r'\b[A-Z][A-Z0-9_]+\b', block)

KINDS = {'boolean': 'BOOLEAN', 'int': 'INT', 'long': 'LONG', 'float': 'FLOAT', 'double': 'DOUBLE', 'string': 'TEXT'}
rows = []
for name in names:
    if name not in declared:
        raise SystemExit(f'{name} is synced but its declaration was not understood: fix the pattern above')
    kind, key = declared[name]
    if LEFT_OUT.search(name) or kind not in KINDS:
        continue
    rows.append((key, KINDS[kind]))
rows.sort()

out = '''package tf.monochrome.android.data.assistant

/**
 * The settings the assistant may read and change, by DataStore key, each with
 * the type it is stored as. Written by tools/assistant_settings/generate.py
 * from PreferencesManager's synced settings: do not edit it by hand.
 */
internal val ASSISTANT_SETTING_KEYS: Map<String, AssistantSettings.Kind> = mapOf(
'''
for key, kind in rows:
    out += f'    "{key}" to AssistantSettings.Kind.{kind},\n'
out += ')\n'
open(os.path.join(ROOT, 'data', 'assistant', 'AssistantSettingKeys.kt'), 'w').write(out)
print(len(rows), 'settings')
