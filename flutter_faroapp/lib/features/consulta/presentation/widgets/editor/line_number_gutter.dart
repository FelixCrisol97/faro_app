import 'package:flutter/material.dart';

/// Passive follower for the editor's own scroll position (see
/// `_SqlEditorState`'s `_editorScrollController`/`_gutterScrollController`
/// in `sql_editor.dart`) — never user-scrollable on its own
/// (NeverScrollableScrollPhysics), all scroll input happens over the real
/// text field and gets mirrored here.
class LineNumberGutter extends StatelessWidget {
  const LineNumberGutter({
    super.key,
    required this.scrollController,
    required this.lineCount,
    required this.lineHeight,
    required this.topPadding,
    required this.width,
    required this.style,
  });

  final ScrollController scrollController;
  final int lineCount;
  final double lineHeight;
  final double topPadding;
  final double width;
  final TextStyle style;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: width,
      // The gutter is never user-scrollable on its own (see the class
      // doc) but Flutter's desktop ScrollBehavior still decorates any
      // Scrollable with a visible scrollbar regardless of physics — same
      // fix already applied to the schema tree's nested lists (see
      // `shared/navigation/tree/schema_object_list.dart`).
      child: ScrollConfiguration(
        behavior: ScrollConfiguration.of(context).copyWith(scrollbars: false),
        child: SingleChildScrollView(
          controller: scrollController,
          physics: const NeverScrollableScrollPhysics(),
          padding: EdgeInsets.only(top: topPadding, right: 10),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            children: [
              for (var i = 1; i <= lineCount; i++)
                SizedBox(
                  height: lineHeight,
                  child: Text('$i', style: style, textAlign: TextAlign.right),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
