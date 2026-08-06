import 'package:flutter/widgets.dart';

/// Centers [child] vertically when there's room, same look as a plain
/// `Center` — but scrolls instead of overflowing (the classic yellow/black
/// "BOTTOM OVERFLOWED BY N PIXELS" hazard stripes) once the available
/// height shrinks below what [child] actually needs. Real bug, 2026-08-02,
/// user-reported with a screenshot: every "empty state" card (no servers
/// registered, no query run yet, running…) used a bare `Center` — since the
/// whole window is freely resizable, shrinking it enough always eventually
/// overflowed one of them. `apariencia_screen.dart` hit the same class of
/// bug earlier (its settings column not scrolling at all) — this is the
/// general-purpose version of that fix, for content that should stay
/// centered in the common case rather than pinned to the top.
///
/// Standard Flutter idiom for "centered, but scrollable as a fallback":
/// `SingleChildScrollView` never overflows on its own, but by default
/// sizes its content to only what it needs, which would leave [child]
/// pinned to the top with dead space below whenever there's room to spare.
/// `ConstrainedBox(minHeight: <all the space actually available>)` forces
/// the scroll view's content to fill that space when [child] is smaller
/// than it, so `Center` still centers it exactly like before.
class CenteredScrollable extends StatelessWidget {
  const CenteredScrollable({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) => SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Center(child: child),
        ),
      ),
    );
  }
}
