import 'package:flutter/material.dart';
import 'screens/area_list_screen.dart';
import 'dart:ui';

void main() {
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});

  @override
  Widget build(BuildContext context) {
    // Determine the route name
    String initialRoute = window.defaultRouteName;

    return MaterialApp(
      title: 'Swaro Mesh Map UI',
      theme: ThemeData(
        primarySwatch: Colors.blue,
        useMaterial3: true,
      ),
      initialRoute: initialRoute,
      onGenerateRoute: (settings) {
        if (settings.name?.startsWith('areaList') ?? false) {
          // Parse parameters from route if needed, 
          // or just use MethodChannel to get them later.
          // For simplicity, we'll assume the native side sends data via MethodChannel.
          final Uri uri = Uri.parse(settings.name!);
          final String svgUri = uri.queryParameters['svg_uri'] ?? '';
          final String siteTitle = uri.queryParameters['site_title'] ?? 'Areas';
          
          return MaterialPageRoute(
            builder: (context) => AreaListScreen(
              svgUri: svgUri,
              siteTitle: siteTitle,
            ),
          );
        }
        
        // Default home
        return MaterialPageRoute(
          builder: (context) => const AreaListScreen(
            svgUri: '',
            siteTitle: 'Map Areas',
          ),
        );
      },
    );
  }
}
