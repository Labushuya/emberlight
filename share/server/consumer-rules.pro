# Ktor (CIO) referenziert java.lang.management für die Debugger-Erkennung (IntellijIdeaDebugDetector).
# Dieses Paket gibt es auf Android nicht. Ohne diese Regeln bricht R8 mit „Missing class“ ab. Die Regel
# unterdrückt nur die Warnung, sie behält keine Klasse. Der Laufzeitpfad der Erkennung ist auf Android nicht
# belegt (offener Punkt in ADR 0012).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
