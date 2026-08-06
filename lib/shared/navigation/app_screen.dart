import 'package:flutter/material.dart';
import 'package:flutter_lucide/flutter_lucide.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

/// The 5 top-level screens (README.md "Interactions & behavior": persistent
/// nav bar, active item colored with the accent).
enum AppScreen {
  consulta('Consulta', LucideIcons.search),
  historial('Historial', LucideIcons.history),
  favoritos('Favoritos', LucideIcons.star),
  administracion('Administración', LucideIcons.settings),
  apariencia('Apariencia', LucideIcons.palette);

  const AppScreen(this.label, this.icon);

  final String label;
  final IconData icon;
}

final currentScreenProvider =
    StateProvider<AppScreen>((ref) => AppScreen.consulta);
