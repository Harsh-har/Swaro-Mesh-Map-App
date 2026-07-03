import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';
import 'package:flutter/services.dart';

class AreaListItem {
  final String label;
  final bool isHeader;
  final String? areaId;
  final List<String> deviceIds;
  final int dotColor; // 0 for none, 1 for green, 2 for orange

  AreaListItem({
    required this.label,
    required this.isHeader,
    this.areaId,
    this.deviceIds = const [],
    this.dotColor = 0,
  });
}

class AreaListScreen extends StatefulWidget {
  final String svgUri;
  final String siteTitle;

  const AreaListScreen({
    Key? key,
    required this.svgUri,
    required this.siteTitle,
  }) : super(key: key);

  @override
  _AreaListScreenState createState() => _AreaListScreenState();
}

class _AreaListScreenState extends State<AreaListScreen> {
  static const platform = MethodChannel('no.nordicsemi.android.mesh/bridge');
  List<AreaListItem> items = [];
  bool isLoading = true;

  @override
  void initState() {
    super.initState();
    _loadAreaData();
  }

  Future<void> _loadAreaData() async {
    try {
      final List<dynamic> result = await platform.invokeMethod('getAreaList', {
        'svg_uri': widget.svgUri,
      });

      setState(() {
        items = result.map((e) => AreaListItem(
          label: e['label'],
          isHeader: e['isHeader'],
          areaId: e['areaId'],
          deviceIds: List<String>.from(e['deviceIds'] ?? []),
          dotColor: e['dotColor'] ?? 0,
        )).toList();
        isLoading = false;
      });
    } on PlatformException catch (e) {
      print("Failed to load areas: '${e.message}'.");
      setState(() => isLoading = false);
    }
  }

  String _getIconPath(String label) {
    String lower = label.toLowerCase();
    if (lower.contains("corridor")) return "assets/area_icons/Corridor.svg";
    if (lower.contains("powder")) return "assets/area_icons/Powder room.svg";
    if (lower.contains("kitchen")) return "assets/area_icons/Wet Kitchen.svg";
    if (lower.contains("restaurant")) return "assets/area_icons/Restaurant Close.svg";
    return "assets/area_icons/master.svg";
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(widget.siteTitle),
        backgroundColor: Colors.white,
        foregroundColor: Colors.black,
        elevation: 0,
      ),
      body: isLoading
          ? Center(child: CircularProgressIndicator())
          : items.isEmpty
              ? Center(child: Text("No Areas Found"))
              : ListView.builder(
                  itemCount: items.length,
                  itemBuilder: (context, index) {
                    final item = items[index];
                    if (item.isHeader) {
                      return Padding(
                        padding: const EdgeInsets.all(16.0),
                        child: Text(
                          item.label,
                          style: TextStyle(
                            fontSize: 18,
                            fontWeight: FontWeight.bold,
                            color: Colors.grey[700],
                          ),
                        ),
                      );
                    } else {
                      return ListTile(
                        leading: Container(
                          width: 40,
                          height: 40,
                          child: SvgPicture.asset(
                            _getIconPath(item.label),
                            placeholderBuilder: (context) => Icon(Icons.settings),
                          ),
                        ),
                        title: Text(item.label),
                        trailing: Row(
                          mainAxisSize: MainAxisSize.min,
                          children: [
                            if (item.dotColor != 0)
                              Container(
                                width: 12,
                                height: 12,
                                decoration: BoxDecoration(
                                  shape: BoxShape.circle,
                                  color: item.dotColor == 1 
                                      ? Color(0xFF7CBB00) // Green
                                      : Color(0xFFF58700), // Orange
                                ),
                              ),
                            Icon(Icons.chevron_right),
                          ],
                        ),
                        onTap: () {
                          platform.invokeMethod('navigateToMap', {
                            'areaId': item.areaId ?? item.label,
                            'svg_uri': widget.svgUri,
                          });
                        },
                      );
                    }
                  },
                ),
    );
  }
}
