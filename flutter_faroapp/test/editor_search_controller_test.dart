import 'package:faro/features/consulta/presentation/widgets/editor/highlighting_controller.dart';
import 'package:faro/features/consulta/presentation/widgets/editor/search_controller.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  late HighlightingController editorController;
  late FocusNode editorFocusNode;
  late EditorSearchController search;
  var changeCount = 0;
  late List<int> jumpTargets;

  setUp(() {
    changeCount = 0;
    jumpTargets = [];
    editorController = HighlightingController(
        text: 'select * from a\nselect * from existencias\nselect * from b');
    editorFocusNode = FocusNode();
    search = EditorSearchController(
      editorController: editorController,
      editorFocusNode: editorFocusNode,
      onChanged: () => changeCount++,
      onJumpTo: jumpTargets.add,
    );
  });

  tearDown(() {
    search.dispose();
    editorFocusNode.dispose();
    editorController.dispose();
  });

  group('EditorSearchController', () {
    test('onQueryChanged moves the real editor selection to the first match',
        () {
      search.visible = true;
      search.queryController.text = 'existencias';
      search.onQueryChanged('existencias');

      expect(search.matches, hasLength(1));
      expect(search.activeMatchIndex, 0);
      final expected = search.matches.first;
      expect(editorController.selection.start, expected.start);
      expect(editorController.selection.end, expected.end);
      // Regression coverage: jumping to a match must ask the host to
      // actually scroll there — user-reported bug: the counter updated
      // but the editor never scrolled, because this used to rely solely
      // on Flutter auto-scrolling a selection change on an unfocused
      // field, which doesn't reliably happen.
      expect(jumpTargets, [expected.start]);
    });

    test('next()/previous() move the real editor selection and scroll to it',
        () {
      search.visible = true;
      editorController.text = 'select a\nselect a\nselect a';
      search.queryController.text = 'select';
      search.onQueryChanged('select');
      expect(search.matches, hasLength(3));
      jumpTargets.clear();

      search.next();
      expect(search.activeMatchIndex, 1);
      expect(editorController.selection.start, search.matches[1].start);
      expect(jumpTargets.last, search.matches[1].start);

      search.previous();
      expect(search.activeMatchIndex, 0);
      expect(editorController.selection.start, search.matches[0].start);
      expect(jumpTargets.last, search.matches[0].start);
    });

    test(
        'onEditorTextChanged updates the match count but never touches the '
        'real editor selection — regression test for the "cursor jumps back '
        'to the old match while typing elsewhere" bug (user-reported with a '
        'screenshot)', () {
      search.visible = true;
      search.queryController.text = 'existencias';
      search.onQueryChanged('existencias');
      expect(search.matches, hasLength(1));

      // Simulate the user typing somewhere else in the script, unrelated to
      // the search box — e.g. accepting an autocomplete suggestion or
      // pressing Enter for a new line. The editor's own selection is set
      // here exactly like real typing would, at a position with nothing to
      // do with the old match.
      editorController.value = const TextEditingValue(
        text: 'select * from a\nselect * from existencias\n'
            'select * from b\nnew line typed here',
        selection: TextSelection.collapsed(offset: 19),
      );
      final selectionBeforeRecompute = editorController.selection;
      jumpTargets.clear();

      search.onEditorTextChanged();

      // The match count is still live...
      expect(search.matches, hasLength(1));
      // ...but the real cursor was NOT moved back to that match, and the
      // editor was NOT asked to scroll anywhere.
      expect(editorController.selection, selectionBeforeRecompute);
      expect(jumpTargets, isEmpty);
    });

    test('onEditorTextChanged is a no-op while search is closed', () {
      search.queryController.text = 'existencias';
      // Search never opened (`visible` stays false) — matches should stay
      // empty and onChanged should never fire.
      search.onEditorTextChanged();
      expect(search.matches, isEmpty);
      expect(changeCount, 0);
    });

    test('onEditorTextChanged clamps activeMatchIndex when matches shrink',
        () {
      search.visible = true;
      editorController.text = 'a a a';
      search.queryController.text = 'a';
      search.onQueryChanged('a');
      search.next();
      search.next();
      expect(search.activeMatchIndex, 2);

      editorController.text = 'a';
      search.onEditorTextChanged();

      expect(search.matches, hasLength(1));
      expect(search.activeMatchIndex, 0);
    });
  });
}
