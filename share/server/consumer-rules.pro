# Ktor (CIO) referenziert java.lang.management für die Debugger-Erkennung (IntellijIdeaDebugDetector).
# Dieses Paket gibt es auf Android nicht. Ohne diese Regeln bricht R8 mit „Missing class“ ab. Die Regel
# unterdrückt nur die Warnung, sie behält keine Klasse. Der Laufzeitpfad der Erkennung ist auf Android nicht
# belegt (offener Punkt in ADR 0012).
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# Keine -keep-Regeln für den Serverpfad (ADR 0014, ADR 0017, ADR 0018). R8 hält die Server-Pipeline über die
# Erreichbarkeit: Der Gastgeber startet den Space aus der Oberfläche des Release-Builds, der Dienst steht im Manifest von
# share:server. R8 darf Klassen der Pipeline zusammenlegen (KtorLocalServer ist so zusammengelegt, ADR 0018). Die Prüfung
# checkReleaseServerPipeline sucht deshalb Methoden im Mapping, nicht nur Klassennamen, und bricht ab, wenn R8 Teile der
# Pipeline tatsächlich entfernt hat.
