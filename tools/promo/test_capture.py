import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import xml.etree.ElementTree as ET
import capture


def tree(text='Audio', package=capture.PACKAGE, selected='true', box='[10,100][200,160]'):
    return ET.fromstring(f'<hierarchy><node package="{package}" selected="{selected}">'
                         f'<node text="{text}" package="{package}" enabled="true" bounds="{box}"/>'
                         '</node></hierarchy>')


class CaptureTests(unittest.TestCase):
    def test_selector_ignores_other_apps(self):
        self.assertIsNone(capture.find_node(tree(package='android'), 'Audio'))

    def test_selector_is_exact_case_insensitive(self):
        self.assertIsNotNone(capture.find_node(tree(), 'audio'))
        self.assertIsNone(capture.find_node(tree(text='Audio tools'), 'Audio'))

    def test_zero_area_and_off_strip_nodes_are_rejected(self):
        self.assertIsNone(capture.find_node(tree(box='[0,0][0,0]'), 'Audio'))
        self.assertIsNone(capture.find_node(tree(), 'Audio', top=90))

    def test_selected_semantics_can_live_on_parent(self):
        root = tree()
        self.assertTrue(capture.selected(root, capture.find_node(root, 'Audio')))
        root = tree(selected='false')
        self.assertFalse(capture.selected(root, capture.find_node(root, 'Audio')))

    def test_compose_tab_reports_checked_not_selected(self):
        # As the Settings tab strip dumps: the clickable parent is checked.
        root = ET.fromstring(
            f'<hierarchy><node package="{capture.PACKAGE}" selected="false" checkable="true" checked="true">'
            f'<node text="Appearance" package="{capture.PACKAGE}" enabled="true" selected="false" '
            'checked="false" bounds="[74,331][268,370]"/></node></hierarchy>')
        self.assertTrue(capture.selected(root, capture.find_node(root, 'Appearance')))

    def test_scroll_fingerprint_changes_when_content_moves(self):
        self.assertNotEqual(capture.fingerprint(tree()),
                            capture.fingerprint(tree(box='[10,200][200,260]')))

    def test_inventory_has_unique_safe_artifact_names(self):
        targets = capture.inventory()
        names = [t['id'] for t in targets]
        self.assertEqual(len(names), len(set(names)))
        for name in names:
            self.assertRegex(name, r'^[a-z0-9-]+$')

    def test_system_dialog_is_found_and_waited_on(self):
        root = ET.fromstring(
            '<hierarchy>'
            '<node package="android" text="Pixel Launcher isn\'t responding" bounds="[168,966][912,1124]"/>'
            '<node package="android" text="Close app" bounds="[84,1177][996,1345]"/>'
            '<node package="android" text="Wait" bounds="[84,1345][996,1513]"/>'
            '</hierarchy>')
        self.assertEqual(capture.system_dialog_button(root).get('text'), 'Wait')

    def test_app_screen_has_no_system_dialog(self):
        self.assertIsNone(capture.system_dialog_button(tree()))
        # An app button called Wait is not a system dialog.
        self.assertIsNone(capture.system_dialog_button(tree(text='Wait')))

    def test_optional_dismiss_skips_intro_only_when_present(self):
        class Device:
            def window_size(self): return 1080, 2400
            class jsonrpc:
                @staticmethod
                def setConfigurator(value): pass
        runner = capture.Capture(Device())
        clicked = []
        with patch.object(runner, 'click', side_effect=lambda t, **k: clicked.append(t)), \
             patch.object(runner, 'home'), patch.object(runner, 'wait'), \
             patch.object(runner, 'save', return_value='screenshots/x.png'), \
             patch.object(capture, 'write_report'), patch.object(capture.time, 'sleep'):
            with patch.object(runner, 'tree', return_value=tree(text='SKIP')):
                runner.run({'id': 'with-intro', 'steps': [{'dismiss': 'SKIP'}]})
            with patch.object(runner, 'tree', return_value=tree(text='Back')):
                runner.run({'id': 'no-intro', 'steps': [{'dismiss': 'SKIP'}]})
        self.assertEqual(clicked, ['SKIP'])
        self.assertTrue(all(r['status'] == 'captured-needs-review' for r in runner.results))

    def test_failed_screen_preserves_report_and_next_screen_restarts(self):
        class Device:
            def window_size(self): return 1080, 2400
            class jsonrpc:
                @staticmethod
                def setConfigurator(value): pass
        with tempfile.TemporaryDirectory() as tmp, patch.object(capture, 'OUT', Path(tmp)):
            runner = capture.Capture(Device())
            with patch.object(runner, 'home', side_effect=RuntimeError('launch failed')) as home:
                with patch.object(runner, 'save', return_value='diagnostics/failure.png'):
                    runner.run({'id': 'first'})
                    runner.run({'id': 'second'})
            self.assertEqual(home.call_count, 2)
            self.assertTrue(all(r['status'] == 'failed' for r in runner.results))
            self.assertIn('0/2', (Path(tmp) / 'coverage.md').read_text())
            self.assertIn('launch failed', (Path(tmp) / 'coverage.json').read_text())


if __name__ == '__main__':
    unittest.main()
