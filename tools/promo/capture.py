"""Capture a declared screen inventory through the published app's public UI.

No private activities, account credentials or production data are required.
Every destination starts from a fresh launch, so a failure cannot cascade into
mislabelled screenshots of the previous page. The report is a capture inventory,
not an assertion that the images are ready to publish.
"""
import hashlib
import html
import json
import os
from pathlib import Path
import re
import time
import xml.etree.ElementTree as ET

PACKAGE = 'tf.monotrypt.android'
OUT = Path('promo-output')


def labels(node):
    return {node.get(key, '').strip().casefold() for key in ('text', 'content-desc')} - {''}


def bounds(node):
    values = [int(n) for n in re.findall(r'-?\d+', node.get('bounds', ''))]
    if len(values) != 4 or values[2] <= values[0] or values[3] <= values[1]:
        return None
    return values


def find_node(root, text, top=None):
    for node in root.iter('node'):
        box = bounds(node)
        if (text.casefold() in labels(node) and box and node.get('enabled', 'true') == 'true'
                and node.get('package') == PACKAGE and (top is None or box[3] <= top)):
            return node
    return None


# Android's own "X isn't responding" / "X keeps stopping" dialogs. On a slow,
# software-rendered emulator the launcher can stall long enough to raise one,
# and it then sits over the app: every tap lands on the dialog and no app label
# is ever visible. 'Wait' leaves the stalled app running; 'Close app' is only
# used where Android offers nothing else.
SYSTEM_DIALOG_TITLES = ("isn't responding", 'keeps stopping', 'has stopped')
SYSTEM_DIALOG_BUTTONS = ('wait', 'close app', 'ok')


def system_dialog_button(root):
    """The button that dismisses a system error dialog, or None when there is none."""
    nodes = [n for n in root.iter('node') if n.get('package') == 'android']
    if not any(title in label for n in nodes for label in labels(n) for title in SYSTEM_DIALOG_TITLES):
        return None
    for wanted in SYSTEM_DIALOG_BUTTONS:
        for node in nodes:
            if wanted in labels(node) and bounds(node):
                return node
    return None


DEMO_TITLES = ('Afterglow (Demo)', 'Night Drive (Demo)', 'Prism (Demo)')


def mini_player_title(root, height):
    """The playing demo track's title in the mini player: the lowest one on
    screen, in its bottom third. Shuffle can start any of the three."""
    wanted = {t.casefold() for t in DEMO_TITLES}
    matches = [n for n in root.iter('node')
               if n.get('package') == PACKAGE and labels(n) & wanted
               and bounds(n) and bounds(n)[1] > height * 2 // 3]
    return max(matches, key=lambda n: bounds(n)[1]) if matches else None


def selected(root, node):
    # A Compose tab reports its state as checked (it is a checkable,
    # selectable node), a View-based tab as selected; accept either.
    parents = {child: parent for parent in root.iter() for child in parent}
    while node is not None:
        if node.get('selected') == 'true' or node.get('checked') == 'true':
            return True
        node = parents.get(node)
    return False


def fingerprint(root):
    # Text + positions distinguish scroll pages. Exclude system clock/battery.
    parts = [(sorted(labels(n)), n.get('bounds'), n.get('selected'))
             for n in root.iter('node') if n.get('package') == PACKAGE and labels(n)]
    return hashlib.sha256(repr(parts).encode()).hexdigest()


class Capture:
    def __init__(self, device):
        self.d = device
        self.width, self.height = self.d.window_size()
        self.d.jsonrpc.setConfigurator({'waitForIdleTimeout': 0, 'waitForSelectorTimeout': 0})
        self.results = []

    def tree(self):
        # Every lookup goes through here, so a system dialog that pops up at
        # any point is cleared before the next step looks for an app label.
        for _ in range(3):
            root = ET.fromstring(self.d.dump_hierarchy(compressed=False))
            button = system_dialog_button(root)
            if button is None:
                return root
            x1, y1, x2, y2 = bounds(button)
            print('Dismissing system dialog: ' + ', '.join(sorted(labels(button))), flush=True)
            self.d.click((x1+x2)//2, (y1+y2)//2)
            time.sleep(1.5)
        return root

    def wait(self, text, timeout=12):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = find_node(self.tree(), text)
            if node is not None:
                return node
            time.sleep(.5)
        raise RuntimeError(f'Expected visible app label: {text}')

    def click(self, text, scroll=True, long=False):
        for attempt in range(9 if scroll else 2):
            root = self.tree()
            node = find_node(root, text)
            if node is not None:
                x1, y1, x2, y2 = bounds(node)
                if long:
                    self.d.long_click((x1+x2)//2, (y1+y2)//2, duration=1.2)
                else:
                    self.d.click((x1+x2)//2, (y1+y2)//2)
                time.sleep(1.5)
                return
            if scroll:
                self.d.swipe(.5, .78, .5, .34, duration=.4)
            time.sleep(.5)
        raise RuntimeError(f'Could not find control: {text}')

    def tab(self, text):
        # Settings and Visual Studio use a horizontal tab strip below the toolbar.
        for _ in range(12):
            root = self.tree()
            node = find_node(root, text, top=int(self.height*.25))
            if node is not None:
                x1, y1, x2, y2 = bounds(node)
                self.d.click((x1+x2)//2, (y1+y2)//2)
                time.sleep(1.5)
                root = self.tree()
                node = find_node(root, text, top=int(self.height*.25))
                if node is not None and selected(root, node):
                    return
                raise RuntimeError(f'Tab selection could not be verified: {text}')
            self.d.swipe(.88, .13, .12, .13, duration=.4)
            time.sleep(.4)
        raise RuntimeError(f'Could not find tab: {text}')

    def home(self):
        self.d.app_stop(PACKAGE)
        self.d.app_start(PACKAGE, use_monkey=True)
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            root = self.tree()
            if find_node(root, 'Skip setup') is not None:
                self.click('Skip setup', scroll=False)
            elif find_node(root, "Don't show again") is not None:
                # The "Updated to x.y" card: dismissing it only hides the card,
                # and keeps it out of every screenshot that follows.
                self.click("Don't show again", scroll=False)
            elif all(find_node(root, x) is not None for x in ('Settings', 'Discover', 'Local')):
                return
            time.sleep(1)
        raise RuntimeError('Home did not appear after launch; see failure screenshot/logcat')

    def open_player(self):
        self.click('Local')
        self.click('Songs')
        if find_node(self.tree(), 'Scan') is not None:
            self.click('Scan', scroll=False)
        self.wait(DEMO_TITLES[0], timeout=40)
        # Start playback with the header's Shuffle all: one labelled button.
        # Tapping a song row by its title was not reliable — the title's
        # centre sits on the row's artist button, and a run landed on the
        # album page instead of playing.
        self.click('Shuffle all', scroll=False)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            root = self.tree()
            if find_node(root, 'Collapse') is not None:
                return
            # Depending on the View Mode, playback either expands the player
            # or starts in the mini player; open the latter by its title.
            node = mini_player_title(root, self.height)
            if node is not None:
                x1, y1, x2, y2 = bounds(node)
                self.d.click((x1+x2)//2, (y1+y2)//2)
                time.sleep(1.5)
                break
            time.sleep(1)
        self.wait('Collapse')

    def save(self, name, diagnostic=False):
        folder = OUT / ('diagnostics' if diagnostic else 'screenshots')
        folder.mkdir(parents=True, exist_ok=True)
        if not diagnostic and self.d.app_current().get('package') != PACKAGE:
            raise RuntimeError('App lost foreground; refusing to label another app as Tryptify')
        self.d.screenshot(str(folder / f'{name}.png'))
        (folder / f'{name}.xml').write_text(self.d.dump_hierarchy(compressed=False))
        return f'{folder.name}/{name}.png'

    def run(self, target):
        result = {'id': target['id'], 'status': 'failed', 'images': []}
        try:
            self.home()
            if target.get('player'):
                self.open_player()
            for step in target.get('steps', []):
                if 'tab' in step:
                    self.tab(step['tab'])
                elif 'click' in step:
                    self.click(step['click'], long=step.get('long', False))
                elif 'expect' in step:
                    self.wait(step['expect'])
                elif 'dismiss' in step:
                    # A first-visit intro (Precision AutoEQ's walkthrough):
                    # tap its skip button when it is showing, carry on if not.
                    if find_node(self.tree(), step['dismiss']) is not None:
                        self.click(step['dismiss'], scroll=False)
            time.sleep(2)
            result['images'].append(self.save(target['id']))
            seen = {fingerprint(self.tree())}
            result['scroll_limit_reached'] = False
            for page in range(1, target.get('scroll_pages', 0) + 1):
                self.d.swipe(.5, .80, .5, .32, duration=.5)
                time.sleep(1)
                signature = fingerprint(self.tree())
                if signature in seen:
                    break
                seen.add(signature)
                result['images'].append(self.save(f"{target['id']}-{page+1:02d}"))
            else:
                result['scroll_limit_reached'] = target.get('scroll_pages', 0) > 0
            result['status'] = 'captured-needs-review'
        except Exception as exc:
            result['error'] = str(exc)
            try:
                result['diagnostic'] = self.save(target['id'] + '-failure', diagnostic=True)
            except Exception as diagnostic_error:
                result['diagnostic_error'] = str(diagnostic_error)
        self.results.append(result)
        write_report(self.results)
        print(f"{result['status']}: {target['id']}", flush=True)


def inventory():
    targets = [{'id': '01-home'}]
    for name in ('Discover', 'World radio', 'Local', 'Overview', 'Playlists', 'Favorites', 'Downloads'):
        # Unlike the Home page list, these pages have a page-jump control.
        marker = {'Discover': 'Discover (Beta)', 'World radio': 'Recentre'}.get(name, 'Go to page')
        targets.append({'id': name.lower().replace(' ', '-'),
                        'steps': [{'click': name}, {'expect': marker}]})
    targets += [{'id': 'search', 'steps': [{'click': 'Search'}, {'expect': 'Close search'}]},
                {'id': 'account', 'steps': [{'click': 'Profile'}, {'expect': 'Account'}]}]
    for category in ('Songs', 'Albums', 'Artists', 'Album Artists', 'Composers', 'Genres', 'Years', 'Folders'):
        targets.append({'id': 'local-' + category.lower().replace(' ', '-'),
                        'steps': [{'click': 'Local'}, {'click': category}, {'expect': 'Back to library'}]})
    tabs = ('Appearance', 'Visual Studio', 'Audio', 'Equalizer', 'Library', 'Downloads',
            'Connections', 'Radio', 'System', 'About')
    for tab in tabs:
        targets.append({'id': 'settings-' + tab.lower().replace(' ', '-'),
                        'steps': [{'click': 'Settings'}, {'tab': tab}], 'scroll_pages': 3})
    for title, tab in [('Open Precision AutoEQ', 'Equalizer'), ('Open Parametric EQ', 'Equalizer'),
                       ('Seap Compressor', 'Audio'), ('Seap Inflator', 'Audio'),
                       ('Atmos Renderer Configuration', 'Audio')]:
        targets.append({'id': re.sub(r'[^a-z0-9]+', '-', title.lower()).strip('-'),
                        'steps': [{'click': 'Settings'}, {'tab': tab}, {'click': title},
                                  {'dismiss': 'SKIP'}, {'expect': 'Back'}]})
    # The player screens come before the Visual Studio tabs: they are the
    # ones a promotion needs most, and must not depend on what follows.
    targets.extend([
        {'id': 'now-playing', 'player': True},
        {'id': 'mixer', 'player': True, 'steps': [{'click': 'Mixer/FX'}, {'expect': 'Insert Rack'}]},
        {'id': 'audio-tools', 'player': True, 'steps': [{'click': 'Audio tools'}, {'expect': 'AutoEQ'}]},
        {'id': 'output-device', 'player': True, 'steps': [{'click': 'Output device'}]},
    ])
    for tab in ('Player', 'UI panels', 'Lyrics', 'Visualizer'):
        targets.append({'id': 'visual-studio-' + tab.lower().replace(' ', '-'),
                        'steps': [{'click': 'Settings'}, {'tab': 'Visual Studio'},
                                  {'click': 'Player Visuals Studio'}, {'tab': tab}],
                        # Scrolling the Visualizer tab took the software-
                        # rendered emulator down twice (system UI restarted,
                        # then the device went offline), so it is captured
                        # one viewport deep and last.
                        'scroll_pages': 0 if tab == 'Visualizer' else 2})
    return targets


LIMITATIONS = [
    'Screenshots require visual review; a captured screen can contain empty, loading or offline content.',
    'Demo audio consists of generated tones. No commercial recordings or personal accounts are used.',
    'Account-connected states, physical USB-DAC connections and Bluetooth hardware states need separate device captures.',
    'Catalog album/artist/playlist detail, genre chart/shelf detail, signed-in statistics, every DSP insert editor, '
    'onboarding steps and every modal are not covered by this initial inventory.',
    'The visualizer configuration page is included; live visualization and lyrics need suitable playback data.',
    'Long pages are bounded to three extra viewports (two in Visual Studio); the report flags the scroll limit.',
]


def write_report(results):
    OUT.mkdir(exist_ok=True)
    (OUT / 'coverage.json').write_text(json.dumps({'screens': results, 'limitations': LIMITATIONS}, indent=2))
    good = sum(r['status'] == 'captured-needs-review' for r in results)
    lines = ['# Tryptify promotional capture', '', f'{good}/{len(results)} attempted destinations captured. '
             'Review images before publishing.', '', '| Destination | Result | Images |', '|---|---|---|']
    cards = []
    for r in results:
        note = r.get('error', r['status']).replace('|', '/').replace('\n', ' ')
        if r.get('scroll_limit_reached'):
            note += '; scroll limit reached'
        lines.append(f"| {r['id']} | {note} | {len(r['images'])} |")
        for image in r['images']:
            cards.append(f'<figure><a href="{html.escape(image)}"><img loading="lazy" '
                         f'src="{html.escape(image)}"></a><figcaption>{html.escape(Path(image).stem)}</figcaption></figure>')
    lines += ['', '## Coverage limits', ''] + ['- ' + x for x in LIMITATIONS]
    (OUT / 'coverage.md').write_text('\n'.join(lines) + '\n')
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><meta name="viewport" '
        'content="width=device-width,initial-scale=1"><title>Tryptify capture gallery</title>'
        '<style>body{background:#10131a;color:#edf4fc;font:16px system-ui;margin:30px}'
        'main{display:grid;grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:24px}'
        'figure{margin:0}img{width:100%;border-radius:16px}figcaption{padding:12px 0}'
        'a{color:#93e6d1}</style><h1>Tryptify capture gallery</h1>'
        f'<p>{good}/{len(results)} attempted destinations captured. '
        'These are real emulator captures, not approved promotional assets. '
        '<a href="coverage.md">Read coverage and limitations</a>.</p><main>' + ''.join(cards) + '</main>')


def selected_targets(only=None):
    """The inventory, or just the ids named in a comma-separated PROMO_TARGETS."""
    only = os.environ.get('PROMO_TARGETS', '') if only is None else only
    wanted = {t.strip() for t in only.split(',') if t.strip()}
    targets = inventory()
    if not wanted:
        return targets
    unknown = wanted - {t['id'] for t in targets}
    if unknown:
        raise SystemExit('Unknown target ids: ' + ', '.join(sorted(unknown)))
    return [t for t in targets if t['id'] in wanted]


def main():
    import uiautomator2 as u2
    runner = Capture(u2.connect())
    for target in selected_targets():
        runner.run(target)
    failures = [r for r in runner.results if r['status'] == 'failed']
    if failures and os.environ.get('STRICT_CAPTURE', 'true').lower() == 'true':
        raise SystemExit(f'{len(failures)} destinations failed. Partial captures and diagnostics were preserved.')


if __name__ == '__main__':
    main()
