import 'package:flutter/painting.dart';

/// Radii — 2026-07-17 redesign (design_system/design_handoff_faro):
/// "7–9px on small controls (buttons, inputs, chips, segmented control),
/// 16px on containers (cards, dialogs). Accent-swatch pickers are the one
/// fully-round (circular) exception." No more pill-shaped buttons/inputs —
/// that was the earlier "Organic" system's look.
class AppRadii {
  const AppRadii._();

  /// Buttons, text inputs.
  static const double control = 9;

  /// Small chips/tags.
  static const double chip = 6;

  /// Segmented control's outer track.
  static const double segmentTrack = 10;

  /// Segmented control's individual selected-option pill.
  static const double segmentOption = 7;

  /// Cards, dialogs.
  static const double container = 16;

  static const BorderRadius controlRadius =
      BorderRadius.all(Radius.circular(control));
  static const BorderRadius chipRadius =
      BorderRadius.all(Radius.circular(chip));
  static const BorderRadius segmentTrackRadius =
      BorderRadius.all(Radius.circular(segmentTrack));
  static const BorderRadius segmentOptionRadius =
      BorderRadius.all(Radius.circular(segmentOption));
  static const BorderRadius containerRadius =
      BorderRadius.all(Radius.circular(container));
}
