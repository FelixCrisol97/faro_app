import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';

import '../../../../../data/models/schema_object.dart';

/// Shared between [SchemaTypeGroup]'s category header and [SchemaObjectRow]'s
/// per-row type icon (only shown in flat search results).
IconData iconForType(SchemaObjectType type) => switch (type) {
      SchemaObjectType.table => LucideIcons.table,
      SchemaObjectType.view => LucideIcons.view,
      SchemaObjectType.function ||
      SchemaObjectType.procedure =>
        LucideIcons.square_function,
      SchemaObjectType.trigger => LucideIcons.zap,
    };
